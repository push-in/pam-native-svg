import CoreGraphics
import Foundation

// Pure model, parser, cache and timeline for svg.view. Foundation + CoreGraphics
// only, so tests/ios-core can compile it with swiftc on macOS without UIKit.

enum SvgPropertyCode {
    static let opacity = 1
    static let strokeDashoffset = 2
    static let trimStart = 3
    static let trimEnd = 4
    static let rotation = 5
    static let scale = 6
    static let translateX = 7
    static let translateY = 8
    static let strokeWidth = 9
}

enum SvgFitCode {
    static let contain = 1
    static let cover = 2
    static let stretch = 3
}

enum SvgEasingCode {
    static let linear = 1
    static let easeIn = 2
    static let easeOut = 3
    static let easeInOut = 4

    static func controlPoints(_ easing: Int) -> (Float, Float, Float, Float) {
        switch easing {
        case easeIn: return (0.42, 0, 1, 1)
        case easeOut: return (0, 0, 0.58, 1)
        case easeInOut: return (0.42, 0, 0.58, 1)
        default: return (0, 0, 1, 1)
        }
    }
}

enum SvgError: Error {
    case invalid(String)
}

// MARK: - Model

enum SvgPaint: Equatable {
    case none
    case current
    case solid(UInt32)
    indirect case ref(String, SvgPaint)
}

struct SvgStyle {
    var fill: SvgPaint = .solid(0xFF00_0000)
    var fillOpacity: CGFloat = 1
    var evenOdd = false
    var stroke: SvgPaint = .none
    var strokeOpacity: CGFloat = 1
    var strokeWidth: CGFloat = 1
    var lineCap = 1
    var lineJoin = 1
    var miterLimit: CGFloat = 4
    var dashes: [CGFloat]?
    var dashOffset: CGFloat = 0
    var color: SvgPaint = .solid(0xFF00_0000)
    var visible = true
}

final class SvgPathData {
    let path: CGPath
    let bounds: CGRect
    let length: CGFloat
    let commandCount: Int

    init(path: CGPath, length: CGFloat, commandCount: Int) {
        self.path = path
        self.bounds = path.isEmpty ? .zero : path.boundingBoxOfPath
        self.length = length
        self.commandCount = commandCount
    }
}

class SvgNode {
    let id: String?
    let transform: CGAffineTransform?
    let opacity: CGFloat
    let clipId: String?

    init(id: String?, transform: CGAffineTransform?, opacity: CGFloat, clipId: String?) {
        self.id = id
        self.transform = transform
        self.opacity = opacity
        self.clipId = clipId
    }

    var bounds: CGRect { .zero }
}

final class SvgGroup: SvgNode {
    let children: [SvgNode]

    init(id: String?, transform: CGAffineTransform?, opacity: CGFloat, clipId: String?, children: [SvgNode]) {
        self.children = children
        super.init(id: id, transform: transform, opacity: opacity, clipId: clipId)
    }

    override var bounds: CGRect {
        children.reduce(CGRect.null) { result, child in
            let b = child.transform.map { child.bounds.applying($0) } ?? child.bounds
            return result.union(b)
        }.standardizedOrZero
    }
}

final class SvgShape: SvgNode {
    let data: SvgPathData
    let style: SvgStyle
    let pathLength: CGFloat?
    let trimStart: CGFloat
    let trimEnd: CGFloat

    init(id: String?, transform: CGAffineTransform?, opacity: CGFloat, clipId: String?, data: SvgPathData,
         style: SvgStyle, pathLength: CGFloat?, trimStart: CGFloat, trimEnd: CGFloat) {
        self.data = data
        self.style = style
        self.pathLength = pathLength
        self.trimStart = trimStart
        self.trimEnd = trimEnd
        super.init(id: id, transform: transform, opacity: opacity, clipId: clipId)
    }

    override var bounds: CGRect { data.bounds }

    var dashScale: CGFloat {
        guard let pathLength, pathLength > 0, data.length > 0 else { return 1 }
        return data.length / pathLength
    }
}

struct SvgStop {
    let offset: CGFloat
    let argb: UInt32
}

struct SvgGradient {
    let id: String
    let radial: Bool
    let userSpace: Bool
    /// linear: x1 y1 x2 y2; radial: cx cy r fx fy
    let coordinates: [CGFloat]
    let stops: [SvgStop]
    let transform: CGAffineTransform?
}

final class SvgClip {
    let id: String
    let shapes: [SvgShape]
    private(set) lazy var path: CGPath = {
        let union = CGMutablePath()
        for shape in shapes {
            union.addPath(shape.data.path, transform: shape.transform ?? .identity)
        }
        return union
    }()

    init(id: String, shapes: [SvgShape]) {
        self.id = id
        self.shapes = shapes
    }
}

final class SvgDocument {
    let viewBox: CGRect
    let alignX: CGFloat
    let alignY: CGFloat
    let none: Bool
    let slice: Bool
    let root: SvgGroup
    let gradients: [String: SvgGradient]
    let clips: [String: SvgClip]
    let byId: [String: SvgNode]
    let estimatedBytes: Int

    init(viewBox: CGRect, alignX: CGFloat, alignY: CGFloat, none: Bool, slice: Bool, root: SvgGroup,
         gradients: [String: SvgGradient], clips: [String: SvgClip], byId: [String: SvgNode], estimatedBytes: Int) {
        self.viewBox = viewBox
        self.alignX = alignX
        self.alignY = alignY
        self.none = none
        self.slice = slice
        self.root = root
        self.gradients = gradients
        self.clips = clips
        self.byId = byId
        self.estimatedBytes = estimatedBytes
    }

