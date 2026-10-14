package dev.pam.svg

import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Parses SVG markup into a retained [SvgDocument]. Pure Kotlin, no Android types. */
internal object SvgParser {
    private const val KAPPA = 0.5522847498f
    private const val MAX_USE_DEPTH = 8

    private val INHERITED = setOf(
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
        "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "stroke-opacity", "color", "visibility",
    )
    private val STYLABLE = INHERITED + setOf("opacity", "display", "stop-color", "stop-opacity", "clip-path", "transform")

    fun parse(source: String): SvgDocument {
        val root = SvgXml.parse(source)
        require(root.name == "svg") { "Root element must be <svg>" }
        return Builder(root).build()
    }

    private class Builder(private val root: XmlElement) {
        private val elementsById = HashMap<String, XmlElement>()
        private val rules = ArrayList<Pair<String, Map<String, String>>>()
        private val gradients = LinkedHashMap<String, SvgGradient>()
        private val clips = LinkedHashMap<String, SvgClip>()
        private val byId = LinkedHashMap<String, SvgNode>()
        private var bytes = 0

        fun build(): SvgDocument {
            index(root)
            val viewBox = numbers(root.attributes["viewBox"] ?: "")
            val width = length(root.attributes["width"]) ?: viewBox.getOrNull(2) ?: 24f
            val height = length(root.attributes["height"]) ?: viewBox.getOrNull(3) ?: 24f
            val box = if (viewBox.size == 4 && viewBox[2] > 0f && viewBox[3] > 0f) viewBox.toFloatArray() else floatArrayOf(0f, 0f, width, height)
            val aspect = (root.attributes["preserveAspectRatio"] ?: "xMidYMid meet").trim().split(Regex("\\s+"))
            val align = aspect.firstOrNull() ?: "xMidYMid"
            val none = align == "none"
            val ax = when { align.startsWith("xMin") -> 0f; align.startsWith("xMax") -> 1f; else -> 0.5f }
            val ay = when { align.contains("YMin") -> 0f; align.contains("YMax") -> 1f; else -> 0.5f }
            for (element in elementsById.values) {
                if (element.name == "linearGradient" || element.name == "radialGradient") gradient(element)?.let { gradients[it.id] = it }
            }
            val style = SvgStyle()
            for (element in elementsById.values) {
                if (element.name == "clipPath") clips[element.attributes["id"]!!] = clip(element, style)
            }
            val children = element(root, style, 0, insideDefs = false) as? SvgGroup
            val doc = SvgGroup(null, null, 1f, null, children?.children ?: emptyList())
            return SvgDocument(box, width, height, ax, ay, none, aspect.getOrNull(1) == "slice", doc, gradients, clips, byId, bytes + 256)
        }

        private fun index(element: XmlElement) {
            element.attributes["id"]?.let { elementsById.putIfAbsent(it, element) }
            if (element.name == "style") parseStylesheet(element.text.toString())
            for (child in element.children) index(child)
        }

        private fun parseStylesheet(css: String) {
            val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            Regex("([^{}]+)\\{([^}]*)\\}").findAll(clean).forEach { match ->
                val declarations = declarations(match.groupValues[2])
                match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { rules.add(it to declarations) }
            }
        }

        private fun declarations(text: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (part in text.split(';')) {
                val colon = part.indexOf(':')
                if (colon <= 0) continue
                val key = part.substring(0, colon).trim()
                if (key in STYLABLE) result[key] = part.substring(colon + 1).replace("!important", "").trim()
            }
            return result
        }

        /** Presentation attributes < stylesheet (tag < class < id) < style attribute. */
        private fun properties(element: XmlElement): Map<String, String> {
            val result = HashMap<String, String>()
            for ((key, value) in element.attributes) if (key in STYLABLE) result[key] = value
            if (rules.isNotEmpty()) {
                val classes = element.attributes["class"]?.split(Regex("\\s+"))?.toSet() ?: emptySet()
                val id = element.attributes["id"]
                for (rank in 0..2) for ((selector, values) in rules) {
                    val matches = when (rank) {
                        0 -> selector == element.name || selector == "*"
                        1 -> selector.startsWith('.') && selector.substring(1) in classes
                        else -> selector.startsWith('#') && selector.substring(1) == id
                    }
                    if (matches) result.putAll(values)
                }
            }
            element.attributes["style"]?.let { result.putAll(declarations(it)) }
            return result
        }

