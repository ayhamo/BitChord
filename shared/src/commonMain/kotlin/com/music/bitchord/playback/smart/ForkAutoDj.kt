package com.music.bitchord.playback.smart

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fork: the "Automix style" setting. [BITCHORD] is the app's own Automix, unchanged; the other two
 * are the owner's YouTube Music browser extension's two Automix versions (`src/automix/automix.js`
 * and `src/crossfade.js` in "YT Music Extension"), which the owner hears as clearly better mixes:
 * [ORIGINAL] is its version 1 ("Automix", BitChord web's planner with web-style curves), [ADVANCED]
 * its version 2 ("Advanced AutoDJ": the same planner plus the phone's ideas, see [ForkAutoDj]).
 * [key] is what both apps store.
 */
enum class AutomixStyle(val key: String, val label: String) {
    BITCHORD("bitchord", "BitChord"),
    ORIGINAL("original_autodj", "Original AutoDJ"),
    ADVANCED("advanced_autodj", "Advanced AutoDJ"),
    ;

    companion object {
        fun fromKey(key: String?): AutomixStyle = entries.firstOrNull { it.key == key } ?: BITCHORD
    }
}

/**
 * Fork: the extension's Automix planner (`automix.js` `plan`, `filterMix`, `fallback`, `autoBars`
 * and its version-2 rules), ported so both apps plan the same way from one place. It reads
 * BitChord's own analysis: the ending's grid for the song going out and the opening's for the song
 * coming in ([ForkAutomix]), the energy curve, intro end, content end, and the vocal model's windows;
 * a song's synced lyrics come from the loop mix's lookup ([noteSung]).
 *
 * Its one way in is [planTransition], hooked into upstream's `planTransition` for these two styles;
 * the curves the plan is played with are [ForkAutoDjCurves], picked by the plan's
 * [TransitionPlan.forkAutoDj]. The loop mix is unchanged: the extension's is BitChord's
 * `LoopMix` already, and runs first in every style.
 *
 * Times are song seconds unless named output seconds (the outgoing song may be sped up).
 */
object ForkAutoDj {

    // ---- the extension's constants (automix.js), names kept ----

    const val BEATS_PER_BAR = 4
    const val BEATS_PER_PHRASE = 32

    private val AUTO_BAR_CHOICES = intArrayOf(32, 16, 8, 4)
    const val AUTO_BEATS = 16
    const val AUTO_MAX_SEC = 12.0
    const val AUTO_CALM_MAX_SEC = 16.0
    const val AUTO_FILTER_MAX_SEC = 8.0
    const val CALM_ENERGY = 0.7
    const val ENERGY_WINDOW_BEATS = 8
    const val MAX_NEXT_SHARE = 0.4

    const val MIN_GRID_CONFIDENCE = 0.2
    const val KILL_DB = -12.0
    const val SHORT_BLEND_KILL_DB = -6.0
    const val FULL_SWAP_MIN_BARS = 8
    const val MAX_SLOW_DOWN = 0.1
    const val MAX_SPEED_UP = 0.25
    const val TEMPO_STEP_PER_BEAT = 0.0075
    const val FILTER_MAX_BARS = 8
    const val FALLBACK_SEC = 12.0

    const val VOCAL_SUNG = 0.25
    const val REROUTE_OVERLAP = 0.3
    const val REROUTE_GAIN = 0.5
    const val MAX_DISCARD_SEC = 12.0
    const val MAX_IN_SKIP = 0.25
    private val ENTRY_SHIFTS = intArrayOf(0, -4, 4, -8, 8)
    const val MUSIC_END_MAX_CUT_SEC = 20.0

    /** How far ahead of the playhead a blend is planned (the extension's `earliest`: pos + 4.5 s). */
    const val LEAD_SEC = 4.5

    /** After a blend: the next song glides back to its own tempo in this many steps, this far apart (`glideToUnity`). */
    const val GLIDE_STEPS = 8
    const val GLIDE_STEP_SEC = 3.0
    /** The extension's KEY_MIN_CONFIDENCE: a key the analysis is less sure of doesn't count as clashing. */
    const val KEY_MIN_CONFIDENCE = 0.5

    /**
     * "Shorter blend when keys clash" (the extension's automixKeyAware, off by default, Advanced
     * AutoDJ only). Set by each app's setting: `ForkSettings.automixKeyAware`, `ForkDesktopAutomixStyle`.
     */
    @kotlin.concurrent.Volatile
    var keyAware: Boolean = false

    enum class Style { BLEND, FILTER, CROSSFADE }

    /**
     * What the curves of one planned mix need, carried on [TransitionPlan.forkAutoDj]: which version
     * draws it, its style, how much both songs sing at once ([clash], version 2), its length in
     * beats of the blend, the bass-swap depth, and one bar as a share of it (version 1's swap width).
     * [outputSeconds] is the blend's length as heard, which is shorter than its outgoing song seconds
     * while the outgoing song is sped up.
     */
    class Shape(
        val v2: Boolean,
        val style: Style,
        val clash: Double,
        val beats: Double,
        val swapDb: Double,
        val barFraction: Double,
        val outputSeconds: Double,
        val rampBeats: Int,
    )

    /** One side of a pair as the planner reads it. */
    class Side(
        val id: String,
        val durationSec: Double,
        val grid: Grid?,
        val confidence: Double,
        val energy: List<EnergySample>,
        val introEndSec: Double?,
        val contentEndSec: Double?,
        val vocalWindows: List<CurveWindow>,
        val vocalMask: List<Double>,
        val stub: Boolean,
        /** The analysis's key ("C# major") and how sure it is, for [keysClash]. */
        val key: String = "",
        val keyConfidence: Double = 0.0,
    ) {
        fun withDuration(d: Double) = Side(id, d, grid, confidence, energy, introEndSec, contentEndSec, vocalWindows, vocalMask, stub, key, keyConfidence)
    }

    class Grid(val beatSec: Double, val downbeatSec: Double) {
        val barSec: Double get() = beatSec * BEATS_PER_BAR
        val phraseSec: Double get() = beatSec * BEATS_PER_PHRASE
        val bpm: Double get() = 60.0 / beatSec
    }

    /** The planner's answer (the extension's plan object). */
    data class Mix(
        val style: Style,
        val v2: Boolean,
        val reason: String,
        /** Outgoing song second the mix starts at. */
        val mixOut: Double,
        /** Output seconds the mix lasts. */
        val durationSec: Double,
        /** Outgoing song seconds the mix covers (= [durationSec] x [outRate]). */
        val outSpan: Double,
        /** Incoming song second it starts from. */
        val mixIn: Double,
        /** The incoming song's speed (>= 1), and the outgoing one's from the mix on (>= 1). */
        val rate: Double = 1.0,
        val outRate: Double = 1.0,
        val rampBeats: Int = 0,
        val rampSongSec: Double = 0.0,
        val beatSec: Double = 0.0,
        val bars: Int = 0,
        val swapDb: Double = 0.0,
        /** One bar in output seconds. */
        val barSec: Double = 0.0,
        val outBpm: Double = 0.0,
        val inBpm: Double = 0.0,
        val clash: Double = 0.0,
        val rerouted: String? = null,
        val vocalsBy: String? = null,
        /** "C# major → B minor" when the key-aware length shortened this blend. */
        val keyClash: String? = null,
    )

    // ---- the analysis as the planner reads it ----

    /** [analysis] as the side [outgoing] says, or a stub (no grid) when it isn't usable. */
    fun side(analysis: TrackAnalysis, id: String, durationSec: Double, outgoing: Boolean, usable: Boolean): Side {
        if (!usable) return stub(id, durationSec)
        val beatSide = if (outgoing) analysis.tailBeats else analysis.headBeats
        val near = if (outgoing) max(0.0, durationSec - 30.0) else 0.0
        val grid = ForkAutomix.grid(analysis, beatSide, near)?.let { (beat, anchor) ->
            // The incoming song is counted from its first downbeat, as the extension's head grid is.
            val first = if (outgoing) anchor else (beatSide?.downbeats?.firstOrNull { it.isFinite() } ?: analysis.downbeats.firstOrNull { it.isFinite() } ?: anchor)
            Grid(beat, first)
        }
        return Side(
            id = id,
            durationSec = durationSec,
            grid = grid,
            confidence = ForkAutomix.confidence(analysis, beatSide),
            energy = analysis.energyCurve,
            introEndSec = analysis.introEndTime.takeIf { it.isFinite() && it > 0 },
            contentEndSec = analysis.contentEndTime.takeIf { it.isFinite() && it > 0 },
            vocalWindows = analysis.vocalWindows,
            vocalMask = analysis.vocalActivityMask,
            stub = false,
            key = analysis.key,
            keyConfidence = analysis.keyConfidence,
        )
    }

    /** A song with no analysis: no grid, so the planner takes the plain-crossfade fallback. */
    fun stub(id: String, durationSec: Double) = Side(id, durationSec, null, 0.0, emptyList(), null, null, emptyList(), emptyList(), true)

    // ---- grid ----

    private fun phraseAtOrAfter(g: Grid, t: Double) = g.downbeatSec + ceil((t - g.downbeatSec) / g.phraseSec - 1e-9) * g.phraseSec
    private fun downbeatAtOrAfter(g: Grid, t: Double) = g.downbeatSec + ceil((t - g.downbeatSec) / g.barSec - 1e-9) * g.barSec
    private fun downbeatAtOrBefore(g: Grid, t: Double) = g.downbeatSec + floor((t - g.downbeatSec) / g.barSec + 1e-9) * g.barSec

    /** The blend's start: the downbeat at or before the one a bar before the song ends, less the blend. */
    private fun naturalMixOut(g: Grid, durationSec: Double, span: Double) = downbeatAtOrBefore(g, durationSec - g.barSec - span)

    // ---- Auto blend length ----

    /** The song's energy over [from]..[to] as a share of its mean; null without a curve. */
    internal fun energyShare(s: Side, from: Double, to: Double): Double? {
        val curve = s.energy.filter { it.energy.isFinite() }
        if (curve.isEmpty() || !(s.durationSec > 0)) return null
        val mean = curve.sumOf { it.energy } / curve.size
        if (!(mean > 0)) return null
        val inside = curve.filter { it.time >= from && it.time <= to }
        val value = if (inside.isNotEmpty()) {
            inside.sumOf { it.energy } / inside.size
        } else {
            val mid = (from + to) / 2
            curve.minBy { abs(it.time - mid) }.energy
        }
        return value / mean
    }

    internal fun autoBars(cur: Side, next: Side, g: Grid, entrySec: Double, filter: Boolean): Int {
        val window = ENERGY_WINDOW_BEATS * g.beatSec
        val exit = cur.durationSec - g.barSec
        val out = energyShare(cur, exit - window, exit)
        val into = energyShare(next, entrySec, entrySec + window)
        val calm = out != null && into != null && out < CALM_ENERGY && into < CALM_ENERGY
        val beats = AUTO_BEATS * (if (calm) 2 else 1)
        val cap = minOf(
            beats * g.beatSec,
            if (filter) AUTO_FILTER_MAX_SEC else if (calm) AUTO_CALM_MAX_SEC else AUTO_MAX_SEC,
            if (next.durationSec > 0) next.durationSec * MAX_NEXT_SHARE else Double.POSITIVE_INFINITY,
        )
        return AUTO_BAR_CHOICES.firstOrNull { it * g.barSec <= cap + 1e-6 } ?: AUTO_BAR_CHOICES.last()
    }

    // ---- the planner ----

    private fun effectiveBlendBars(requested: Int, fromConf: Double, toConf: Double): Int {
        val conf = min(fromConf, toConf)
        return when {
            conf >= 0.6 -> requested
            conf >= 0.45 -> min(requested, 8)
            else -> min(requested, 4)
        }
    }

    private fun stretchAllowed(rate: Double) = if (rate >= 1) rate - 1 <= MAX_SPEED_UP + 1e-9 else 1 - rate <= MAX_SLOW_DOWN + 1e-9

    /** Half or double time folded so the stretch stays near 1. */
    private fun foldRate(raw: Double) = listOf(raw, raw / 2, raw * 2).reduce { best, r -> if (abs(r - 1) < abs(best - 1)) r else best }

    /**
     * The mix from [from] into [to] (the extension's `plan`). [pos] is the playhead, [earliest] the
     * first song second the mix may start at; [barsSetting] 0 is Auto, a number the extension's
     * "Up to N bars" (the apps' Automix phrase length: Auto, 8 or 16). [sungOut] / [sungIn] are each song's sung
     * spans from synced lyrics, when known (version 2 only reads them).
     */
    fun plan(
        from: Side,
        to: Side,
        pos: Double,
        earliest: Double,
        v2: Boolean,
        barsSetting: Int = 0,
        sungOut: List<LoopMix.Span>? = null,
        sungIn: List<LoopMix.Span>? = null,
        keyAware: Boolean = false,
    ): Mix {
        val both = !from.stub && !to.stub
        val cur = if (both) from else stub(from.id, from.durationSec)
        val next = if (both) to else stub(to.id, 0.0)
        val fromGrid = cur.grid
        val toGrid = next.grid
        fun requested(entry: Double, filter: Boolean) =
            if (barsSetting > 0) barsSetting else autoBars(cur, next, fromGrid!!, entry, filter)

        if (fromGrid == null || cur.confidence < MIN_GRID_CONFIDENCE) {
            return fallback(cur, next, earliest, if (both) "this song has no beat grid at its end" else "songs not analysed in time")
        }
        if (toGrid == null || next.confidence < MIN_GRID_CONFIDENCE) {
            return filterMix(cur, next, fromGrid, null, requested(0.0, true), pos, earliest, "next song has no beat grid", v2, sungOut, sungIn)
        }
        val ratio = foldRate(fromGrid.bpm / toGrid.bpm)
        if (!stretchAllowed(ratio)) {
            return filterMix(
                cur, next, fromGrid, toGrid, requested(toGrid.downbeatSec, true), pos, earliest,
                "tempos ${(abs(ratio - 1) * 100).roundToInt()}% apart", v2, sungOut, sungIn,
            )
        }
        // Tempo only ever moves up: the next song faster, the playing one is brought up to its tempo
        // over its last beats before the blend; the next song slower, it is sped up.
        val rate = if (ratio < 1) 1.0 else ratio
        val outRate = if (ratio < 1) 1 / ratio else 1.0
        val rampBeats = if (outRate > 1.0001) ceil((outRate - 1) / TEMPO_STEP_PER_BEAT - 1e-9).toInt() else 0
        val rampSongSec = rampBeats * fromGrid.beatSec
        // AutoDJ's key-aware length (its own toggle, version 2): keys that clash get a short blend.
        val keyClash = if (v2 && keyAware) keysClash(cur, next) else null
        val bars = if (keyClash != null) keyClashBars(fromGrid) else requested(toGrid.downbeatSec, false)
        val effBars = effectiveBlendBars(bars, cur.confidence, next.confidence)
        val outSpan = effBars * fromGrid.barSec
        val dur = outSpan / outRate

        val first = earliest + rampSongSec
        var mixOut = naturalMixOut(fromGrid, if (v2) musicEnd(cur) else cur.durationSec, outSpan)
        if (mixOut < pos + rampSongSec) mixOut = phraseAtOrAfter(fromGrid, first)
        if (mixOut < first) mixOut = downbeatAtOrAfter(fromGrid, first)
        if (mixOut + outSpan > cur.durationSec - 0.3) {
            return filterMix(cur, next, fromGrid, toGrid, bars, pos, earliest, "too close to the end for a blend", v2, sungOut, sungIn)
        }

        // The incoming song starts so that its pre-full-energy phrase lands at the blend's end.
        val introEnd = next.introEndSec ?: 0.0
        val fullEnergyPhrase = phraseAtOrAfter(toGrid, max(introEnd, toGrid.downbeatSec))
        var mixIn = fullEnergyPhrase - dur * rate
        if (mixIn < toGrid.downbeatSec) mixIn = toGrid.downbeatSec

        var clash = 0.0
        var rerouted: String? = null
        var by: String? = null
        if (v2) {
            mixIn = capIncomingSkip(next, toGrid, mixIn)
            val dj = rerouteForVocals(cur, next, fromGrid, toGrid, mixOut, mixIn, outSpan, rate / outRate, max(first, pos + rampSongSec), sungOut, sungIn)
            mixOut = dj.mixOut
            mixIn = dj.mixIn
            clash = dj.clash
            rerouted = dj.rerouted
            by = dj.by
        }
        return Mix(
            style = Style.BLEND, v2 = v2, reason = "blend",
            mixOut = mixOut, durationSec = dur, outSpan = outSpan, mixIn = mixIn, rate = rate,
            outRate = outRate, rampBeats = rampBeats, rampSongSec = rampSongSec, beatSec = fromGrid.beatSec,
            bars = effBars, swapDb = if (effBars >= FULL_SWAP_MIN_BARS) KILL_DB else SHORT_BLEND_KILL_DB,
            barSec = fromGrid.barSec / outRate, outBpm = fromGrid.bpm, inBpm = toGrid.bpm,
            clash = clash, rerouted = rerouted, vocalsBy = by, keyClash = keyClash,
        )
    }

    // ---- key-aware blend length (the extension's automixKeyAware) ----

    /**
     * The extension's `keysClash`: harmonic mixing on the Camelot wheel, compatible when the number
     * is the same, or one step round it on the same letter. A key the analysis is unsure of
     * (under [KEY_MIN_CONFIDENCE]) or can't name doesn't count. Returns "from → to" on a clash.
     */
    internal fun keysClash(a: Side, b: Side): String? {
        if (a.keyConfidence < KEY_MIN_CONFIDENCE || b.keyConfidence < KEY_MIN_CONFIDENCE) return null
        val x = camelot(a.key) ?: return null
        val y = camelot(b.key) ?: return null
        val step = (x.first - y.first + 12) % 12
        val ok = x.first == y.first || (x.second == y.second && (step == 1 || step == 11))
        return if (ok) null else "${a.key} → ${b.key}"
    }

    /** 2 bars, unless that is under 4 s (6 s from 140 BPM): then 4. */
    internal fun keyClashBars(g: Grid): Int = if (2 * g.barSec >= (if (g.bpm >= 140) 6.0 else 4.0)) 2 else 4

    /** "C# major" / "A minor" / "F♯m" as a Camelot number and letter (B major, A minor), or null. */
    internal fun camelot(key: String): Pair<Int, Char>? {
        val k = key.trim()
        if (k.isEmpty()) return null
        val base = when (k[0].uppercaseChar()) { 'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11; else -> return null }
        var i = 1
        var pc = base
        if (i < k.length && (k[i] == '#' || k[i] == '♯')) { pc += 1; i++ } else if (i < k.length && (k[i] == 'b' || k[i] == '♭')) { pc -= 1; i++ }
        pc = (pc + 12) % 12
        val rest = k.substring(i).trim().lowercase()
        val minor = rest.startsWith("min") || rest == "m"
        val fifths = pc * 7 % 12
        return if (minor) ((fifths + 4) % 12 + 1) to 'A' else ((fifths + 7) % 12 + 1) to 'B'
    }

    /** Incoming on its own downbeat at its own tempo, low-passed; outgoing through a rising high-pass. */
    private fun filterMix(
        cur: Side,
        next: Side,
        fromGrid: Grid,
        toGrid: Grid?,
        requestedBars: Int,
        pos: Double,
        earliest: Double,
        reason: String,
        v2: Boolean,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
    ): Mix {
        var bars = min(requestedBars, FILTER_MAX_BARS)
        var dur = bars * fromGrid.barSec
        var mixOut = naturalMixOut(fromGrid, if (v2) musicEnd(cur) else cur.durationSec, dur)
        if (mixOut < pos) mixOut = phraseAtOrAfter(fromGrid, earliest)
        if (mixOut < earliest) mixOut = downbeatAtOrAfter(fromGrid, earliest)
        // never past the outgoing song's end: shortened to the bars that still fit
        val room = cur.durationSec - 0.3 - mixOut
        if (dur > room) {
            bars = max(1, floor(room / fromGrid.barSec).toInt())
            dur = bars * fromGrid.barSec
        }
        if (room < fromGrid.barSec) return fallback(cur, next, earliest, reason)
        val mixIn = toGrid?.downbeatSec ?: min(next.introEndSec ?: 0.0, 1.0)
        // Version 2: how much the two sing at once, for the filter shapes (no re-route: nothing to keep in time).
        val clash = if (v2) vocalOverlap(cur, next, mixOut, dur, mixIn, 1.0, sungOut, sungIn) ?: 0.0 else 0.0
        return Mix(
            style = Style.FILTER, v2 = v2, reason = reason,
            mixOut = mixOut, durationSec = dur, outSpan = dur, mixIn = mixIn,
            beatSec = fromGrid.beatSec, bars = bars, swapDb = KILL_DB, barSec = fromGrid.barSec,
            outBpm = fromGrid.bpm, inBpm = toGrid?.bpm ?: 0.0, clash = clash,
            vocalsBy = if (v2) sourceOf(sungOut, sungIn) else null,
        )
    }

    /** The plain 12 s equal-power fade, no beatmatch, no EQ, ending with the song. */
    private fun fallback(cur: Side, next: Side, earliest: Double, reason: String): Mix {
        val target = cur.durationSec - 0.3 - FALLBACK_SEC
        val mixOut = max(earliest, target)
        val dur = max(0.0, min(FALLBACK_SEC, cur.durationSec - 0.3 - mixOut))
        return Mix(
            style = Style.CROSSFADE, v2 = false, reason = reason,
            mixOut = mixOut, durationSec = dur, outSpan = dur, mixIn = next.introEndSec ?: 0.0,
        )
    }

    // ---- version 2 (Advanced AutoDJ) ----

    /** The song's length cut to where its music ends, when there is real silence after it; never over 20 s cut. */
    private fun musicEnd(s: Side): Double {
        val end = s.contentEndSec ?: return s.durationSec
        if (!(end > 0) || end >= s.durationSec - 1) return s.durationSec
        return max(end, s.durationSec - MUSIC_END_MAX_CUT_SEC)
    }

    /** The cue pulled back by whole bars to within a quarter of the song. */
    private fun capIncomingSkip(next: Side, toGrid: Grid, mixIn: Double): Double {
        val cap = if (next.durationSec > 0) next.durationSec * MAX_IN_SKIP else Double.POSITIVE_INFINITY
        if (mixIn <= cap) return mixIn
        return max(toGrid.downbeatSec, mixIn - ceil((mixIn - cap) / toGrid.barSec) * toGrid.barSec)
    }

    /** Sung or not at song second [t]: 1/0 from lyric spans, else the vocal model; null where neither knows. */
    internal fun vocalAt(s: Side, t: Double, spans: List<LoopMix.Span>?): Double? {
        if (spans != null) return if (spans.any { t >= it.start && t < it.end }) 1.0 else 0.0
        for (w in s.vocalWindows) {
            val i = ((t - w.startTime) * w.frameRate).roundToInt()
            if (i >= 0 && i < w.values.size) return w.values[i].toDouble()
        }
        // The resampled mask, on the energy curve's times (TrackAnalysis says when it counts).
        val mask = s.vocalMask
        if (mask.isNotEmpty() && mask.size == s.energy.size) {
            val curve = s.energy
            if (t < curve.first().time - 1.0 || t > curve.last().time + 1.0) return null
            var low = 0
            var high = curve.size - 1
            while (low < high) {
                val mid = (low + high) ushr 1
                if (curve[mid].time < t) low = mid + 1 else high = mid
            }
            val k = if (low > 0 && abs(curve[low - 1].time - t) < abs(curve[low].time - t)) low - 1 else low
            return mask[k]
        }
        return null
    }

    /**
     * The share of the mix where both songs sing; [inPerOut] incoming song seconds per outgoing
     * song second. Null when the two sources cover under half of it.
     */
    internal fun vocalOverlap(
        cur: Side,
        next: Side,
        mixOut: Double,
        outSpan: Double,
        mixIn: Double,
        inPerOut: Double,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
    ): Double? {
        var both = 0
        var n = 0
        var steps = 0
        var t = mixOut
        while (t < mixOut + outSpan) {
            steps++
            val a = vocalAt(cur, t, sungOut)
            val b = vocalAt(next, mixIn + (t - mixOut) * inPerOut, sungIn)
            if (a != null && b != null) {
                n++
                if (a >= VOCAL_SUNG && b >= VOCAL_SUNG) both++
            }
            t += 0.05
        }
        return if (steps > 0 && n >= steps * 0.5) both.toDouble() / n else null
    }

    private fun sourceOf(sungOut: List<LoopMix.Span>?, sungIn: List<LoopMix.Span>?) =
        "${if (sungOut != null) "lyrics" else "mask"}/${if (sungIn != null) "lyrics" else "mask"}"

    private class Reroute(val mixOut: Double, val mixIn: Double, val clash: Double, val rerouted: String?, val by: String)
    private class Candidate(val o: Double, val cost: Double, val mo: Double, val mi: Double, val k: Int, val j: Int)
    private class RerouteMemo(val key: String, val primary: Double?, val candidates: List<Candidate>)

    /** The overlaps are measured once per blend and kept: the plan is redone every tick. */
    @Volatile
    private var rerouteMemo: RerouteMemo? = null

    /**
     * Over 30% sung by both: the exit tried up to 12 s of music earlier (whole bars) and the cue up
     * to 8 bars either way; the cleanest is taken if it halves the overlap.
     */
    private fun rerouteForVocals(
        cur: Side,
        next: Side,
        fromGrid: Grid,
        toGrid: Grid,
        mixOut: Double,
        mixIn: Double,
        outSpan: Double,
        inPerOut: Double,
        earliestOut: Double,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
    ): Reroute {
        val by = sourceOf(sungOut, sungIn)
        val key = listOf(cur.id, next.id, mixOut, mixIn, outSpan, inPerOut, fromGrid.barSec, toGrid.barSec, by).joinToString("|")
        val memo = rerouteMemo?.takeIf { it.key == key } ?: run {
            val first = vocalOverlap(cur, next, mixOut, outSpan, mixIn, inPerOut, sungOut, sungIn)
            val candidates = ArrayList<Candidate>()
            if (first != null && first > REROUTE_OVERLAP) {
                val inCap = if (next.durationSec > 0) next.durationSec * MAX_IN_SKIP else Double.POSITIVE_INFINITY
                var k = 0
                while (k * fromGrid.barSec <= MAX_DISCARD_SEC) {
                    for (j in ENTRY_SHIFTS) {
                        if (k == 0 && j == 0) continue
                        val mi = mixIn + j * toGrid.barSec
                        if (mi < toGrid.downbeatSec - 1e-6 || mi > inCap) continue
                        val mo = mixOut - k * fromGrid.barSec
                        val o = vocalOverlap(cur, next, mo, outSpan, mi, inPerOut, sungOut, sungIn)
                        if (o != null) candidates += Candidate(o, k + abs(j) / 2.0, mo, mi, k, j)
                    }
                    k++
                }
            }
            RerouteMemo(key, first, candidates).also { rerouteMemo = it }
        }
        val primary = memo.primary
        val keep = Reroute(mixOut, mixIn, primary ?: 0.0, null, by)
        if (primary == null || primary <= REROUTE_OVERLAP) return keep
        var best: Candidate? = null
        for (c in memo.candidates) {
            if (c.mo < earliestOut) continue
            val b = best
            if (b == null || c.o < b.o - 1e-9 || (abs(c.o - b.o) < 1e-9 && c.cost < b.cost)) best = c
        }
        fun pct(x: Double) = "${(x * 100).roundToInt()}%"
        val b = best
        if (b == null || b.o > primary * REROUTE_GAIN) {
            return Reroute(mixOut, mixIn, primary, "both sing ${pct(primary)} of it, no cleaner point", by)
        }
        return Reroute(
            b.mo, b.mi, b.o,
            "both sang ${pct(primary)} of it, moved to ${pct(b.o)} (exit ${b.k} bars earlier, cue ${if (b.j >= 0) "+" else ""}${b.j} bars)",
            by,
        )
    }

    // ---- synced lyrics, from the loop mix's lookup ----

    /** Copied on write (a lookup lands once per song), so the planner's threads read it without a lock. */
    @Volatile
    private var sung: Map<String, List<LoopMix.Span>?> = emptyMap()

    /** Where [id] is sung, from its synced lyrics (null: it has none). Both apps' loop-mix lookups call this. */
    fun noteSung(id: String, spans: List<LoopMix.Span>?) {
        val next = LinkedHashMap(sung)
        next.remove(id)
        next[id] = spans
        while (next.size > 24) next.remove(next.keys.first())
        sung = next
    }

    fun sungFor(id: String): List<LoopMix.Span>? = sung[id]

    // ---- into upstream's TransitionPlan ----

    private class Latch(val key: String, val plan: TransitionPlan, val armAt: Double, val end: Double)

    @Volatile
    private var latch: Latch? = null

    /**
     * Upstream's `planTransition` for [style] (not [AutomixStyle.BITCHORD]): null leaves the pair to
     * upstream (no duration, a short song, speech or a live recording, an album played through).
     *
     * The plan is redone every tick, as the extension's is, until it is close enough to be armed:
     * from [LEAD_SEC] before its ramp (or start) it is held, so arming and the blend see one plan
     * however the playhead and a refining analysis move.
     */
    fun planTransition(
        style: AutomixStyle,
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        currentTrack: TransitionTrackInfo?,
        nextTrack: TransitionTrackInfo?,
        currentTime: Double,
        duration: Double,
        albumSequential: Boolean,
        phraseBars: Int = 0,
        keyAware: Boolean = ForkAutoDj.keyAware,
    ): TransitionPlan? {
        if (style == AutomixStyle.BITCHORD || albumSequential) return null
        val length = max(duration.takeIf { it.isFinite() } ?: 0.0, (currentTrack?.durationMs ?: 0L) / 1000.0)
        if (length < MIN_DURATION_SEC) return null
        if (spokenOrLive(currentTrack) || spokenOrLive(nextTrack)) return null
        val pos = max(0.0, currentTime.takeIf { it.isFinite() } ?: 0.0)
        val v2 = style == AutomixStyle.ADVANCED
        val curId = currentTrack?.id ?: analysis.trackId
        val nextId = nextTrack?.id ?: nextAnalysis.trackId
        val bars = phraseBars.takeIf { it == 8 || it == 16 } ?: 0
        val key = "${style.key}|$curId|$nextId|$length|$bars|$keyAware"
        latch?.takeIf { it.key == key && pos >= it.armAt - 0.5 && pos < it.end }?.let { held ->
            return held.plan.copy(shouldStart = pos >= held.plan.transitionStart)
        }
        val nextLength = max(nextAnalysis.duration.takeIf { it.isFinite() } ?: 0.0, (nextTrack?.durationMs ?: 0L) / 1000.0)
        val from = side(analysis, curId, length, outgoing = true, usable = ready(analysis, currentTrack))
        val to = side(nextAnalysis, nextId, nextLength, outgoing = false, usable = ready(nextAnalysis, nextTrack))
        val mix = plan(from, to, pos, pos + LEAD_SEC, v2, bars, sungOut = if (v2) sungFor(curId) else null, sungIn = if (v2) sungFor(nextId) else null, keyAware = keyAware)
        val plan = toTransitionPlan(mix, pos, style)
        latch = Latch(key, plan, mix.mixOut - mix.rampSongSec - LEAD_SEC, mix.mixOut + mix.outSpan)
        return plan
    }

    /** Drops the held plan (tests). */
    fun forget() {
        latch = null
        rerouteMemo = null
    }

    private const val MIN_DURATION_SEC = 45.0

    private fun spokenOrLive(track: TransitionTrackInfo?): Boolean =
        track != null && isSpokenOrLiveForTest(track.title, track.artist, track.album)

    private fun ready(analysis: TrackAnalysis, track: TransitionTrackInfo?): Boolean =
        analysis.isUsable && (analysis.trackId.isBlank() || track?.id.isNullOrBlank() || analysis.trackId == track?.id)

    fun toTransitionPlan(mix: Mix, pos: Double, style: AutomixStyle): TransitionPlan {
        val transitionStyle = when (mix.style) {
            Style.BLEND -> TransitionStyle.DJ_BLEND
            Style.FILTER -> TransitionStyle.DJ_FILTER
            Style.CROSSFADE -> TransitionStyle.EQUAL_POWER
        }
        val beats = if (mix.style == Style.CROSSFADE) 0.0 else mix.bars * 4.0
        val shape = Shape(
            v2 = mix.v2 || (style == AutomixStyle.ADVANCED && mix.style != Style.CROSSFADE),
            style = mix.style,
            clash = mix.clash.coerceIn(0.0, 1.0),
            beats = beats,
            swapDb = mix.swapDb,
            barFraction = if (mix.durationSec > 0 && mix.barSec > 0) min(mix.barSec / mix.durationSec, 0.5) else 0.0,
            outputSeconds = mix.durationSec,
            rampBeats = mix.rampBeats,
        )
        val tag = "${if (style == AutomixStyle.ADVANCED) "advanced" else "original"}-autodj-${mix.style.name.lowercase()}"
        val started = pos >= mix.mixOut
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            reason = if (started) tag else "before-$tag",
            transitionStart = mix.mixOut,
            transitionEnd = mix.mixOut + mix.outSpan,
            fadeSeconds = mix.outSpan,
            transitionStyle = transitionStyle,
            incomingCueTime = max(0.0, mix.mixIn),
            incomingHandoffTime = max(0.0, mix.mixIn) + mix.durationSec * mix.rate,
            incomingPlaybackRate = roundRate(mix.rate),
            outgoingPlaybackRate = roundRate(mix.outRate),
            handoffDuration = mix.outSpan,
            transitionBeats = beats.toInt(),
            bassSwap = mix.style != Style.CROSSFADE,
            bassSwapFraction = 0.5,
            filterSweep = if (mix.style == Style.FILTER) 1.0 else 0.0,
            filterVariant = FilterTransitionVariant.SWEEP,
            vocalOverlap = if (shape.v2) shape.clash else 0.0,
            outgoingBpm = mix.outBpm,
            incomingBpm = mix.inBpm,
            beatSeconds = if (mix.style == Style.CROSSFADE) 0.0 else mix.beatSec,
            phaseLock = false,
            echoSeconds = 0.0,
            policyReasons = listOfNotNull(mix.reason, mix.rerouted, mix.vocalsBy?.let { "vocals by $it" }, mix.keyClash?.let { "keys clash ($it)" }),
            forkAutoDj = shape,
        )
    }

    private fun roundRate(rate: Double) = (rate * 10000).roundToInt() / 10000.0

    // ---- what the players read ----

    /** Output seconds an AutoDJ mix lasts (the outgoing song may be sped up); [TransitionPlan.fadeSeconds] otherwise. */
    fun outputSeconds(plan: TransitionPlan): Double = plan.forkAutoDj?.outputSeconds ?: plan.fadeSeconds

    /**
     * The outgoing song's speed at its song second [pos] while it is brought up to a faster next
     * song: one 0.75% step a beat over its last beats before the mix, the full rate from the mix on.
     * Null for no ramp (another style, no speed-up, or before the ramp).
     */
    fun outgoingRampRate(plan: TransitionPlan, pos: Double): Double? {
        val shape = plan.forkAutoDj ?: return null
        val target = plan.outgoingPlaybackRate
        if (!(target > 1.0001) || shape.rampBeats <= 0 || !(plan.beatSeconds > 0)) return null
        val n = shape.rampBeats
        val rampStart = plan.transitionStart - n * plan.beatSeconds
        if (pos < rampStart) return null
        val due = min(n, floor((pos - rampStart) / plan.beatSeconds).toInt() + 1)
        return 1.0 + (target - 1.0) * due / n
    }

    /** The glide back to the song's own tempo after an AutoDJ blend: steps and seconds between them; null otherwise. */
    fun glide(plan: TransitionPlan?): Pair<Int, Double>? = plan?.forkAutoDj?.let { GLIDE_STEPS to GLIDE_STEP_SEC }
}