    /// viewBox -> view transform, same math as the Android renderer.
    func fit(width: CGFloat, height: CGFloat, override fit: Int?) -> CGAffineTransform {
        guard viewBox.width > 0, viewBox.height > 0, width > 0, height > 0 else { return .identity }
        let stretch = fit.map { $0 == SvgFitCode.stretch } ?? none
        let cover = fit.map { $0 == SvgFitCode.cover } ?? slice
        let ax = fit == nil ? alignX : 0.5
        let ay = fit == nil ? alignY : 0.5
        var sx = width / viewBox.width
        var sy = height / viewBox.height
        if !stretch {
            let s = cover ? max(sx, sy) : min(sx, sy)
            sx = s
            sy = s
        }
        let tx = (width - viewBox.width * sx) * ax - viewBox.minX * sx
        let ty = (height - viewBox.height * sy) * ay - viewBox.minY * sy
        return CGAffineTransform(a: sx, b: 0, c: 0, d: sy, tx: tx, ty: ty)
    }
}

extension CGRect {
    var standardizedOrZero: CGRect { isNull ? .zero : standardized }
}

// MARK: - XML

final class SvgXmlElement {
    let name: String
    let attributes: [String: String]
    var children: [SvgXmlElement] = []
    var text = ""

    init(name: String, attributes: [String: String]) {
        self.name = name
        self.attributes = attributes
    }
}

final class SvgXmlReader: NSObject, XMLParserDelegate {
    static let maxBytes = 262_144
    static let maxElements = 20_000
    static let maxDepth = 64

    private var stack: [SvgXmlElement] = []
    private var root: SvgXmlElement?
    private var count = 0
    private var failure: String?

    static func parse(_ source: String) throws -> SvgXmlElement {
        guard source.utf8.count <= maxBytes else { throw SvgError.invalid("SVG source exceeds 256 KiB") }
        if source.range(of: "<!DOCTYPE", options: .caseInsensitive) != nil || source.range(of: "<!ENTITY", options: .caseInsensitive) != nil {
            throw SvgError.invalid("DOCTYPE and entity declarations are not allowed")
        }
        let reader = SvgXmlReader()
        let parser = XMLParser(data: Data(source.utf8))
        parser.shouldResolveExternalEntities = false
        parser.shouldProcessNamespaces = false
        parser.delegate = reader
        guard parser.parse(), reader.failure == nil, let root = reader.root else {
            throw SvgError.invalid(reader.failure ?? parser.parserError?.localizedDescription ?? "Malformed SVG")
        }
        return root
    }

    private static func localName(_ name: String) -> String {
        guard let colon = name.firstIndex(of: ":") else { return name }
        return String(name[name.index(after: colon)...])
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        count += 1
        guard count <= Self.maxElements, stack.count < Self.maxDepth else {
            failure = "SVG document too large or too deep"
            parser.abortParsing()
            return
        }
        let element = SvgXmlElement(name: Self.localName(elementName), attributes: attributeDict)
        if let parent = stack.last {
            parent.children.append(element)
        } else if root == nil {
            root = element
        }
        stack.append(element)
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        _ = stack.popLast()
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        stack.last?.text += string
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        stack.last?.text += String(decoding: CDATABlock, as: UTF8.self)
    }
}

// MARK: - Numbers, paths, colors, transforms

struct SvgNumberScanner {
    private let chars: [UInt8]
    private(set) var index = 0

    init(_ text: String) {
        chars = Array(text.utf8)
    }

    private func isDigit(_ c: UInt8) -> Bool { c >= 48 && c <= 57 }
    private func isSeparator(_ c: UInt8) -> Bool { c == 32 || c == 9 || c == 10 || c == 13 || c == 44 }

    mutating func skipSeparators() {
        while index < chars.count, isSeparator(chars[index]) { index += 1 }
    }

    mutating func next() -> CGFloat? {
        skipSeparators()
        let start = index
        if index < chars.count, chars[index] == 43 || chars[index] == 45 { index += 1 }
        var digits = false
        var dot = false
        while index < chars.count {
            let c = chars[index]
            if isDigit(c) { digits = true; index += 1 } else if c == 46, !dot { dot = true; index += 1 } else { break }
        }
        guard digits else { index = start; return nil }
        if index < chars.count, chars[index] == 101 || chars[index] == 69 {
            let save = index
            index += 1
            if index < chars.count, chars[index] == 43 || chars[index] == 45 { index += 1 }
            if index < chars.count, isDigit(chars[index]) {
                while index < chars.count, isDigit(chars[index]) { index += 1 }
            } else {
                index = save
            }
        }
        guard let value = Double(String(decoding: chars[start..<index], as: UTF8.self)), value.isFinite else { return nil }
        return CGFloat(value)
    }

    mutating func take(_ count: Int) -> [CGFloat]? {
        var values: [CGFloat] = []
        values.reserveCapacity(count)
        for _ in 0..<count {
            guard let value = next() else { return nil }
            values.append(value)
        }
        return values
    }

    mutating func flag() -> Bool? {
        skipSeparators()
        guard index < chars.count else { return nil }
        switch chars[index] {
        case 48: index += 1; return false
        case 49: index += 1; return true
        default: return nil
        }
    }

    mutating func peekCommand() -> Character? {
        skipSeparators()
        guard index < chars.count else { return nil }
        let c = chars[index]
        let isLetter = (c >= 65 && c <= 90) || (c >= 97 && c <= 122)
        return isLetter && c != 101 && c != 69 ? Character(UnicodeScalar(c)) : nil
    }

    mutating func advance() { index += 1 }

    mutating func atEnd() -> Bool {
        skipSeparators()
        return index >= chars.count
    }
}

/// Builds a CGPath plus its flattened length.
struct SvgPathBuilder {
    let path = CGMutablePath()
    private(set) var current = CGPoint.zero
    private var start = CGPoint.zero
    private var hasCurrent = false
    private(set) var length: CGFloat = 0
    private(set) var commands = 0

    mutating func move(_ p: CGPoint) {
        path.move(to: p)
        current = p
        start = p
        hasCurrent = true
        commands += 1
    }

    mutating func line(_ p: CGPoint) {
        ensure()
        path.addLine(to: p)
        length += hypot(p.x - current.x, p.y - current.y)
        current = p
        commands += 1
    }