        private fun element(element: XmlElement, parentStyle: SvgStyle, useDepth: Int, insideDefs: Boolean): SvgNode? {
            val props = properties(element)
            if (props["display"] == "none") return null
            val style = inherit(parentStyle, props)
            val id = element.attributes["id"]
            val transform = props["transform"]?.let(::transform)
            val opacity = props["opacity"]?.let(::number)?.coerceIn(0f, 1f) ?: 1f
            val clipId = props["clip-path"]?.let(::urlId)
            val node: SvgNode? = when (element.name) {
                "svg", "g", "a", "switch", "symbol" -> {
                    if (element.name == "symbol" && useDepth == 0) return null
                    val children = element.children.mapNotNull { element(it, style, useDepth, insideDefs) }
                    var groupTransform = transform
                    if (element !== root && element.name == "svg") {
                        val x = length(element.attributes["x"]) ?: 0f
                        val y = length(element.attributes["y"]) ?: 0f
                        if (x != 0f || y != 0f) groupTransform = Affine.multiply(groupTransform ?: IDENTITY, floatArrayOf(1f, 0f, 0f, 1f, x, y))
                    }
                    SvgGroup(id, groupTransform, opacity, clipId, children)
                }
                "use" -> {
                    if (useDepth >= MAX_USE_DEPTH) return null
                    val target = (element.attributes["href"] ?: element.attributes["xlink:href"])?.removePrefix("#")?.let { elementsById[it] } ?: return null
                    if (target === element) return null
                    val x = length(element.attributes["x"]) ?: 0f
                    val y = length(element.attributes["y"]) ?: 0f
                    val placed = Affine.multiply(transform ?: IDENTITY, floatArrayOf(1f, 0f, 0f, 1f, x, y))
                    val child = element(target, style, useDepth + 1, insideDefs = false) ?: return null
                    SvgGroup(id, placed, opacity, clipId, listOf(child))
                }
                "path", "circle", "ellipse", "rect", "line", "polyline", "polygon" -> {
                    if (insideDefs) return null
                    val path = geometry(element) ?: return null
                    bytes += path.ops.size * 4 + 96
                    SvgShape(
                        id, transform, opacity, clipId, path, style,
                        element.attributes["pathLength"]?.let(::number)?.takeIf { it > 0f },
                        element.attributes["pam-trim-start"]?.let(::number)?.coerceIn(0f, 1f) ?: 0f,
                        element.attributes["pam-trim-end"]?.let(::number)?.coerceIn(0f, 1f) ?: 1f,
                    )
                }
                else -> null
            }
            if (node != null && id != null) byId.putIfAbsent(id, node)
            return node
        }

        private fun clip(element: XmlElement, style: SvgStyle): SvgClip {
            val shapes = ArrayList<SvgShape>()
            fun collect(e: XmlElement, parent: FloatArray?) {
                val props = properties(e)
                val t = props["transform"]?.let(::transform)
                val combined = when { parent == null -> t; t == null -> parent; else -> Affine.multiply(parent, t) }
                val path = geometry(e)
                if (path != null) shapes.add(SvgShape(null, combined, 1f, null, path, inherit(style, props), null, 0f, 1f))
                if (e.name == "g") e.children.forEach { collect(it, combined) }
                if (e.name == "use") {
                    (e.attributes["href"] ?: e.attributes["xlink:href"])?.removePrefix("#")?.let { elementsById[it] }?.takeIf { it !== e && it.name != "use" }?.let { collect(it, combined) }
                }
            }
            element.children.forEach { collect(it, null) }
            return SvgClip(element.attributes["id"]!!, shapes)
        }

