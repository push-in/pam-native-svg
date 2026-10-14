package dev.pam.svg

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Wire values of the PHP `SvgProperty` enum. */
internal object SvgProperty {
    const val OPACITY = 1
    const val STROKE_DASHOFFSET = 2
    const val TRIM_START = 3
    const val TRIM_END = 4
    const val ROTATION = 5
    const val SCALE = 6
    const val TRANSLATE_X = 7
    const val TRANSLATE_Y = 8
    const val STROKE_WIDTH = 9
    fun isLayerProperty(property: Int) = property == OPACITY || property in ROTATION..TRANSLATE_Y
}

/** Wire values of the PHP `SvgFit` enum. */
internal object SvgFit {
    const val CONTAIN = 1
    const val COVER = 2
    const val STRETCH = 3
}

/**
 * Absolute path commands packed in a FloatArray: [op, args..., op, args...].
 * Arcs, smooth curves and relative commands are normalized at parse time so
 * renderers only see move/line/cubic/quad/close.
 */
internal class PathData(val ops: FloatArray) {
    val bounds: FloatArray = computeBounds()
    /** Euclidean length of all contours, flattened once at parse time. */
    val length: Float = computeLength()

    companion object {
        const val MOVE = 0f
        const val LINE = 1f
        const val CUBIC = 2f
        const val QUAD = 3f
        const val CLOSE = 4f
        val EMPTY = PathData(FloatArray(0))

        fun argumentCount(op: Float): Int = when (op) {
            MOVE, LINE -> 2
            CUBIC -> 6
            QUAD -> 4
            else -> 0
        }
    }

