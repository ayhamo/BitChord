package com.music.bitchord.playback.smart.automixv2

/**
 * A small JSON reader and writer (objects are maps, arrays lists, numbers doubles), so the
 * analysis cache and the planner need nothing from Android and run the same on a desktop JVM.
 * A port of the phone's Json.java, kept as its own parser (not kotlinx.serialization) so a saved
 * analysis reads into exactly the same doubles the Java planner saw.
 */
class ForkV2Json private constructor(private val s: String) {
    private var i = 0

    /** Nesting so far: a body of "[[[[..." from an addon must not overflow the stack. */
    private var depth = 0

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        val c = s[i]
        if (c == '{' || c == '[') {
            if (++depth > MAX_DEPTH) throw IllegalArgumentException("nested deeper than $MAX_DEPTH at $i")
            try {
                return if (c == '{') obj() else array()
            } finally {
                depth--
            }
        }
        if (c == '"') return string()
        if (s.startsWith("true", i)) { i += 4; return true }
        if (s.startsWith("false", i)) { i += 5; return false }
        if (s.startsWith("null", i)) { i += 4; return null }
        val start = i
        while (i < s.length && "+-0123456789.eE".indexOf(s[i]) >= 0) i++
        if (start == i) throw IllegalArgumentException("unexpected '$c' at $i")
        return s.substring(start, i).toDouble()
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = string()
            ws()
            i++ // ':'
            ws()
            m[k] = value()
            ws()
            val c = s[i++]
            if (c == '}') return m
        }
    }

    private fun array(): List<Any?> {
        val l = ArrayList<Any?>()
        i++
        ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            ws()
            l.add(value())
            ws()
            val c = s[i++]
            if (c == ']') return l
        }
    }

    private fun string(): String {
        val b = StringBuilder()
        i++
        while (true) {
            val c = s[i++]
            if (c == '"') return b.toString()
            if (c == '\\') {
                when (val e = s[i++]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            } else {
                b.append(c)
            }
        }
    }

    companion object {
        private const val MAX_DEPTH = 64

        fun parse(text: String): Any? {
            val j = ForkV2Json(text)
            j.ws()
            val v = j.value()
            j.ws()
            if (j.i != j.s.length) throw IllegalArgumentException("trailing text at ${j.i}")
            return v
        }

        // ---- writing ----

        fun write(v: Any?): String {
            val b = StringBuilder()
            write(b, v)
            return b.toString()
        }

        @Suppress("UNCHECKED_CAST")
        private fun write(b: StringBuilder, v: Any?) {
            when (v) {
                null -> b.append("null")
                is Map<*, *> -> {
                    b.append('{')
                    var first = true
                    for ((k, value) in v as Map<String, Any?>) {
                        if (!first) b.append(',')
                        first = false
                        quote(b, k)
                        b.append(':')
                        write(b, value)
                    }
                    b.append('}')
                }
                is List<*> -> {
                    b.append('[')
                    var first = true
                    for (o in v) {
                        if (!first) b.append(',')
                        first = false
                        write(b, o)
                    }
                    b.append(']')
                }
                is DoubleArray -> {
                    b.append('[')
                    for (k in v.indices) {
                        if (k > 0) b.append(',')
                        number(b, v[k])
                    }
                    b.append(']')
                }
                is FloatArray -> {
                    b.append('[')
                    for (k in v.indices) {
                        if (k > 0) b.append(',')
                        // the vocal curve: 4 decimals are plenty and keep the cache small. NaN / infinity
                        // as null: "%.4f" would write NaN, which no JSON reader (this one included) reads
                        if (v[k].isNaN() || v[k].isInfinite()) b.append("null")
                        else b.append(forkV2Format("%.4f", v[k]))
                    }
                    b.append(']')
                }
                is Number -> number(b, v.toDouble())
                is Boolean -> b.append(v.toString())
                else -> quote(b, v.toString())
            }
        }

        private fun number(b: StringBuilder, d: Double) {
            if (d.isNaN() || d.isInfinite()) b.append("null")
            else if (d == kotlin.math.round(d) && kotlin.math.abs(d) < 1e15) b.append(d.toLong())
            else b.append(d)
        }

        private fun quote(b: StringBuilder, str: String) {
            b.append('"')
            for (c in str) {
                if (c == '"' || c == '\\') b.append('\\').append(c)
                else if (c == '\n') b.append("\\n")
                else if (c.code < 0x20) b.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else b.append(c)
            }
            b.append('"')
        }

        // ---- reading helpers ----

        fun num(m: Map<String, Any?>, k: String): Double {
            val v = m[k]
            return if (v is Number) v.toDouble() else Double.NaN
        }

        fun nums(list: Any?): DoubleArray? {
            if (list !is List<*>) return null
            return DoubleArray(list.size) { k -> val x = list[k]; if (x is Number) x.toDouble() else Double.NaN }
        }

        fun floats(list: Any?): FloatArray {
            if (list !is List<*>) return FloatArray(0)
            return FloatArray(list.size) { k -> val x = list[k]; if (x is Number) x.toFloat() else 0f }
        }
    }
}
