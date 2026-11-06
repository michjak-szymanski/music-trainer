package com.musictrainer.notation

/** A parsed XML element. [text] is the concatenated character data directly inside it. */
class XmlNode(val name: String, val attrs: Map<String, String>, val children: List<XmlNode>, val text: String) {
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun all(name: String): List<XmlNode> = children.filter { it.name == name }
    fun text(name: String): String? = child(name)?.text?.trim()
    fun int(name: String): Int? = text(name)?.toIntOrNull()
}

/** Small, tolerant XML reader (elements, attributes, text, CDATA, comments, entities). */
object Xml {
    fun parse(src: String): XmlNode {
        var i = 0
        val n = src.length

        fun decode(s: String): String {
            if ('&' !in s) return s
            val sb = StringBuilder()
            var k = 0
            while (k < s.length) {
                val c = s[k]
                if (c == '&') {
                    val end = s.indexOf(';', k)
                    if (end > k) {
                        val ent = s.substring(k + 1, end)
                        val rep = when {
                            ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "amp" -> "&"; ent == "quot" -> "\""; ent == "apos" -> "'"
                            ent.startsWith("#x") -> ent.drop(2).toIntOrNull(16)?.let { codePoint(it) }
                            ent.startsWith("#") -> ent.drop(1).toIntOrNull()?.let { codePoint(it) }
                            else -> null
                        }
                        if (rep != null) { sb.append(rep); k = end + 1; continue }
                    }
                }
                sb.append(c); k++
            }
            return sb.toString()
        }

        class Builder(val name: String, val attrs: Map<String, String>) {
            val children = ArrayList<XmlNode>()
            val text = StringBuilder()
            fun build() = XmlNode(name, attrs, children, text.toString())
        }
        val root = Builder("#document", emptyMap())
        val stack = ArrayList<Builder>().apply { add(root) }

        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) { stack.last().text.append(decode(src.substring(i))); break }
            if (lt > i) stack.last().text.append(decode(src.substring(i, lt)))
            i = lt
            when {
                src.startsWith("<!--", i) -> i = src.indexOf("-->", i).let { if (it < 0) n else it + 3 }
                src.startsWith("<![CDATA[", i) -> {
                    val end = src.indexOf("]]>", i).let { if (it < 0) n else it }
                    stack.last().text.append(src, i + 9, end)
                    i = (end + 3).coerceAtMost(n)
                }
                src.startsWith("<?", i) -> i = src.indexOf("?>", i).let { if (it < 0) n else it + 2 }
                src.startsWith("<!", i) -> i = src.indexOf('>', i).let { if (it < 0) n else it + 1 }
                src.startsWith("</", i) -> {
                    val gt = src.indexOf('>', i).let { if (it < 0) n else it }
                    val name = src.substring(i + 2, gt).trim()
                    // Close up to the matching element (tolerates unbalanced input).
                    val idx = stack.indexOfLast { it.name == name }
                    if (idx > 0) while (stack.size > idx) { val b = stack.removeAt(stack.lastIndex); stack.last().children += b.build() }
                    i = gt + 1
                }
                else -> {
                    var j = i + 1
                    while (j < n && !src[j].isWhitespace() && src[j] != '>' && src[j] != '/') j++
                    val name = src.substring(i + 1, j)
                    val attrs = HashMap<String, String>()
                    var selfClosing = false
                    while (j < n) {
                        while (j < n && src[j].isWhitespace()) j++
                        if (j >= n) break
                        if (src[j] == '>') { j++; break }
                        if (src[j] == '/') { selfClosing = true; j++; continue }
                        val eq = src.indexOf('=', j)
                        if (eq < 0) { j = n; break }
                        val key = src.substring(j, eq).trim()
                        var q = eq + 1
                        while (q < n && src[q].isWhitespace()) q++
                        val quote = src[q]
                        val close = src.indexOf(quote, q + 1).let { if (it < 0) n else it }
                        attrs[key] = decode(src.substring(q + 1, close))
                        j = close + 1
                    }
                    val b = Builder(name, attrs)
                    if (selfClosing) stack.last().children += b.build() else stack += b
                    i = j
                }
            }
        }
        while (stack.size > 1) { val b = stack.removeAt(stack.lastIndex); stack.last().children += b.build() }
        return root.build().children.firstOrNull() ?: throw MidiFormatException("Empty XML document")
    }

    private fun codePoint(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString()
        else { val v = cp - 0x10000; charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString() }
}

/** Reads the entries of a ZIP archive (MuseScore .mscz). */
expect fun unzipEntries(bytes: ByteArray): Map<String, ByteArray>
