import Foundation
import PamNative
import QuartzCore
import UIKit

/// Registers `svg.view`.
public final class SvgViewFactory: NativeViewFactory, @unchecked Sendable {
    private static let memoryObserver: NSObjectProtocol = NotificationCenter.default.addObserver(
        forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main
    ) { _ in
        SvgDocumentCache.shared.trim(keeping: 0)
    }

    public init() {
        _ = Self.memoryObserver
    }

    public func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView {
        PamSvgView(emit: emit)
    }

    public func update(view: UIView, properties: [String: WireValue]) {
        (view as? PamSvgView)?.update(properties)
    }

    public func release(view: UIView) {
        (view as? PamSvgView)?.releaseResources()
    }
}

/// Layers created for one node id: `pivot` carries transform/opacity
/// animations, `strokes` are the CAShapeLayers whose stroke properties
/// (trim, dash phase, width) animate.
private struct SvgNodeLayers {
    let pivot: CALayer
    var strokes: [CAShapeLayer]
}

private final class SvgAnimationDelegate: NSObject, CAAnimationDelegate {
    let onEnd: () -> Void

    init(onEnd: @escaping () -> Void) {
        self.onEnd = onEnd
    }

    func animationDidStop(_ anim: CAAnimation, finished flag: Bool) {
        if flag { onEnd() }
    }
}

/// Retained SVG renderer built from Core Animation layers.
///
/// Each document (parsed once per content hash, cached process-wide) becomes a
/// CAShapeLayer tree composited by the render server. Native animations are
/// CABasicAnimations on strokeEnd/strokeStart/lineDashPhase/lineWidth/opacity/
/// transform, so frames are produced outside the app's main thread and never
/// call PHP.
final class PamSvgView: UIView {
    private let emit: (Data) -> Void
    private let content = CALayer()
    private var document: SvgDocument?
    private var key: String?
    private var generation = 0
    private var tint: UInt32?
    private var currentColor: UInt32?
    private var fitOverride: Int?
    private var events = false
    private var specs: [String: SvgAnimationSpec] = [:]
    private var pendingSpecs: [SvgAnimationSpec] = []
    /// Settled model values per spec key, re-applied when layers are rebuilt.
    private var values: [String: CGFloat] = [:]
    private var nodeLayers: [String: SvgNodeLayers] = [:]
    private var appearanceSignature = ""

    private static let parseQueue = DispatchQueue(label: "dev.pam.svg.parse", qos: .utility)
    private static let syncParseLimit = 16 * 1024

    init(emit: @escaping (Data) -> Void) {
        self.emit = emit
        super.init(frame: .zero)
        backgroundColor = .clear
        isUserInteractionEnabled = false
        content.anchorPoint = .zero
        layer.addSublayer(content)
    }

    required init?(coder: NSCoder) {
        nil
    }

    func update(_ properties: [String: WireValue]) {
        tint = text(properties["tint"]).flatMap(SvgColor.argb)
        currentColor = text(properties["color"]).flatMap(SvgColor.argb)
        if case let .flag(set)? = properties["fitSet"], set, case let .integer(fit)? = properties["fit"] {
            fitOverride = Int(fit)
        } else {
            fitOverride = nil
        }
        if case let .flag(flag)? = properties["events"] { events = flag } else { events = false }
        pendingSpecs = SvgAnimationSpec.parseAll(text(properties["anim"]))

        let signature = "\(tint ?? 0)|\(tint != nil)|\(currentColor ?? 0)|\(currentColor != nil)"
        if let nextKey = text(properties["key"]), let source = text(properties["source"]), nextKey != key {
            key = nextKey
            appearanceSignature = signature
            load(key: nextKey, source: source)
            return
        }
        if signature != appearanceSignature {
            appearanceSignature = signature
            rebuildLayers()
        }
        applySpecs()
        setNeedsLayout()
    }

    private func text(_ value: WireValue?) -> String? {
        if case let .text(string)? = value { return string }
        return nil
    }

    private func load(key nextKey: String, source: String) {
        generation += 1
        let current = generation
        if let cached = SvgDocumentCache.shared.get(nextKey) {
            setDocument(cached)
            return
        }
        if source.utf8.count <= Self.syncParseLimit {
            setDocument(try? SvgDocumentCache.shared.obtain(nextKey, source))
            return
        }
        Self.parseQueue.async { [weak self] in
            let parsed = try? SvgDocumentCache.shared.obtain(nextKey, source)
            DispatchQueue.main.async {
                guard let self, self.generation == current else { return }
                self.setDocument(parsed)
            }
        }
    }