    mutating func cubic(_ c1: CGPoint, _ c2: CGPoint, _ p: CGPoint) {
        ensure()
        path.addCurve(to: p, control1: c1, control2: c2)
        var previous = current
        for step in 1...16 {
            let t = CGFloat(step) / 16
            let u = 1 - t
            let q = CGPoint(
                x: u * u * u * current.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * p.x,
                y: u * u * u * current.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * p.y)
            length += hypot(q.x - previous.x, q.y - previous.y)
            previous = q
        }
        current = p
        commands += 1
    }

    mutating func quad(_ c: CGPoint, _ p: CGPoint) {
        ensure()
        path.addQuadCurve(to: p, control: c)
        var previous = current
        for step in 1...12 {
            let t = CGFloat(step) / 12
            let u = 1 - t
            let q = CGPoint(x: u * u * current.x + 2 * u * t * c.x + t * t * p.x,
                            y: u * u * current.y + 2 * u * t * c.y + t * t * p.y)
            length += hypot(q.x - previous.x, q.y - previous.y)
            previous = q
        }
        current = p
        commands += 1
    }

    mutating func close() {
        guard hasCurrent else { return }
        path.closeSubpath()
        length += hypot(start.x - current.x, start.y - current.y)
        current = start
        commands += 1
    }

    private mutating func ensure() {
        if !hasCurrent { move(current) }
    }

    /// SVG arc endpoint parameterization (F.6) converted to cubic segments.
    mutating func arc(rx rxIn: CGFloat, ry ryIn: CGFloat, rotation: CGFloat, large: Bool, sweep: Bool, to p: CGPoint) {
        let x0 = Double(current.x), y0 = Double(current.y)
        let px = Double(p.x), py = Double(p.y)
        if x0 == px, y0 == py { return }
        var rx = abs(Double(rxIn)), ry = abs(Double(ryIn))
        if rx == 0 || ry == 0 { line(p); return }
        let phi = Double(rotation) * .pi / 180
        let cosPhi = cos(phi), sinPhi = sin(phi)
        let dx2 = (x0 - px) / 2, dy2 = (y0 - py) / 2
        let x1p = cosPhi * dx2 + sinPhi * dy2
        let y1p = -sinPhi * dx2 + cosPhi * dy2
        let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if lambda > 1 { let s = lambda.squareRoot(); rx *= s; ry *= s }
        let num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        let den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = den == 0 ? 0 : max(0, num / den).squareRoot()
        if large == sweep { coef = -coef }
        let cxp = coef * (rx * y1p / ry)
        let cyp = coef * -(ry * x1p / rx)
        let cx = cosPhi * cxp - sinPhi * cyp + (x0 + px) / 2
        let cy = sinPhi * cxp + cosPhi * cyp + (y0 + py) / 2
        let theta1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var delta = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - theta1
        if sweep, delta < 0 { delta += 2 * .pi }
        if !sweep, delta > 0 { delta -= 2 * .pi }
        let segments = max(1, Int((abs(delta) / (.pi / 2)).rounded(.up)))
        let step = delta / Double(segments)
        let k = 4.0 / 3.0 * tan(step / 4)
        var t = theta1
        func map(_ ux: Double, _ uy: Double) -> CGPoint {
            CGPoint(x: cosPhi * ux - sinPhi * uy + cx, y: sinPhi * ux + cosPhi * uy + cy)
        }
        for index in 0..<segments {
            let t2 = t + step
            let c1 = map(rx * cos(t) - k * rx * sin(t), ry * sin(t) + k * ry * cos(t))
            let c2 = map(rx * cos(t2) + k * rx * sin(t2), ry * sin(t2) - k * ry * cos(t2))
            let end = index == segments - 1 ? p : map(rx * cos(t2), ry * sin(t2))
            cubic(c1, c2, end)
            t = t2
        }
    }

    func build() -> SvgPathData {
        SvgPathData(path: path.copy() ?? path, length: length, commandCount: commands)
    }
}

enum SvgPathDataParser {
    static func parse(_ d: String) -> SvgPathData {
        var b = SvgPathBuilder()
        var s = SvgNumberScanner(d)
        var command: Character = " "
        var lastControl = CGPoint.zero
        var lastKind: Character = " "
        loop: while !s.atEnd() {
            if let explicit = s.peekCommand() {
                command = explicit
                s.advance()
            } else if command == " " {
                break
            }
            let relative = command.isLowercase
            let o = relative ? b.current : .zero
            switch command.uppercased() {
            case "M":
                guard let v = s.take(2) else { break loop }
                b.move(CGPoint(x: o.x + v[0], y: o.y + v[1]))
                command = relative ? "l" : "L"
                lastKind = "M"
            case "L":
                guard let v = s.take(2) else { break loop }
                b.line(CGPoint(x: o.x + v[0], y: o.y + v[1]))
                lastKind = "L"
            case "H":
                guard let x = s.next() else { break loop }
                b.line(CGPoint(x: o.x + x, y: b.current.y))
                lastKind = "L"
            case "V":
                guard let y = s.next() else { break loop }
                b.line(CGPoint(x: b.current.x, y: o.y + y))
                lastKind = "L"
            case "C":
                guard let v = s.take(6) else { break loop }
                let c2 = CGPoint(x: o.x + v[2], y: o.y + v[3])
                b.cubic(CGPoint(x: o.x + v[0], y: o.y + v[1]), c2, CGPoint(x: o.x + v[4], y: o.y + v[5]))
                lastControl = c2
                lastKind = "C"
            case "S":
                guard let v = s.take(4) else { break loop }
                let c1 = lastKind == "C" ? CGPoint(x: 2 * b.current.x - lastControl.x, y: 2 * b.current.y - lastControl.y) : b.current
                let c2 = CGPoint(x: o.x + v[0], y: o.y + v[1])
                b.cubic(c1, c2, CGPoint(x: o.x + v[2], y: o.y + v[3]))
                lastControl = c2
                lastKind = "C"
            case "Q":
                guard let v = s.take(4) else { break loop }
                let c = CGPoint(x: o.x + v[0], y: o.y + v[1])
                b.quad(c, CGPoint(x: o.x + v[2], y: o.y + v[3]))
                lastControl = c
                lastKind = "Q"
            case "T":
                guard let v = s.take(2) else { break loop }
                let c = lastKind == "Q" ? CGPoint(x: 2 * b.current.x - lastControl.x, y: 2 * b.current.y - lastControl.y) : b.current
                b.quad(c, CGPoint(x: o.x + v[0], y: o.y + v[1]))
                lastControl = c
                lastKind = "Q"
            case "A":
                guard let r = s.take(3), let large = s.flag(), let sweep = s.flag(), let v = s.take(2) else { break loop }
                b.arc(rx: r[0], ry: r[1], rotation: r[2], large: large, sweep: sweep, to: CGPoint(x: o.x + v[0], y: o.y + v[1]))
                lastKind = "A"
            case "Z":
                b.close()
                lastKind = "Z"
                command = " "
            default:
                break loop
            }
        }
        return b.build()
    }
}

