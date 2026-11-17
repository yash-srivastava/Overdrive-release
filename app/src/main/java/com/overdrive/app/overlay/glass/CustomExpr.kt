package com.overdrive.app.overlay.glass

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The widget file's expression language: numbers, text, colours, arithmetic, comparisons, `?:` and a
 * fixed set of pure functions over car signals. No loops, no assignment, no I/O; parsed once.
 * An unknown reading is null and stays null through arithmetic, so it draws as "–", never as 0.
 * The builder's engine.js implements the same rules. Pure Kotlin: also runs in the daemon.
 */
object CustomExpr {
    /** A colour value (ARGB), kept apart from numbers. */
    class Col(val argb: Int) {
        override fun equals(other: Any?) = other is Col && other.argb == argb
        override fun hashCode() = argb
    }

    class Error(message: String, val at: Int) : Exception(message)

    sealed class Node
    class Lit(val v: Any?) : Node()
    class Ref(val name: String) : Node()
    class Unary(val op: String, val a: Node) : Node()
    class Binary(val op: String, val a: Node, val b: Node) : Node()
    class Cond(val c: Node, val a: Node, val b: Node) : Node()
    class Call(val fn: String, val args: List<Node>) : Node()

    fun interface Scope { fun get(name: String): Any? }

    const val MAX_LEN = 400
    /** No number leaves an evaluation beyond this (or non-finite): it is an unknown instead. */
    const val MAX_NUM = 1e9
    // nesting bound, so a hostile 400-character expression can't run the parser out of stack
    private const val MAX_DEPTH = 64
    // the unit functions follow the car's own picks, which the scope carries as these builtins
    const val MILES = "miles"
    const val TYRE_UNIT = "tyre_unit"
    private const val KM_PER_MI = 1.609344
    private const val YD_PER_M = 1.0936133
    private const val FT_PER_M = 3.2808399
    private const val PSI_PER_KPA = 0.1450377
    /** `mqtt('x')` reads the data key "mqtt:x": the latest payload of that automation channel. */
    const val MQTT_PREFIX = "mqtt:"
    private const val MQTT_TEXT = 64
    private val CHANNEL = Regex("[A-Za-z0-9._-]{1,64}")
    private val DECIMAL = Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?")

    private val ARITY = mapOf(
        "min" to 1..8, "max" to 1..8, "clamp" to 3..3, "lerp" to 3..3, "abs" to 1..1, "round" to 1..1,
        "floor" to 1..1, "ceil" to 1..1, "sqrt" to 1..1, "pow" to 2..2, "sin" to 1..1, "cos" to 1..1,
        "known" to 1..1, "ifknown" to 2..2, "fmt" to 1..2, "ramp" to 3..31, "mix" to 3..3, "alpha" to 2..2,
        "dist" to 1..1, "near" to 1..1, "height" to 1..1, "consumption" to 1..1, "tyre" to 1..1, "mqtt" to 1..1,
    )
    private val TWO = setOf("||", "&&", "==", "!=", "<=", ">=")
    private const val ONE = "?:+-*/%<>!(),"
    private val POW10 = DoubleArray(10).also { var p = 1.0; for (i in it.indices) { it[i] = p; p *= 10.0 } }

    private class Tok(val kind: Char, val text: String, val num: Double, val col: Int, val at: Int)

