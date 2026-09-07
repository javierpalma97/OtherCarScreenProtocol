package axlink

/** Minimal JSON reader/writer for the AXLink message subset (no dependencies). */
object AxJson {
    fun parse(s: String): Any? = Parser(s).value()

    fun stringify(v: Any?): String = buildString { write(this, v) }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> sb.append(v.toString())
            is String -> {
                sb.append('"')
                for (c in v) when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
                sb.append('"')
            }
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    write(sb, e.key.toString()); sb.append(':'); write(sb, e.value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, e -> if (i > 0) sb.append(','); write(sb, e) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported $v")
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun value(): Any? {
            ws(); val v = when (s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }; ws(); return v
        }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++
            ws(); if (s[i] == '}') { i++; return m }
            while (true) { ws(); val k = str(); ws(); i++; val v = value(); m[k] = v; ws()
                if (s[i] == ',') { i++; continue }; i++; return m }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++
            ws(); if (s[i] == ']') { i++; return l }
            while (true) { l.add(value()); ws()
                if (s[i] == ',') { i++; continue }; i++; return l }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (true) { val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') { when (val e = s[i++]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\')
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> throw IllegalArgumentException("bad escape $e") } }
                else sb.append(c) }
        }
        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("bad literal"); i += w.length; return v
        }
        fun num(): Double {
            val st = i
            while (i < s.length && s[i] in "-+0123456789.eE") i++
            return s.substring(st, i).toDouble()
        }
    }
}