    private func setDocument(_ next: SvgDocument?) {
        document = next
        specs.removeAll()
        values.removeAll()
        rebuildLayers()
        applySpecs()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        content.frame = bounds
        if let document {
            content.sublayerTransform = CATransform3DMakeAffineTransform(
                document.fit(width: bounds.width, height: bounds.height, override: fitOverride))
        }
        CATransaction.commit()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // Nothing animates while the view is off-window.
        if window == nil {
            pause(content)
        } else {
            resume(content)
        }
    }

    func releaseResources() {
        generation += 1
        content.removeAllAnimationsRecursively()
        content.sublayers = nil
        nodeLayers.removeAll()
        specs.removeAll()
        values.removeAll()
        document = nil
        key = nil
    }

    // MARK: Layer tree

    private func rebuildLayers() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        content.removeAllAnimationsRecursively()
        content.sublayers = nil
        nodeLayers.removeAll()
        if let document {
            for child in document.root.children {
                content.addSublayer(build(child, document: document))
            }
            // Re-apply settled animation values to the fresh layers.
            for (specKey, spec) in specs {
                if let value = values[specKey] { setModel(spec, value) }
            }
        }
        CATransaction.commit()
    }

    private func build(_ node: SvgNode, document: SvgDocument) -> CALayer {
        let holder = CALayer()
        holder.anchorPoint = .zero
        holder.bounds = .zero
        holder.position = .zero
        if let transform = node.transform {
            holder.setAffineTransform(transform)
        }
        if let clipId = node.clipId, let clip = document.clips[clipId] {
            let mask = CAShapeLayer()
            mask.path = clip.path
            mask.fillColor = UIColor.black.cgColor
            holder.mask = mask
        }
        // Animatable nodes get a pivot layer positioned at their rotation/scale origin.
        var container = holder
        var pivotLayer: CALayer?
        if let id = node.id {
            let origin = specOrigin(id) ?? CGPoint(x: node.bounds.midX, y: node.bounds.midY)
            let pivot = CALayer()
            pivot.bounds = .zero
            pivot.position = origin
            let inner = CALayer()
            inner.anchorPoint = .zero
            inner.bounds = .zero
            inner.position = CGPoint(x: -origin.x, y: -origin.y)
            pivot.addSublayer(inner)
            holder.addSublayer(pivot)
            pivot.opacity = Float(node.opacity)
            container = inner
            pivotLayer = pivot
        } else {
            holder.opacity = Float(node.opacity)
        }
        var strokes: [CAShapeLayer] = []
        switch node {
        case let group as SvgGroup:
            for child in group.children {
                container.addSublayer(build(child, document: document))
            }
        case let shape as SvgShape:
            strokes = addShape(shape, document: document, to: container)
        default:
            break
        }
        if let id = node.id, let pivotLayer, nodeLayers[id] == nil {
            nodeLayers[id] = SvgNodeLayers(pivot: pivotLayer, strokes: strokes)
        }
        return holder
    }

    private func specOrigin(_ id: String) -> CGPoint? {
        (specs.values.first { $0.id == id && $0.origin != nil } ?? pendingSpecs.first { $0.id == id && $0.origin != nil })?.origin
    }

    /// Returns the layers that carry the stroke (for trim/dash/width animations).
    private func addShape(_ shape: SvgShape, document: SvgDocument, to container: CALayer) -> [CAShapeLayer] {
        let style = shape.style
        guard style.visible else { return [] }
        let fill = resolve(style.fill, shape: shape, document: document, opacity: style.fillOpacity)
        let stroke = style.strokeWidth > 0 ? resolve(style.stroke, shape: shape, document: document, opacity: style.strokeOpacity) : .none

        // Fast path: solid fill and stroke share one CAShapeLayer.
        if case .gradient = fill {} else if case .gradient = stroke {} else {
            let layer = CAShapeLayer()
            layer.path = shape.data.path
            layer.fillRule = style.evenOdd ? .evenOdd : .nonZero
            layer.fillColor = fill.color
            configureStroke(layer, shape: shape, color: stroke.color)
            container.addSublayer(layer)
            return stroke.color == nil ? [] : [layer]
        }
        var strokes: [CAShapeLayer] = []
        for (paint, isStroke) in [(fill, false), (stroke, true)] {
            switch paint {
            case .none:
                continue
            case let .solid(color):
                let layer = CAShapeLayer()
                layer.path = shape.data.path
                layer.fillRule = style.evenOdd ? .evenOdd : .nonZero
                if isStroke {
                    layer.fillColor = nil
                    configureStroke(layer, shape: shape, color: color)
                    strokes.append(layer)
                } else {
                    layer.fillColor = color
                }
                container.addSublayer(layer)
            case let .gradient(gradient, alpha):
                let mask = CAShapeLayer()
                mask.path = shape.data.path
                mask.fillRule = style.evenOdd ? .evenOdd : .nonZero
                if isStroke {
                    mask.fillColor = nil
                    configureStroke(mask, shape: shape, color: UIColor.black.cgColor)
                    strokes.append(mask)
                } else {
                    mask.fillColor = UIColor.black.cgColor
                }
                let layer = gradientLayer(gradient, shape: shape, document: document, mask: mask, stroke: isStroke)
                layer.opacity = Float(alpha)
                container.addSublayer(layer)
            }
        }
        return strokes
    }

    private func configureStroke(_ layer: CAShapeLayer, shape: SvgShape, color: CGColor?) {
        let style = shape.style
        layer.strokeColor = color
        guard color != nil else { return }
        layer.lineWidth = style.strokeWidth
        layer.lineCap = style.lineCap == 2 ? .round : (style.lineCap == 3 ? .square : .butt)
        layer.lineJoin = style.lineJoin == 2 ? .round : (style.lineJoin == 3 ? .bevel : .miter)
        layer.miterLimit = style.miterLimit
        if let dashes = style.dashes {
            let scale = shape.dashScale
            layer.lineDashPattern = dashes.map { NSNumber(value: Double(max(0.01, $0 * scale))) }
            layer.lineDashPhase = style.dashOffset * scale
        }
        layer.strokeStart = shape.trimStart
        layer.strokeEnd = shape.trimEnd
    }

    private enum ResolvedPaint {
        case none
        case solid(CGColor)
        case gradient(SvgGradient, CGFloat)

        var color: CGColor? {
            if case let .solid(color) = self { return color }
            return nil
        }
    }

    private func resolve(_ paint: SvgPaint, shape: SvgShape, document: SvgDocument, opacity: CGFloat) -> ResolvedPaint {
        if paint == .none { return .none }
        if let tint { return .solid(SvgColor.cgColor(SvgColor.multiplyAlpha(tint, opacity))) }
        switch paint {
        case .none:
            return .none
        case .current:
            return .solid(SvgColor.cgColor(SvgColor.multiplyAlpha(currentColorValue(shape), opacity)))
        case let .solid(argb):
            return .solid(SvgColor.cgColor(SvgColor.multiplyAlpha(argb, opacity)))
        case let .ref(id, fallback):
            guard let gradient = document.gradients[id], !gradient.stops.isEmpty else {
                return fallback == .none ? .none : resolve(fallback, shape: shape, document: document, opacity: opacity)
            }
            if gradient.stops.count == 1 {
                return .solid(SvgColor.cgColor(SvgColor.multiplyAlpha(gradient.stops[0].argb, opacity)))
            }
            return .gradient(gradient, opacity)
        }
    }

    private func currentColorValue(_ shape: SvgShape) -> UInt32 {
        if let currentColor { return currentColor }
        if case let .solid(argb) = shape.style.color { return argb }
        return 0xFF00_0000
    }

    /// CAGradientLayer framed on the gradient's reference box, masked by the shape.
    private func gradientLayer(_ gradient: SvgGradient, shape: SvgShape, document: SvgDocument, mask: CAShapeLayer, stroke: Bool) -> CALayer {
        var box = gradient.userSpace ? document.viewBox : shape.data.bounds
        if stroke { box = box.insetBy(dx: -shape.style.strokeWidth, dy: -shape.style.strokeWidth) }
        if box.width <= 0 { box.size.width = 1 }
        if box.height <= 0 { box.size.height = 1 }
        let layer = CAGradientLayer()
        layer.frame = box
        layer.colors = gradient.stops.map { SvgColor.cgColor($0.argb) }
        layer.locations = gradient.stops.map { NSNumber(value: Double($0.offset)) }
        let reference = gradient.userSpace ? document.viewBox : shape.data.bounds
        func unit(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            // Gradient coordinates -> user space -> fraction of this layer's frame.
            var point = gradient.userSpace ? CGPoint(x: x, y: y)
                : CGPoint(x: reference.minX + x * reference.width, y: reference.minY + y * reference.height)
            if let transform = gradient.transform {
                if gradient.userSpace {
                    point = point.applying(transform)
                } else {
                    let local = CGPoint(x: x, y: y).applying(transform)
                    point = CGPoint(x: reference.minX + local.x * reference.width, y: reference.minY + local.y * reference.height)
                }
            }
            return CGPoint(x: (point.x - box.minX) / box.width, y: (point.y - box.minY) / box.height)
        }
        let c = gradient.coordinates
        if gradient.radial {
            layer.type = .radial
            let center = unit(c[0], c[1])
            let edge = unit(c[0] + c[2], c[1] + c[2])
            layer.startPoint = center
            layer.endPoint = edge
        } else {
            layer.type = .axial
            layer.startPoint = unit(c[0], c[1])
            layer.endPoint = unit(c[2], c[3])
        }
        mask.frame = CGRect(origin: .zero, size: box.size)
        mask.setAffineTransform(.identity)
        mask.path = {
            var translate = CGAffineTransform(translationX: -box.minX, y: -box.minY)
            return shape.data.path.copy(using: &translate)
        }()
        layer.mask = mask
        return layer
    }

    // MARK: Animations

    private func applySpecs() {
        guard document != nil else { return }
        let incoming = Dictionary(pendingSpecs.map { ($0.key, $0) }, uniquingKeysWith: { _, last in last })
        for removed in Set(specs.keys).subtracting(incoming.keys) {
            guard let spec = specs.removeValue(forKey: removed) else { continue }
            values.removeValue(forKey: removed)
            targets(spec).forEach { $0.0.removeAnimation(forKey: removed) }
            if let node = document?.byId[spec.id] { setModel(spec, staticValue(node, spec.property)) }
        }
        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        for spec in incoming.values {
            let previous = specs[spec.key]
            if previous == spec { continue }
            guard let node = document?.byId[spec.id] else { continue }
            specs[spec.key] = spec
            if let origin = spec.origin, let layers = nodeLayers[spec.id], layers.pivot.position != origin {
                rebuildPivot(spec.id, origin: origin)
            }
            let current = presentationValue(spec) ?? values[spec.key] ?? staticValue(node, spec.property)
            let start = spec.from ?? current
            let final = spec.endValue ?? start
            let immediate = (previous == nil && spec.from == nil && !spec.animateOnMount)
                || (spec.duration == 0 && spec.delay == 0)
                || (reduceMotion && spec.repeatCount >= 0)
            values[spec.key] = spec.repeatCount < 0 ? spec.to : final
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            setModel(spec, values[spec.key] ?? spec.to)
            CATransaction.commit()
            if immediate {
                targets(spec).forEach { $0.0.removeAnimation(forKey: spec.key) }
                if previous != nil, events { emitEnd(spec) }
                continue
            }
            for (layer, keyPath, scale) in targets(spec) {
                let animation = CABasicAnimation(keyPath: keyPath)
                animation.fromValue = convert(spec.property, start * scale)
                animation.toValue = convert(spec.property, spec.to * scale)
                animation.duration = max(0.001, spec.duration)
                let (x1, y1, x2, y2) = SvgEasingCode.controlPoints(spec.easing)
                animation.timingFunction = CAMediaTimingFunction(controlPoints: x1, y1, x2, y2)
                animation.beginTime = layer.convertTime(CACurrentMediaTime(), from: nil) + spec.delay
                animation.fillMode = .backwards
                animation.autoreverses = spec.reverse
                if spec.repeatCount < 0 {
                    animation.repeatCount = .infinity
                } else if spec.reverse {
                    animation.repeatCount = Float(spec.repeatCount + 1) / 2
                } else {
                    animation.repeatCount = Float(spec.repeatCount + 1)
                }
                if events, spec.repeatCount >= 0, layer === targets(spec).first?.0 {
                    animation.delegate = SvgAnimationDelegate { [weak self] in self?.emitEnd(spec) }
                }
                layer.add(animation, forKey: spec.key)
            }
        }
    }

    private func rebuildPivot(_ id: String, origin: CGPoint) {
        guard var layers = nodeLayers[id], let inner = layers.pivot.sublayers?.first else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layers.pivot.position = origin
        inner.position = CGPoint(x: -origin.x, y: -origin.y)
        CATransaction.commit()
        layers = SvgNodeLayers(pivot: layers.pivot, strokes: layers.strokes)
        nodeLayers[id] = layers
    }

    private func staticValue(_ node: SvgNode, _ property: Int) -> CGFloat {
        let shape = node as? SvgShape
        switch property {
        case SvgPropertyCode.opacity: return node.opacity
        case SvgPropertyCode.strokeDashoffset: return shape?.style.dashOffset ?? 0
        case SvgPropertyCode.trimStart: return shape?.trimStart ?? 0
        case SvgPropertyCode.trimEnd: return shape?.trimEnd ?? 1
        case SvgPropertyCode.scale: return 1
        case SvgPropertyCode.strokeWidth: return shape?.style.strokeWidth ?? 1
        default: return 0
        }
    }

    /// (layer, keyPath, value scale) for a spec.
    private func targets(_ spec: SvgAnimationSpec) -> [(CALayer, String, CGFloat)] {
        guard let layers = nodeLayers[spec.id] else { return [] }
        let dashScale = (document?.byId[spec.id] as? SvgShape)?.dashScale ?? 1
        switch spec.property {
        case SvgPropertyCode.opacity: return [(layers.pivot, "opacity", 1)]
        case SvgPropertyCode.rotation: return [(layers.pivot, "transform.rotation.z", .pi / 180)]
        case SvgPropertyCode.scale: return [(layers.pivot, "transform.scale", 1)]
        case SvgPropertyCode.translateX: return [(layers.pivot, "transform.translation.x", 1)]
        case SvgPropertyCode.translateY: return [(layers.pivot, "transform.translation.y", 1)]
        case SvgPropertyCode.trimStart: return layers.strokes.map { ($0, "strokeStart", 1) }
        case SvgPropertyCode.trimEnd: return layers.strokes.map { ($0, "strokeEnd", 1) }
        case SvgPropertyCode.strokeDashoffset: return layers.strokes.map { ($0, "lineDashPhase", dashScale) }
        case SvgPropertyCode.strokeWidth: return layers.strokes.map { ($0, "lineWidth", 1) }
        default: return []
        }
    }

    private func convert(_ property: Int, _ value: CGFloat) -> Any {
        property == SvgPropertyCode.opacity ? Float(value) as Any : Double(value) as Any
    }

    private func setModel(_ spec: SvgAnimationSpec, _ value: CGFloat) {
        for (layer, keyPath, scale) in targets(spec) {
            layer.setValue(convert(spec.property, min(spec.property == SvgPropertyCode.opacity ? 1 : .greatestFiniteMagnitude, value * scale)), forKeyPath: keyPath)
        }
    }

    private func presentationValue(_ spec: SvgAnimationSpec) -> CGFloat? {
        guard let (layer, keyPath, scale) = targets(spec).first, scale != 0,
              layer.animation(forKey: spec.key) != nil,
              let value = layer.presentation()?.value(forKeyPath: keyPath) as? NSNumber else { return nil }
        return CGFloat(value.doubleValue) / scale
    }

    private func emitEnd(_ spec: SvgAnimationSpec) {
        guard let data = try? WireMap.encode([
            "event": .integer(1),
            "id": .text(spec.id),
            "property": .integer(Int64(spec.property)),
        ]) else { return }
        emit(data)
    }

    private func pause(_ layer: CALayer) {
        guard layer.speed != 0 else { return }
        let paused = layer.convertTime(CACurrentMediaTime(), from: nil)
        layer.speed = 0
        layer.timeOffset = paused
    }

    private func resume(_ layer: CALayer) {
        guard layer.speed == 0 else { return }
        let paused = layer.timeOffset
        layer.speed = 1
        layer.timeOffset = 0
        layer.beginTime = 0
        layer.beginTime = layer.convertTime(CACurrentMediaTime(), from: nil) - paused
    }
}

private extension CALayer {
    func removeAllAnimationsRecursively() {
        removeAllAnimations()
        sublayers?.forEach { $0.removeAllAnimationsRecursively() }
        mask?.removeAllAnimationsRecursively()
    }
}