    private fun digit(c: Char) = c in '0'..'9'
    private fun alpha(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
    private fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** `#RRGGBB` or `#AARRGGBB` as ARGB; null for anything else. */
    fun parseColor(s: String): Int? {
        if ((s.length != 7 && s.length != 9) || s[0] != '#') return null
        var v = 0L
        for (i in 1 until s.length) {
            val c = s[i]
            if (!hex(c)) return null
            v = v * 16 + Character.digit(c, 16)
        }
        return if (s.length == 7) (0xFF000000L or v).toInt() else v.toInt()
    }

    private fun tokenize(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            when {
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' -> i++
                digit(ch) || ch == '.' -> {
                    var j = i
                    while (j < src.length && (digit(src[j]) || src[j] == '.')) j++
                    val v = src.substring(i, j).toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw Error("Bad number", i)
                    out.add(Tok('n', "", v, 0, i)); i = j
                }
                alpha(ch) -> {
                    var j = i
                    while (j < src.length && (alpha(src[j]) || digit(src[j]))) j++
                    out.add(Tok('i', src.substring(i, j), 0.0, 0, i)); i = j
                }
                ch == '\'' || ch == '"' -> {
                    val j = src.indexOf(ch, i + 1)
                    if (j < 0) throw Error("Unclosed text", i)
                    out.add(Tok('s', src.substring(i + 1, j), 0.0, 0, i)); i = j + 1
                }
                ch == '#' -> {
                    var j = i + 1
                    while (j < src.length && (alpha(src[j]) || digit(src[j]))) j++
                    val c = parseColor(src.substring(i, j)) ?: throw Error("Bad colour", i)
                    out.add(Tok('c', "", 0.0, c, i)); i = j
                }
                else -> {
                    val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                    when {
                        two in TWO -> { out.add(Tok('o', two, 0.0, 0, i)); i += 2 }
                        ch in ONE -> { out.add(Tok('o', ch.toString(), 0.0, 0, i)); i++ }
                        else -> throw Error("Unexpected “$ch”", i)
                    }
                }
            }
        }
        out.add(Tok('e', "", 0.0, 0, src.length))
        return out
    }

    /** Parses one expression (the text after a property's "="); throws [Error] with the position. */
    fun parse(src: String): Node {
        if (src.length > MAX_LEN) throw Error("Expression is too long", 0)
        return Parser(tokenize(src)).run()
    }

    private class Parser(val tk: List<Tok>) {
        var p = 0
        var depth = 0
        fun isOp(v: String) = tk[p].kind == 'o' && tk[p].text == v
        fun expect(v: String) { if (!isOp(v)) throw Error("Expected “$v”", tk[p].at); p++ }
        fun deeper() { if (++depth > MAX_DEPTH) throw Error("Expression is nested too deeply", tk[p].at) }

        fun run(): Node {
            val e = cond()
            if (tk[p].kind != 'e') throw Error("Unexpected “${tk[p].text}”", tk[p].at)
            return e
        }

        fun cond(): Node {
            deeper()
            val c = binary(0)
            val r = if (!isOp("?")) c else {
                p++
                val a = cond()
                expect(":")
                Cond(c, a, cond())
            }
            depth--
            return r
        }

        private val levels = listOf(listOf("||"), listOf("&&"), listOf("==", "!="), listOf("<", "<=", ">", ">="), listOf("+", "-"), listOf("*", "/", "%"))

        fun binary(level: Int): Node {
            if (level == levels.size) return unary()
            var a = binary(level + 1)
            while (tk[p].kind == 'o' && tk[p].text in levels[level]) {
                val op = tk[p++].text
                a = Binary(op, a, binary(level + 1))
            }
            return a
        }

        fun unary(): Node {
            if (!isOp("-") && !isOp("!")) return primary()
            val op = tk[p++].text
            deeper()
            val a = unary()
            depth--
            return Unary(op, a)
        }