        private fun gradient(element: XmlElement): SvgGradient? {
            val id = element.attributes["id"] ?: return null
            // Inherit stops/attributes through href chains (bounded).
            val chain = ArrayList<XmlElement>()
            var current: XmlElement? = element
            while (current != null && chain.size < 8 && current !in chain) {
                chain.add(current)
                current = (current.attributes["href"] ?: current.attributes["xlink:href"])?.removePrefix("#")?.let { elementsById[it] }
            }
            fun attr(name: String): String? = chain.firstNotNullOfOrNull { it.attributes[name] }
            val stopsOwner = chain.firstOrNull { e -> e.children.any { it.name == "stop" } } ?: return null
            val radial = element.name == "radialGradient"
            val userSpace = attr("gradientUnits") == "userSpaceOnUse"
            fun coordinate(name: String, default: Float): Float {
                val raw = attr(name) ?: return default
                return if (raw.endsWith('%')) (number(raw.dropLast(1)) ?: 0f) / 100f else number(raw) ?: default
            }
            val coordinates = if (radial) {
                val cx = coordinate("cx", 0.5f); val cy = coordinate("cy", 0.5f)
                floatArrayOf(cx, cy, coordinate("r", 0.5f), coordinate("fx", cx), coordinate("fy", cy))
            } else {
                floatArrayOf(coordinate("x1", 0f), coordinate("y1", 0f), coordinate("x2", 1f), coordinate("y2", 0f))
            }
            var lastOffset = 0f
            val stops = stopsOwner.children.filter { it.name == "stop" }.take(64).map { stop ->
                val props = properties(stop)
                val rawOffset = stop.attributes["offset"] ?: "0"
                val offset = (if (rawOffset.endsWith('%')) (number(rawOffset.dropLast(1)) ?: 0f) / 100f else number(rawOffset) ?: 0f)
                    .coerceIn(0f, 1f).coerceAtLeast(lastOffset)
                lastOffset = offset
                val color = (SvgColor.parse(props["stop-color"] ?: "black") as? SvgPaint.Solid)?.argb ?: 0xFF000000.toInt()
                val alpha = props["stop-opacity"]?.let(::number)?.coerceIn(0f, 1f) ?: 1f
                SvgStop(offset, SvgColor.multiplyAlpha(color, alpha))
            }
            return SvgGradient(id, radial, userSpace, coordinates, stops, attr("gradientTransform")?.let(::transform))
        }

        private fun inherit(parent: SvgStyle, p: Map<String, String>): SvgStyle {
            if (p.keys.none { it in INHERITED }) return parent
            var s = parent
            p["color"]?.let { v -> SvgColor.parse(v)?.let { s = s.copy(color = if (it is SvgPaint.Current) parent.color else it) } }
            p["fill"]?.let { v -> paint(v)?.let { s = s.copy(fill = it) } }
            p["stroke"]?.let { v -> paint(v)?.let { s = s.copy(stroke = it) } }
            p["fill-opacity"]?.let(::number)?.let { s = s.copy(fillOpacity = it.coerceIn(0f, 1f)) }
            p["stroke-opacity"]?.let(::number)?.let { s = s.copy(strokeOpacity = it.coerceIn(0f, 1f)) }
            p["fill-rule"]?.let { s = s.copy(evenOdd = it.trim() == "evenodd") }
            p["stroke-width"]?.let(::length)?.let { s = s.copy(strokeWidth = max(0f, it)) }
            p["stroke-linecap"]?.let { s = s.copy(lineCap = when (it.trim()) { "round" -> 2; "square" -> 3; else -> 1 }) }
            p["stroke-linejoin"]?.let { s = s.copy(lineJoin = when (it.trim()) { "round" -> 2; "bevel" -> 3; else -> 1 }) }
            p["stroke-miterlimit"]?.let(::number)?.let { s = s.copy(miterLimit = max(1f, it)) }
            p["stroke-dasharray"]?.let { v ->
                val values = numbers(v).map { max(0f, it) }
                s = s.copy(dashes = if (values.isEmpty() || values.all { it == 0f }) null else (if (values.size % 2 == 1) values + values else values).toFloatArray())
            }
            p["stroke-dashoffset"]?.let(::length)?.let { s = s.copy(dashOffset = it) }
            p["visibility"]?.let { s = s.copy(visible = it.trim() != "hidden" && it.trim() != "collapse") }
            return s
        }

        private fun paint(value: String): SvgPaint? {
            val v = value.trim()
            if (v.startsWith("url(")) {
                val id = urlId(v) ?: return null
                val fallback = v.substringAfter(')').trim().takeIf { it.isNotEmpty() }?.let { SvgColor.parse(it) } ?: SvgPaint.None
                return SvgPaint.Ref(id, fallback)
            }
            return SvgColor.parse(v)
        }

