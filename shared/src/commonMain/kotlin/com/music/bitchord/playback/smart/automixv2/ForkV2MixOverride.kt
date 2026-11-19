package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * A user's edit of one pair's transition (spec/automix/editor.md, override.schema.json), saved per
 * pair under "fromId>toId". A field left out is Auto; an override with nothing but Auto in it is
 * no override (Auto is the reset). A port of automix.js normalizeOverride, migratePick,
 * presetCurves and resolveWindow, checked against spec/automix/editor-cases.json; ported from the
 * phone's MixOverride.java. The planner reads it for the per-pair override ([ForkV2Planner.plan]),
 * which is where the fork's overview picks (ForkMixPicks) can feed it: a pick saved as
 * {style, bars} migrates as it is read ([fromJson]).
 */
class ForkV2MixOverride {
    /** null: Auto. */
    var preset: String? = null

    /**
     * Read from a pick saved before the editor as blend, filter or crossfade (the master's preset "auto"
     * after migratePick): the planner's reason is "picked in the overview". Not saved: an edit saves it
     * normalized, as the master does.
     */
    var migrated = false

    /** volume, eq, effect: only with preset custom. */
    var volume: String? = null
    var eq: String? = null
    var effect: String? = null

    /** 0: Auto. */
    var bars = 0

    /** null: Auto. */
    var beatMatch: String? = null
    var out: Position? = null
    var `in`: Position? = null
    var lock = false

    /** Only in picks migrated from {style, bars}: blend | filter, the planner's own mix forced. */
    var family: String? = null
    var engine = 0
    var savedAt = 0L

    /** A window position on a song's grid (override.schema.json $defs.position). */
    class Position(var bar: Int, var beat: Int, var nudgeMs: Int, var sec: Double)

    /** A saved position read back on a song's grid: song seconds, and whether the grid moved under it. */
    class Resolved internal constructor(
        val sec: Double,
        val moved: Boolean,
        val snapped: Boolean,
        /** The bar it snapped to when moved, else the saved one. */
        val bar: Int,
    )

    fun copy(): ForkV2MixOverride {
        val o = ForkV2MixOverride()
        o.preset = preset
        o.volume = volume
        o.eq = eq
        o.effect = effect
        o.bars = bars
        o.beatMatch = beatMatch
        o.out = out?.let { Position(it.bar, it.beat, it.nudgeMs, it.sec) }
        o.`in` = `in`?.let { Position(it.bar, it.beat, it.nudgeMs, it.sec) }
        o.lock = lock
        o.family = family
        o.engine = engine
        o.savedAt = savedAt
        o.migrated = migrated
        return o
    }

    /** The preset as the planner reads it ("auto" when none). */
    fun presetOrAuto(): String = preset ?: "auto"

    fun beatMatchOrAuto(): String = beatMatch ?: "auto"

    /** The loop mix asked for. */
    fun loop(): Boolean = "loop" == preset

    /** The user's own choice beyond Auto: the planner's style gates warn instead of refusing (editor.md §6). */
    fun forced(): Boolean =
        (preset != null && preset != "auto" && preset != "loop") ||
            "sync" == beatMatch || "glide" == beatMatch || out != null || `in` != null

    /** The length the window asks for in bars (a preset's own default when Auto), 0: Auto. */
    fun pinnedBars(): Int {
        if (loop()) return 0
        if (bars > 0) return bars
        return if ("slam" == preset) 2 else 0
    }

    /** The lanes this override plays (a preset's, or the custom ones), or null: the planner's own. */
    fun lanes(): Array<String?>? = if ("custom" == preset) arrayOf(volume, eq, effect) else lanesOf(preset)

    /** As saved (override.schema.json), fields in the schema's order. */
    fun toJson(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["v"] = 1
        if (preset != null) m["preset"] = preset
        if ("custom" == preset) {
            val l = LinkedHashMap<String, Any?>()
            l["volume"] = volume
            l["eq"] = eq
            l["effect"] = effect
            m["lanes"] = l
        }
        if (bars > 0) m["bars"] = bars
        if (beatMatch != null) m["beatMatch"] = beatMatch
        if (out != null || `in` != null) {
            val w = LinkedHashMap<String, Any?>()
            out?.let { w["out"] = positionJson(it) }
            `in`?.let { w["in"] = positionJson(it) }
            m["window"] = w
        }
        if (lock) m["lock"] = true
        if (family != null) m["family"] = family
        if (engine > 0) m["engine"] = engine
        if (savedAt > 0) m["savedAt"] = savedAt
        return m
    }

