package dev.pam.svg

import android.animation.ValueAnimator
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.animation.AnimationUtils
import android.annotation.TargetApi
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.views.NativeViewFactory
import java.util.concurrent.Executors

/** Registers `svg.view`. */
class SvgViewFactory(context: Context) : NativeViewFactory {
    init {
        registerMemoryTrim(context.applicationContext)
    }

    override fun create(context: Context, emit: (ByteArray) -> Unit): View = PamSvgView(context, emit)
    override fun update(view: View, properties: Map<String, WireValue>) = (view as PamSvgView).update(properties)
    override fun release(view: View) = (view as PamSvgView).release()

    private companion object {
        @Volatile var trimRegistered = false

        fun registerMemoryTrim(context: Context) {
            if (trimRegistered) return
            synchronized(this) {
                if (trimRegistered) return
                trimRegistered = true
                context.registerComponentCallbacks(object : ComponentCallbacks2 {
                    @Suppress("DEPRECATION")
                    override fun onTrimMemory(level: Int) {
                        when {
                            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> SvgDocumentCache.shared.trim(0f)
                            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> SvgDocumentCache.shared.trim(0.5f)
                        }
                    }
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    @Deprecated("Deprecated in Java") override fun onLowMemory() = SvgDocumentCache.shared.trim(0f)
                })
            }
        }
    }
}

/** Lazily built Android objects for one node, shared by every view rendering the same cached document. */
private class NodePlatform {
    var matrix: Matrix? = null
    var path: Path? = null
    var shaders: HashMap<String, Shader>? = null
    var dash: DashPathEffect? = null
}

/**
 * Retained SVG renderer.
 *
 * The document is parsed once per content hash and its Paths/Shaders/Matrices
 * are built once per document. A frame callback runs only while a native
 * animation is active and the view is attached and visible. On API 29+ a node
 * whose animations only touch opacity/transform is recorded once into its own
 * RenderNode and each frame merely updates RenderNode properties.
 */
internal class PamSvgView(context: Context, private val emit: (ByteArray) -> Unit) : View(context), Choreographer.FrameCallback {
    internal var document: SvgDocument? = null
        private set
    private var key: String? = null
    private var loadGeneration = 0
    private var tint: Int? = null
    private var currentColor: Int? = null
    private var fitOverride: Int? = null
    private var events = false

    private val specs = LinkedHashMap<String, SvgAnimationSpec>()
    private val tracks = LinkedHashMap<String, SvgTrack>()
    /** node id -> overrides indexed by SvgProperty (NaN = static value). */
    private val overrides = HashMap<String, FloatArray>()
    private val pivots = HashMap<String, FloatArray>()
    private var pendingSpecs: List<SvgAnimationSpec> = emptyList()
    private var frameScheduled = false
    private var visibleToUser = true

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val measure = PathMeasure()
    private val trimmed = Path()
    private val segment = Path()
    private var layers: Any? = null

    fun update(values: Map<String, WireValue>) {
        tint = (values["tint"] as? WireValue.Text)?.value?.let(SvgColor::argb)
        currentColor = (values["color"] as? WireValue.Text)?.value?.let(SvgColor::argb)
        fitOverride = if ((values["fitSet"] as? WireValue.Flag)?.value == true) (values["fit"] as? WireValue.Integer)?.value?.toInt() else null
        events = (values["events"] as? WireValue.Flag)?.value == true
        pendingSpecs = SvgAnimationSpec.parseAll((values["anim"] as? WireValue.Text)?.value)
        val nextKey = (values["key"] as? WireValue.Text)?.value
        val source = (values["source"] as? WireValue.Text)?.value
        if (nextKey != null && source != null && nextKey != key) {
            key = nextKey
            load(nextKey, source)
        } else {
            applySpecs()
        }
        invalidateLayers()
        invalidate()
    }

    private fun load(nextKey: String, source: String) {
        val generation = ++loadGeneration
        val cached = SvgDocumentCache.shared.get(nextKey)
        if (cached != null || source.length <= SYNC_PARSE_LIMIT) {
            setDocument(cached ?: runCatching { SvgDocumentCache.shared.obtain(nextKey, source) }.getOrNull())
            return
        }
        parser.execute {
            val parsed = runCatching { SvgDocumentCache.shared.obtain(nextKey, source) }.getOrNull()
            main.post { if (generation == loadGeneration) { setDocument(parsed); invalidate() } }
        }
    }