        fun primary(): Node {
            val t = tk[p++]
            return when (t.kind) {
                'n' -> Lit(t.num)
                's' -> Lit(t.text)
                'c' -> Lit(Col(t.col))
                'i' -> when {
                    isOp("(") -> {
                        p++
                        val ar = ARITY[t.text] ?: throw Error("Unknown function “${t.text}”", t.at)
                        val args = ArrayList<Node>()
                        if (!isOp(")")) { args.add(cond()); while (isOp(",")) { p++; args.add(cond()) } }
                        expect(")")
                        // a channel is named outright, so the file says which channels it reads before it runs
                        if (t.text == "mqtt" && ((args.singleOrNull() as? Lit)?.v as? String)?.let { CHANNEL.matches(it) } != true) {
                            throw Error("mqtt() needs a channel name in quotes", t.at)
                        }
                        if (args.size !in ar) throw Error("${t.text}() takes ${if (ar.first == ar.last) "${ar.first}" else "${ar.first}–${ar.last}"} values", t.at)
                        Call(t.text, args)
                    }
                    t.text == "true" -> Lit(true)
                    t.text == "false" -> Lit(false)
                    t.text == "null" -> Lit(null)
                    else -> Ref(t.text)
                }
                'o' -> if (t.text == "(") cond().also { expect(")") } else throw Error("Unexpected “${t.text}”", t.at)
                else -> throw Error("Unfinished expression", t.at)
            }
        }
    }

    /** Every name [n] reads (signals, builtins and colour tokens alike). */
    fun names(n: Node, out: MutableSet<String>) {
        when (n) {
            is Ref -> out.add(n.name)
            is Unary -> names(n.a, out)
            is Binary -> { names(n.a, out); names(n.b, out) }
            is Cond -> { names(n.c, out); names(n.a, out); names(n.b, out) }
            is Call -> for (a in n.args) names(a, out)
            is Lit -> {}
        }
    }

    /** Every MQTT channel [n] reads through `mqtt('…')`. */
    fun channels(n: Node, out: MutableSet<String>) {
        when (n) {
            is Call -> {
                // the parser lets mqtt() through only with one channel literal
                if (n.fn == "mqtt") out.add((n.args[0] as Lit).v as String) else for (a in n.args) channels(a, out)
            }
            is Unary -> channels(n.a, out)
            is Binary -> { channels(n.a, out); channels(n.b, out) }
            is Cond -> { channels(n.c, out); channels(n.a, out); channels(n.b, out) }
            is Ref, is Lit -> {}
        }
    }

    /**
     * A channel's latest payload as a widget reads it: control characters dropped and the ends trimmed (as
     * JavaScript's trim does), then a plain decimal is its finite number, other text its first 64 characters;
     * nothing, or nothing left, is null.
     */
    fun mqttValue(raw: Any?): Any? {
        if (raw is Number) return raw.toDouble().takeIf { it.isFinite() }
        if (raw !is String) return null
        val sb = StringBuilder(raw.length)
        for (ch in raw) if (ch.code >= 0x20 && ch.code !in 0x7F..0x9F) sb.append(ch)
        var a = 0
        var b = sb.length
        while (a < b && jsSpace(sb[a])) a++
        while (b > a && jsSpace(sb[b - 1])) b--
        if (a == b) return null
        val t = sb.substring(a, b)
        if (DECIMAL.matches(t)) t.toDouble().takeIf { it.isFinite() }?.let { return it }
        if (t.length <= MQTT_TEXT) return t
        // a cut never leaves half of a surrogate pair
        return t.substring(0, if (Character.isHighSurrogate(t[MQTT_TEXT - 1])) MQTT_TEXT - 1 else MQTT_TEXT)
    }