    inline fun forEach(block: (op: Float, a: FloatArray, offset: Int) -> Unit) {
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            block(op, ops, i + 1)
            i += 1 + argumentCount(op)
        }
    }

    private fun computeBounds(): FloatArray {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        forEach { op, a, o ->
            var k = 0
            while (k < argumentCount(op)) {
                minX = min(minX, a[o + k]); maxX = max(maxX, a[o + k])
                minY = min(minY, a[o + k + 1]); maxY = max(maxY, a[o + k + 1])
                k += 2
            }
        }
        return if (minX > maxX) floatArrayOf(0f, 0f, 0f, 0f) else floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun computeLength(): Float {
        var total = 0.0
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        forEach { op, a, o ->
            when (op) {
                MOVE -> { x = a[o]; y = a[o + 1]; sx = x; sy = y }
                LINE -> { total += dist(x, y, a[o], a[o + 1]); x = a[o]; y = a[o + 1] }
                CUBIC -> {
                    var px = x; var py = y
                    for (step in 1..16) {
                        val t = step / 16f; val u = 1 - t
                        val qx = u * u * u * x + 3 * u * u * t * a[o] + 3 * u * t * t * a[o + 2] + t * t * t * a[o + 4]
                        val qy = u * u * u * y + 3 * u * u * t * a[o + 1] + 3 * u * t * t * a[o + 3] + t * t * t * a[o + 5]
                        total += dist(px, py, qx, qy); px = qx; py = qy
                    }
                    x = a[o + 4]; y = a[o + 5]
                }
                QUAD -> {
                    var px = x; var py = y
                    for (step in 1..12) {
                        val t = step / 12f; val u = 1 - t
                        val qx = u * u * x + 2 * u * t * a[o] + t * t * a[o + 2]
                        val qy = u * u * y + 2 * u * t * a[o + 1] + t * t * a[o + 3]
                        total += dist(px, py, qx, qy); px = qx; py = qy
                    }
                    x = a[o + 2]; y = a[o + 3]
                }
                CLOSE -> { total += dist(x, y, sx, sy); x = sx; y = sy }
            }
        }
        return total.toFloat()
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Double {
        val dx = (x2 - x1).toDouble(); val dy = (y2 - y1).toDouble()
        return sqrt(dx * dx + dy * dy)
    }
}

/** Growable float buffer used while building [PathData]. */
internal class PathBuilder {
    private var data = FloatArray(64)
    private var size = 0
    var x = 0f; private set
    var y = 0f; private set
    private var startX = 0f
    private var startY = 0f
    private var hasCurrent = false

    private fun push(vararg values: Float) {
        if (size + values.size > data.size) data = data.copyOf(max(data.size * 2, size + values.size))
        for (v in values) data[size++] = v
    }

    fun moveTo(px: Float, py: Float) { push(PathData.MOVE, px, py); x = px; y = py; startX = px; startY = py; hasCurrent = true }
    fun lineTo(px: Float, py: Float) { ensure(); push(PathData.LINE, px, py); x = px; y = py }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, px: Float, py: Float) { ensure(); push(PathData.CUBIC, x1, y1, x2, y2, px, py); x = px; y = py }
    fun quadTo(x1: Float, y1: Float, px: Float, py: Float) { ensure(); push(PathData.QUAD, x1, y1, px, py); x = px; y = py }
    fun close() { if (hasCurrent) { push(PathData.CLOSE); x = startX; y = startY } }

    private fun ensure() { if (!hasCurrent) moveTo(x, y) }

    /** SVG elliptical arc (F.6 endpoint parameterization) as cubic segments. */
    fun arcTo(rxIn: Float, ryIn: Float, rotationDeg: Float, largeArc: Boolean, sweep: Boolean, px: Float, py: Float) {
        val x0 = x.toDouble(); val y0 = y.toDouble()
        if (x0 == px.toDouble() && y0 == py.toDouble()) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { lineTo(px, py); return }
        val phi = Math.toRadians(rotationDeg.toDouble())
        val cosPhi = cos(phi); val sinPhi = sin(phi)
        val dx2 = (x0 - px) / 2; val dy2 = (y0 - py) / 2
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
        if (largeArc == sweep) coef = -coef
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * -(ry * x1p / rx)
        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + px) / 2
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + py) / 2
        val theta1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var delta = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - theta1
        if (sweep && delta < 0) delta += 2 * Math.PI
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        val segments = max(1, ceil(abs(delta) / (Math.PI / 2)).toInt())
        val step = delta / segments
        val k = 4.0 / 3.0 * tan(step / 4)
        var t = theta1
        repeat(segments) { index ->
            val cos1 = cos(t); val sin1 = sin(t)
            val t2 = t + step
            val cos2 = cos(t2); val sin2 = sin(t2)
            val e1x = -rx * sin1; val e1y = ry * cos1
            val e2x = -rx * sin2; val e2y = ry * cos2
            val p1x = rx * cos1; val p1y = ry * sin1
            val p2x = rx * cos2; val p2y = ry * sin2
            fun mapX(ux: Double, uy: Double) = (cosPhi * ux - sinPhi * uy + cx).toFloat()
            fun mapY(ux: Double, uy: Double) = (sinPhi * ux + cosPhi * uy + cy).toFloat()
            val c1x = p1x + k * e1x; val c1y = p1y + k * e1y
            val c2x = p2x - k * e2x; val c2y = p2y - k * e2y
            val last = index == segments - 1
            cubicTo(
                mapX(c1x, c1y), mapY(c1x, c1y), mapX(c2x, c2y), mapY(c2x, c2y),
                if (last) px else mapX(p2x, p2y), if (last) py else mapY(p2x, p2y),
            )
            t = t2
        }
    }

    fun build(): PathData = PathData(data.copyOf(size))
}

/** Paint reference: none, solid ARGB, `currentColor` or a gradient id. */
internal sealed interface SvgPaint {
    data object None : SvgPaint
    data object Current : SvgPaint
    data class Solid(val argb: Int) : SvgPaint
    data class Ref(val id: String, val fallback: SvgPaint) : SvgPaint
}

internal class SvgStop(val offset: Float, val argb: Int)

internal class SvgGradient(
    val id: String,
    val radial: Boolean,
    val userSpace: Boolean,
    /** linear: x1 y1 x2 y2; radial: cx cy r fx fy */
    val coordinates: FloatArray,
    val stops: List<SvgStop>,
    /** affine a b c d e f or null */
    val transform: FloatArray?,
)

/** Resolved (inherited) presentation state of a node. */
internal data class SvgStyle(
    val fill: SvgPaint = SvgPaint.Solid(0xFF000000.toInt()),
    val fillOpacity: Float = 1f,
    val evenOdd: Boolean = false,
    val stroke: SvgPaint = SvgPaint.None,
    val strokeOpacity: Float = 1f,
    val strokeWidth: Float = 1f,
    /** 1 butt, 2 round, 3 square */
    val lineCap: Int = 1,
    /** 1 miter, 2 round, 3 bevel */
    val lineJoin: Int = 1,
    val miterLimit: Float = 4f,
    val dashes: FloatArray? = null,
    val dashOffset: Float = 0f,
    val color: SvgPaint = SvgPaint.Solid(0xFF000000.toInt()),
    val visible: Boolean = true,
)