    companion object {
        val PRESETS = arrayOf("auto", "smooth", "rise", "blend", "wave", "melt", "slam", "loop", "custom")
        val VOLUMES = arrayOf("smooth", "crossfade", "overlap", "cutInFadeOut", "fadeInCutOut", "centerCut")
        val EQS = arrayOf("centerBassSwap", "endBassSwap", "threeBandFade", "quickBassCut", "none")
        val EFFECTS = arrayOf("none", "lpOut", "hpIn", "lpBoth", "hpBoth", "lpInHpOut")
        val BEAT_MATCH = arrayOf("auto", "sync", "glide", "off")
        val BARS = intArrayOf(2, 4, 8, 16, 32)

        /** Unlocked overrides kept (oldest saved first out); locked ones have a cap of their own. */
        const val MAX_UNLOCKED = 300
        const val MAX_LOCKED = 300

        internal const val CUT_DB = ForkV2Planner.BASS_CUT_DB
        internal const val LP_CLOSED = 400.0
        internal const val HP_CLOSED = 1000.0
        internal const val CUT_RAMP_SEC = 0.008

        // -----------------------------------------------------------------------------------------
        // reading and writing (override.schema.json; a pick from before the editor migrates as read)

        /** An override or a pick from before the editor ({style, bars}), as saved JSON; null: Auto. */
        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: Any?): ForkV2MixOverride? {
            if (json !is Map<*, *>) return null
            val m = json as Map<String, Any?>
            val o = ForkV2MixOverride()
            if (m.containsKey("style")) {
                // editor.md §1: today's picks migrate as they're read
                val st = m["style"]
                if ("loop" == st) o.preset = "loop"
                else if ("blend" == st || "filter" == st) o.family = st as String
                else if ("crossfade" == st) o.beatMatch = "off"
                o.bars = intOf(m["bars"], 0)
                val n = normalize(o)
                if (n != null) n.migrated = "blend" == st || "filter" == st || "crossfade" == st
                return n
            }
            o.preset = str(m["preset"])
            val lanes = m["lanes"]
            if (lanes is Map<*, *>) {
                o.volume = str(lanes["volume"])
                o.eq = str(lanes["eq"])
                o.effect = str(lanes["effect"])
            }
            o.bars = intOf(m["bars"], 0)
            o.beatMatch = str(m["beatMatch"])
            val w = m["window"]
            if (w is Map<*, *>) {
                o.out = position(w["out"])
                o.`in` = position(w["in"])
            }
            o.lock = m["lock"] == true
            o.family = str(m["family"])
            o.engine = intOf(m["engine"], 0)
            val sa = m["savedAt"]
            o.savedAt = if (sa is Number) sa.toLong() else 0L
            return normalize(o)
        }

        private fun position(json: Any?): Position? {
            if (json !is Map<*, *>) return null
            val bar = json["bar"]
            val sec = json["sec"]
            if (bar !is Number || sec !is Number) return null
            val b = bar.toDouble()
            val s = sec.toDouble()
            if (b != round(b) || s.isNaN() || s.isInfinite()) return null
            return Position(b.toInt(), intOf(json["beat"], 0), intOf(json["nudgeMs"], 0), s)
        }

        /**
         * The override as saved (automix.js normalizeOverride): unknown values and Auto fields dropped,
         * null when nothing but Auto is left. Picking a preset drops a migrated pick's family.
         */
        fun normalize(o: ForkV2MixOverride?): ForkV2MixOverride? {
            if (o == null) return null
            val n = ForkV2MixOverride()
            if (o.preset != null && o.preset != "auto" && o.preset in PRESETS) n.preset = o.preset
            if ("custom" == n.preset) {
                // only the schema's values (override.schema.json lanes)
                if (o.volume in VOLUMES && o.eq in EQS && o.effect in EFFECTS) {
                    n.volume = o.volume
                    n.eq = o.eq
                    n.effect = o.effect
                } else {
                    n.preset = null
                }
            }
            if (n.preset == null && ("blend" == o.family || "filter" == o.family)) n.family = o.family
            if (!n.loop()) {
                for (b in BARS) if (o.bars == b) n.bars = b
                n.out = clean(o.out)
                n.`in` = clean(o.`in`)
            }
            // a Loop stores no beat match either (editor.md §1, spec 4d14863: "preset: loop stores no bars, window or beatMatch")
            if (!n.loop() && ("sync" == o.beatMatch || "glide" == o.beatMatch || "off" == o.beatMatch)) n.beatMatch = o.beatMatch
            if (n.preset == null && n.family == null && n.bars == 0 && n.out == null && n.`in` == null && n.beatMatch == null) return null
            n.lock = o.lock
            n.engine = o.engine
            n.savedAt = o.savedAt
            return n
        }

