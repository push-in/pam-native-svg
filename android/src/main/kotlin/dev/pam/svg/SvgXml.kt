package dev.pam.svg

/** Minimal generic XML element produced by [SvgXml]. */
internal class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
) {
    val children = ArrayList<XmlElement>(4)
    val text = StringBuilder()
}

/**
 * Dependency-free XML reader for SVG documents.
 *
 * Supports elements, attributes, comments, processing instructions and CDATA.
 * DOCTYPE/ENTITY declarations are rejected (no entity expansion), predefined
 * and numeric character references are decoded, namespace prefixes are
 * dropped from element names (`svg:path` -> `path`). Pure Kotlin so it runs
 * in JVM unit tests and never touches the network or filesystem.
 */
internal object SvgXml {
    const val MAX_BYTES = 262_144
    const val MAX_ELEMENTS = 20_000
    const val MAX_DEPTH = 64

    fun parse(source: String): XmlElement {
        require(source.length <= MAX_BYTES) { "SVG source exceeds 256 KiB" }
        val stack = ArrayList<XmlElement>()
        var root: XmlElement? = null
        var count = 0
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            if (c != '<') {
                val next = source.indexOf('<', i).let { if (it < 0) n else it }
                stack.lastOrNull()?.text?.append(decode(source.substring(i, next)))
                i = next
                continue
            }
            when {
                source.startsWith("<!--", i) -> {
                    val end = source.indexOf("-->", i + 4)
                    require(end >= 0) { "Unterminated comment" }
                    i = end + 3
                }
                source.startsWith("<![CDATA[", i) -> {
                    val end = source.indexOf("]]>", i + 9)
                    require(end >= 0) { "Unterminated CDATA" }
                    stack.lastOrNull()?.text?.append(source, i + 9, end)
                    i = end + 3
                }
                source.startsWith("<?", i) -> {
                    val end = source.indexOf("?>", i + 2)
                    require(end >= 0) { "Unterminated processing instruction" }
                    i = end + 2
                }
                source.startsWith("<!", i) -> throw IllegalArgumentException("DOCTYPE and entity declarations are not allowed")
                source.startsWith("</", i) -> {
                    val end = source.indexOf('>', i + 2)
                    require(end >= 0) { "Unterminated end tag" }
                    val name = localName(source.substring(i + 2, end).trim())
                    val open = stack.removeLastOrNull() ?: throw IllegalArgumentException("Unexpected </$name>")
                    require(open.name == name) { "Mismatched </$name>, expected </${open.name}>" }
                    i = end + 1
                }
                else -> {
                    i = readStartTag(source, i + 1) { element, selfClosing ->
                        count++
                        require(count <= MAX_ELEMENTS) { "SVG documents are limited to $MAX_ELEMENTS elements" }
                        val parent = stack.lastOrNull()
                        if (parent == null) {
                            require(root == null) { "Multiple root elements" }
                            root = element
                        } else {
                            parent.children.add(element)
                        }
                        if (!selfClosing) {
                            stack.add(element)
                            require(stack.size <= MAX_DEPTH) { "SVG nesting is limited to $MAX_DEPTH levels" }
                        }
                    }
                }
            }
        }
        require(stack.isEmpty()) { "Unclosed <${stack.last().name}>" }
        return root ?: throw IllegalArgumentException("Empty SVG document")
    }

    private inline fun readStartTag(s: String, start: Int, emit: (XmlElement, Boolean) -> Unit): Int {
        var i = start
        val n = s.length
        while (i < n && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
        val name = localName(s.substring(start, i))
        require(name.isNotEmpty()) { "Empty element name" }
        val attributes = LinkedHashMap<String, String>(8)
        while (true) {
            while (i < n && s[i].isWhitespace()) i++
            require(i < n) { "Unterminated <$name>" }
            if (s[i] == '>') { emit(XmlElement(name, attributes), false); return i + 1 }
            if (s[i] == '/') {
                require(i + 1 < n && s[i + 1] == '>') { "Malformed <$name>" }
                emit(XmlElement(name, attributes), true)
                return i + 2
            }
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            val key = s.substring(keyStart, i)
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != '=') { attributes[key] = ""; continue }
            i++
            while (i < n && s[i].isWhitespace()) i++
            require(i < n && (s[i] == '"' || s[i] == '\'')) { "Unquoted attribute $key" }
            val quote = s[i]
            val end = s.indexOf(quote, i + 1)
            require(end >= 0) { "Unterminated attribute $key" }
            attributes[key] = decode(s.substring(i + 1, end))
            i = end + 1
        }
    }

    private fun localName(name: String): String {
        val colon = name.indexOf(':')
        return if (colon >= 0) name.substring(colon + 1) else name
    }

    fun decode(value: String): String {
        if (value.indexOf('&') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val end = if (c == '&') value.indexOf(';', i) else -1
            if (end < 0 || end - i > 10) { out.append(c); i++; continue }
            val entity = value.substring(i + 1, end)
            val decoded: String? = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let(::codePoint)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            if (decoded == null) { out.append(c); i++ } else { out.append(decoded); i = end + 1 }
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null
}