enum SvgColor {
    private static let named: [String: UInt32] = [
        "black": 0x000000, "white": 0xFFFFFF, "red": 0xFF0000, "green": 0x008000, "blue": 0x0000FF,
        "yellow": 0xFFFF00, "orange": 0xFFA500, "purple": 0x800080, "gray": 0x808080, "grey": 0x808080,
        "silver": 0xC0C0C0, "maroon": 0x800000, "navy": 0x000080, "teal": 0x008080, "lime": 0x00FF00,
        "aqua": 0x00FFFF, "cyan": 0x00FFFF, "fuchsia": 0xFF00FF, "magenta": 0xFF00FF, "olive": 0x808000,
        "pink": 0xFFC0CB, "gold": 0xFFD700, "brown": 0xA52A2A, "darkgray": 0xA9A9A9, "lightgray": 0xD3D3D3,
    ]

    static func parse(_ raw: String) -> SvgPaint? {
        let v = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if v == "none" { return SvgPaint.none }
        if v == "transparent" { return .solid(0) }
        if v.caseInsensitiveCompare("currentColor") == .orderedSame { return .current }
        if v.hasPrefix("#") { return hex(String(v.dropFirst())).map { .solid($0) } }
        if v.hasPrefix("rgb") { return rgb(v).map { .solid($0) } }
        return named[v.lowercased()].map { .solid(0xFF00_0000 | $0) }
    }

    static func argb(_ raw: String) -> UInt32? {
        if case let .solid(value)? = parse(raw) { return value }
        return nil
    }

    private static func hex(_ h: String) -> UInt32? {
        guard let value = UInt32(h, radix: 16) else { return nil }
        switch h.count {
        case 3:
            let r = (value >> 8) & 0xF, g = (value >> 4) & 0xF, b = value & 0xF
            return 0xFF00_0000 | (r * 17) << 16 | (g * 17) << 8 | (b * 17)
        case 4:
            let r = (value >> 12) & 0xF, g = (value >> 8) & 0xF, b = (value >> 4) & 0xF, a = value & 0xF
            return (a * 17) << 24 | (r * 17) << 16 | (g * 17) << 8 | (b * 17)
        case 6: return 0xFF00_0000 | value
        case 8: return (value & 0xFF) << 24 | (value >> 8)
        default: return nil
        }
    }

    private static func rgb(_ v: String) -> UInt32? {
        guard let open = v.firstIndex(of: "("), let close = v.firstIndex(of: ")") , open < close else { return nil }
        let parts = v[v.index(after: open)..<close]
            .split(whereSeparator: { $0 == "," || $0 == " " || $0 == "/" })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard parts.count >= 3 else { return nil }
        func channel(_ p: String) -> UInt32? {
            if p.hasSuffix("%") { return Double(p.dropLast()).map { UInt32(max(0, min(255, $0 * 2.55))) } }
            return Double(p).map { UInt32(max(0, min(255, $0))) }
        }
        guard let r = channel(parts[0]), let g = channel(parts[1]), let b = channel(parts[2]) else { return nil }
        var alpha = 1.0
        if parts.count > 3 {
            let p = parts[3]
            alpha = (p.hasSuffix("%") ? Double(p.dropLast()).map { $0 / 100 } : Double(p)) ?? 1
        }
        return UInt32((max(0, min(1, alpha)) * 255).rounded()) << 24 | r << 16 | g << 8 | b
    }

    static func multiplyAlpha(_ argb: UInt32, _ factor: CGFloat) -> UInt32 {
        guard factor < 1 else { return argb }
        let alpha = UInt32((CGFloat(argb >> 24) * max(0, factor)).rounded())
        return alpha << 24 | (argb & 0x00FF_FFFF)
    }

    static func cgColor(_ argb: UInt32) -> CGColor {
        CGColor(srgbRed: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat(argb >> 24) / 255)
    }
}

enum SvgValues {
    static func number(_ value: String?) -> CGFloat? {
        guard var v = value?.trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
        if v.hasSuffix("px") { v.removeLast(2) }
        guard let d = Double(v.trimmingCharacters(in: .whitespaces)), d.isFinite else { return nil }
        return CGFloat(d)
    }

    static func length(_ value: String?) -> CGFloat? {
        guard let v = value?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty else { return nil }
        var unit = ""
        var numberPart = Substring(v)
        while let last = numberPart.last, last.isLetter || last == "%" {
            unit = String(last) + unit
            numberPart = numberPart.dropLast()
        }
        guard let d = Double(numberPart.trimmingCharacters(in: .whitespaces)), d.isFinite else { return nil }
        let n = CGFloat(d)
        switch unit {
        case "", "px": return n
        case "pt": return n * 4 / 3
        case "pc": return n * 16
        case "mm": return n * 96 / 25.4
        case "cm": return n * 96 / 2.54
        case "in": return n * 96
        default: return nil
        }
    }

    static func numbers(_ value: String) -> [CGFloat] {
        var scanner = SvgNumberScanner(value)
        var result: [CGFloat] = []
        while let n = scanner.next() { result.append(n) }
        return result
    }

    static func fraction(_ value: String?, _ fallback: CGFloat) -> CGFloat {
        guard let raw = value?.trimmingCharacters(in: .whitespaces) else { return fallback }
        if raw.hasSuffix("%") { return (number(String(raw.dropLast())) ?? 0) / 100 }
        return number(raw) ?? fallback
    }

