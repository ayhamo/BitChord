package com.music.bitchord.playback.smart.automixv2

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A lane the user drew point by point instead of picking a shape (spec/automix/editor.md §10, the
 * extension's 8c18f0f): Spotify's mixes have gates, bumps and twice-bent slopes the shapes of §3
 * can't make. A port of automix.js pointCurves, drawSec, drawX, shapeDrawing and normalizeDrawing,
 * checked against editor-cases.json group `draw`.
 *
 * [unit] is "beat" (x in beats of A from the window's start) or "sec" (x in seconds of the mix).
 * Each deck holds its curves by their plan name ([KEYS]: gain; bassDb, highDb; lowpassHz,
 * highpassHz), each a list of [x, v]; saved, they take the stored names (a volume deck is the list
 * itself, an EQ deck {bass, high}, an effect deck {lp, hp}). Two points at one x are a step, played
 * as an 8 ms ramp so a gate never clicks.
 */
class ForkV2Drawing(
    val lane: String,
    val unit: String,
    val out: Map<String, Array<DoubleArray>>,
    val `in`: Map<String, Array<DoubleArray>>,
    /** An Effects drawing's echo time in beats (1, 0.75 or 0.5; 0: missing, 0.75) and its delay's (0.75 or 0.5; 0: missing, 0.5), editor.md §12 rule 1. */
    val echoBeats: Double = 0.0,
    val delayBeats: Double = 0.0,
) {
    fun side(out: Boolean): Map<String, Array<DoubleArray>> = if (out) this.out else this.`in`

    /** One curve's points on a deck (empty when it isn't drawn). */
    fun points(key: String, out: Boolean): Array<DoubleArray> = side(out)[key] ?: emptyArray()

    /** This drawing with one deck's curve replaced ([pts] empty: that curve gone). Not normalized. */
    fun with(key: String, out: Boolean, pts: Array<DoubleArray>): ForkV2Drawing {
        fun put(m: Map<String, Array<DoubleArray>>): Map<String, Array<DoubleArray>> {
            val n = LinkedHashMap(m)
            if (pts.isEmpty()) n.remove(key) else n[key] = Array(pts.size) { pts[it].copyOf() }
            return n
        }
        return if (out) ForkV2Drawing(lane, unit, put(this.out), this.`in`, echoBeats, delayBeats) else ForkV2Drawing(lane, unit, this.out, put(this.`in`), echoBeats, delayBeats)
    }

    /** As saved (override.schema.json lanes: unit, out, in). */
    fun toJson(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["unit"] = unit
        for (s in listOf(true, false)) {
            val deck = side(s)
            m[if (s) "out" else "in"] = if (lane == "volume") {
                pointsJson(deck["gain"] ?: emptyArray())
            } else {
                LinkedHashMap<String, Any?>().apply { for (k in KEYS[lane]!!) deck[k]?.let { put(CURVES[k]!!.name!!, pointsJson(it)) } }
            }
        }
        if (lane == "fx") {
            if (echoBeats > 0) m["echoBeats"] = echoBeats
            if (delayBeats > 0) m["delayBeats"] = delayBeats
        }
        return m
    }

    /** A drawn curve: its lane, its name in the stored deck (null: the volume deck itself), its range. */
    class Curve internal constructor(val lane: String, val name: String?, val lo: Double, val hi: Double, val hz: Boolean, val chip: String)

    companion object {
        /** DRAW_MAX_POINTS: 20 gates of 4 points (a stutter) and their lead-in fit. */
        const val MAX_POINTS = 128
        const val TOO_MANY = "128 points is the most for one line"

        /** A lane's plan curves (automix.js DRAW_KEYS; mid, noise and the Effects lane: editor.md §12), in the chips' order. */
        val KEYS: Map<String, List<String>> = linkedMapOf(
            "volume" to listOf("gain"),
            "eq" to listOf("bassDb", "midDb", "highDb"),
            "effect" to listOf("lowpassHz", "highpassHz", "noise"),
            "fx" to listOf("echoSend", "reverbSend", "delaySend"),
        )

        /** automix.js DRAW_CURVES, with the overview's chip names (automix-overview.js CURVES). */
        val CURVES: Map<String, Curve> = linkedMapOf(
            "gain" to Curve("volume", null, 0.0, 1.0, false, "Volume"),
            "bassDb" to Curve("eq", "bass", ForkV2MixOverride.CUT_DB, 0.0, false, "Bass"),
            "midDb" to Curve("eq", "mid", ForkV2MixOverride.CUT_DB, 0.0, false, "Mid"),
            "highDb" to Curve("eq", "high", ForkV2MixOverride.CUT_DB, 0.0, false, "High"),
            "lowpassHz" to Curve("effect", "lp", 20.0, 20000.0, true, "Lowpass"),
            "highpassHz" to Curve("effect", "hp", 20.0, 20000.0, true, "Highpass"),
            "noise" to Curve("effect", "noise", 0.0, 1.0, false, "Noise"),
            "echoSend" to Curve("fx", "echo", 0.0, 1.0, false, "Echo"),
            "reverbSend" to Curve("fx", "reverb", 0.0, 1.0, false, "Reverb"),
            "delaySend" to Curve("fx", "delay", 0.0, 1.0, false, "Delay"),
        )

        /** A new curve in an empty deck starts open: 0 dB, the filter open, no noise, nothing sent (automix-overview.js OPEN). */
        val OPEN: Map<String, Double> = mapOf(
            "bassDb" to 0.0, "midDb" to 0.0, "highDb" to 0.0, "lowpassHz" to 20000.0, "highpassHz" to 20.0,
            "noise" to 0.0, "echoSend" to 0.0, "reverbSend" to 0.0, "delaySend" to 0.0,
        )

        private fun pointsJson(pts: Array<DoubleArray>): List<Any?> = pts.map { listOf(it[0], it[1]) }

        private fun r3(v: Double): Double = jround(v * 1e3) / 1e3
        private fun r6(v: Double): Double = jround(v * 1e6) / 1e6

        // -----------------------------------------------------------------------------------------
        // x <-> the mix's seconds (rule 4)

        /** x (beats of A, or seconds) to seconds of the mix: a beat is [beatSec], or under a glide its own step. */
        fun drawSec(x: Double, unit: String, beatSec: Double, glide: ForkV2Planner.Glide?): Double {
            if (unit == "sec") return x
            if (glide != null && glide.q > 0 && glide.beats > 0) {
                val g = ForkV2Planner.glideSteps(glide.q, glide.beats, glide.beatOutSec)
                val b = g.dur.size
                val k = min(b - 1, max(0, floor(x).toInt()))
                return g.at[k] + (x - k) * g.dur[k]
            }
            return x * (if (beatSec > 0) beatSec else 0.5)
        }

        /** Seconds of the mix back to x (the editor's inverse of [drawSec]). */
        fun drawX(t: Double, unit: String, beatSec: Double, glide: ForkV2Planner.Glide?): Double {
            if (unit == "sec") return t
            if (glide != null && glide.q > 0 && glide.beats > 0) {
                val g = ForkV2Planner.glideSteps(glide.q, glide.beats, glide.beatOutSec)
                val b = g.dur.size
                var k = 0
                while (k < b - 1 && g.at[k + 1] <= t) k++
                return k + (t - g.at[k]) / g.dur[k]
            }
            return t / (if (beatSec > 0) beatSec else 0.5)
        }

        private fun valueAt(a: DoubleArray, b: DoubleArray, t: Double, hz: Boolean): Double {
            val f = (t - a[0]) / max(1e-12, b[0] - a[0])
            return if (hz) a[1] * (b[1] / a[1]).pow(f) else a[1] + (b[1] - a[1]) * f
        }

        /**
         * Rules 3-5: the drawn lanes as plan curves over a window of [D] seconds. A step's second point
         * comes 8 ms on (held at the next point's t when that's sooner); points past D are cut, the
         * value at D added as the last one; a curve that starts past D is its first value held.
         */
        fun pointCurves(drawings: Collection<ForkV2Drawing>, D: Double, beatSec: Double, glide: ForkV2Planner.Glide?): ForkV2Curves {
            val res = ForkV2Curves()
            res.durationSec = D
            for (lane in KEYS.keys) {
                val d = drawings.firstOrNull { it.lane == lane } ?: continue
                for (s in listOf(true, false)) for (key in KEYS[lane]!!) {
                    val pts = d.side(s)[key]
                    if (pts == null || pts.isEmpty()) continue
                    val hz = CURVES[key]!!.hz
                    val ts = Array(pts.size) { doubleArrayOf(drawSec(pts[it][0], d.unit, beatSec, glide), pts[it][1]) }
                    for (i in 1 until ts.size) {
                        if (pts[i][0] == pts[i - 1][0]) {
                            ts[i][0] = min(ts[i][0] + ForkV2MixOverride.CUT_RAMP_SEC, if (i + 1 < ts.size) ts[i + 1][0] else Double.POSITIVE_INFINITY)
                        }
                    }
                    var c: List<DoubleArray> = ts.toList()
                    val past = ts.indexOfFirst { it[0] > D + 1e-9 }
                    if (past == 0) {
                        c = listOf(doubleArrayOf(0.0, ts[0][1]))
                    } else if (past > 0) {
                        val cut = ts.copyOfRange(0, past).toMutableList()
                        if (cut.last()[0] < D - 1e-9) cut.add(doubleArrayOf(D, valueAt(ts[past - 1], ts[past], D, hz)))
                        c = cut
                    }
                    (if (s) res.out else res.`in`)[key] = Array(c.size) { doubleArrayOf(r6(c[it][0]), r6(c[it][1])) }
                }
            }
            return res
        }

        // -----------------------------------------------------------------------------------------
        // saving (rule 2)

        /**
         * A drawing as saved, from its stored JSON form (automix.js normalizeDrawing): a [ForkV2Drawing],
         * "none" for an EQ or effect drawing with no curve left, or null when it is invalid (no valid
         * unit, a volume deck with no points). x rounded to 0.001 and never below 0, v clamped and
         * rounded (Hz to 1 Hz), sorted by x with ties in the drawn order, a third point at one x and
         * everything past 128 points dropped.
         */
        fun normalize(lane: String, json: Any?): Any? {
            if (json is ForkV2Drawing) return normalize(lane, json.toJson())
            if (json !is Map<*, *>) return null
            val unit = json["unit"]
            if (unit != "beat" && unit != "sec") return null
            val keys = KEYS[lane] ?: return null
            unit as String
            val out = LinkedHashMap<String, Array<DoubleArray>>()
            val inn = LinkedHashMap<String, Array<DoubleArray>>()
            var any = false
            for (s in listOf(true, false)) {
                val deck = json[if (s) "out" else "in"]
                val into = if (s) out else inn
                if (lane == "volume") {
                    val c = clean(deck, CURVES["gain"]!!) ?: return null
                    into["gain"] = c
                    any = true
                } else {
                    for (key in keys) {
                        val c = clean((deck as? Map<*, *>)?.get(CURVES[key]!!.name), CURVES[key]!!) ?: continue
                        into[key] = c
                        any = true
                    }
                }
            }
            if (!any) return "none"
            // an Effects drawing's echo and delay times (editor.md §12 rule 1): only the schema's, else missing
            val eb = (json["echoBeats"] as? Number)?.toDouble()?.takeIf { lane == "fx" && it in listOf(1.0, 0.75, 0.5) } ?: 0.0
            val db = (json["delayBeats"] as? Number)?.toDouble()?.takeIf { lane == "fx" && it in listOf(0.75, 0.5) } ?: 0.0
            return ForkV2Drawing(lane, unit, out, inn, eb, db)
        }

        private fun clean(pts: Any?, c: Curve): Array<DoubleArray>? {
            if (pts !is List<*>) return null
            val ok = ArrayList<DoubleArray>()
            for (p in pts) {
                if (p !is List<*> || p.size < 2) continue
                val x = (p[0] as? Number)?.toDouble() ?: continue
                val v = (p[1] as? Number)?.toDouble() ?: continue
                if (!x.isFinite() || !v.isFinite()) continue
                val cv = max(c.lo, min(c.hi, v))
                ok.add(doubleArrayOf(max(0.0, r3(x)), if (c.hz) jround(cv).toDouble() else r3(cv)))
            }
            val sorted = ok.sortedBy { it[0] } // stable: ties keep the drawn order
            val kept = ArrayList<DoubleArray>()
            for (p in sorted) {
                if (kept.size >= MAX_POINTS) break
                val n = kept.size
                if (n >= 2 && kept[n - 1][0] == p[0] && kept[n - 2][0] == p[0]) continue // a third at one x
                kept.add(p)
            }
            return if (kept.isEmpty()) null else kept.toTypedArray()
        }

        // -----------------------------------------------------------------------------------------
        // Draw starts from the shape (rule 6)

        /**
         * A named shape as a drawing, so Draw starts from what the lane played: the shape's curves read
         * back to x, an 8 ms ramp read back as a step (two points at one x). Normalized as [normalize].
         */
        fun shapeDrawing(lane: String, shape: String, D: Double, beatSec: Double, glide: ForkV2Planner.Glide?, unit: String): Any? {
            val names = arrayOf<String?>("crossfade", "none", "none", "none")
            names[KEYS.keys.indexOf(lane)] = shape
            val pc = ForkV2MixOverride.namedCurves(names, D, beatSec) ?: return null
            fun toX(pts: Array<DoubleArray>): List<Any?> {
                val xs = Array(pts.size) { doubleArrayOf(r3(drawX(pts[it][0], unit, beatSec, glide)), pts[it][1]) }
                for (i in 1 until pts.size) {
                    val dt = pts[i][0] - pts[i - 1][0]
                    if (dt <= ForkV2MixOverride.CUT_RAMP_SEC + 1e-6 && dt > 0) xs[i][0] = xs[i - 1][0]
                }
                return xs.map { listOf(it[0], r3(it[1])) }
            }
            val d = LinkedHashMap<String, Any?>()
            d["unit"] = unit
            for (s in listOf(true, false)) {
                val side = if (s) pc.out else pc.`in`
                d[if (s) "out" else "in"] = if (lane == "volume") {
                    toX(side["gain"] ?: emptyArray())
                } else {
                    LinkedHashMap<String, Any?>().apply { for (k in KEYS[lane]!!) side[k]?.let { put(CURVES[k]!!.name!!, toX(it)) } }
                }
            }
            // an Effects shape's echo or delay time goes with it (editor.md §12 rule 4)
            val fs = if (lane == "fx") ForkV2MixOverride.FX_SHAPES[shape] else null
            if (fs?.send == "echoSend") d["echoBeats"] = fs.beats
            if (fs?.send == "delaySend") d["delayBeats"] = fs.beats
            return normalize(lane, d)
        }
    }
}