internal sealed class SvgNode(
    val id: String?,
    val transform: FloatArray?,
    val opacity: Float,
    val clipId: String?,
) {
    /** Renderer-owned lazily built platform objects (Path, Matrix, Shader...). */
    @JvmField var platform: Any? = null
    abstract val bounds: FloatArray
}

internal class SvgGroup(
    id: String?,
    transform: FloatArray?,
    opacity: Float,
    clipId: String?,
    val children: List<SvgNode>,
) : SvgNode(id, transform, opacity, clipId) {
    override val bounds: FloatArray by lazy {
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (child in children) {
            val b = Affine.mapBounds(child.transform, child.bounds)
            minX = min(minX, b[0]); minY = min(minY, b[1]); maxX = max(maxX, b[2]); maxY = max(maxY, b[3])
        }
        if (minX > maxX) floatArrayOf(0f, 0f, 0f, 0f) else floatArrayOf(minX, minY, maxX, maxY)
    }
}

internal class SvgShape(
    id: String?,
    transform: FloatArray?,
    opacity: Float,
    clipId: String?,
    val path: PathData,
    val style: SvgStyle,
    val pathLength: Float?,
    val trimStart: Float,
    val trimEnd: Float,
) : SvgNode(id, transform, opacity, clipId) {
    override val bounds: FloatArray get() = path.bounds
    /** Converts author dash units (pathLength) to geometry units. */
    val dashScale: Float get() = if (pathLength != null && pathLength > 0f && path.length > 0f) path.length / pathLength else 1f
}

internal class SvgClip(val id: String, val shapes: List<SvgShape>) {
    @JvmField var platform: Any? = null
}

internal class SvgDocument(
    val viewBox: FloatArray,
    val width: Float,
    val height: Float,
    /** x/y alignment 0, 0.5 or 1; [none] stretches, [slice] covers. */
    val alignX: Float,
    val alignY: Float,
    val none: Boolean,
    val slice: Boolean,
    val root: SvgGroup,
    val gradients: Map<String, SvgGradient>,
    val clips: Map<String, SvgClip>,
    val byId: Map<String, SvgNode>,
    val estimatedBytes: Int,
) {
    /** viewBox -> view transform as [scaleX, scaleY, translateX, translateY]. */
    fun fit(viewWidth: Float, viewHeight: Float, fit: Int?): FloatArray {
        val vbW = viewBox[2]; val vbH = viewBox[3]
        if (vbW <= 0f || vbH <= 0f || viewWidth <= 0f || viewHeight <= 0f) return floatArrayOf(1f, 1f, 0f, 0f)
        val stretch = if (fit != null) fit == SvgFit.STRETCH else none
        val cover = if (fit != null) fit == SvgFit.COVER else slice
        val ax = if (fit != null) 0.5f else alignX
        val ay = if (fit != null) 0.5f else alignY
        var sx = viewWidth / vbW
        var sy = viewHeight / vbH
        if (!stretch) {
            val s = if (cover) max(sx, sy) else min(sx, sy)
            sx = s; sy = s
        }
        val tx = (viewWidth - vbW * sx) * ax - viewBox[0] * sx
        val ty = (viewHeight - vbH * sy) * ay - viewBox[1] * sy
        return floatArrayOf(sx, sy, tx, ty)
    }
}

/** 2D affine helpers on [a b c d e f] (SVG matrix order). */
internal object Affine {
    fun multiply(m: FloatArray, n: FloatArray): FloatArray = floatArrayOf(
        m[0] * n[0] + m[2] * n[1],
        m[1] * n[0] + m[3] * n[1],
        m[0] * n[2] + m[2] * n[3],
        m[1] * n[2] + m[3] * n[3],
        m[0] * n[4] + m[2] * n[5] + m[4],
        m[1] * n[4] + m[3] * n[5] + m[5],
    )

    fun mapBounds(m: FloatArray?, b: FloatArray): FloatArray {
        if (m == null) return b
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (corner in 0..3) {
            val x = if (corner and 1 == 0) b[0] else b[2]
            val y = if (corner and 2 == 0) b[1] else b[3]
            val px = m[0] * x + m[2] * y + m[4]
            val py = m[1] * x + m[3] * y + m[5]
            minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