    private fun setDocument(next: SvgDocument?) {
        document = next
        specs.clear()
        tracks.clear()
        overrides.clear()
        pivots.clear()
        invalidateLayers()
        applySpecs()
    }

    private fun applySpecs() {
        val doc = document ?: return
        val now = AnimationUtils.currentAnimationTimeMillis()
        val incoming = pendingSpecs.associateBy { it.key }
        val removed = specs.keys - incoming.keys
        for (gone in removed) {
            val spec = specs.remove(gone) ?: continue
            tracks.remove(gone)
            overrides[spec.id]?.set(spec.property, Float.NaN)
        }
        val animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
        for (spec in incoming.values) {
            val previous = specs[spec.key]
            if (previous == spec) continue
            val node = doc.byId[spec.id] ?: continue
            specs[spec.key] = spec
            if (spec.originX != null && spec.originY != null) pivots[spec.id] = floatArrayOf(spec.originX, spec.originY)
            val current = currentValue(node, spec.property)
            val immediate = (previous == null && spec.from == null && !spec.animateOnMount) ||
                (spec.duration == 0L && spec.delay == 0L) ||
                (!animatorsEnabled && spec.repeat >= 0)
            if (immediate) {
                tracks.remove(spec.key)
                setOverride(spec.id, spec.property, if (!animatorsEnabled) SvgTrack(spec, spec.from ?: current, now).endValue() else spec.to)
                if (previous != null && events) emitEnd(spec)
            } else {
                val track = SvgTrack(spec, spec.from ?: current, now)
                tracks[spec.key] = track
                setOverride(spec.id, spec.property, track.value(now))
            }
        }
        scheduleFrame()
    }

    private fun currentValue(node: SvgNode, property: Int): Float {
        val override = overrides[node.id ?: ""]?.getOrNull(property)
        if (override != null && !override.isNaN()) return override
        return staticValue(node, property)
    }

    private fun staticValue(node: SvgNode, property: Int): Float {
        val shape = node as? SvgShape
        return when (property) {
            SvgProperty.OPACITY -> node.opacity
            SvgProperty.STROKE_DASHOFFSET -> shape?.style?.dashOffset ?: 0f
            SvgProperty.TRIM_START -> shape?.trimStart ?: 0f
            SvgProperty.TRIM_END -> shape?.trimEnd ?: 1f
            SvgProperty.SCALE -> 1f
            SvgProperty.STROKE_WIDTH -> shape?.style?.strokeWidth ?: 1f
            else -> 0f
        }
    }

    private fun setOverride(id: String, property: Int, value: Float) {
        overrides.getOrPut(id) { FloatArray(10) { Float.NaN } }[property] = value
    }

    // -- frame loop -------------------------------------------------------------------------