        private fun geometry(e: XmlElement): PathData? {
            val a = e.attributes
            fun n(name: String) = length(a[name]) ?: 0f
            val b = PathBuilder()
            when (e.name) {
                "path" -> return a["d"]?.let { PathDataParser.parse(it) }?.takeIf { it.ops.isNotEmpty() }
                "circle" -> { val r = n("r"); if (r <= 0f) return null; ellipse(b, n("cx"), n("cy"), r, r) }
                "ellipse" -> {
                    val rx = length(a["rx"]) ?: length(a["ry"]) ?: 0f
                    val ry = length(a["ry"]) ?: rx
                    if (rx <= 0f || ry <= 0f) return null
                    ellipse(b, n("cx"), n("cy"), rx, ry)
                }
                "rect" -> {
                    val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height")
                    if (w <= 0f || h <= 0f) return null
                    var rx = length(a["rx"]); var ry = length(a["ry"])
                    if (rx == null) rx = ry
                    if (ry == null) ry = rx
                    val cx = min(max(0f, rx ?: 0f), w / 2); val cy = min(max(0f, ry ?: 0f), h / 2)
                    if (cx == 0f || cy == 0f) {
                        b.moveTo(x, y); b.lineTo(x + w, y); b.lineTo(x + w, y + h); b.lineTo(x, y + h); b.close()
                    } else {
                        val kx = cx * KAPPA; val ky = cy * KAPPA
                        b.moveTo(x + cx, y); b.lineTo(x + w - cx, y)
                        b.cubicTo(x + w - cx + kx, y, x + w, y + cy - ky, x + w, y + cy); b.lineTo(x + w, y + h - cy)
                        b.cubicTo(x + w, y + h - cy + ky, x + w - cx + kx, y + h, x + w - cx, y + h); b.lineTo(x + cx, y + h)
                        b.cubicTo(x + cx - kx, y + h, x, y + h - cy + ky, x, y + h - cy); b.lineTo(x, y + cy)
                        b.cubicTo(x, y + cy - ky, x + cx - kx, y, x + cx, y); b.close()
                    }
                }
                "line" -> { b.moveTo(n("x1"), n("y1")); b.lineTo(n("x2"), n("y2")) }
                "polyline", "polygon" -> {
                    val points = numbers(a["points"] ?: "")
                    if (points.size < 4) return null
                    b.moveTo(points[0], points[1])
                    var i = 2
                    while (i + 1 < points.size) { b.lineTo(points[i], points[i + 1]); i += 2 }
                    if (e.name == "polygon") b.close()
                }
                else -> return null
            }
            return b.build()
        }

        /** Starts at (cx + rx, cy) and runs toward (cx, cy + ry), as the SVG spec requires. */
        private fun ellipse(b: PathBuilder, cx: Float, cy: Float, rx: Float, ry: Float) {
            val kx = rx * KAPPA; val ky = ry * KAPPA
            b.moveTo(cx + rx, cy)
            b.cubicTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry)
            b.cubicTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)
            b.cubicTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry)
            b.cubicTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy)
            b.close()
        }
    }

    val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    fun urlId(value: String): String? =
        Regex("url\\(\\s*['\"]?#([^)'\"]+)['\"]?\\s*\\)").find(value)?.groupValues?.get(1)

    fun number(value: String): Float? = value.trim().removeSuffix("px").trim().toFloatOrNull()?.takeIf { it.isFinite() }

    /** Lengths in user units; px/pt/mm/cm/in converted, % and em ignored (null). */
    fun length(value: String?): Float? {
        val v = value?.trim() ?: return null
        val unit = Regex("[a-z%]+$").find(v)?.value ?: ""
        val n = v.dropLast(unit.length).trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return when (unit) {
            "", "px" -> n
            "pt" -> n * 4f / 3f
            "pc" -> n * 16f
            "mm" -> n * 96f / 25.4f
            "cm" -> n * 96f / 2.54f
            "in" -> n * 96f
            else -> null
        }
    }

    fun numbers(value: String): List<Float> {
        val result = ArrayList<Float>()
        val scanner = NumberScanner(value)
        while (true) { result.add(scanner.next() ?: break) }
        return result
    }

    /** Parses an SVG transform list into one affine matrix. */
    fun transform(value: String): FloatArray? {
        var m = IDENTITY
        val regex = Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)")
        var any = false
        for (match in regex.findAll(value)) {
            val a = numbers(match.groupValues[2])
            val next: FloatArray = when (match.groupValues[1]) {
                "matrix" -> if (a.size == 6) a.toFloatArray() else continue
                "translate" -> floatArrayOf(1f, 0f, 0f, 1f, a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f })
                "scale" -> { val sx = a.getOrElse(0) { 1f }; floatArrayOf(sx, 0f, 0f, a.getOrElse(1) { sx }, 0f, 0f) }
                "rotate" -> {
                    val rad = Math.toRadians(a.getOrElse(0) { 0f }.toDouble())
                    val c = kotlin.math.cos(rad).toFloat(); val s = kotlin.math.sin(rad).toFloat()
                    val r = floatArrayOf(c, s, -s, c, 0f, 0f)
                    if (a.size >= 3) {
                        val cx = a[1]; val cy = a[2]
                        Affine.multiply(Affine.multiply(floatArrayOf(1f, 0f, 0f, 1f, cx, cy), r), floatArrayOf(1f, 0f, 0f, 1f, -cx, -cy))
                    } else r
                }
                "skewX" -> floatArrayOf(1f, 0f, tan(Math.toRadians(a.getOrElse(0) { 0f }.toDouble())).toFloat(), 1f, 0f, 0f)
                else -> floatArrayOf(1f, tan(Math.toRadians(a.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f, 1f, 0f, 0f)
            }
            m = Affine.multiply(m, next)
            any = true
        }
        return if (any) m else null
    }
}