    static func urlId(_ value: String) -> String? {
        guard let start = value.range(of: "url("), let hash = value[start.upperBound...].firstIndex(of: "#") else { return nil }
        let rest = value[value.index(after: hash)...]
        let id = rest.prefix { $0 != ")" && $0 != "'" && $0 != "\"" && !$0.isWhitespace }
        return id.isEmpty ? nil : String(id)
    }

    static func transform(_ value: String) -> CGAffineTransform? {
        var m = CGAffineTransform.identity
        var any = false
        var rest = Substring(value)
        while let open = rest.firstIndex(of: "("), let close = rest[open...].firstIndex(of: ")") {
            let name = rest[..<open].trimmingCharacters(in: CharacterSet(charactersIn: " ,\t\n\r"))
            let a = numbers(String(rest[rest.index(after: open)..<close]))
            rest = rest[rest.index(after: close)...]
            let next: CGAffineTransform
            switch name {
            case "matrix":
                guard a.count == 6 else { continue }
                next = CGAffineTransform(a: a[0], b: a[1], c: a[2], d: a[3], tx: a[4], ty: a[5])
            case "translate":
                next = CGAffineTransform(translationX: a.first ?? 0, y: a.count > 1 ? a[1] : 0)
            case "scale":
                let sx = a.first ?? 1
                next = CGAffineTransform(scaleX: sx, y: a.count > 1 ? a[1] : sx)
            case "rotate":
                let r = CGAffineTransform(rotationAngle: (a.first ?? 0) * .pi / 180)
                if a.count >= 3 {
                    next = CGAffineTransform(translationX: -a[1], y: -a[2]).concatenating(r).concatenating(CGAffineTransform(translationX: a[1], y: a[2]))
                } else {
                    next = r
                }
            case "skewX":
                next = CGAffineTransform(a: 1, b: 0, c: tan((a.first ?? 0) * .pi / 180), d: 1, tx: 0, ty: 0)
            case "skewY":
                next = CGAffineTransform(a: 1, b: tan((a.first ?? 0) * .pi / 180), c: 0, d: 1, tx: 0, ty: 0)
            default:
                continue
            }
            // SVG lists apply left-to-right in the parent's frame: M = M_list[0] * M_list[1] ...
            m = next.concatenating(m)
            any = true
        }
        return any ? m : nil
    }
}

// MARK: - Document builder

enum SvgParser {
    private static let kappa: CGFloat = 0.5522847498
    private static let maxUseDepth = 8
    private static let inherited: Set<String> = [
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
        "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "stroke-opacity", "color", "visibility",
    ]
    private static let stylable = inherited.union(["opacity", "display", "stop-color", "stop-opacity", "clip-path", "transform"])

    static func parse(_ source: String) throws -> SvgDocument {
        let root = try SvgXmlReader.parse(source)
        guard root.name == "svg" else { throw SvgError.invalid("Root element must be <svg>") }
        return Builder(root: root).build()
    }

    private final class Builder {
        let root: SvgXmlElement
        var elementsById: [String: SvgXmlElement] = [:]
        var rules: [(String, [String: String])] = []
        var gradients: [String: SvgGradient] = [:]
        var clips: [String: SvgClip] = [:]
        var byId: [String: SvgNode] = [:]
        var bytes = 0

        init(root: SvgXmlElement) {
            self.root = root
        }

        func build() -> SvgDocument {
            index(root)
            let box = SvgValues.numbers(root.attributes["viewBox"] ?? "")
            let width = SvgValues.length(root.attributes["width"]) ?? (box.count == 4 ? box[2] : 24)
            let height = SvgValues.length(root.attributes["height"]) ?? (box.count == 4 ? box[3] : 24)
            let viewBox = box.count == 4 && box[2] > 0 && box[3] > 0
                ? CGRect(x: box[0], y: box[1], width: box[2], height: box[3])
                : CGRect(x: 0, y: 0, width: width, height: height)
            let aspect = (root.attributes["preserveAspectRatio"] ?? "xMidYMid meet").split(separator: " ").map(String.init)
            let align = aspect.first ?? "xMidYMid"
            let ax: CGFloat = align.hasPrefix("xMin") ? 0 : (align.hasPrefix("xMax") ? 1 : 0.5)
            let ay: CGFloat = align.contains("YMin") ? 0 : (align.contains("YMax") ? 1 : 0.5)
            for element in elementsById.values where element.name == "linearGradient" || element.name == "radialGradient" {
                if let gradient = gradient(element) { gradients[gradient.id] = gradient }
            }
            for element in elementsById.values where element.name == "clipPath" {
                if let id = element.attributes["id"] { clips[id] = clip(element) }
            }
            let top = node(root, parent: SvgStyle(), useDepth: 0) as? SvgGroup
            let group = SvgGroup(id: nil, transform: nil, opacity: 1, clipId: nil, children: top?.children ?? [])
            return SvgDocument(viewBox: viewBox, alignX: ax, alignY: ay, none: align == "none",
                               slice: aspect.count > 1 && aspect[1] == "slice", root: group, gradients: gradients,
                               clips: clips, byId: byId, estimatedBytes: bytes + 256)
        }

        func index(_ element: SvgXmlElement) {
            if let id = element.attributes["id"], elementsById[id] == nil { elementsById[id] = element }
            if element.name == "style" { stylesheet(element.text) }
            element.children.forEach(index)
        }

        func stylesheet(_ css: String) {
            var text = css
            while let start = text.range(of: "/*"), let end = text.range(of: "*/", range: start.upperBound..<text.endIndex) {
                text.removeSubrange(start.lowerBound..<end.upperBound)
            }
            for block in text.split(separator: "}") {
                let parts = block.split(separator: "{", maxSplits: 1)
                guard parts.count == 2 else { continue }
                let values = declarations(String(parts[1]))
                for selector in parts[0].split(separator: ",") {
                    let trimmed = selector.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty { rules.append((trimmed, values)) }
                }
            }
        }

