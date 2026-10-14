package dev.pam.svg

import kotlin.math.abs
import kotlin.math.floor

/**
 * Process-wide LRU of parsed documents keyed by the content hash sent from
 * PHP. Bounded by entry count and estimated bytes; trimmed on memory pressure.
 */
internal class SvgDocumentCache(private val maxEntries: Int = 64, private val maxBytes: Int = 2 * 1024 * 1024) {
    private val entries = LinkedHashMap<String, SvgDocument>(16, 0.75f, true)
    private var bytes = 0

    @Synchronized fun get(key: String): SvgDocument? = entries[key]

    @Synchronized fun put(key: String, document: SvgDocument) {
        entries.remove(key)?.let { bytes -= it.estimatedBytes }
        entries[key] = document
        bytes += document.estimatedBytes
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || bytes > maxBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) break
            bytes -= eldest.value.estimatedBytes
            iterator.remove()
        }
    }

    /** Parses once per key; later calls are a map lookup. */
    fun obtain(key: String, source: String): SvgDocument =
        get(key) ?: SvgParser.parse(source).also { put(key, it) }

    @Synchronized fun trim(keepFraction: Float) {
        val keep = (entries.size * keepFraction).toInt()
        val iterator = entries.entries.iterator()
        while (entries.size > keep && iterator.hasNext()) {
            bytes -= iterator.next().value.estimatedBytes
            iterator.remove()
        }
    }

    @Synchronized fun size(): Int = entries.size
    @Synchronized fun bytes(): Int = bytes

    companion object {
        val shared = SvgDocumentCache()
    }
}

/** Mirrors the PHP SvgEasing enum with the same cubic-bezier curves Core Animation uses. */
internal object SvgEasing {
    const val LINEAR = 1
    const val EASE_IN = 2
    const val EASE_OUT = 3
    const val EASE_IN_OUT = 4

    fun controlPoints(easing: Int): FloatArray = when (easing) {
        EASE_IN -> floatArrayOf(0.42f, 0f, 1f, 1f)
        EASE_OUT -> floatArrayOf(0f, 0f, 0.58f, 1f)
        EASE_IN_OUT -> floatArrayOf(0.42f, 0f, 0.58f, 1f)
        else -> floatArrayOf(0f, 0f, 1f, 1f)
    }

    fun apply(easing: Int, t: Float): Float {
        if (easing == LINEAR || t <= 0f || t >= 1f) return t.coerceIn(0f, 1f)
        val p = controlPoints(easing)
        return bezier(p[0], p[1], p[2], p[3], t)
    }

    private fun bezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        fun sample(a1: Float, a2: Float, t: Float): Float {
            val u = 1 - t
            return 3 * u * u * t * a1 + 3 * u * t * t * a2 + t * t * t
        }
        fun slope(a1: Float, a2: Float, t: Float): Float {
            val u = 1 - t
            return 3 * u * u * a1 + 6 * u * t * (a2 - a1) + 3 * t * t * (1 - a2)
        }
        var t = x
        repeat(8) {
            val error = sample(x1, x2, t) - x
            if (abs(error) < 1e-5f) return sample(y1, y2, t)
            val d = slope(x1, x2, t)
            if (abs(d) < 1e-6f) return@repeat
            t = (t - error / d).coerceIn(0f, 1f)
        }
        var lo = 0f; var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2
            if (sample(x1, x2, mid) < x) lo = mid else hi = mid
        }
        return sample(y1, y2, (lo + hi) / 2)
    }
}

/** One animation target as decoded from the `anim` host property. */
internal data class SvgAnimationSpec(
    val id: String,
    val property: Int,
    val to: Float,
    val from: Float?,
    val duration: Long,
    val easing: Int,
    val delay: Long,
    val repeat: Int,
    val reverse: Boolean,
    val animateOnMount: Boolean,
    val originX: Float?,
    val originY: Float?,
) {
    val key: String get() = "$id:$property"

    companion object {
        fun parseAll(json: String?): List<SvgAnimationSpec> {
            if (json.isNullOrBlank()) return emptyList()
            return SvgJson.parseArray(json).take(64).mapNotNull { entry ->
                val id = entry["i"] as? String ?: return@mapNotNull null
                val property = (entry["p"] as? Number)?.toInt()?.takeIf { it in 1..9 } ?: return@mapNotNull null
                val to = (entry["t"] as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                val origin = entry["o"] as? List<*>
                SvgAnimationSpec(
                    id, property, to,
                    (entry["f"] as? Number)?.toFloat()?.takeIf { it.isFinite() },
                    ((entry["d"] as? Number)?.toLong() ?: 250L).coerceIn(0L, 60_000L),
                    (entry["e"] as? Number)?.toInt() ?: SvgEasing.EASE_IN_OUT,
                    ((entry["w"] as? Number)?.toLong() ?: 0L).coerceIn(0L, 60_000L),
                    ((entry["r"] as? Number)?.toInt() ?: 0).coerceIn(-1, 10_000),
                    ((entry["a"] as? Number)?.toInt() ?: 0) == 1,
                    ((entry["m"] as? Number)?.toInt() ?: 0) == 1,
                    (origin?.getOrNull(0) as? Number)?.toFloat(),
                    (origin?.getOrNull(1) as? Number)?.toFloat(),
                )
            }
        }
    }
}

/** Running timeline for one spec; pure so it is unit tested on the JVM. */
internal class SvgTrack(val spec: SvgAnimationSpec, val start: Float, private val startMillis: Long) {
    private val iterations: Long = if (spec.repeat < 0) Long.MAX_VALUE else spec.repeat.toLong() + 1L

    fun value(nowMillis: Long): Float {
        val elapsed = nowMillis - startMillis - spec.delay
        if (elapsed <= 0L) return start
        if (spec.duration <= 0L) return endValue()
        val progress = elapsed.toDouble() / spec.duration.toDouble()
        val iteration = floor(progress).toLong()
        if (iteration >= iterations) return endValue()
        var fraction = (progress - iteration).toFloat()
        if (spec.reverse && iteration % 2L == 1L) fraction = 1f - fraction
        return start + (spec.to - start) * SvgEasing.apply(spec.easing, fraction)
    }

    fun finished(nowMillis: Long): Boolean =
        iterations != Long.MAX_VALUE &&
            (spec.duration <= 0L || nowMillis - startMillis - spec.delay >= spec.duration * iterations)

    fun endValue(): Float = if (spec.reverse && iterations % 2L == 0L) start else spec.to
}

/** Tiny JSON reader for the animation list (arrays, objects, numbers, strings). */
internal object SvgJson {
    fun parseArray(json: String): List<Map<String, Any?>> {
        val reader = Reader(json)
        val value = runCatching { reader.value() }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return (value as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
    }

    private class Reader(private val s: String) {
        private var i = 0
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length)
            return when (s[i]) {
                '[' -> { i++; val list = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return list }
                    while (true) { list.add(value()); ws(); if (s[i] == ',') { i++; continue }; require(s[i] == ']'); i++; return list } }
                '{' -> { i++; val map = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return map }
                    while (true) { ws(); val key = string(); ws(); require(s[i] == ':'); i++; map[key] = value(); ws()
                        if (s[i] == ',') { i++; continue }; require(s[i] == '}'); i++; return map } }
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val start = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(start, i).toDouble() }
            }
        }
        private fun string(): String {
            require(s[i] == '"'); i++
            val out = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r'); 'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                        'u' -> { out.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> out.append(s[i])
                    }
                } else out.append(s[i])
                i++
            }
            i++
            return out.toString()
        }
    }
}