/** Scans SVG numbers: "1.5.5", "-1-2", "1e-3", comma/space separators. */
internal class NumberScanner(private val s: String) {
    var index = 0
        private set

    fun skipSeparators() {
        while (index < s.length && (s[index].isWhitespace() || s[index] == ',')) index++
    }

    fun next(): Float? {
        skipSeparators()
        val start = index
        if (index < s.length && (s[index] == '+' || s[index] == '-')) index++
        var digits = false
        var dot = false
        while (index < s.length) {
            val c = s[index]
            if (c.isDigit()) { digits = true; index++ }
            else if (c == '.' && !dot) { dot = true; index++ }
            else break
        }
        if (!digits) { index = start; return null }
        if (index < s.length && (s[index] == 'e' || s[index] == 'E')) {
            val save = index
            index++
            if (index < s.length && (s[index] == '+' || s[index] == '-')) index++
            if (index < s.length && s[index].isDigit()) { while (index < s.length && s[index].isDigit()) index++ } else index = save
        }
        return s.substring(start, index).toFloatOrNull()?.takeIf { it.isFinite() }
    }

    /** Arc flags may be packed without separators ("a1 1 0 11 2 2"). */
    fun flag(): Boolean? {
        skipSeparators()
        if (index >= s.length) return null
        return when (s[index]) { '0' -> { index++; false }; '1' -> { index++; true }; else -> null }
    }

    fun peekCommand(): Char? {
        skipSeparators()
        return if (index < s.length && s[index].isLetter() && s[index] != 'e' && s[index] != 'E') s[index] else null
    }

    fun take(count: Int): FloatArray? {
        val values = FloatArray(count)
        for (i in 0 until count) values[i] = next() ?: return null
        return values
    }

    fun advance() { index++ }
    fun atEnd(): Boolean { skipSeparators(); return index >= s.length }
}

/** SVG path data (`d`) to normalized absolute [PathData]. */
internal object PathDataParser {
    fun parse(d: String): PathData {
        val b = PathBuilder()
        val s = NumberScanner(d)
        var command = ' '
        var lastControlX = 0f; var lastControlY = 0f
        var lastKind = ' '
        while (!s.atEnd()) {
            val explicit = s.peekCommand()
            if (explicit != null) { command = explicit; s.advance() }
            else if (command == ' ') break
            val relative = command.isLowerCase()
            val ox = if (relative) b.x else 0f
            val oy = if (relative) b.y else 0f
            when (command.uppercaseChar()) {
                'M' -> {
                    val x = s.next() ?: break; val y = s.next() ?: break
                    b.moveTo(ox + x, oy + y)
                    command = if (relative) 'l' else 'L'
                    lastKind = 'M'
                }
                'L' -> { val x = s.next() ?: break; val y = s.next() ?: break; b.lineTo(ox + x, oy + y); lastKind = 'L' }
                'H' -> { val x = s.next() ?: break; b.lineTo(ox + x, b.y); lastKind = 'L' }
                'V' -> { val y = s.next() ?: break; b.lineTo(b.x, oy + y); lastKind = 'L' }
                'C' -> {
                    val v = s.take(6) ?: break
                    b.cubicTo(ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5])
                    lastControlX = ox + v[2]; lastControlY = oy + v[3]; lastKind = 'C'
                }
                'S' -> {
                    val v = s.take(4) ?: break
                    val c1x = if (lastKind == 'C') 2 * b.x - lastControlX else b.x
                    val c1y = if (lastKind == 'C') 2 * b.y - lastControlY else b.y
                    b.cubicTo(c1x, c1y, ox + v[0], oy + v[1], ox + v[2], oy + v[3])
                    lastControlX = ox + v[0]; lastControlY = oy + v[1]; lastKind = 'C'
                }
                'Q' -> {
                    val v = s.take(4) ?: break
                    b.quadTo(ox + v[0], oy + v[1], ox + v[2], oy + v[3])
                    lastControlX = ox + v[0]; lastControlY = oy + v[1]; lastKind = 'Q'
                }
                'T' -> {
                    val x = s.next() ?: break; val y = s.next() ?: break
                    val cx = if (lastKind == 'Q') 2 * b.x - lastControlX else b.x
                    val cy = if (lastKind == 'Q') 2 * b.y - lastControlY else b.y
                    b.quadTo(cx, cy, ox + x, oy + y)
                    lastControlX = cx; lastControlY = cy; lastKind = 'Q'
                }
                'A' -> {
                    val rx = s.next() ?: break; val ry = s.next() ?: break; val rotation = s.next() ?: break
                    val large = s.flag() ?: break; val sweep = s.flag() ?: break
                    val x = s.next() ?: break; val y = s.next() ?: break
                    b.arcTo(rx, ry, rotation, large, sweep, ox + x, oy + y)
                    lastKind = 'A'
                }
                'Z' -> { b.close(); lastKind = 'Z'; command = ' ' }
                else -> break
            }
        }
        return b.build()
    }
}