        func declarations(_ text: String) -> [String: String] {
            var result: [String: String] = [:]
            for part in text.split(separator: ";") {
                guard let colon = part.firstIndex(of: ":") else { continue }
                let key = part[..<colon].trimmingCharacters(in: .whitespacesAndNewlines)
                guard SvgParser.stylable.contains(key) else { continue }
                result[key] = part[part.index(after: colon)...].replacingOccurrences(of: "!important", with: "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            }
            return result
        }

        func properties(_ element: SvgXmlElement) -> [String: String] {
            var result: [String: String] = [:]
            for (key, value) in element.attributes where SvgParser.stylable.contains(key) { result[key] = value }
            if !rules.isEmpty {
                let classes = Set((element.attributes["class"] ?? "").split(separator: " ").map(String.init))
                let id = element.attributes["id"]
                for rank in 0...2 {
                    for (selector, values) in rules {
                        let matches: Bool
                        switch rank {
                        case 0: matches = selector == element.name || selector == "*"
                        case 1: matches = selector.hasPrefix(".") && classes.contains(String(selector.dropFirst()))
                        default: matches = selector.hasPrefix("#") && String(selector.dropFirst()) == id
                        }
                        if matches { result.merge(values) { _, new in new } }
                    }
                }
            }
            if let style = element.attributes["style"] { result.merge(declarations(style)) { _, new in new } }
            return result
        }

        func node(_ element: SvgXmlElement, parent: SvgStyle, useDepth: Int) -> SvgNode? {
            let props = properties(element)
            if props["display"] == "none" { return nil }
            let style = inherit(parent, props)
            let id = element.attributes["id"]
            let transform = props["transform"].flatMap(SvgValues.transform)
            let opacity = min(1, max(0, SvgValues.number(props["opacity"]) ?? 1))
            let clipId = props["clip-path"].flatMap(SvgValues.urlId)
            var result: SvgNode?
            switch element.name {
            case "svg", "g", "a", "switch", "symbol":
                if element.name == "symbol", useDepth == 0 { return nil }
                var groupTransform = transform
                if element !== root, element.name == "svg" {
                    let x = SvgValues.length(element.attributes["x"]) ?? 0
                    let y = SvgValues.length(element.attributes["y"]) ?? 0
                    if x != 0 || y != 0 {
                        groupTransform = CGAffineTransform(translationX: x, y: y).concatenating(groupTransform ?? .identity)
                    }
                }
                let children = element.children.compactMap { node($0, parent: style, useDepth: useDepth) }
                result = SvgGroup(id: id, transform: groupTransform, opacity: opacity, clipId: clipId, children: children)
            case "use":
                guard useDepth < SvgParser.maxUseDepth,
                      let href = (element.attributes["href"] ?? element.attributes["xlink:href"]),
                      let target = elementsById[String(href.drop { $0 == "#" })], target !== element else { return nil }
                let x = SvgValues.length(element.attributes["x"]) ?? 0
                let y = SvgValues.length(element.attributes["y"]) ?? 0
                let placed = CGAffineTransform(translationX: x, y: y).concatenating(transform ?? .identity)
                guard let child = node(target, parent: style, useDepth: useDepth + 1) else { return nil }
                result = SvgGroup(id: id, transform: placed, opacity: opacity, clipId: clipId, children: [child])
            case "path", "circle", "ellipse", "rect", "line", "polyline", "polygon":
                guard let data = geometry(element) else { return nil }
                bytes += data.commandCount * 32 + 96
                result = SvgShape(
                    id: id, transform: transform, opacity: opacity, clipId: clipId, data: data, style: style,
                    pathLength: SvgValues.number(element.attributes["pathLength"]).flatMap { $0 > 0 ? $0 : nil },
                    trimStart: min(1, max(0, SvgValues.number(element.attributes["pam-trim-start"]) ?? 0)),
                    trimEnd: min(1, max(0, SvgValues.number(element.attributes["pam-trim-end"]) ?? 1)))
            default:
                return nil
            }
            if let id, let result, byId[id] == nil { byId[id] = result }
            return result
        }

        func clip(_ element: SvgXmlElement) -> SvgClip {
            var shapes: [SvgShape] = []
            func collect(_ e: SvgXmlElement, _ parentTransform: CGAffineTransform?) {
                let props = properties(e)
                let own = props["transform"].flatMap(SvgValues.transform)
                let combined: CGAffineTransform?
                if let own, let parentTransform { combined = own.concatenating(parentTransform) } else { combined = own ?? parentTransform }
                if let data = geometry(e) {
                    shapes.append(SvgShape(id: nil, transform: combined, opacity: 1, clipId: nil, data: data,
                                           style: inherit(SvgStyle(), props), pathLength: nil, trimStart: 0, trimEnd: 1))
                }
                if e.name == "g" { e.children.forEach { collect($0, combined) } }
                if e.name == "use", let href = e.attributes["href"] ?? e.attributes["xlink:href"],
                   let target = elementsById[String(href.drop { $0 == "#" })], target !== e, target.name != "use" {
                    collect(target, combined)
                }
            }
            element.children.forEach { collect($0, nil) }
            return SvgClip(id: element.attributes["id"] ?? "", shapes: shapes)
        }

        func gradient(_ element: SvgXmlElement) -> SvgGradient? {
            guard let id = element.attributes["id"] else { return nil }
            var chain: [SvgXmlElement] = []
            var current: SvgXmlElement? = element
            while let c = current, chain.count < 8, !chain.contains(where: { $0 === c }) {
                chain.append(c)
                current = (c.attributes["href"] ?? c.attributes["xlink:href"]).flatMap { elementsById[String($0.drop { $0 == "#" })] }
            }
            func attr(_ name: String) -> String? { chain.lazy.compactMap { $0.attributes[name] }.first }
            guard let owner = chain.first(where: { $0.children.contains { $0.name == "stop" } }) else { return nil }
            let radial = element.name == "radialGradient"
            let coordinates: [CGFloat]
            if radial {
                let cx = SvgValues.fraction(attr("cx"), 0.5), cy = SvgValues.fraction(attr("cy"), 0.5)
                coordinates = [cx, cy, SvgValues.fraction(attr("r"), 0.5), SvgValues.fraction(attr("fx"), cx), SvgValues.fraction(attr("fy"), cy)]
            } else {
                coordinates = [SvgValues.fraction(attr("x1"), 0), SvgValues.fraction(attr("y1"), 0),
                               SvgValues.fraction(attr("x2"), 1), SvgValues.fraction(attr("y2"), 0)]
            }
            var last: CGFloat = 0
            let stops: [SvgStop] = owner.children.filter { $0.name == "stop" }.prefix(64).map { stop in
                let props = properties(stop)
                let offset = max(last, min(1, max(0, SvgValues.fraction(stop.attributes["offset"], 0))))
                last = offset
                let color = SvgColor.argb(props["stop-color"] ?? "black") ?? 0xFF00_0000
                let alpha = min(1, max(0, SvgValues.number(props["stop-opacity"]) ?? 1))
                return SvgStop(offset: offset, argb: SvgColor.multiplyAlpha(color, alpha))
            }
            return SvgGradient(id: id, radial: radial, userSpace: attr("gradientUnits") == "userSpaceOnUse",
                               coordinates: coordinates, stops: stops, transform: attr("gradientTransform").flatMap(SvgValues.transform))
        }

        func paint(_ value: String) -> SvgPaint? {
            let v = value.trimmingCharacters(in: .whitespaces)
            if v.hasPrefix("url(") {
                guard let id = SvgValues.urlId(v) else { return nil }
                let tail = v.split(separator: ")", maxSplits: 1).dropFirst().first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
                return .ref(id, tail.isEmpty ? SvgPaint.none : (SvgColor.parse(tail) ?? SvgPaint.none))
            }
            return SvgColor.parse(v)
        }

        func inherit(_ parent: SvgStyle, _ p: [String: String]) -> SvgStyle {
            guard p.keys.contains(where: { SvgParser.inherited.contains($0) }) else { return parent }
            var s = parent
            if let v = p["color"], let c = SvgColor.parse(v) { s.color = c == .current ? parent.color : c }
            if let v = p["fill"], let c = paint(v) { s.fill = c }
            if let v = p["stroke"], let c = paint(v) { s.stroke = c }
            if let v = SvgValues.number(p["fill-opacity"]) { s.fillOpacity = min(1, max(0, v)) }
            if let v = SvgValues.number(p["stroke-opacity"]) { s.strokeOpacity = min(1, max(0, v)) }
            if let v = p["fill-rule"] { s.evenOdd = v.trimmingCharacters(in: .whitespaces) == "evenodd" }
            if let v = SvgValues.length(p["stroke-width"]) { s.strokeWidth = max(0, v) }
            if let v = p["stroke-linecap"]?.trimmingCharacters(in: .whitespaces) { s.lineCap = v == "round" ? 2 : (v == "square" ? 3 : 1) }
            if let v = p["stroke-linejoin"]?.trimmingCharacters(in: .whitespaces) { s.lineJoin = v == "round" ? 2 : (v == "bevel" ? 3 : 1) }
            if let v = SvgValues.number(p["stroke-miterlimit"]) { s.miterLimit = max(1, v) }
            if let v = p["stroke-dasharray"] {
                let values = SvgValues.numbers(v).map { max(0, $0) }
                s.dashes = values.isEmpty || values.allSatisfy { $0 == 0 } ? nil : (values.count % 2 == 1 ? values + values : values)
            }
            if let v = SvgValues.length(p["stroke-dashoffset"]) { s.dashOffset = v }
            if let v = p["visibility"]?.trimmingCharacters(in: .whitespaces) { s.visible = v != "hidden" && v != "collapse" }
            return s
        }

        func geometry(_ e: SvgXmlElement) -> SvgPathData? {
            let a = e.attributes
            func n(_ name: String) -> CGFloat { SvgValues.length(a[name]) ?? 0 }
            var b = SvgPathBuilder()
            switch e.name {
            case "path":
                guard let d = a["d"] else { return nil }
                let data = SvgPathDataParser.parse(d)
                return data.commandCount > 0 ? data : nil
            case "circle":
                let r = n("r")
                guard r > 0 else { return nil }
                ellipse(&b, n("cx"), n("cy"), r, r)
            case "ellipse":
                let rx = SvgValues.length(a["rx"]) ?? SvgValues.length(a["ry"]) ?? 0
                let ry = SvgValues.length(a["ry"]) ?? rx
                guard rx > 0, ry > 0 else { return nil }
                ellipse(&b, n("cx"), n("cy"), rx, ry)
            case "rect":
                let x = n("x"), y = n("y"), w = n("width"), h = n("height")
                guard w > 0, h > 0 else { return nil }
                var rx = SvgValues.length(a["rx"]), ry = SvgValues.length(a["ry"])
                if rx == nil { rx = ry }
                if ry == nil { ry = rx }
                let cx = min(max(0, rx ?? 0), w / 2), cy = min(max(0, ry ?? 0), h / 2)
                if cx == 0 || cy == 0 {
                    b.move(CGPoint(x: x, y: y)); b.line(CGPoint(x: x + w, y: y)); b.line(CGPoint(x: x + w, y: y + h)); b.line(CGPoint(x: x, y: y + h)); b.close()
                } else {
                    let kx = cx * SvgParser.kappa, ky = cy * SvgParser.kappa
                    b.move(CGPoint(x: x + cx, y: y)); b.line(CGPoint(x: x + w - cx, y: y))
                    b.cubic(CGPoint(x: x + w - cx + kx, y: y), CGPoint(x: x + w, y: y + cy - ky), CGPoint(x: x + w, y: y + cy)); b.line(CGPoint(x: x + w, y: y + h - cy))
                    b.cubic(CGPoint(x: x + w, y: y + h - cy + ky), CGPoint(x: x + w - cx + kx, y: y + h), CGPoint(x: x + w - cx, y: y + h)); b.line(CGPoint(x: x + cx, y: y + h))
                    b.cubic(CGPoint(x: x + cx - kx, y: y + h), CGPoint(x: x, y: y + h - cy + ky), CGPoint(x: x, y: y + h - cy)); b.line(CGPoint(x: x, y: y + cy))
                    b.cubic(CGPoint(x: x, y: y + cy - ky), CGPoint(x: x + cx - kx, y: y), CGPoint(x: x + cx, y: y)); b.close()
                }
            case "line":
                b.move(CGPoint(x: n("x1"), y: n("y1")))
                b.line(CGPoint(x: n("x2"), y: n("y2")))
            case "polyline", "polygon":
                let p = SvgValues.numbers(a["points"] ?? "")
                guard p.count >= 4 else { return nil }
                b.move(CGPoint(x: p[0], y: p[1]))
                var i = 2
                while i + 1 < p.count { b.line(CGPoint(x: p[i], y: p[i + 1])); i += 2 }
                if e.name == "polygon" { b.close() }
            default:
                return nil
            }
            return b.build()
        }

        /// Starts at (cx + rx, cy) and runs toward (cx, cy + ry), as the SVG spec requires.
        func ellipse(_ b: inout SvgPathBuilder, _ cx: CGFloat, _ cy: CGFloat, _ rx: CGFloat, _ ry: CGFloat) {
            let kx = rx * SvgParser.kappa, ky = ry * SvgParser.kappa
            b.move(CGPoint(x: cx + rx, y: cy))
            b.cubic(CGPoint(x: cx + rx, y: cy + ky), CGPoint(x: cx + kx, y: cy + ry), CGPoint(x: cx, y: cy + ry))
            b.cubic(CGPoint(x: cx - kx, y: cy + ry), CGPoint(x: cx - rx, y: cy + ky), CGPoint(x: cx - rx, y: cy))
            b.cubic(CGPoint(x: cx - rx, y: cy - ky), CGPoint(x: cx - kx, y: cy - ry), CGPoint(x: cx, y: cy - ry))
            b.cubic(CGPoint(x: cx + kx, y: cy - ry), CGPoint(x: cx + rx, y: cy - ky), CGPoint(x: cx + rx, y: cy))
            b.close()
        }
    }
}

// MARK: - Cache

/// Process-wide LRU of parsed documents keyed by the PHP content hash.
final class SvgDocumentCache: @unchecked Sendable {
    static let shared = SvgDocumentCache()