    private fun scheduleFrame() {
        if (frameScheduled || tracks.isEmpty() || !isAttachedToWindow || !visibleToUser) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (tracks.isEmpty() || !isAttachedToWindow || !visibleToUser) return
        val now = AnimationUtils.currentAnimationTimeMillis()
        var contentChanged = false
        val iterator = tracks.values.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            val spec = track.spec
            setOverride(spec.id, spec.property, track.value(now))
            if (!SvgProperty.isLayerProperty(spec.property)) contentChanged = true
            if (track.finished(now)) {
                setOverride(spec.id, spec.property, track.endValue())
                iterator.remove()
                if (events) emitEnd(spec)
            }
        }
        if (contentChanged) invalidateLayers()
        invalidate()
        scheduleFrame()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible
        if (isVisible) scheduleFrame() else stopFrames()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        releaseLayers()
        super.onDetachedFromWindow()
    }

    private fun stopFrames() {
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
    }

    fun release() {
        stopFrames()
        tracks.clear()
        specs.clear()
        overrides.clear()
        releaseLayers()
        document = null
        key = null
        loadGeneration++
    }

    private fun emitEnd(spec: SvgAnimationSpec) {
        runCatching {
            emit(WireMap.encode(mapOf(
                "event" to WireValue.Integer(1),
                "id" to WireValue.Text(spec.id),
                "property" to WireValue.Integer(spec.property.toLong()),
            )))
        }
    }

    // -- drawing ---------------------------------------------------------------------------

    internal fun isAnimating(): Boolean = tracks.isNotEmpty()

    internal fun overrideValue(id: String, property: Int): Float? = overrides[id]?.get(property)?.takeIf { !it.isNaN() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val doc = document ?: return
        val fit = doc.fit(width.toFloat(), height.toFloat(), fitOverride)
        val checkpoint = canvas.save()
        canvas.translate(fit[2], fit[3])
        canvas.scale(fit[0], fit[1])
        for (child in doc.root.children) drawNode(canvas, doc, child, 1f, allowLayers = true)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawNode(canvas: Canvas, doc: SvgDocument, node: SvgNode, parentAlpha: Float, allowLayers: Boolean) {
        val o = node.id?.let { overrides[it] }
        if (allowLayers && o != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated) {
            if (layerHost().draw(canvas, doc, node, o, parentAlpha)) return
        }
        val opacity = o.valueOr(SvgProperty.OPACITY, node.opacity).coerceIn(0f, 1f)
        if (opacity <= 0f || parentAlpha <= 0f) return
        val save = canvas.save()
        node.transform?.let { canvas.concat(matrix(node)) }
        if (o != null) applyAnimatedTransform(canvas, node, o)
        drawContent(canvas, doc, node, parentAlpha * opacity, opacity, o, allowLayers)
        canvas.restoreToCount(save)
    }

    /** Content in local coordinates (after node transform + animated transform). */
    private fun drawContent(canvas: Canvas, doc: SvgDocument, node: SvgNode, alpha: Float, ownOpacity: Float, o: FloatArray?, allowLayers: Boolean) {
        node.clipId?.let { id -> doc.clips[id]?.let { clipPath(it) }?.let { canvas.clipPath(it) } }
        when (node) {
            is SvgGroup -> {
                if (ownOpacity < 1f && node.children.size > 1) {
                    val b = node.bounds
                    val layer = canvas.saveLayerAlpha(b[0] - 1e4f, b[1] - 1e4f, b[2] + 1e4f, b[3] + 1e4f, (ownOpacity * 255f + 0.5f).toInt())
                    for (child in node.children) drawNode(canvas, doc, child, alpha / ownOpacity, allowLayers)
                    canvas.restoreToCount(layer)
                } else {
                    for (child in node.children) drawNode(canvas, doc, child, alpha, allowLayers)
                }
            }
            is SvgShape -> drawShape(canvas, doc, node, alpha, o)
        }
    }

    private fun applyAnimatedTransform(canvas: Canvas, node: SvgNode, o: FloatArray) {
        val tx = o.valueOr(SvgProperty.TRANSLATE_X, 0f)
        val ty = o.valueOr(SvgProperty.TRANSLATE_Y, 0f)
        val rotation = o.valueOr(SvgProperty.ROTATION, 0f)
        val scale = o.valueOr(SvgProperty.SCALE, 1f)
        if (tx != 0f || ty != 0f) canvas.translate(tx, ty)
        if (rotation != 0f || scale != 1f) {
            val pivot = pivot(node)
            canvas.translate(pivot[0], pivot[1])
            if (rotation != 0f) canvas.rotate(rotation)
            if (scale != 1f) canvas.scale(scale, scale)
            canvas.translate(-pivot[0], -pivot[1])
        }
    }

    private fun pivot(node: SvgNode): FloatArray =
        pivots[node.id ?: ""] ?: node.bounds.let { floatArrayOf((it[0] + it[2]) / 2f, (it[1] + it[3]) / 2f) }

    private fun drawShape(canvas: Canvas, doc: SvgDocument, shape: SvgShape, alpha: Float, o: FloatArray?) {
        val style = shape.style
        if (!style.visible) return
        val platform = platform(shape)
        val path = platform.path ?: buildPath(shape).also { platform.path = it }
        if (style.fill != SvgPaint.None && configure(fillPaint, doc, shape, style.fill, style.fillOpacity * alpha, platform, "f")) {
            canvas.drawPath(path, fillPaint)
        }
        val width = o.valueOr(SvgProperty.STROKE_WIDTH, style.strokeWidth)
        if (style.stroke == SvgPaint.None || width <= 0f) return
        if (!configure(strokePaint, doc, shape, style.stroke, style.strokeOpacity * alpha, platform, "s")) return
        strokePaint.strokeWidth = width
        strokePaint.strokeCap = when (style.lineCap) { 2 -> Paint.Cap.ROUND; 3 -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
        strokePaint.strokeJoin = when (style.lineJoin) { 2 -> Paint.Join.ROUND; 3 -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
        strokePaint.strokeMiter = style.miterLimit
        val dashes = style.dashes
        val offset = o.valueOr(SvgProperty.STROKE_DASHOFFSET, style.dashOffset)
        strokePaint.pathEffect = when {
            dashes == null -> null
            offset == style.dashOffset -> platform.dash ?: dash(dashes, offset, shape.dashScale).also { platform.dash = it }
            else -> dash(dashes, offset, shape.dashScale)
        }
        val start = o.valueOr(SvgProperty.TRIM_START, shape.trimStart).coerceIn(0f, 1f)
        val end = o.valueOr(SvgProperty.TRIM_END, shape.trimEnd).coerceIn(0f, 1f)
        when {
            end <= start -> return
            start <= 0f && end >= 1f -> canvas.drawPath(path, strokePaint)
            else -> canvas.drawPath(trim(path, start, end), strokePaint)
        }
    }

    private fun dash(dashes: FloatArray, offset: Float, scale: Float): DashPathEffect {
        val scaled = FloatArray(dashes.size) { (dashes[it] * scale).coerceAtLeast(0.01f) }
        return DashPathEffect(scaled, offset * scale)
    }

    /** strokeStart/strokeEnd over the total length of all contours (Core Animation semantics). */
    private fun trim(path: Path, start: Float, end: Float): Path {
        trimmed.rewind()
        measure.setPath(path, false)
        var total = 0f
        do { total += measure.length } while (measure.nextContour())
        if (total <= 0f) return trimmed
        val from = start * total
        val to = end * total
        measure.setPath(path, false)
        var offset = 0f
        do {
            val length = measure.length
            val a = (from - offset).coerceIn(0f, length)
            val b = (to - offset).coerceIn(0f, length)
            if (b > a) {
                segment.rewind()
                measure.getSegment(a, b, segment, true)
                trimmed.addPath(segment)
            }
            offset += length
        } while (offset < to && measure.nextContour())
        return trimmed
    }

    private fun configure(paint: Paint, doc: SvgDocument, shape: SvgShape, value: SvgPaint, alpha: Float, platform: NodePlatform, slot: String): Boolean {
        paint.shader = null
        val color: Int = tint ?: when (value) {
            SvgPaint.None -> return false
            SvgPaint.Current -> currentColor ?: (shape.style.color as? SvgPaint.Solid)?.argb ?: BLACK
            is SvgPaint.Solid -> value.argb
            is SvgPaint.Ref -> {
                val gradient = doc.gradients[value.id]
                if (gradient == null || gradient.stops.isEmpty()) {
                    return when (val fallback = value.fallback) {
                        is SvgPaint.Solid -> configureColor(paint, fallback.argb, alpha)
                        SvgPaint.Current -> configureColor(paint, currentColor ?: BLACK, alpha)
                        else -> false
                    }
                }
                if (gradient.stops.size == 1) gradient.stops[0].argb else {
                    val shaders = platform.shaders ?: HashMap<String, Shader>(2).also { platform.shaders = it }
                    paint.shader = shaders.getOrPut(slot + value.id) { shader(gradient, shape.bounds) }
                    paint.color = BLACK
                    paint.alpha = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    return paint.alpha > 0
                }
            }
        }
        return configureColor(paint, color, alpha)
    }

    private fun configureColor(paint: Paint, argb: Int, alpha: Float): Boolean {
        val color = SvgColor.multiplyAlpha(argb, alpha)
        paint.color = color
        return (color ushr 24) != 0
    }

    private fun shader(gradient: SvgGradient, bounds: FloatArray): Shader {
        val c = gradient.coordinates
        val colors = IntArray(gradient.stops.size) { gradient.stops[it].argb }
        val positions = FloatArray(gradient.stops.size) { gradient.stops[it].offset }
        val shader = if (gradient.radial) {
            RadialGradient(c[0], c[1], c[2].coerceAtLeast(1e-4f), colors, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(c[0], c[1], c[2], c[3], colors, positions, Shader.TileMode.CLAMP)
        }
        val local = Matrix()
        if (!gradient.userSpace) {
            local.setScale((bounds[2] - bounds[0]).coerceAtLeast(1e-4f), (bounds[3] - bounds[1]).coerceAtLeast(1e-4f))
            local.postTranslate(bounds[0], bounds[1])
        }
        gradient.transform?.let { local.preConcat(toMatrix(it)) }
        shader.setLocalMatrix(local)
        return shader
    }

    private fun platform(node: SvgNode): NodePlatform =
        node.platform as? NodePlatform ?: NodePlatform().also { node.platform = it }

    private fun matrix(node: SvgNode): Matrix {
        val platform = platform(node)
        return platform.matrix ?: toMatrix(node.transform!!).also { platform.matrix = it }
    }

    private fun clipPath(clip: SvgClip): Path {
        (clip.platform as? Path)?.let { return it }
        val result = Path()
        for (shape in clip.shapes) {
            val path = buildPath(shape)
            shape.transform?.let { path.transform(toMatrix(it)) }
            result.addPath(path)
        }
        clip.platform = result
        return result
    }

    private fun buildPath(shape: SvgShape): Path {
        val path = Path()
        shape.path.forEach { op, a, o ->
            when (op) {
                PathData.MOVE -> path.moveTo(a[o], a[o + 1])
                PathData.LINE -> path.lineTo(a[o], a[o + 1])
                PathData.CUBIC -> path.cubicTo(a[o], a[o + 1], a[o + 2], a[o + 3], a[o + 4], a[o + 5])
                PathData.QUAD -> path.quadTo(a[o], a[o + 1], a[o + 2], a[o + 3])
                PathData.CLOSE -> path.close()
            }
        }
        path.fillType = if (shape.style.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        return path
    }

    private fun toMatrix(m: FloatArray): Matrix = Matrix().apply {
        setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f))
    }

    // -- RenderNode layers (API 29+) ------------------------------------------------------

    private fun layerHost(): LayerHost = (layers as? LayerHost) ?: LayerHost().also { layers = it }

    private fun invalidateLayers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (layers as? LayerHost)?.invalidate()
    }

    private fun releaseLayers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (layers as? LayerHost)?.release()
        layers = null
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private inner class LayerHost {
        private val nodes = HashMap<String, RenderNode>()
        private val dirty = HashSet<String>()

        fun invalidate() { dirty.addAll(nodes.keys) }

        fun release() {
            nodes.values.forEach { it.discardDisplayList() }
            nodes.clear()
            dirty.clear()
        }

        /** Returns false when the node must be drawn directly (content animations or nested animated ids). */
        fun draw(canvas: Canvas, doc: SvgDocument, node: SvgNode, o: FloatArray, parentAlpha: Float): Boolean {
            val id = node.id ?: return false
            for (property in arrayOf(SvgProperty.STROKE_DASHOFFSET, SvgProperty.TRIM_START, SvgProperty.TRIM_END, SvgProperty.STROKE_WIDTH)) {
                if (!o[property].isNaN()) return false
            }
            if (hasAnimatedDescendant(node)) return false
            val renderNode = nodes.getOrPut(id) { RenderNode("svg:$id").also { dirty.add(id) } }
            if (id in dirty || !renderNode.hasDisplayList()) {
                renderNode.setPosition(0, 0, 1, 1)
                renderNode.setClipToBounds(false)
                val recording: RecordingCanvas = renderNode.beginRecording()
                try {
                    drawContent(recording, doc, node, parentAlpha, 1f, o, allowLayers = false)
                } finally {
                    renderNode.endRecording()
                }
                dirty.remove(id)
            }
            val pivot = pivot(node)
            renderNode.setPivotX(pivot[0])
            renderNode.setPivotY(pivot[1])
            renderNode.setRotationZ(o.valueOr(SvgProperty.ROTATION, 0f))
            val scale = o.valueOr(SvgProperty.SCALE, 1f)
            renderNode.setScaleX(scale)
            renderNode.setScaleY(scale)
            renderNode.setTranslationX(o.valueOr(SvgProperty.TRANSLATE_X, 0f))
            renderNode.setTranslationY(o.valueOr(SvgProperty.TRANSLATE_Y, 0f))
            renderNode.setAlpha(o.valueOr(SvgProperty.OPACITY, node.opacity).coerceIn(0f, 1f))
            val save = canvas.save()
            node.transform?.let { canvas.concat(matrix(node)) }
            canvas.drawRenderNode(renderNode)
            canvas.restoreToCount(save)
            return true
        }

        private fun hasAnimatedDescendant(node: SvgNode): Boolean {
            if (node !is SvgGroup) return false
            for (child in node.children) {
                if (child.id != null && overrides.containsKey(child.id)) return true
                if (hasAnimatedDescendant(child)) return true
            }
            return false
        }
    }

    private fun FloatArray?.valueOr(property: Int, fallback: Float): Float {
        val value = this?.get(property) ?: return fallback
        return if (value.isNaN()) fallback else value
    }

    private companion object {
        const val SYNC_PARSE_LIMIT = 16 * 1024
        const val BLACK = 0xFF000000.toInt()
        val parser = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pam-svg-parse").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
        val main = Handler(Looper.getMainLooper())
    }
}
