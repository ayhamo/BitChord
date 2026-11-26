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

    /** The Effects lane (editor.md §12), only with custom: a shape of [FXS] or [DRAW]; null is none, which is never stored. */
    var fx: String? = null

    /** The Looping lane (editor.md §13), only with custom: a shape of [LOOPINGS] (never drawn); null is none, never stored. */
    var looping: String? = null

    /**
     * Each song's speed in the playlist the mix came from (spotify-mixes.md rule 10, editor.md §1): A's ([speedOut]) and
     * B's ([speedIn]), pitch kept; 1 is none. Played with Automix on and the listener's speed at x1 ([ForkSongSpeed]).
     */
    var speedOut = 1.0
    var speedIn = 1.0

    /**
     * editor.md §10: the lanes drawn point by point (lane name → its drawing), only with custom. A
     * drawn lane's name ([volume], [eq], [effect]) is [DRAW].
     */
    val drawings: MutableMap<String, ForkV2Drawing> = LinkedHashMap()

    /** A lane's name (volume, eq, effect, fx): a shape, or [DRAW] (fx: null is none). */
    fun laneName(lane: String): String? = when (lane) {
        "volume" -> volume
        "eq" -> eq
        "fx" -> fx
        "looping" -> looping
        else -> effect
    }

    /** A lane's drawing, or null when it is a shape. */
    fun drawing(lane: String): ForkV2Drawing? = if (laneName(lane) == DRAW) drawings[lane] else null

    /** Sets a lane to a shape (its drawing gone) or, with [d], to that drawing. */
    fun setLane(lane: String, name: String?, d: ForkV2Drawing? = null) {
        val v = if (d != null) DRAW else name
        when (lane) {
            "volume" -> volume = v
            "eq" -> eq = v
            "fx" -> fx = v?.takeIf { it != "none" } // none is not stored (editor.md §12 rule 1)
            "looping" -> looping = name?.takeIf { it != "none" } // a shape only (§13)
            else -> effect = v
        }
        if (d != null) drawings[lane] = d else drawings.remove(lane)
    }

    /** Whether a song's speed is set ([speedOut] or [speedIn] not 1). */
    fun hasSpeed(): Boolean = speedOut != 1.0 || speedIn != 1.0

    /** 0: Auto. */
    var bars = 0

    /** null: Auto. */
    var beatMatch: String? = null
    var out: Position? = null
    var `in`: Position? = null
    var lock = false

    /** Only in picks migrated from {style, bars}: blend | filter, the planner's own mix forced. */
    var family: String? = null

    /**
     * The tempo of A its beats count on, from a mix file (spec/automix/mix-file.md rule 4, editor.md §1): not an edit
     * on its own. 0: none. Another app may read A at half or double that tempo ([ForkV2MixFile.fold]).
     */
    var gridBpm = 0.0
    var engine = 0
    var savedAt = 0L

    /**
     * A window position on a song's grid (override.schema.json $defs.position). [bar] null: a position in seconds only, as a mix
     * file from another app gives it, played at the song's nearest beat to [sec] (editor.md §6 "Seconds only"); it has no beat.
     * [delaySec] (window.in only; mix-file.md rule 2 [all], B's late entry): B stays silent and unstarted that long after the
     * mix starts, then starts at [sec]; the curves still run from the mix start. 0: B starts with the mix.
     */
    class Position(var bar: Int?, var beat: Int, var nudgeMs: Int, var sec: Double, var delaySec: Double = 0.0)

    /** A saved position read back on a song's grid: song seconds, and whether the grid moved under it. */
    class Resolved internal constructor(
        val sec: Double,
        val moved: Boolean,
        val snapped: Boolean,
        /** The bar it snapped to when moved, else the saved one; null for a position in seconds only. */
        val bar: Int?,
    )

    fun copy(): ForkV2MixOverride {
        val o = ForkV2MixOverride()
        o.preset = preset
        o.volume = volume
        o.eq = eq
        o.effect = effect
        o.fx = fx
        o.looping = looping
        o.speedOut = speedOut
        o.speedIn = speedIn
        o.drawings.putAll(drawings)
        o.bars = bars
        o.beatMatch = beatMatch
        o.out = out?.let { Position(it.bar, it.beat, it.nudgeMs, it.sec, it.delaySec) }
        o.`in` = `in`?.let { Position(it.bar, it.beat, it.nudgeMs, it.sec, it.delaySec) }
        o.lock = lock
        o.family = family
        o.gridBpm = gridBpm
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
        return bars // no preset sets a length (editor.md §2, 2026-11-25: Slam no longer asks for 2 bars)
    }

    /** The lanes this override plays (a preset's, or the custom ones; the fourth the Effects lane), or null: the planner's own. */
    fun lanes(): Array<String?>? = if ("custom" == preset) arrayOf(volume, eq, effect, fx ?: "none", looping ?: "none") else lanesOf(preset)

    /** As saved (override.schema.json), fields in the schema's order. */
    fun toJson(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["v"] = 1
        if (preset != null) m["preset"] = preset
        if ("custom" == preset) {
            val l = LinkedHashMap<String, Any?>()
            for (k in LANES) {
                if ((k == "fx" || k == "looping") && laneName(k) == null) continue // none is not stored (editor.md §12 rule 1, §13)
                l[k] = drawing(k)?.toJson() ?: laneName(k)
            }
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
        if (hasSpeed()) {
            val s = LinkedHashMap<String, Any?>()
            if (speedOut != 1.0) s["out"] = speedOut
            if (speedIn != 1.0) s["in"] = speedIn
            m["speed"] = s
        }
        if (lock) m["lock"] = true
        if (family != null) m["family"] = family
        if (gridBpm > 0) m["gridBpm"] = gridBpm
        if (engine > 0) m["engine"] = engine
        if (savedAt > 0) m["savedAt"] = savedAt
        return m
    }

    companion object {
        val PRESETS = arrayOf("auto", "smooth", "rise", "blend", "wave", "melt", "slam", "afterglow", "surge", "dissolve", "none", "loop", "custom")
        // (in the order the editor's dropdowns show them, automix-overview.js LANE_OPTIONS; the shapes added
        // 2026-11-25 carry Spotify's style names, editor.md §3, spotify-mixes.md rule 4)
        val VOLUMES = arrayOf("smooth", "crossfade", "overlap", "cutInFadeOut", "fadeInCutOut", "centerCut", "fadeInFadeOut", "fadeInFastOut", "fadeIn1BarCutOut", "overlap1BarCutOut")
        val EQS = arrayOf("centerBassSwap", "endBassSwap", "threeBandFade", "quickBassCut", "none", "startBassSwap", "longBassCut", "bassFadeOut")
        val EFFECTS = arrayOf("none", "lpOut", "hpIn", "lpBoth", "hpBoth", "lpInHpOut", "hpOut", "lpIn", "hpInLpOut", "hpHalfOut", "noiseOutEnd")

        /** The Effects lane's shapes (editor.md §12 rule 4: Spotify's Effects list). */
        val FXS = arrayOf(
            "none", "echo1CutEnd", "echo34CutEnd", "echoHalfCutEnd", "echo1OutEnd", "echo34OutEnd", "echoHalfOutEnd",
            "delay34CutEnd", "delayHalfCutEnd", "reverbCutEnd", "reverbOutEnd", "reverbOutCenter",
        )
        val BEAT_MATCH = arrayOf("auto", "sync", "glide", "off")
        val BARS = intArrayOf(2, 4, 8, 16, 32)
        val LANES = arrayOf("volume", "eq", "effect", "fx", "looping")

        /** The Looping lane's shapes (editor.md §13), and their beats. */
        val LOOPINGS = arrayOf("none", "loop1", "loop2", "loop4", "loop8", "loop16")
        val LOOP_BEATS = mapOf("loop1" to 1, "loop2" to 2, "loop4" to 4, "loop8" to 8, "loop16" to 16)

        /** The lanes that may be missing (none, never stored). */
        internal fun optional(lane: String) = lane == "fx" || lane == "looping"

        /** An Effects shape (editor.md §12 rule 4): the outgoing deck's send, its curve over the window's fraction, the time in beats (0: the default). */
        internal class FxShape(val send: String, val curve: Array<DoubleArray>, val beats: Double)

        private val FX_CUT = arrayOf(doubleArrayOf(0.75, 0.0), doubleArrayOf(1.0, 0.5))
        private val FX_OUT = arrayOf(doubleArrayOf(0.5, 0.0), doubleArrayOf(1.0, 0.5))

        /** automix.js FX_SHAPES. */
        internal val FX_SHAPES: Map<String, FxShape> = linkedMapOf(
            "echo1CutEnd" to FxShape("echoSend", FX_CUT, 1.0),
            "echo34CutEnd" to FxShape("echoSend", FX_CUT, 0.75),
            "echoHalfCutEnd" to FxShape("echoSend", FX_CUT, 0.5),
            "echo1OutEnd" to FxShape("echoSend", FX_OUT, 1.0),
            "echo34OutEnd" to FxShape("echoSend", FX_OUT, 0.75),
            "echoHalfOutEnd" to FxShape("echoSend", FX_OUT, 0.5),
            "delay34CutEnd" to FxShape("delaySend", FX_CUT, 0.75),
            "delayHalfCutEnd" to FxShape("delaySend", FX_CUT, 0.5),
            "reverbCutEnd" to FxShape("reverbSend", FX_CUT, 0.0),
            "reverbOutEnd" to FxShape("reverbSend", FX_OUT, 0.0),
            "reverbOutCenter" to FxShape("reverbSend", arrayOf(doubleArrayOf(0.25, 0.0), doubleArrayOf(0.5, 0.5), doubleArrayOf(0.75, 0.0)), 0.0),
        )

        /** The echo's and the delay's time in beats when a drawing or shape doesn't set one (editor.md §12 rule 1). */
        const val FX_ECHO_BEATS = 0.75
        const val FX_DELAY_BEATS = 0.5

        /** The echo's and the delay's seconds (editor.md §12 rule 3, plan curves.fxTime: echoSec, delaySec); a beat 0.5 s without one. */
        internal fun fxTime(echoBeats: Double, delayBeats: Double, beatSec: Double): DoubleArray {
            val b = if (beatSec > 0) beatSec else 0.5
            return doubleArrayOf(r6((if (echoBeats > 0) echoBeats else FX_ECHO_BEATS) * b), r6((if (delayBeats > 0) delayBeats else FX_DELAY_BEATS) * b))
        }

        /** A song's speed as saved (automix.js normalizeSpeed): 0.5..2 to 0.00001, else 1 (none). */
        fun speedOf(v: Any?): Double {
            val d = (v as? Number)?.toDouble() ?: return 1.0
            if (!d.isFinite() || d < 0.5 || d > 2 || abs(d - 1) < 1e-4) return 1.0
            return jround(d * 1e5) / 1e5
        }

        /**
         * A song's speed while it plays (spotify-mixes.md rule 10, automix.js songSpeed): the speed the mix that brought it in
         * gives it ([prevId] to [id]), else the one the mix taking it out gives it ([id] to [nextId]), else 1.
         */
        fun songSpeed(id: String, prevId: String?, nextId: String?, overrideOf: (String, String) -> ForkV2MixOverride?): Double {
            prevId?.let { p -> overrideOf(p, id)?.let { if (it.speedIn != 1.0) return it.speedIn } }
            nextId?.let { n -> overrideOf(id, n)?.let { if (it.speedOut != 1.0) return it.speedOut } }
            return 1.0
        }

        /** A drawn lane's name (editor.md §10; the overview's dropdown item "Draw…"). Never saved as a name. */
        const val DRAW = "draw"

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
                // each a shape name (§3) or a drawing (§10 rule 2)
                for (k in LANES) {
                    val v = lanes[k]
                    if (optional(k) && v == null) continue // missing: none (editor.md §12 rule 1, §13)
                    if (k == "looping" && v !is String) { o.looping = "?"; continue } // never drawn: invalid
                    if (v is String) {
                        o.setLane(k, if (v == DRAW) null else v)
                    } else {
                        val d = ForkV2Drawing.normalize(k, v)
                        if (d is ForkV2Drawing) o.setLane(k, null, d) else o.setLane(k, d as? String)
                    }
                }
            }
            o.bars = intOf(m["bars"], 0)
            o.beatMatch = str(m["beatMatch"])
            val w = m["window"]
            if (w is Map<*, *>) {
                o.out = position(w["out"])
                o.`in` = position(w["in"])
            }
            (m["speed"] as? Map<*, *>)?.let {
                o.speedOut = speedOf(it["out"])
                o.speedIn = speedOf(it["in"])
            }
            o.lock = m["lock"] == true
            o.family = str(m["family"])
            (m["gridBpm"] as? Number)?.let { o.gridBpm = it.toDouble() }
            o.engine = intOf(m["engine"], 0)
            val sa = m["savedAt"]
            o.savedAt = if (sa is Number) sa.toLong() else 0L
            return normalize(o)
        }

        private fun position(json: Any?): Position? {
            if (json !is Map<*, *>) return null
            val bar = json["bar"]
            val sec = json["sec"]
            if (sec !is Number) return null
            val s = sec.toDouble()
            if (s.isNaN() || s.isInfinite()) return null
            // no bar: seconds only (editor.md §6), and so no beat either
            val late = (json["delaySec"] as? Number)?.toDouble() ?: 0.0
            if (bar == null) return Position(null, 0, intOf(json["nudgeMs"], 0), s, late)
            if (bar !is Number) return null
            val b = bar.toDouble()
            if (b != round(b)) return null
            return Position(b.toInt(), intOf(json["beat"], 0), intOf(json["nudgeMs"], 0), s, late)
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
                // only the schema's values (override.schema.json lanes), or valid drawings (§10 rule 2)
                var ok = true
                for (k in LANES) {
                    val name = if (optional(k)) o.laneName(k) ?: "none" else o.laneName(k) // (no Effects or Looping lane: none)
                    if (name == DRAW) {
                        val d = o.drawings[k]?.let { ForkV2Drawing.normalize(k, it) }
                        if (d is ForkV2Drawing) n.setLane(k, null, d) else if (d is String) n.setLane(k, d) else ok = false
                    } else if (name != null && name in options(k)) {
                        n.setLane(k, name)
                    } else {
                        ok = false
                    }
                }
                if (!ok) {
                    n.preset = null
                    for (k in LANES) n.setLane(k, null)
                }
            }
            if (n.preset == null && ("blend" == o.family || "filter" == o.family)) n.family = o.family
            if (!n.loop()) {
                for (b in BARS) if (o.bars == b) n.bars = b
                n.out = clean(o.out, late = false)
                n.`in` = clean(o.`in`, late = true)
            }
            // a Loop stores no beat match either (editor.md §1, spec 4d14863: "preset: loop stores no bars, window or beatMatch")
            if (!n.loop() && ("sync" == o.beatMatch || "glide" == o.beatMatch || "off" == o.beatMatch)) n.beatMatch = o.beatMatch
            // each song's speed in the playlist it came from (editor.md §1, spotify-mixes.md rule 10)
            n.speedOut = speedOf(o.speedOut)
            n.speedIn = speedOf(o.speedIn)
            if (n.preset == null && n.family == null && n.bars == 0 && n.out == null && n.`in` == null && n.beatMatch == null && !n.hasSpeed()) return null
            n.lock = o.lock
            // the tempo of A the beats count on (mix-file.md rule 4): not an edit on its own
            if (o.gridBpm in 20.0..400.0) n.gridBpm = jround(o.gridBpm * 100) / 100.0
            n.engine = o.engine
            n.savedAt = o.savedAt
            return n
        }

        private fun clean(p: Position?, late: Boolean): Position? {
            if (p == null || p.sec.isNaN() || p.sec.isInfinite()) return null
            val nudge = max(-2000L, min(2000L, jround(p.nudgeMs / 10.0) * 10)).toInt()
            val beat = if (p.bar != null && p.beat in 1..3) p.beat else 0 // as normalizeOverride: beats 1 to 3, else on the downbeat; none without a bar
            // B's late entry (mix-file.md rule 2): on the mix-in only, 1 ms to 60 s, to 1 ms
            val delay = if (late && p.delaySec.isFinite() && p.delaySec >= 0.001) jround(min(p.delaySec, 60.0) * 1e3) / 1e3 else 0.0
            return Position(p.bar, beat, nudge, jround(p.sec * 1e3) / 1e3, delay)
        }

        private fun positionJson(p: Position): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            p.bar?.let { m["bar"] = it } // none: seconds only
            if (p.bar != null && p.beat != 0) m["beat"] = p.beat
            if (p.nudgeMs != 0) m["nudgeMs"] = p.nudgeMs
            m["sec"] = p.sec
            if (p.delaySec > 0) m["delaySec"] = p.delaySec // (window.in only: clean drops it elsewhere)
            return m
        }

        // -----------------------------------------------------------------------------------------
        // presets and lanes as curves (editor.md §2, §3)

        /** volume, eq, effect, fx of a preset (a preset other than Custom has no effects, editor.md §12 rule 1); null for auto, loop or an unknown one. */
        internal fun lanesOf(preset: String?): Array<String?>? = when (preset) {
            "smooth" -> arrayOf("smooth", "centerBassSwap", "none", "none", "none")
            "rise" -> arrayOf("overlap", "endBassSwap", "lpInHpOut", "none", "none")
            "blend" -> arrayOf("overlap", "threeBandFade", "none", "none", "none")
            "wave" -> arrayOf("overlap", "centerBassSwap", "lpBoth", "none", "none")
            "melt" -> arrayOf("fadeInFadeOut", "centerBassSwap", "hpBoth", "none", "none")
            "slam" -> arrayOf("centerCut", "none", "none", "none", "none")
            // Spotify's presets with effects (editor.md §2)
            "afterglow" -> arrayOf("smooth", "centerBassSwap", "none", "reverbOutCenter", "none")
            "surge" -> arrayOf("fadeInCutOut", "endBassSwap", "hpHalfOut", "reverbOutEnd", "none")
            "dissolve" -> arrayOf("fadeInCutOut", "endBassSwap", "hpHalfOut", "echoHalfOutEnd", "none")
            else -> null
        }

        /**
         * The curves of a preset or custom lanes over a window of D seconds (automix.js presetCurves):
         * gain, the shelves and the filters, no rate. beatSec: the beat, NaN with beat match Off.
         * Null for Auto, Loop, an unknown shape or no window.
         */
        fun presetCurves(o: ForkV2MixOverride?, D: Double, beatSec: Double): ForkV2Curves? = presetCurves(o, D, beatSec, Double.NaN, null)

        /**
         * As [presetCurves], with drawn lanes (editor.md §10 rule 7): the named lanes as §3, then each
         * drawn lane's curves in their place. [drawBeatSec]: the beat a drawing's x counts in (NaN:
         * [beatSec], else 0.5 s); [glide]: the plan's glide, whose steps the beats follow.
         */
        fun presetCurves(o: ForkV2MixOverride?, D: Double, beatSec: Double, drawBeatSec: Double, glide: ForkV2Planner.Glide?): ForkV2Curves? {
            val lanes = o?.lanes()
            if (lanes == null || !(D > 0)) return null
            if (DRAW !in lanes) return namedCurves(lanes, D, beatSec)
            val named = Array(lanes.size) { k -> if (lanes[k] == DRAW) (if (k == 0) "crossfade" else "none") else lanes[k] }
            val base = namedCurves(named, D, beatSec) ?: return null
            val drawn = LANES.indices.filter { lanes[it] == DRAW }.map { LANES[it] }
            val ds = drawn.mapNotNull { o.drawings[it] }
            if (ds.size != drawn.size) return null
            val beat = if (drawBeatSec > 0) drawBeatSec else if (beatSec > 0) beatSec else 0.5
            val pc = ForkV2Drawing.pointCurves(ds, D, beat, glide)
            for (lane in drawn) for (key in ForkV2Drawing.KEYS[lane]!!) {
                val o2 = pc.out[key]
                if (o2 != null) base.out[key] = o2 else base.out.remove(key)
                val i2 = pc.`in`[key]
                if (i2 != null) base.`in`[key] = i2 else base.`in`.remove(key)
            }
            // a drawn Effects lane's echo and delay times (editor.md §12 rule 3)
            if ("fx" in drawn) o.drawings["fx"]?.let { base.fxTime = fxTime(it.echoBeats, it.delayBeats, if (drawBeatSec > 0) drawBeatSec else beatSec) }
            return base
        }

        /** The named lanes' curves (§3): null for an unknown shape. */
        internal fun namedCurves(lanes: Array<String?>, D: Double, beatSec: Double): ForkV2Curves? {
            if (!(D > 0)) return null
            val e = CUT_RAMP_SEC / D
            val hb = if (beatSec > 0) 0.5 * beatSec / D else 0.02
            // one bar before the end (editor.md §3 fadeIn1BarCutOut, overlap1BarCutOut); beat match Off: 0.875
            val bar = if (beatSec > 0) max(0.5, 1 - 4 * beatSec / D) else 0.875
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
                // (Spotify's names, editor.md §3: its own shapes aren't known, these are this app's)
                "fadeInFadeOut" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.5, 1.0), doubleArrayOf(1.0, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, 1.0)))
                }
                "fadeInFastOut" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.75, 1.0), doubleArrayOf(1.0, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.75, 1.0)))
                }
                // (these two follow screenshots of Spotify's editor, editor.md §3: A cut a bar before the end)
                "fadeIn1BarCutOut" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(bar, 1.0), doubleArrayOf(bar + e, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, 1.0)))
                }
                "overlap1BarCutOut" -> {
                    c.out["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(bar, 1.0), doubleArrayOf(bar + e, 0.0)))
                    c.`in`["gain"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(e, 1.0)))
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
                "startBassSwap" -> swap(c, D, 0.125, hb)
                "longBassCut" -> c.out["bassDb"] = at(D, arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, C)))
                // an S-curve to CUT at the middle, held there (editor.md §3, from Spotify's editor)
                "bassFadeOut" -> c.out["bassDb"] = at(D, Array(17) { i -> val t = i / 16.0; doubleArrayOf(t / 2, C * (1 - cos(t * PI)) / 2) })
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
                "hpOut" -> c.out["highpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, 20.0), doubleArrayOf(1.0, HP_CLOSED)))
                "lpIn" -> c.`in`["lowpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, LP_CLOSED), doubleArrayOf(1.0, 20000.0)))
                "hpInLpOut" -> {
                    c.out["lowpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, 20000.0), doubleArrayOf(1.0, LP_CLOSED)))
                    c.`in`["highpassHz"] = at(D, arrayOf(doubleArrayOf(0.0, HP_CLOSED), doubleArrayOf(1.0, 20.0)))
                }
                "hpHalfOut" -> c.out["highpassHz"] = at(D, arrayOf(doubleArrayOf(0.5, 20.0), doubleArrayOf(1.0, HP_CLOSED)))
                "noiseOutEnd" -> c.out["noise"] = at(D, arrayOf(doubleArrayOf(0.5, 0.0), doubleArrayOf(1.0, 1.0)))
                else -> return null
            }
            // the Effects lane (editor.md §12; missing: none): a send on the outgoing deck, and the echo's and delay's times
            val fx = lanes.getOrNull(3) ?: "none"
            if (fx != "none") {
                val s = FX_SHAPES[fx] ?: return null
                c.out[s.send] = at(D, s.curve)
                c.fxTime = fxTime(if (s.send == "echoSend") s.beats else 0.0, if (s.send == "delaySend") s.beats else 0.0, beatSec)
            }
            // the Looping lane (§13; missing: none): N beats of A, looped by the engine
            val lo = lanes.getOrNull(4) ?: "none"
            if (lo != "none") c.loopBeats = LOOP_BEATS[lo] ?: return null
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
            // seconds only (no bar, editor.md §6): the song's nearest beat, the nudges on top, never moved
            val savedBar = pos.bar ?: return Resolved(r6(downbeatSec + jround((pos.sec - nudge - downbeatSec) / beatSec) * beatSec + nudge), false, true, null)
            val bar = 4 * beatSec
            val t = downbeatSec + savedBar * bar + pos.beat * beatSec + nudge
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
        fun options(lane: String): List<String> = (if (lane == "volume") VOLUMES else if (lane == "eq") EQS else if (lane == "fx") FXS else if (lane == "looping") LOOPINGS else EFFECTS).toList()
    }
}