    private let lock = NSLock()
    private let maxEntries: Int
    private let maxBytes: Int
    private var entries: [String: SvgDocument] = [:]
    private var order: [String] = []
    private var bytes = 0

    init(maxEntries: Int = 64, maxBytes: Int = 2 * 1024 * 1024) {
        self.maxEntries = maxEntries
        self.maxBytes = maxBytes
    }

    func get(_ key: String) -> SvgDocument? {
        lock.lock(); defer { lock.unlock() }
        guard let document = entries[key] else { return nil }
        touch(key)
        return document
    }

    func put(_ key: String, _ document: SvgDocument) {
        lock.lock(); defer { lock.unlock() }
        if let old = entries[key] { bytes -= old.estimatedBytes }
        entries[key] = document
        bytes += document.estimatedBytes
        touch(key)
        while (entries.count > maxEntries || bytes > maxBytes), let eldest = order.first, eldest != key {
            order.removeFirst()
            if let removed = entries.removeValue(forKey: eldest) { bytes -= removed.estimatedBytes }
        }
    }

    func obtain(_ key: String, _ source: String) throws -> SvgDocument {
        if let cached = get(key) { return cached }
        let document = try SvgParser.parse(source)
        put(key, document)
        return document
    }

    func trim(keeping fraction: Double) {
        lock.lock(); defer { lock.unlock() }
        let keep = Int(Double(entries.count) * fraction)
        while entries.count > keep, let eldest = order.first {
            order.removeFirst()
            if let removed = entries.removeValue(forKey: eldest) { bytes -= removed.estimatedBytes }
        }
    }

