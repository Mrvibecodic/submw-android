package app.svc.core.a1

object Json {
    class Bad(msg: String) : Exception(msg)

    fun write(v: Any?): String = StringBuilder().also { put(it, v) }.toString()

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k.toString())
                    sb.append(':')
                    put(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    put(sb, x)
                }
                sb.append(']')
            }
            is Array<*> -> put(sb, v.asList())
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    fun parse(s: String): Any? {
        val p = P(s)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != s.length) throw Bad("tail")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(s: String): Map<String, Any?> = parse(s) as? Map<String, Any?> ?: throw Bad("not object")

    private class P(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 32) throw Bad("deep")
            if (i >= s.length) throw Bad("eof")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> number()
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws()
                if (peek() != '"') throw Bad("key")
                val k = string()
                ws()
                expect(':')
                ws()
                m[k] = value(depth + 1)
                ws()
                when (next()) {
                    ',' -> {}
                    '}' -> return m
                    else -> throw Bad("obj")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value(depth + 1))
                ws()
                when (next()) {
                    ',' -> {}
                    ']' -> return l
                    else -> throw Bad("arr")
                }
            }
        }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun next(): Char = if (i < s.length) s[i++] else throw Bad("eof")

        fun expect(c: Char) {
            if (next() != c) throw Bad("expect $c")
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Bad("lit")
            i += w.length
            return v
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw Bad("u")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw Bad("esc $e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val st = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(st, i)
            if (t.isEmpty() || t == "-") throw Bad("num")
            return if (t.contains('.') || t.contains('e') || t.contains('E')) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }
}

fun Map<String, Any?>.str(k: String): String = (this[k] as? String) ?: ""

fun Map<String, Any?>.long(k: String, d: Long = 0): Long = (this[k] as? Number)?.toLong() ?: d

fun Map<String, Any?>.int(k: String, d: Int = 0): Int = (this[k] as? Number)?.toInt() ?: d

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.map(k: String): Map<String, Any?> = (this[k] as? Map<String, Any?>) ?: emptyMap()

fun Map<String, Any?>.list(k: String): List<Any?> = (this[k] as? List<Any?>) ?: emptyList()