        private fun clean(p: Position?): Position? {
            if (p == null || p.sec.isNaN() || p.sec.isInfinite()) return null
            val nudge = max(-2000L, min(2000L, jround(p.nudgeMs / 10.0) * 10)).toInt()
            val beat = if (p.beat in 1..3) p.beat else 0 // as normalizeOverride: beats 1 to 3, else on the downbeat
            return Position(p.bar, beat, nudge, jround(p.sec * 1e3) / 1e3)
        }

        private fun positionJson(p: Position): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["bar"] = p.bar
            if (p.beat != 0) m["beat"] = p.beat
            if (p.nudgeMs != 0) m["nudgeMs"] = p.nudgeMs
            m["sec"] = p.sec
            return m
        }

        // -----------------------------------------------------------------------------------------
        // presets and lanes as curves (editor.md §2, §3)

        /** volume, eq, effect of a preset; null for auto, loop or an unknown one. */
        internal fun lanesOf(preset: String?): Array<String?>? = when (preset) {
            "smooth" -> arrayOf("smooth", "centerBassSwap", "none")
            "rise" -> arrayOf("overlap", "endBassSwap", "lpInHpOut")
            "blend" -> arrayOf("overlap", "threeBandFade", "none")
            "wave" -> arrayOf("overlap", "centerBassSwap", "lpBoth")
            "melt" -> arrayOf("crossfade", "centerBassSwap", "hpBoth")
            "slam" -> arrayOf("centerCut", "none", "none")
            else -> null
        }

        /**
         * The curves of a preset or custom lanes over a window of D seconds (automix.js presetCurves):
         * gain, the shelves and the filters, no rate. beatSec: the beat, NaN with beat match Off.
         * Null for Auto, Loop, an unknown shape or no window.
         */
        fun presetCurves(o: ForkV2MixOverride?, D: Double, beatSec: Double): ForkV2Curves? {
            val lanes = o?.lanes()
            if (lanes == null || !(D > 0)) return null
            val e = CUT_RAMP_SEC / D
            val hb = if (beatSec > 0) 0.5 * beatSec / D else 0.02
            val C = CUT_DB
            val c = ForkV2Curves()
            c.durationSec = r6(D)
            when (lanes[0].toString()) {
                "smooth" -> {
                    val a = Array(17) { i -> val x = i / 16.0; doubleArrayOf(x, cos(x * PI / 2)) }
                    val b = Array(17) { i -> val x = i / 16.0; doubleArrayOf(x, sin(x * PI / 2)) }
                    c.out["gain"] = at(D, a)
                    c.`in`["gain"] = at(D, b)
                }
                "crossfade" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0)))
                }
                "overlap" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.75, 1.0), doubleArrayOf(1.0, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.25, 1.0)))
                }
                "cutInFadeOut" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(e, 1.0)))
                }
                "fadeInCutOut" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1 - e, 1.0), doubleArrayOf(1.0, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0)))
                }
                "centerCut" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.5, 1.0), doubleArrayOf(0.5 + e, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, 0.0), doubleArrayOf(0.5 + e, 1.0)))
                }
                else -> return null
            }
            when (lanes[1].toString()) {
                "centerBassSwap" -> swap(c, D, 0.5, hb)
                "endBassSwap" -> swap(c, D, 0.875, hb)
                "threeBandFade" -> {
                    swap(c, D, 0.5, hb)
                    c.out["highDb"] = at(D, arrayOf(doubleArrayOf(0.5, 0.0), doubleArrayOf(1.0, C)))
                    c.`in`["highDb"] = at(D, arrayOf(doubleArrayOf(0.0, C), doubleArrayOf(0.5, 0.0)))
                }
                "quickBassCut" -> c.out["bassDb"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(2 * hb, C)))
                "none" -> {}
                else -> return null
            }
            when (lanes[2].toString()) {
                "none" -> {}
                "lpOut" -> c.out["lowpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, 20000.0), doubleArrayOf(1.0, LP_CLOSED)))
                "hpIn" -> c.`in`["highpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, HP_CLOSED), doubleArrayOf(0.5, 20.0)))
                "lpBoth" -> {
                    c.out["lowpassHz"] = at(D, arrayOf(doubleArrayOf(0.5, 20000.0), doubleArrayOf(1.0, LP_CLOSED)))
                    c.`in`["lowpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, LP_CLOSED), doubleArrayOf(0.5, 20000.0)))
                }
                "hpBoth" -> {
                    c.out["highpassHz"] = at(D, arrayOf(doubleArrayOf(0.5, 20.0), doubleArrayOf(1.0, HP_CLOSED)))
                    c.`in`["highpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, HP_CLOSED), doubleArrayOf(0.5, 20.0)))
                }
                "lpInHpOut" -> {
                    c.out["highpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, 20.0), doubleArrayOf(1.0, HP_CLOSED)))
                    c.`in`["lowpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, LP_CLOSED), doubleArrayOf(1.0, 20000.0)))
                }
                else -> return null
            }
            return c
        }

        private fun swap(c: ForkV2Curves, D: Double, centre: Double, hb: Double) {
            c.out["bassDb"] = at(D, arrayOf(doubleArrayOf(centre - hb, 0.0), doubleArrayOf(centre + hb, CUT_DB)))
            c.`in`["bassDb"] = at(D, arrayOf(doubleArrayOf(0.0, CUT_DB), doubleArrayOf(centre - hb, CUT_DB), doubleArrayOf(centre + hb, 0.0)))
        }

        /** Points at fractions of the window, in seconds (rounded as the extension rounds them). */
        private fun at(D: Double, pts: Array<DoubleArray>): Array<DoubleArray> =
            Array(pts.size) { i -> doubleArrayOf(r6(pts[i][0] * D), r6(pts[i][1])) }

        private fun r6(v: Double): Double = jround(v * 1e6) / 1e6

        // -----------------------------------------------------------------------------------------
        // the window (editor.md §6)

        /**
         * A saved position back to song seconds on a grid (downbeatSec, beatSec of that song; NaN beat:
         * no grid, the saved seconds as they are). More than a beat from the saved seconds: the grid
         * changed under it, so the saved seconds snap to the new grid's nearest downbeat (moved).
         */
        fun resolveWindow(pos: Position?, downbeatSec: Double, beatSec: Double): Resolved? {
            if (pos == null) return null
            val nudge = pos.nudgeMs / 1000.0
            if (!(beatSec > 0) || downbeatSec.isNaN() || downbeatSec.isInfinite()) return Resolved(pos.sec, false, false, pos.bar)
            val bar = 4 * beatSec
            val t = downbeatSec + pos.bar * bar + pos.beat * beatSec + nudge
            if (pos.sec.isNaN() || abs(t - pos.sec) <= beatSec) return Resolved(r6(t), false, true, pos.bar)
            val k = jround((pos.sec - downbeatSec) / bar).toInt()
            return Resolved(r6(downbeatSec + k * bar), true, true, k)
        }

        internal fun resolveWindow(pos: Position?, g: ForkV2Planner.Grid?): Resolved? =
            if (g == null) resolveWindow(pos, Double.NaN, Double.NaN) else resolveWindow(pos, g.downbeatSec, g.beatSec)

        /** A position at song seconds [sec] snapped to a grid's nearest downbeat (or beat): snap() of positionOf. */
        fun snap(sec: Double, downbeatSec: Double, beatSec: Double, toBeats: Boolean): Position =
            positionOf(sec, downbeatSec, beatSec, true, toBeats)

        /**
         * Song seconds as a saved position (automix-overview.js positionOf): snapped, the nearest
         * downbeat (or beat); else the beat at or before it plus the rest as a nudge in 10 ms steps.
         * No grid (NaN beat): bar 0 and the seconds as they are.
         */
        fun positionOf(sec: Double, downbeatSec: Double, beatSec: Double, snap: Boolean, toBeats: Boolean): Position {
            if (!(beatSec > 0) || downbeatSec.isNaN()) return Position(0, 0, 0, jround(sec * 1e3) / 1e3)
            val j: Long
            if (snap) {
                j = if (toBeats) jround((sec - downbeatSec) / beatSec) else 4 * jround((sec - downbeatSec) / (4 * beatSec))
                val bar = j.floorDiv(4L).toInt()
                return Position(bar, (j - 4L * bar).toInt(), 0, jround((downbeatSec + j * beatSec) * 1e3) / 1e3)
            }
            j = floor((sec - downbeatSec) / beatSec + 1e-9).toLong()
            val bar = j.floorDiv(4L).toInt()
            val nudge = (jround((sec - downbeatSec - j * beatSec) * 100) * 10).toInt()
            return Position(bar, (j - 4L * bar).toInt(), nudge, jround(sec * 1e3) / 1e3)
        }

        // -----------------------------------------------------------------------------------------

        private fun str(v: Any?): String? = v as? String

        private fun intOf(v: Any?, dflt: Int): Int = if (v is Number) jround(v.toDouble()).toInt() else dflt

        /** The lane names in the order the editor's dropdowns show them. */
        fun options(lane: String): List<String> = (if (lane == "volume") VOLUMES else if (lane == "eq") EQS else EFFECTS).toList()
    }
}