    var count: Int { lock.lock(); defer { lock.unlock() }; return entries.count }

    private func touch(_ key: String) {
        if let index = order.firstIndex(of: key) { order.remove(at: index) }
        order.append(key)
    }
}

// MARK: - Animation specs

struct SvgAnimationSpec: Equatable {
    let id: String
    let property: Int
    let to: CGFloat
    let from: CGFloat?
    let duration: Double
    let easing: Int
    let delay: Double
    let repeatCount: Int
    let reverse: Bool
    let animateOnMount: Bool
    let origin: CGPoint?

    var key: String { "\(id):\(property)" }

    var endValue: CGFloat? {
        // Even iteration counts with autoreverse settle back at the start value.
        reverse && repeatCount >= 0 && (repeatCount + 1) % 2 == 0 ? nil : to
    }

    static func parseAll(_ json: String?) -> [SvgAnimationSpec] {
        guard let json, let data = json.data(using: .utf8),
              let entries = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
        return entries.prefix(64).compactMap { entry in
            guard let id = entry["i"] as? String,
                  let property = (entry["p"] as? NSNumber)?.intValue, (1...9).contains(property),
                  let to = (entry["t"] as? NSNumber)?.doubleValue, to.isFinite else { return nil }
            let origin = (entry["o"] as? [NSNumber]).flatMap { $0.count == 2 ? CGPoint(x: $0[0].doubleValue, y: $0[1].doubleValue) : nil }
            return SvgAnimationSpec(
                id: id, property: property, to: CGFloat(to),
                from: (entry["f"] as? NSNumber).map { CGFloat($0.doubleValue) },
                duration: min(60, max(0, ((entry["d"] as? NSNumber)?.doubleValue ?? 250) / 1000)),
                easing: (entry["e"] as? NSNumber)?.intValue ?? SvgEasingCode.easeInOut,
                delay: min(60, max(0, ((entry["w"] as? NSNumber)?.doubleValue ?? 0) / 1000)),
                repeatCount: min(10_000, max(-1, (entry["r"] as? NSNumber)?.intValue ?? 0)),
                reverse: (entry["a"] as? NSNumber)?.intValue == 1,
                animateOnMount: (entry["m"] as? NSNumber)?.intValue == 1,
                origin: origin)
        }
    }
}
