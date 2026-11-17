package com.overdrive.app.overlay.glass

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * A user widget file (format 1), checked and compiled: its glass size and layers, each property fixed,
 * bound to car data ("=…") or a text template. Pure data with no views, so the daemon validates saves
 * with it too. Unknown layer types and properties are skipped; [canonical] drops them.
 */
class CustomDoc private constructor(
    val id: String,
    val name: String,
    val size: String,
    val about: String,
    val w: Float,
    val h: Float,
    val layers: List<Spec>,
    /** Every car signal the file reads (names that aren't builtins or colour tokens). */
    val signals: Set<String>,
    /** The MQTT channels its `mqtt('…')` calls name; their values arrive as data keys "mqtt:<channel>". */
    val mqttChannels: Set<String>,
) {
    /** True when the file reads a signal only the live poll carries. */
    val needsLive: Boolean = signals.any { it in CustomSignals.LIVE_KEYS }

    sealed class Prop
    class Fixed(val v: Any?) : Prop()
    class Bound(val node: CustomExpr.Node) : Prop()
    class Template(val parts: List<Any>) : Prop()

    /** One layer: its [type] and its checked properties. Numbers are Doubles. */
    class Spec(val type: String, val props: Map<String, Prop>) {
        val bound = props.values.any { it is Bound || (it is Template && it.parts.any { p -> p is CustomExpr.Hole }) }
        fun fixed(key: String): Any? = (props[key] as? Fixed)?.v
    }

    class Invalid(message: String) : Exception(message)

    private class Bad(message: String) : Exception(message)
    private class Key(val name: String, val kind: Int, val options: List<String>? = null)
    private class Checked(val prop: Prop, val json: Any)

    companion object {
        const val FORMAT = 1
        const val MAX_BYTES = 16 * 1024
        const val MAX_LAYERS = 24
        const val MAX_NAME = 60
        const val MAX_ABOUT = 500
        const val MAX_TEXT = 400
        const val HEIGHT = 184f
        /** Car and road layers each hold a whole built-in widget's drawing. */
        const val MAX_OBJECTS = 4
        private const val MAX_PATH = 4000
        private const val MAX_GEO = 10_000.0
        private const val MAX_NUM = CustomExpr.MAX_NUM
        private const val UNTITLED = "Untitled"
        private const val NO_LINKS = "Links aren't allowed in widgets"

        val SIZES = mapOf("small" to 272f, "medium" to 400f, "wide" to 520f)
        /** The car layer's parts, in their canonical order. */
        val PARTS = listOf("body", "pack", "motors", "tyres", "pressures", "flow", "port")
        /** The theme colours a file may name; they follow night and day. */
        val COLOR_TOKENS = setOf("accent", "good", "warn", "danger", "glowAccent", "glowGood", "glowWarn", "glowDanger",
            "fuel", "regen", "regenText", "text", "text2", "text3", "etch", "white", "black")
        /** Names the renderer or the reading answers: theme, motion state, the car's own units, the costs' currency. */
        val BUILTINS = setOf("night", "driving", "parked", "pi", CustomExpr.MILES, "dist_unit", "speed_unit", "near_unit", "height_unit",
            "consumption_unit", CustomExpr.TYRE_UNIT, "currency")

        private const val GEO = 0
        private const val NUM = 1
        private const val COL = 2
        private const val TXT = 3
        private const val BOOL = 4
        private const val CHOICE = 5
        private const val WEIGHT = 6
        private const val PARTS_KIND = 7
        private const val PATH = 8
        private const val NAME = 9
        private const val PHASE = 10

        private val MOTIONS = listOf("none", "number", "liquid")
        private fun geo(vararg names: String) = names.map { Key(it, GEO) }
        private fun choice(name: String, vararg options: String) = Key(name, CHOICE, options.toList())
        private val HEAD = listOf(Key("name", NAME))
        private val TAIL = listOf(Key("visible", BOOL), Key("opacity", NUM))
        private val TYPES: Map<String, List<Key>> = mapOf(
            "ring" to geo("cx", "cy", "r", "width", "start", "sweep") +
                listOf(Key("value", NUM), Key("color", COL), Key("track", COL), Key("glow", GEO), choice("motion", *MOTIONS.toTypedArray())),
            "bar" to geo("x", "y", "w", "h") +
                listOf(Key("value", NUM), Key("color", COL), Key("track", COL), Key("glow", GEO), choice("motion", *MOTIONS.toTypedArray())),
            "text" to geo("x", "y", "size") +
                listOf(Key("weight", WEIGHT), choice("align", "left", "center", "right"), Key("text", TXT), Key("unit", TXT), Key("color", COL),
                    choice("motion", *MOTIONS.toTypedArray())),
            "spark" to geo("x", "y", "w", "h", "window", "fill") + listOf(Key("value", NUM), Key("color", COL)),
            "capsule" to geo("x", "y", "w", "h") +
                listOf(Key("level", NUM), Key("limit", NUM), Key("color", COL), choice("motion", *MOTIONS.toTypedArray())),
            "pulse" to geo("cx", "cy", "r", "period") + listOf(Key("color", COL)),
            "shape" to listOf(choice("shape", "rect", "circle", "path")) + geo("x", "y", "w", "h", "radius") +
                listOf(Key("d", PATH), Key("strokeWidth", GEO), Key("fill", COL), Key("stroke", COL), Key("rotate", NUM),
                    choice("motion", *MOTIONS.toTypedArray())),
            "car" to listOf(Key("parts", PARTS_KIND)) + geo("x", "y", "scale") +
                listOf(Key("power", NUM), Key("soc", NUM), Key("phase", PHASE, listOf("driving", "charging", "parked")),
                    Key("fl", NUM), Key("fr", NUM), Key("rl", NUM), Key("rr", NUM),
                    choice("drive", "awd", "fwd", "rwd")),
            "road" to geo("x", "y", "scale") +
                listOf(Key("ahead", BOOL), Key("hazard", NUM), Key("distance", NUM), Key("severity", NUM), Key("count", NUM),
                    choice("region", "eu", "us", "cn"), choice("side", "left", "right")),
        )

        /** Reads a stored or bundled file; a property that doesn't check out is dropped and draws at its default. */
        fun parse(json: String, id: String): CustomDoc = compile(json, id, null)

        /** The file validated and normalised (unknown types and keys, `tap` among them, removed); throws [Invalid]. */
        fun canonical(json: String): String {
            val out = JSONObject()
            compile(json, "", out)
            val s = plainSlashes(out.toString())
            if (utf8Length(s) > MAX_BYTES) throw Invalid("The widget is larger than 16 KB")
            return s
        }

        /** The car parts named in a `parts` list, as indexes into [PARTS]; null when it names none. */
        fun partIndexes(list: String): List<Int>? {
            val named = list.split(',').map { it.trim() }
            return PARTS.indices.filter { PARTS[it] in named }.takeIf { it.isNotEmpty() }
        }

        // strict (canonical) when [out] is given: the first problem throws; otherwise only the file's frame must hold
        private fun compile(json: String, id: String, out: JSONObject?): CustomDoc {
            val strict = out != null
            if (json.length > MAX_BYTES || utf8Length(json) > MAX_BYTES) throw Invalid("The widget is larger than 16 KB")
            // a deeply nested file overflows the JSON parser's stack: that is a bad file, not a crash
            val o = try { JSONObject(json) } catch (_: Exception) { null } catch (_: StackOverflowError) { null }
                ?: throw Invalid("This isn't a widget file")
            val format = o.opt("format")
            if (format != null && format != JSONObject.NULL) {
                if (format is Number && format.toDouble() > FORMAT) throw Invalid("This widget needs a newer version of OverDrive")
                if (format !is Number || format.toDouble() != FORMAT.toDouble()) throw Invalid("This isn't a widget file")
            }
            // a community file may come without a name; it still needs one to show in the tray
            val name = when (val n = o.opt("name")) {
                null, JSONObject.NULL -> ""
                is String -> n.trim()
                else -> if (strict) throw Invalid("The name must be text") else ""
            }.ifEmpty { UNTITLED }
            if (name.length > MAX_NAME) throw Invalid("The name is longer than $MAX_NAME characters")
            val size = when (val s = o.opt("size")) { null, JSONObject.NULL -> "small"; is String -> s.trim().lowercase(); else -> "" }
            val w = SIZES[size] ?: throw Invalid("Unknown widget size")
            val about = when (val a = o.opt("about")) {
                null, JSONObject.NULL -> ""
                is String -> a.trim()
                else -> if (strict) throw Invalid("The description must be text") else ""
            }
            if (about.length > MAX_ABOUT) throw Invalid("The description is longer than $MAX_ABOUT characters")
            if (hasLink(name) || hasLink(about)) throw Invalid(NO_LINKS)
            val arr = when (val a = o.opt("layers")) {
                null, JSONObject.NULL -> JSONArray()
                is JSONArray -> a
                else -> throw Invalid("This isn't a widget file")
            }
            if (arr.length() > MAX_LAYERS) throw Invalid("A widget can have at most $MAX_LAYERS layers")

            val specs = ArrayList<Spec>()
            val outLayers = if (strict) JSONArray() else null
            val names = HashSet<String>()
            val channels = LinkedHashSet<String>()
            var objects = 0
            for (i in 0 until arr.length()) {
                val l = arr.opt(i) as? JSONObject ?: if (strict) throw Invalid("Layer ${i + 1} isn't a layer") else continue
                // a newer file's layer type is skipped, not fatal
                val type = l.opt("type") as? String ?: continue
                val keys = TYPES[type] ?: continue
                if ((type == "car" || type == "road") && ++objects > MAX_OBJECTS) throw Invalid("At most $MAX_OBJECTS car or road layers")
                val label = "Layer ${i + 1}" + ((l.opt("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { " “${it.take(MAX_NAME)}”" } ?: "")
                val props = LinkedHashMap<String, Prop>()
                val ol = if (strict) JSONObject().put("type", type) else null
                for (keyList in arrayOf(HEAD, keys, TAIL)) for (k in keyList) {
                    // an early file named a lone pack with "part"; a null is the same as no value
                    val raw = when {
                        l.has(k.name) -> l.opt(k.name)
                        k.kind == PARTS_KIND && l.opt("part") == "pack" -> "pack"
                        else -> continue
                    }
                    if (raw == null || raw == JSONObject.NULL) continue
                    val c = try { check(k, raw) } catch (e: Bad) {
                        if (strict) throw Invalid("$label: ${k.name} ${e.message}") else continue
                    } catch (e: CustomExpr.Error) {
                        if (strict) throw Invalid("$label: ${k.name}: ${e.message}") else continue
                    } ?: continue
                    props[k.name] = c.prop
                    ol?.put(k.name, c.json)
                    when (val p = c.prop) {
                        is Bound -> { CustomExpr.names(p.node, names); CustomExpr.channels(p.node, channels) }
                        is Template -> for (part in p.parts) if (part is CustomExpr.Hole) { CustomExpr.names(part.node, names); CustomExpr.channels(part.node, channels) }
                        is Fixed -> {}
                    }
                }
                specs.add(Spec(type, props))
                outLayers?.put(ol)
            }
            if (strict && specs.isEmpty()) throw Invalid("The widget has no layers")
            if (out != null) {
                out.put("format", FORMAT).put("name", name).put("size", size)
                if (about.isNotEmpty()) out.put("about", about)
                out.put("layers", outLayers)
            }
            val signals = names.filterTo(LinkedHashSet()) { it !in BUILTINS && it !in COLOR_TOKENS }
            return CustomDoc(id, name, size, about, w, HEIGHT, specs, signals, channels)
        }

        // a value as the file keeps it, or null to leave the key out (an empty layer name); [raw] is never null
        private fun check(k: Key, raw: Any): Checked? {
            if (raw is String && hasLink(raw)) throw Invalid(NO_LINKS)
            return when (k.kind) {
                GEO -> {
                    val d = finite(raw) ?: throw Bad("must be a number")
                    val v = Math.round(d.coerceIn(-MAX_GEO, MAX_GEO) * 1000.0) / 1000.0
                    Checked(Fixed(v), v)
                }
                NUM, BOOL -> when {
                    raw is Boolean -> Checked(Fixed(raw), raw)
                    raw is Number -> finite(raw)?.coerceIn(-MAX_NUM, MAX_NUM)?.let { Checked(Fixed(it), it) } ?: throw Bad("must be a number")
                    raw is String && raw.startsWith("=") -> expression(raw)
                    else -> throw Bad(if (k.kind == BOOL) "must be true, false or an expression" else "must be a number or an expression")
                }
                COL -> when {
                    raw !is String -> throw Bad("isn't a colour")
                    raw.startsWith("=") -> expression(raw)
                    raw == "none" || raw in COLOR_TOKENS -> Checked(Fixed(raw), raw)
                    CustomExpr.parseColor(raw) != null -> raw.uppercase().let { Checked(Fixed(it), it) }
                    else -> throw Bad("isn't a colour")
                }
                TXT -> when {
                    // a constant true or false shows as the word, so it is kept as one
                    raw is Boolean -> raw.toString().let { Checked(Fixed(it), it) }
                    raw is Number -> finite(raw)?.coerceIn(-MAX_NUM, MAX_NUM)?.let { Checked(Fixed(it), it) } ?: throw Bad("must be a number")
                    raw !is String -> throw Bad("must be text")
                    raw.startsWith("=") -> expression(raw)
                    raw.length > MAX_TEXT -> throw Bad("is longer than $MAX_TEXT characters")
                    raw.contains('{') -> Checked(Template(CustomExpr.template(raw)), raw)
                    else -> Checked(Fixed(raw), raw)
                }
                CHOICE, PHASE -> {
                    if (k.kind == PHASE && raw is String && raw.startsWith("=")) return expression(raw)
                    val opts = k.options.orEmpty()
                    val s = (raw as? String)?.trim()?.lowercase()
                    if (s == null || s !in opts) throw Bad("must be ${opts.joinToString(", ")}${if (k.kind == PHASE) " or an expression" else ""}")
                    Checked(Fixed(s), s)
                }
                WEIGHT -> {
                    val d = finite(raw) ?: throw Bad("must be 500, 600 or 700")
                    val v = if (d >= 700.0) 700.0 else if (d >= 600.0) 600.0 else 500.0
                    Checked(Fixed(v), v)
                }
                PARTS_KIND -> {
                    val list = (raw as? String)?.let { partIndexes(it) } ?: throw Bad("names no car parts")
                    val s = list.joinToString(",") { PARTS[it] }
                    Checked(Fixed(s), s)
                }
                PATH -> {
                    if (raw !is String || raw.length > MAX_PATH || !pathData(raw)) throw Bad("isn't SVG path data")
                    Checked(Fixed(raw), raw)
                }
                else -> {
                    val s = (raw as? String)?.trim() ?: throw Bad("must be text")
                    if (s.length > MAX_NAME) throw Bad("is longer than $MAX_NAME characters")
                    if (s.isEmpty()) null else Checked(Fixed(s), s)
                }
            }
        }

        private fun expression(raw: String) = Checked(Bound(CustomExpr.parse(raw.substring(1))), raw)

        /**
         * The links rule the builder and the community backend share: compatibility forms folded (NFKC), soft
         * hyphens, zero-width and direction marks dropped, lower case; then no "://", "www." or "javascript:".
         */
        fun hasLink(s: String): Boolean {
            val n = Normalizer.normalize(s, Normalizer.Form.NFKC)
            val sb = StringBuilder(n.length)
            for (ch in n) {
                if (ch == '\u00AD' || ch == '\uFEFF' || ch in '\u200B'..'\u200F' || ch in '\u202A'..'\u202E' || ch in '\u2060'..'\u2064') continue
                sb.append(ch)
            }
            val t = sb.toString().lowercase()
            return t.contains("://") || t.contains("www.") || t.contains("javascript:")
        }

        private fun finite(raw: Any?): Double? = if (raw is Number) raw.toDouble().takeIf { it.isFinite() } else null

        // SVG path letters and numbers, with the community backend's (JavaScript's) whitespace; the renderer skips a path it can't parse
        private fun pathData(s: String): Boolean {
            for (ch in s) if (ch !in PATH_CHARS && ch !in JS_SPACE && ch !in '\u2000'..'\u200A') return false
            return true
        }

        private const val PATH_CHARS = "MmLlHhVvCcSsQqTtAaZz0123456789.,+-eE"
        private const val JS_SPACE = "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF"

        // org.json writes every "/" as "\/"; the plain form is what the builder and the community backend measure
        private fun plainSlashes(s: String): String {
            if (!s.contains("\\/")) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    val next = s[i + 1]
                    if (next != '/') sb.append(c)
                    sb.append(next)
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        private fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }
    }
}