/** CSS/SVG color parsing to ARGB ints without android.graphics. */
internal object SvgColor {
    private val NAMED = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "orange" to 0xFFA500, "purple" to 0x800080, "gray" to 0x808080, "grey" to 0x808080,
        "silver" to 0xC0C0C0, "maroon" to 0x800000, "navy" to 0x000080, "teal" to 0x008080, "lime" to 0x00FF00,
        "aqua" to 0x00FFFF, "cyan" to 0x00FFFF, "fuchsia" to 0xFF00FF, "magenta" to 0xFF00FF, "olive" to 0x808000,
        "pink" to 0xFFC0CB, "gold" to 0xFFD700, "brown" to 0xA52A2A, "darkgray" to 0xA9A9A9, "lightgray" to 0xD3D3D3,
    )

    fun parse(value: String): SvgPaint? {
        val v = value.trim()
        return when {
            v == "none" -> SvgPaint.None
            v == "transparent" -> SvgPaint.Solid(0)
            v.equals("currentColor", ignoreCase = true) -> SvgPaint.Current
            v.startsWith("#") -> hex(v.substring(1))?.let { SvgPaint.Solid(it) }
            v.startsWith("rgb") -> rgb(v)?.let { SvgPaint.Solid(it) }
            else -> NAMED[v.lowercase()]?.let { SvgPaint.Solid(0xFF000000.toInt() or it) }
        }
    }

    fun argb(value: String): Int? = (parse(value) as? SvgPaint.Solid)?.argb

    private fun hex(h: String): Int? {
        if (h.any { Character.digit(it, 16) < 0 }) return null
        fun d(i: Int) = Character.digit(h[i], 16)
        return when (h.length) {
            3 -> (0xFF shl 24) or (d(0) * 17 shl 16) or (d(1) * 17 shl 8) or (d(2) * 17)
            4 -> (d(3) * 17 shl 24) or (d(0) * 17 shl 16) or (d(1) * 17 shl 8) or (d(2) * 17)
            6 -> (0xFF shl 24) or h.toInt(16)
            8 -> (h.substring(6, 8).toInt(16) shl 24) or h.substring(0, 6).toInt(16)
            else -> null
        }
    }

    private fun rgb(v: String): Int? {
        val inner = v.substringAfter('(', "").substringBefore(')', "")
        val parts = inner.split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        fun channel(p: String): Int? = if (p.endsWith('%')) p.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).toInt() } else p.toFloatOrNull()?.toInt()
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        val a = parts.getOrNull(3)?.let { if (it.endsWith('%')) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull() } ?: 1f
        return ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    fun multiplyAlpha(argb: Int, factor: Float): Int {
        if (factor >= 1f) return argb
        val alpha = ((argb ushr 24) * factor.coerceIn(0f, 1f) + 0.5f).toInt()
        return (alpha shl 24) or (argb and 0x00FFFFFF)
    }
}