    // JavaScript's whitespace, control characters aside (they are already gone)
    private fun jsSpace(ch: Char) = ch == ' ' || ch == '\u00A0' || ch == '\u1680' || ch in '\u2000'..'\u200A' || ch == '\u2028' ||
        ch == '\u2029' || ch == '\u202F' || ch == '\u205F' || ch == '\u3000' || ch == '\uFEFF'

    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Number -> v.toDouble() != 0.0
        is Boolean -> v
        is String -> v.isNotEmpty()
        else -> true
    }

    fun num(v: Any?): Double? = when (v) {
        is Double -> if (v.isFinite()) v else null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }

    /** [x] with [d] decimals, rounded half away from zero like JavaScript's toFixed. */
    fun fixed(x: Double, d: Int): String {
        if (!x.isFinite()) return "–"
        val dd = d.coerceIn(0, POW10.size - 1)
        val scaled = abs(x) * POW10[dd]
        if (scaled >= 9e15) return String.format(Locale.US, "%.${dd}f", x)
        val r = Math.round(scaled)
        val sb = StringBuilder(24)
        if (x < 0) sb.append('-')
        if (dd == 0) return sb.append(r).toString()
        val p = POW10[dd].toLong()
        sb.append(r / p).append('.')
        val frac = (r % p).toString()
        for (i in frac.length until dd) sb.append('0')
        return sb.append(frac).toString()
    }

    fun text(v: Any?): String = when (v) {
        null -> "–"
        is Double -> if (v.isFinite()) Math.round(v).toString() else "–"
        is Number -> text(v.toDouble())
        is Col -> String.format(Locale.US, "#%08X", v.argb)
        else -> v.toString()
    }

    private fun same(a: Any, b: Any): Boolean {
        val x = num(a); val y = num(b)
        if (x != null && y != null) return x == y
        if (a is Col && b is Col) return a.argb == b.argb
        return a is String && b is String && a == b
    }

    /** Evaluates [n]; any failure, and any number not finite or beyond ±[MAX_NUM], is an unknown (null), never an exception. */
    fun eval(n: Node, s: Scope): Any? = try { bounded(evaluate(n, s)) } catch (_: RuntimeException) { null }

    private fun bounded(v: Any?): Any? {
        if (v !is Number) return v
        val d = v.toDouble()
        return if (d.isFinite() && abs(d) <= MAX_NUM) d else null
    }

    private fun evaluate(n: Node, s: Scope): Any? = when (n) {
        is Lit -> n.v
        is Ref -> s.get(n.name)
        is Unary -> { val a = evaluate(n.a, s); if (n.op == "!") !truthy(a) else num(a)?.let { -it } }
        is Cond -> if (truthy(evaluate(n.c, s))) evaluate(n.a, s) else evaluate(n.b, s)
        is Binary -> binary(n, s)
        is Call -> call(n, s)
    }

    private fun binary(n: Binary, s: Scope): Any? {
        if (n.op == "&&") { val a = evaluate(n.a, s); return if (truthy(a)) evaluate(n.b, s) else a }
        if (n.op == "||") { val a = evaluate(n.a, s); return if (truthy(a)) a else evaluate(n.b, s) }
        val a = evaluate(n.a, s) ?: return null
        val b = evaluate(n.b, s) ?: return null
        if (n.op == "==") return same(a, b)
        if (n.op == "!=") return !same(a, b)
        if (n.op == "+" && (a is String || b is String)) return text(a) + text(b)
        val x = num(a) ?: return null
        val y = num(b) ?: return null
        return when (n.op) {
            "+" -> x + y
            "-" -> x - y
            "*" -> x * y
            "/" -> if (y == 0.0) null else x / y
            "%" -> if (y == 0.0) null else x % y
            "<" -> x < y
            "<=" -> x <= y
            ">" -> x > y
            ">=" -> x >= y
            else -> null
        }
    }

    private fun call(n: Call, s: Scope): Any? {
        val v = arrayOfNulls<Any>(n.args.size)
        for (i in v.indices) v[i] = evaluate(n.args[i], s)
        when (n.fn) {
            "known" -> return v[0] != null
            "ifknown" -> return v[0] ?: v[1]
            "fmt" -> {
                val x = num(v[0]) ?: return "–"
                val d = if (v.size > 1) num(v[1]) ?: 0.0 else 0.0
                return fixed(x, d.toInt().coerceIn(0, 3))
            }
            "ramp" -> return ramp(v)
            "mqtt" -> return mqttValue(s.get(MQTT_PREFIX + v[0]))
            "mix" -> {
                val a = v[0] as? Col ?: return null; val b = v[1] as? Col ?: return null; val t = num(v[2]) ?: return null
                return Col(GlassColor.mix(a.argb, b.argb, t.toFloat()))
            }
            "alpha" -> {
                val a = v[0] as? Col ?: return null; val t = num(v[1]) ?: return null
                return Col(GlassColor.alpha(a.argb, ((a.argb ushr 24) / 255.0 * t).toFloat()))
            }
            // a pressure in bar as the car shows it: psi and bar to one decimal, kPa whole
            "tyre" -> {
                val bar = num(v[0])?.takeIf { abs(it) <= MAX_NUM } ?: return null
                val kpa = Math.round(bar * 100.0)
                return when (s.get(TYRE_UNIT)) {
                    "psi" -> fixed(kpa * PSI_PER_KPA, 1)
                    "kPa" -> kpa.toString()
                    else -> fixed(kpa / 100.0, 1)
                }
            }
        }
        val x = DoubleArray(v.size)
        for (i in v.indices) x[i] = num(v[i]) ?: return null
        return when (n.fn) {
            "min" -> { var m = x[0]; for (e in x) m = min(m, e); m }
            "max" -> { var m = x[0]; for (e in x) m = max(m, e); m }
            "clamp" -> min(max(x[0], x[1]), x[2])
            "lerp" -> x[0] + (x[1] - x[0]) * x[2]
            "abs" -> abs(x[0])
            "round" -> Math.round(x[0]).toDouble()
            "floor" -> floor(x[0])
            "ceil" -> ceil(x[0])
            "sqrt" -> if (x[0] < 0) null else sqrt(x[0])
            "pow" -> x[0].pow(x[1]).takeIf { it.isFinite() }
            "sin" -> sin(x[0])
            "cos" -> cos(x[0])
            // km (or km/h), m and Wh/km in the car's own units; the signals themselves stay metric
            "dist" -> if (miles(s)) x[0] / KM_PER_MI else x[0]
            "near" -> if (miles(s)) x[0] * YD_PER_M else x[0]
            "height" -> if (miles(s)) x[0] * FT_PER_M else x[0]
            "consumption" -> if (miles(s)) x[0] * KM_PER_MI / 10.0 else x[0] / 10.0
            else -> null
        }
    }

    private fun miles(s: Scope) = truthy(s.get(MILES))

    private fun ramp(v: Array<Any?>): Any? {
        val x = num(v[0]) ?: return null
        val n = (v.size - 1) / 2
        if (n == 0) return null
        val xs = FloatArray(n)
        val cs = arrayOfNulls<Any>(n)
        for (i in 0 until n) { xs[i] = (num(v[1 + 2 * i]) ?: return null).toFloat(); cs[i] = v[2 + 2 * i] }
        if (cs.all { it is Col }) return Col(GlassColor.rampHue(xs, IntArray(n) { (cs[it] as Col).argb }, x.toFloat()))
        val ys = DoubleArray(n)
        for (i in 0 until n) ys[i] = num(cs[i]) ?: return null
        if (x <= xs[0]) return ys[0]
        for (i in 1 until n) if (x <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / max(1e-6, (xs[i] - xs[i - 1]).toDouble())
        return ys[n - 1]
    }

    /** A hole in a text template: `{trip_km:1}` is the expression plus one decimal. */
    class Hole(val node: Node, val decimals: Int?)

    /** "Range {ev_range_km} km": literal text and [Hole]s, in order. */
    fun template(s: String): List<Any> {
        val parts = ArrayList<Any>()
        var i = 0
        while (i < s.length) {
            val o = s.indexOf('{', i)
            if (o < 0) { parts.add(s.substring(i)); break }
            if (o > i) parts.add(s.substring(i, o))
            val c = s.indexOf('}', o)
            if (c < 0) throw Error("Missing “}”", o)
            var body = s.substring(o + 1, c).trim()
            var dec: Int? = null
            if (body.length >= 2 && body[body.length - 2] == ':' && digit(body[body.length - 1])) {
                dec = body[body.length - 1] - '0'
                body = body.substring(0, body.length - 2).trim()
            }
            parts.add(Hole(parse(body), dec))
            i = c + 1
        }
        return parts
    }
}
