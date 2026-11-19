package com.music.bitchord.playback.smart.automixv2

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The YT Music Extension's automix planner (src/automix/automix.js: plan, filterMix, fallback,
 * the AutoDJ v2 additions, planLoopMix and loopSchedule), ported line by line. Times are song
 * seconds. Pure Kotlin: it runs the same on a desktop JVM, where it is checked against the
 * extension's golden plans (ForkV2SpecFixturesTest).
 *
 * A port of the phone's Planner.java (app.ayham.extension.music.automix), kept to its arithmetic
 * order so the plans agree to the last digit.
 *
 * Version 1 is the extension's "Original AutoDJ" (BitChord web), version 2 its "Advanced
 * AutoDJ" (plus the BitChord phone's ideas), version 3 Automix v2; the loop mix is the same in all.
 */
object ForkV2Planner {

    /** The extension's automix settings. */
    class Settings {
        /** 1: Original AutoDJ, 2: Advanced AutoDJ, 3: Automix v2 (AutoDJ plus Orchard's rules). */
        var version = 1

        /** Advanced AutoDJ's key-aware blend length. */
        var keyAware = false

        /** 0: Auto; else "Up to N bars". */
        var bars = 0
    }

    /** Sung spans of each song from synced lyrics ([start, end] seconds), or null: the vocal mask. */
    class Sung {
        var out: Array<DoubleArray>? = null
        var `in`: Array<DoubleArray>? = null
    }

    class Plan {
        /** blend | filter | crossfade | loop */
        var style: String = ""
        var fallback = false
        var reason: String? = null

        /** The editor's preset whose curves replaced the planner's (editor.md §9), or null. */
        var preset: String? = null

        /** What an override asked for that the planner would have refused or had to move (editor.md §6). */
        var warnings: List<String>? = null

        /** The mix-in was placed in the editor: Automix v2's kick lock leaves it (editor.md §6). */
        var windowIn = false
        var v2 = false

        /** Automix v2's blend (its evidence fields below are set). */
        var v3 = false

        /** Automix v2's hand-over (the boundary switch) or beatless bridge, both crossfades. */
        var handoff = false
        var bridge = false

        /** The fallback ladder's shape: dropin | muffle, or null. */
        var shape: String? = null
        var muffleLead = Double.NaN
        var subBassContrast = Double.NaN
        var swapAt = Double.NaN
        var swapMoved: String? = null
        var glide: Glide? = null
        var harmonic: Harmonic? = null
        var trim: Trim? = null
        var clash = 0.0
        var rerouted: String? = null
        var vocalsBy: String? = null
        var keyClash: String? = null
        var mixOutTrackTime = 0.0
        var durationSec = 0.0
        var mixInTrackTime = 0.0
        var rate = 1.0
        var outRate = 1.0
        var rampBeats = 0
        var rampSongSec = 0.0
        var beatSec = Double.NaN
        var bars = 0
        var swapDb = 0.0
        var barSec = 0.0
        var bpm = Double.NaN
        var outBpm = Double.NaN
        var inBpm = Double.NaN
        var loop: LoopPlan? = null

        /** What each deck does over the transition (spec/automix/plan.schema.json "curves"). */
        var curves: ForkV2Curves? = null

        /** Automix v2's kick lock applied to mixInTrackTime, or null. */
        var kick: ForkV2KickLock.Result? = null

        /** A shallow copy (the kick lock moves the copy's cue). */
        fun copy(): Plan {
            val p = Plan()
            p.style = style
            p.fallback = fallback
            p.reason = reason
            p.preset = preset
            p.warnings = warnings
            p.windowIn = windowIn
            p.v2 = v2
            p.v3 = v3
            p.handoff = handoff
            p.bridge = bridge
            p.shape = shape
            p.muffleLead = muffleLead
            p.subBassContrast = subBassContrast
            p.swapAt = swapAt
            p.swapMoved = swapMoved
            p.glide = glide
            p.harmonic = harmonic
            p.trim = trim
            p.clash = clash
            p.rerouted = rerouted
            p.vocalsBy = vocalsBy
            p.keyClash = keyClash
            p.mixOutTrackTime = mixOutTrackTime
            p.durationSec = durationSec
            p.mixInTrackTime = mixInTrackTime
            p.rate = rate
            p.outRate = outRate
            p.rampBeats = rampBeats
            p.rampSongSec = rampSongSec
            p.beatSec = beatSec
            p.bars = bars
            p.swapDb = swapDb
            p.barSec = barSec
            p.bpm = bpm
            p.outBpm = outBpm
            p.inBpm = inBpm
            p.loop = loop
            p.curves = curves
            p.kick = kick
            return p
        }

        override fun toString(): String {
            loop?.let { return it.toString() }
            val b = StringBuilder()
            b.append(forkV2Format("%s from %.2f s for %.2f s", style, mixOutTrackTime, durationSec))
            if (style != "crossfade") b.append(forkV2Format(", %d bars at %.1f BPM", bars, bpm))
            b.append(forkV2Format(", next song from %.2f s at x%.3f", mixInTrackTime, rate))
            if (outRate > 1.0001) b.append(forkV2Format(", playing song up to x%.4f over %d beats", outRate, rampBeats))
            if (shape != null) b.append(", ").append(shape)
            glide?.let { b.append(forkV2Format(", glide x%.4f over %d beats", it.q, it.beats)) }
            trim?.let { if (it.outDb != 0.0 || it.inDb != 0.0) b.append(forkV2Format(", trim out %.1f / in %.1f dB", it.outDb, it.inDb)) }
            if (reason != null) b.append(" (").append(reason).append(')')
            if (preset != null || warnings != null) {
                b.append(" [editor: ").append(preset ?: "auto")
                warnings?.forEach { b.append("; ").append(it) }
                b.append(']')
            }
            if (v2 && style != "crossfade") {
                b.append(forkV2Format(" [AutoDJ: both sing %d%%", jround(clash * 100)))
                if (vocalsBy != null) b.append(" (").append(vocalsBy).append(')')
                if (rerouted != null) b.append("; ").append(rerouted)
                if (keyClash != null) b.append("; keys clash (").append(keyClash).append("), short blend")
                b.append(']')
            }
            return b.toString()
        }
    }

    // ───────── grid (BitChord engine/grid.ts) ─────────
    internal const val BEATS_PER_BAR = 4
    internal const val BEATS_PER_PHRASE = 32

    class Grid {
        var beatSec = 0.0
        var barSec = 0.0
        var phraseSec = 0.0
        var downbeatSec = 0.0
        var bpm = 0.0
    }

    internal fun gridOf(a: ForkV2Analysis?): Grid? {
        if (a == null || !(a.bpm > 0) || !(a.beatIntervalSec > 0) || a.firstDownbeatSec.isNaN()) return null
        val g = Grid()
        g.beatSec = a.beatIntervalSec
        g.barSec = g.beatSec * BEATS_PER_BAR
        g.phraseSec = g.beatSec * BEATS_PER_PHRASE
        g.downbeatSec = a.firstDownbeatSec
        g.bpm = a.bpm
        return g
    }

    /** The analysis as the outgoing side sees it: its grid fields swapped for the tail grid. */
    fun asOutgoing(a: ForkV2Analysis): ForkV2Analysis {
        if (!a.tailGridKnown) return a
        val o = a.copy()
        val t = a.tailGrid
        if (t != null) {
            o.bpm = t.bpm
            o.beatIntervalSec = t.beatIntervalSec
            o.firstDownbeatSec = t.firstDownbeatSec
            o.gridConfidence = t.gridConfidence
        } else {
            o.beatIntervalSec = Double.NaN
            o.firstDownbeatSec = Double.NaN
            o.gridConfidence = 0.0
        }
        return o
    }

    internal fun phraseAtOrAfter(g: Grid, t: Double): Double =
        g.downbeatSec + ceil((t - g.downbeatSec) / g.phraseSec) * g.phraseSec

    internal fun downbeatAtOrAfter(g: Grid, t: Double): Double =
        g.downbeatSec + ceil((t - g.downbeatSec) / g.barSec) * g.barSec

    internal fun downbeatAtOrBefore(g: Grid, t: Double): Double =
        g.downbeatSec + floor((t - g.downbeatSec) / g.barSec) * g.barSec

    internal fun naturalMixOut(g: Grid, cur: ForkV2Analysis, dur: Double): Double =
        downbeatAtOrBefore(g, cur.durationSec - g.barSec - dur)

    // ───────── Auto blend length (BitChord phone, TransitionPlanner.kt) ─────────
    internal val AUTO_BAR_CHOICES = intArrayOf(32, 16, 8, 4)
    internal const val AUTO_BEATS = 16
    internal const val AUTO_MAX_SEC = 12.0
    internal const val AUTO_CALM_MAX_SEC = 16.0
    internal const val AUTO_FILTER_MAX_SEC = 8.0
    internal const val CALM_ENERGY = 0.7
    internal const val ENERGY_WINDOW_BEATS = 8
    internal const val MAX_NEXT_SHARE = 0.4

    /** The song's energy over [from, to] as a share of its mean; NaN: no curve. */
    internal fun energyShare(a: ForkV2Analysis?, from: Double, to: Double): Double {
        val c = a?.energyCurve
        if (c == null || c.isEmpty() || !(a.durationSec > 0) || !(a.energy > 0)) return Double.NaN
        val w = a.durationSec / c.size
        val i0 = max(0.0, min((c.size - 1).toDouble(), floor(from / w))).toInt()
        val i1 = max(i0.toDouble(), min((c.size - 1).toDouble(), floor(max(from, to - 1e-6) / w))).toInt()
        var sum = 0.0
        for (i in i0..i1) sum += c[i]
        return sum / (i1 - i0 + 1) / a.energy
    }

    internal fun autoBars(cur: ForkV2Analysis, next: ForkV2Analysis, g: Grid, entrySec: Double, filter: Boolean): Int {
        val window = ENERGY_WINDOW_BEATS * g.beatSec
        val exit = cur.durationSec - g.barSec
        val out = energyShare(cur, exit - window, exit)
        val into = energyShare(next, entrySec, entrySec + window)
        val calm = !out.isNaN() && !into.isNaN() && out < CALM_ENERGY && into < CALM_ENERGY
        val beats = AUTO_BEATS * (if (calm) 2 else 1)
        val cap = min(
            min(beats * g.beatSec, if (filter) AUTO_FILTER_MAX_SEC else if (calm) AUTO_CALM_MAX_SEC else AUTO_MAX_SEC),
            if (next.durationSec > 0) next.durationSec * MAX_NEXT_SHARE else Double.POSITIVE_INFINITY,
        )
        for (b in AUTO_BAR_CHOICES) if (b * g.barSec <= cap + 1e-6) return b
        return AUTO_BAR_CHOICES[AUTO_BAR_CHOICES.size - 1]
    }

    internal fun requestedBars(s: Settings, cur: ForkV2Analysis, next: ForkV2Analysis, g: Grid, entrySec: Double, filter: Boolean): Int =
        if (s.bars <= 0) autoBars(cur, next, g, entrySec, filter) else s.bars

    // ───────── transition planner (BitChord engine/transition-scheduler.ts) ─────────
    internal const val MIN_GRID_CONFIDENCE = 0.2
    internal const val KILL_DB = -12.0
    internal const val SHORT_BLEND_KILL_DB = -6.0
    internal const val FULL_SWAP_MIN_BARS = 8
    internal const val MAX_SLOW_DOWN = 0.1
    internal const val MAX_SPEED_UP = 0.25
    const val TEMPO_STEP_PER_BEAT = 0.0075
    internal const val FILTER_MAX_BARS = 8
    internal const val FALLBACK_SEC = 12.0

    internal fun effectiveBlendBars(requested: Int, fromConf: Double, toConf: Double): Int {
        val conf = min(fromConf, toConf)
        if (conf >= 0.6) return requested
        if (conf >= 0.45) return min(requested, 8)
        return min(requested, 4)
    }

    internal fun stretchAllowed(rate: Double): Boolean =
        if (rate >= 1) rate - 1 <= MAX_SPEED_UP else 1 - rate <= MAX_SLOW_DOWN

    internal fun foldRate(raw: Double): Double {
        var best = raw
        for (r in doubleArrayOf(raw / 2, raw * 2)) if (abs(r - 1) < abs(best - 1)) best = r
        return best
    }

    internal fun stub(id: String?, durationSec: Double): ForkV2Analysis {
        val a = ForkV2Analysis()
        a.fileKey = id
        a.durationSec = durationSec
        a.gridConfidence = 0.0
        a.energy = 0.5
        a.energyCurve = DoubleArray(0)
        a.stub = true
        a.tailGridKnown = false
        return a
    }

    /**
     * The blend from the playing song into the next. from, to: analyses (null: not analysed yet);
     * durationSec: the playing song's length as the player reports it; pos: where it is now;
     * earliest: the first song time the blend can start.
     */
    fun plan(
        s: Settings, fromId: String?, toId: String?, from: ForkV2Analysis?, to: ForkV2Analysis?, durationSec: Double,
        pos: Double, earliest: Double, sung: Sung?,
    ): Plan = plan(s, fromId, toId, from, to, durationSec, pos, earliest, sung, null)

    /**
     * With the editor's override for this pair (editor.md §9; a pick from before the editor is
     * migrated as it's read). Its length comes before the setting; a preset other than Auto
     * replaces the plan's gain, shelves and filters, and the planner's tempo stays.
     */
    fun plan(
        s0: Settings, fromId: String?, toId: String?, from: ForkV2Analysis?, to: ForkV2Analysis?, durationSec: Double,
        pos: Double, earliest: Double, sung: Sung?, ov: ForkV2MixOverride?,
    ): Plan {
        var s = s0
        val pinned = ov?.pinnedBars() ?: 0
        if (pinned > 0) {
            val p = Settings()
            p.version = s.version
            p.keyAware = s.keyAware
            p.bars = pinned
            s = p
        }
        val warnings = ArrayList<String>()
        val p = planFor(s, fromId, toId, from, to, durationSec, pos, earliest, sung, ov, warnings)
        p.curves = ForkV2Curves.blend(p)
        if (ov != null && !ov.loop()) {
            if (warnings.isNotEmpty()) p.warnings = warnings
            if (ov.`in` != null) p.windowIn = true // placed by the user: the kick lock leaves it
            val both = from != null && to != null
            val pc = if (!(p.fallback && !both)) ForkV2MixOverride.presetCurves(ov, p.durationSec, if (p.style == "crossfade") Double.NaN else p.beatSec) else null
            if (pc != null) {
                // the preset's gain, shelves and filters; the planner's tempo stays
                val cur = p.curves!!
                cur.out["rate"]?.let { pc.out["rate"] = it }
                cur.`in`["rate"]?.let { pc.`in`["rate"] = it }
                p.curves = pc
                p.preset = ov.preset
                // the phone: Morphe's crossfade can't play a preset, our decks can
                if (both) p.fallback = false
            }
        }
        return p
    }

    /**
     * Beat match Off (or Sync/Glide out of range): both songs at their own speed, overlapping over
     * the window and length asked for; with neither asked for, the plain crossfade at the song's end.
     */
    internal fun overlap(
        cur: ForkV2Analysis, next: ForkV2Analysis, fromGrid: Grid?, ov: ForkV2MixOverride?, earliest: Double, reason: String,
        warnings: MutableList<String>?,
    ): Plan {
        val base = fallback(cur, next, earliest, reason)
        val outW = if (ov != null) ForkV2MixOverride.resolveWindow(ov.out, null as Grid?) else null
        val inW = if (ov != null) ForkV2MixOverride.resolveWindow(ov.`in`, null as Grid?) else null
        // the length asked for, or the preset's own (Slam: 2 bars)
        val bars = ov?.pinnedBars() ?: 0
        if (bars == 0 && outW == null && inW == null) return base
        val end = musicEnd(cur).durationSec
        val room = end - 0.3 - earliest
        if (room < 1) {
            warnings?.add("no room left in this song for the window asked for")
            return base
        }
        val want = if (bars > 0 && fromGrid != null) bars * fromGrid.barSec else if (base.durationSec > 0) base.durationSec else FALLBACK_SEC
        if (bars > 0 && fromGrid != null && want > room + 0.01 && warnings != null) {
            warnings.add(forkV2Format("%d bars don't fit before the end: %.1f s", bars, room))
        }
        val D = min(want, room)
        val hi = max(earliest, end - 0.3 - D)
        var mixOut = hi
        if (outW != null && outW.sec < earliest - 0.01) {
            warnings?.add(playedNote(outW.sec))
        } else if (outW != null) {
            mixOut = clampWindow(outW.sec, earliest, hi, "the mix-out", warnings, fromGrid)
        }
        val mixIn = if (inW != null) clampWindow(inW.sec, 0.0, max(0.0, next.durationSec - 10 - D), "the mix-in", warnings, gridOf(next)) else base.mixInTrackTime
        base.fallback = false
        base.mixOutTrackTime = mixOut
        base.durationSec = D
        base.mixInTrackTime = mixIn
        base.trim = loudnessTrim(cur, mixOut, D, next, mixIn, D)
        return base
    }

    /**
     * A saved position held inside [lo, hi]; one past a limit moves to the nearest downbeat inside
     * it (the clamped second itself with no grid there), with the reason shown (editor.md §6).
     */
    internal fun clampWindow(t: Double, lo: Double, hi: Double, what: String, warnings: MutableList<String>?, grid: Grid?): Double {
        var c = clamp(t, lo, hi)
        if (abs(c - t) <= 0.01) return c
        if (grid != null && grid.barSec > 0) {
            val d = if (t < lo) downbeatAtOrAfter(grid, lo) else downbeatAtOrBefore(grid, hi)
            if (d >= lo - 1e-6 && d <= hi + 1e-6) c = d
        }
        warnings?.add(forkV2Format("%s moved to %.1f s (%s)", what, c, if (t < lo) "before the song" else "too close to the end"))
        return c
    }

    /** A saved mix-out the playhead has passed: the planner's own plays (editor.md §6). */
    internal fun playedNote(sec: Double): String =
        forkV2Format("the mix-out at %.1f s has already played: Auto's mix-out instead", sec)

    /** May Automix v2's kick lock move this plan's mix-in (automix.js kickLockAllowed): a blend not placed in the editor. */
    fun kickLockAllowed(p: Plan?): Boolean = p != null && "blend" == p.style && !p.windowIn

    private fun planFor(
        s: Settings, fromId: String?, toId: String?, from: ForkV2Analysis?, to: ForkV2Analysis?, durationSec: Double,
        pos: Double, earliest: Double, sungIn: Sung?, pick: ForkV2MixOverride?, warnings: MutableList<String>,
    ): Plan {
        val both = from != null && to != null
        val cur: ForkV2Analysis
        if (both) {
            val f = from!!.copy()
            f.durationSec = durationSec
            cur = asOutgoing(f)
        } else {
            cur = asOutgoing(stub(fromId, durationSec))
        }
        val next = if (both) to!! else stub(toId, 0.0)
        val fromGrid = gridOf(cur)
        val toGrid = gridOf(next)
        val sung = sungIn ?: Sung()
        val forced = pick != null && pick.forced()
        val bm = pick?.beatMatchOrAuto() ?: "auto"
        val family = pick?.family

        if (bm == "off" && both) {
            return overlap(cur, next, fromGrid, pick, earliest, if (family != null || pick!!.migrated) "picked in the overview" else "beat match off", warnings)
        }
        val v3 = s.version == 3
        if (fromGrid == null || cur.gridConfidence < MIN_GRID_CONFIDENCE) {
            if (forced && both) {
                warnings.add("no beat grid at this song's end: plays without beat match")
                return overlap(cur, next, null, pick, earliest, "this song has no beat grid at its end", warnings)
            }
            if (v3 && both) return handoff(cur, next, earliest, "this song has no beat grid at its end", sung)
            return fallback(cur, next, earliest, if (both) "this song has no beat grid at its end" else "songs not analysed in time")
        }
        val toWeak = toGrid == null || next.gridConfidence < MIN_GRID_CONFIDENCE
        if (forced && both && toWeak) {
            warnings.add("the next song has no beat grid: plays without beat match")
            return overlap(cur, next, fromGrid, pick, earliest, "next song has no beat grid", warnings)
        }
        if (both && (bm == "sync" || bm == "glide")) {
            val st = stretchOf(foldRate(fromGrid.bpm / toGrid!!.bpm))
            if (st > V2_MAX_STRETCH) {
                val why = "tempos " + jround(st * 100) + "% apart: " + (if (bm == "sync") "Sync" else "Glide") + " stretches at most " +
                    jround(V2_MAX_STRETCH * 100) + "%, so this plays without beat match"
                warnings.add(why)
                return overlap(cur, next, fromGrid, pick, earliest, why, warnings)
            }
        }
        if ("filter" == family) {
            val g = if (!toWeak) toGrid else null
            return filterMix(
                s, cur, next, fromGrid, g, requestedBars(s, cur, next, fromGrid, toGrid?.downbeatSec ?: 0.0, true), pos, earliest,
                "picked in the overview", sung,
            )
        }
        if (v3 && toWeak) return handoff(cur, next, earliest, "next song has no beat grid", sung)
        if (toWeak) {
            return filterMix(s, cur, next, fromGrid, null, requestedBars(s, cur, next, fromGrid, 0.0, true), pos, earliest, "next song has no beat grid", sung)
        }
        toGrid!!

        val ratio = foldRate(fromGrid.bpm / toGrid.bpm)
        // v2: never more than V2_MAX_STRETCH of speed-up, never two 808 styles at once, never
        // keys that clash badly; it can't beatmatch those, so it hands over instead (an override
        // is the user's choice: the style gates warn instead)
        if (v3 && stretchOf(ratio) > V2_MAX_STRETCH) {
            val why = "tempos " + jround(stretchOf(ratio) * 100) + "% apart (v2 stretches at most " + jround(V2_MAX_STRETCH * 100) + "%)"
            if (!forced && (pick == null || pick.bars == 0)) return handoff(cur, next, earliest, why, sung)
            // the user's preset, length or window: played as asked without beat match (editor.md §6)
            warnings.add("$why: plays without beat match")
            return overlap(cur, next, fromGrid, pick, earliest, why, warnings)
        }
        val contrast = subBassContrast(cur, next)
        if (v3 && contrast >= V2_SUB_BASS_VETO) {
            if (!forced) return handoff(cur, next, earliest, "different sub-bass styles (" + forkV2Format("%.2f", contrast) + " apart)", sung)
            warnings.add("the productions clash (sub-bass " + forkV2Format("%.2f", contrast) + " apart)")
        }
        val harmonic = if (v3) harmonicEvidence(cur, next) else null
        if (harmonic != null && harmonic.severeClash) {
            if (!forced) return handoff(cur, next, earliest, "the keys clash (" + cur.keyName + " → " + next.keyName + ", " + forkV2Js(harmonic.score) + ")", sung)
            warnings.add("the keys clash (" + cur.keyName + " → " + next.keyName + ")")
        }
        if (!stretchAllowed(ratio)) {
            val why = "tempos " + jround(abs(ratio - 1) * 100) + "% apart"
            if (!forced) return filterMix(s, cur, next, fromGrid, toGrid, requestedBars(s, cur, next, fromGrid, toGrid.downbeatSec, true), pos, earliest, why, sung)
            warnings.add("$why: too far to stretch, plays without beat match")
            return overlap(cur, next, fromGrid, pick, earliest, why, warnings)
        }
        // Tempo only moves up: the next song faster, the playing one is brought up to it before the
        // blend; slower, it is sped up. Automix v2: over 4% apart, a glide on both songs instead
        // (glideSteps). The editor's Sync: always the speed-up; Glide: always the glide.
        val glideQ = if (bm == "sync") Double.NaN
        else if (bm == "glide") (if (abs(ratio - 1) > 1e-4) 1 / ratio else Double.NaN)
        else if (v3 && stretchOf(ratio) > V2_MAX_CONSTANT) 1 / ratio else Double.NaN
        val gliding = !glideQ.isNaN()
        val rate = if (gliding) 1.0 else if (ratio < 1) 1.0 else ratio
        val outRate = if (gliding) 1.0 else if (ratio < 1) 1 / ratio else 1.0
        val rampBeats = if (outRate > 1.0001) ceil((outRate - 1) / TEMPO_STEP_PER_BEAT).toInt() else 0
        val rampSongSec = rampBeats * fromGrid.beatSec
        // AutoDJ's key-aware length (its own toggle); Automix v2: Orchard's key score, always on
        val keyClash = if (v3) (if (harmonic!!.shortBlend) cur.keyName + " → " + next.keyName + ", " + forkV2Js(harmonic.score) else null)
        else if (s.version >= 2 && s.keyAware) keysClash(cur, next) else null
        val pinnedBars = pick != null && pick.pinnedBars() > 0
        val bars = if (keyClash != null && !pinnedBars) keyClashBars(fromGrid) else requestedBars(s, cur, next, fromGrid, toGrid.downbeatSec, false)
        val effBars = effectiveBlendBars(bars, cur.gridConfidence, next.gridConfidence)
        val outSpan = effBars * fromGrid.barSec
        val glide = if (gliding) glideSteps(glideQ, effBars * 4, fromGrid.beatSec) else null
        val dur = glide?.total ?: (outSpan / outRate)
        val inPerOut = if (glide != null) 1 / glideQ else rate / outRate

        val first = earliest + rampSongSec
        val v2 = s.version >= 2
        var mixOut = naturalMixOut(fromGrid, if (v2) musicEnd(cur) else cur, outSpan)
        if (mixOut < pos + rampSongSec) mixOut = phraseAtOrAfter(fromGrid, first)
        if (mixOut < first) mixOut = downbeatAtOrAfter(fromGrid, first)
        // the editor's window (editor.md §6): A's mix-out on A's end grid, clamped to what can still play
        val wOut = if (pick != null) ForkV2MixOverride.resolveWindow(pick.out, fromGrid) else null
        if (wOut != null && wOut.sec < first - 0.01) {
            warnings.add(playedNote(wOut.sec)) // already played: the planner's own mix-out
        } else if (wOut != null) {
            mixOut = clampWindow(wOut.sec, first, max(first, min(musicEnd(cur).durationSec, cur.durationSec - 0.3) - outSpan), "the mix-out", warnings, fromGrid)
        }
        if (wOut != null && wOut.moved) warnings.add("the mix-out moved with a new beat grid")
        if (mixOut + outSpan > cur.durationSec - 0.3) {
            if (forced) {
                warnings.add("too close to the end for the window asked for")
                return overlap(cur, next, fromGrid, pick, earliest, "too close to the end for a blend", warnings)
            }
            if (v3) return handoff(cur, next, earliest, "too close to the end for a blend", sung)
            return filterMix(s, cur, next, fromGrid, toGrid, bars, pos, earliest, "too close to the end for a blend", sung)
        }

        val introEnd = if (next.introEndSec.isNaN()) 0.0 else next.introEndSec
        val fullEnergyPhrase = phraseAtOrAfter(toGrid, max(introEnd, toGrid.downbeatSec))
        var mixIn = fullEnergyPhrase - outSpan * inPerOut
        val introShort = mixIn < toGrid.downbeatSec - 1e-6
        if (mixIn < toGrid.downbeatSec) mixIn = toGrid.downbeatSec

        var dj: Reroute? = null
        val wIn = if (pick != null) ForkV2MixOverride.resolveWindow(pick.`in`, toGrid) else null
        if (wIn != null) {
            mixIn = clampWindow(wIn.sec, 0.0, max(0.0, next.durationSec - 10 - outSpan * inPerOut), "the mix-in", warnings, toGrid)
            if (wIn.moved) warnings.add("the mix-in moved with a new beat grid")
        }
        if (v2 && wIn == null && wOut == null) {
            mixIn = capIncomingSkip(next, toGrid, mixIn)
            dj = rerouteForVocals(cur, next, fromGrid, toGrid, mixOut, mixIn, outSpan, inPerOut, max(first, pos + rampSongSec), sung)
            mixOut = dj.mixOut
            mixIn = dj.mixIn
        }
        // v2: the bass changes hands on a bar where the playing song isn't mid-line
        val swap = if (v3 && effBars >= 2) vocalSafeSwap(cur, mixOut, outSpan, effBars, sung.out) else null

        // v2's fallback ladder for a pair that beatmatches but can't blend: a drop-in, then a
        // muffle, else the hand-over (an override plays as asked, with the reason as a warning)
        var shape: String? = null
        var shapeWhy: String? = null
        var muffleLead = Double.NaN
        var djClash = dj?.clash ?: 0.0
        if (forced && v3) {
            if (dj == null) {
                val bothSing = vocalOverlap(cur, next, mixOut, outSpan, mixIn, inPerOut, sung)
                djClash = if (bothSing.isNaN()) 0.0 else bothSing
            }
            if (djClash > V2_CLASH_MAX) warnings.add("both sing over " + jround(djClash * 100) + "% of the window")
            if (introShort && wIn == null) warnings.add("no intro in the next song to blend over")
        }
        if (v3 && !forced && (introShort || djClash > V2_CLASH_MAX)) {
            val why = if (introShort) "no intro in the next song to blend over" else "both would sing " + jround(djClash * 100) + "% of a blend"
            val dSpan = DROP_IN_BARS * fromGrid.barSec
            val dGlide = if (gliding) glideSteps(glideQ, DROP_IN_BARS * 4, fromGrid.beatSec) else null
            val lo = max(first, pos + rampSongSec)
            val natural = naturalMixOut(fromGrid, musicEnd(cur), dSpan)
            var kNext = 0
            while (kNext * fromGrid.barSec <= MAX_DISCARD_SEC) {
                val k = kNext++
                val o = natural - k * fromGrid.barSec
                if (o < lo) break
                val cut = voiceRisk(cur, o + dSpan - 1.5, o + dSpan, sung.out)
                if (!cut.isNaN() && cut >= SWAP_CLEAR) continue
                val bothSing = vocalOverlap(cur, next, o, dSpan, toGrid.downbeatSec, inPerOut, sung)
                if ((if (bothSing.isNaN()) 0.0 else bothSing) > V2_CLASH_MAX) continue
                val dDur = dGlide?.total ?: (dSpan / outRate)
                val p = Plan()
                p.style = "blend"
                p.shape = "dropin"
                p.reason = why + ": a drop-in on the next song's first beat" + (if (k > 0) " (exit " + k + " bar" + (if (k == 1) "" else "s") + " earlier, off a sung line)" else "")
                p.v2 = v2
                p.v3 = true
                p.clash = if (bothSing.isNaN()) 0.0 else bothSing
                p.vocalsBy = dj?.by
                p.keyClash = keyClash
                p.subBassContrast = contrast
                p.glide = if (dGlide != null) Glide(glideQ, DROP_IN_BARS * 4, fromGrid.beatSec) else null
                p.harmonic = harmonic
                p.trim = loudnessTrim(cur, o, dSpan, next, toGrid.downbeatSec, dSpan * inPerOut)
                p.mixOutTrackTime = o
                p.durationSec = dDur
                p.mixInTrackTime = toGrid.downbeatSec
                p.rate = if (dGlide != null) dGlide.inRate[0] else rate
                p.outRate = outRate
                p.rampBeats = rampBeats
                p.rampSongSec = rampSongSec
                p.beatSec = fromGrid.beatSec
                p.bars = DROP_IN_BARS
                p.swapDb = SHORT_BLEND_KILL_DB
                p.barSec = if (dGlide != null) dDur / DROP_IN_BARS else fromGrid.barSec / outRate
                p.bpm = if (dGlide != null) fromGrid.bpm * sqrt(glideQ) else fromGrid.bpm * outRate
                p.outBpm = fromGrid.bpm
                p.inBpm = toGrid.bpm
                return p
            }
            // muffle: the playing song keeps its level while a low-pass closes over it, the next
            // fades in over the bars before the bass swap and takes the bass on it
            val B = effBars * 4
            val at = swap?.at ?: 0.5
            val leadBeats = min(MUFFLE_LEAD_BEATS.toLong(), jround(at * B)).toInt()
            val lead = leadBeats / B.toDouble()
            val inAtSwap = mixIn + at * outSpan * inPerOut
            val inAtLead = mixIn + (at - lead) * outSpan * inPerOut
            val body = bassMean(cur, mixOut, mixOut + at * outSpan)
            val outRisk = voiceRisk(cur, mixOut + at * outSpan - 0.5, mixOut + at * outSpan + 0.5, sung.out)
            val inRisk = voiceRisk(next, inAtSwap - 0.5, inAtSwap + 0.5, sung.`in`)
            val swapsSingers = (if (outRisk.isNaN()) 0.0 else outRisk) >= 0.5 && (if (inRisk.isNaN()) 0.0 else inRisk) >= 0.7
            if (!introShort && leadBeats >= MUFFLE_MIN_LEAD_BEATS && at + MUFFLE_EXIT_BEATS / B.toDouble() <= 1 + 1e-6 &&
                (body.isNaN() || body >= MUFFLE_MIN_BODY) && inAtLead >= MUFFLE_MIN_OPENING_SEC && !swapsSingers
            ) {
                shape = "muffle"
                muffleLead = lead
                shapeWhy = "$why: a muffle (the playing song closes behind a low-pass)"
            } else {
                return handoff(cur, next, earliest, "$why and no drop-in or muffle fits", sung)
            }
        }

        val p = Plan()
        p.style = "blend"
        p.reason = shapeWhy
        p.shape = shape
        p.muffleLead = muffleLead
        p.v2 = v2
        p.v3 = v3
        p.clash = djClash
        p.rerouted = dj?.rerouted
        p.vocalsBy = dj?.by
        p.keyClash = keyClash
        // every blend carries its glide: Classic's too, when the editor's Glide asks for one
        p.glide = if (glide != null) Glide(glideQ, effBars * 4, fromGrid.beatSec) else null
        if (v3) {
            p.subBassContrast = contrast
            p.swapAt = swap?.at ?: Double.NaN
            p.swapMoved = swap?.moved
            p.harmonic = harmonic
            p.trim = loudnessTrim(cur, mixOut, outSpan, next, mixIn, outSpan * inPerOut)
        }
        p.mixOutTrackTime = mixOut
        p.durationSec = dur
        p.mixInTrackTime = mixIn
        p.rate = if (glide != null) glide.inRate[0] else rate
        p.outRate = outRate
        p.rampBeats = rampBeats
        p.rampSongSec = rampSongSec
        p.beatSec = fromGrid.beatSec
        p.bars = effBars
        p.swapDb = if (effBars >= FULL_SWAP_MIN_BARS) KILL_DB else SHORT_BLEND_KILL_DB
        p.barSec = if (glide != null) dur / effBars else fromGrid.barSec / outRate
        p.bpm = if (glide != null) fromGrid.bpm * sqrt(glideQ) else fromGrid.bpm * outRate
        p.outBpm = fromGrid.bpm
        p.inBpm = toGrid.bpm
        return p
    }

    /** The phone's DJ_FILTER: incoming on its own downbeat at its own tempo. */
    internal fun filterMix(
        s: Settings, cur: ForkV2Analysis, next: ForkV2Analysis, fromGrid: Grid, toGrid: Grid?, requested: Int, pos: Double,
        earliest: Double, reason: String, sung: Sung,
    ): Plan {
        var bars = min(requested, FILTER_MAX_BARS)
        var dur = bars * fromGrid.barSec
        val v2 = s.version >= 2
        var mixOut = naturalMixOut(fromGrid, if (v2) musicEnd(cur) else cur, dur)
        if (mixOut < pos) mixOut = phraseAtOrAfter(fromGrid, earliest)
        if (mixOut < earliest) mixOut = downbeatAtOrAfter(fromGrid, earliest)
        val room = cur.durationSec - 0.3 - mixOut
        if (dur > room) {
            bars = max(1.0, floor(room / fromGrid.barSec)).toInt()
            dur = bars * fromGrid.barSec
        }
        if (room < fromGrid.barSec) return fallback(cur, next, earliest, reason)
        val mixIn = toGrid?.downbeatSec ?: min(if (next.introEndSec.isNaN()) 0.0 else next.introEndSec, 1.0)
        var clash = 0.0
        if (v2) {
            val o = vocalOverlap(cur, next, mixOut, dur, mixIn, 1.0, sung)
            clash = if (o.isNaN()) 0.0 else o
        }
        val p = Plan()
        p.style = "filter"
        p.reason = reason
        p.v2 = v2
        p.clash = clash
        p.mixOutTrackTime = mixOut
        p.durationSec = dur
        p.mixInTrackTime = mixIn
        p.rate = 1.0
        p.bars = bars
        p.swapDb = KILL_DB
        p.barSec = fromGrid.barSec
        p.bpm = fromGrid.bpm
        p.inBpm = toGrid?.bpm ?: Double.NaN
        return p
    }

    // ───────── AutoDJ (Automix version 2): BitChord phone ideas ─────────
    internal const val VOCAL_SUNG = 0.25
    internal const val REROUTE_OVERLAP = 0.3
    internal const val REROUTE_GAIN = 0.5
    internal const val MAX_DISCARD_SEC = 12.0
    internal const val MAX_IN_SKIP = 0.25
    internal val ENTRY_SHIFTS = intArrayOf(0, -4, 4, -8, 8)
    internal const val KEY_MIN_CONFIDENCE = 0.5

    internal fun keysClash(a: ForkV2Analysis, b: ForkV2Analysis): String? {
        if (a.camelotNum <= 0 || b.camelotNum <= 0 || !(a.keyConfidence >= KEY_MIN_CONFIDENCE) || !(b.keyConfidence >= KEY_MIN_CONFIDENCE)) return null
        val step = ((a.camelotNum - b.camelotNum) % 12 + 12) % 12
        val ok = a.camelotNum == b.camelotNum || (a.camelotLetter == b.camelotLetter && (step == 1 || step == 11))
        if (ok) return null
        return (a.keyName ?: (a.camelotNum.toString() + a.camelotLetter)) + " → " +
            (b.keyName ?: (b.camelotNum.toString() + b.camelotLetter))
    }

    internal fun keyClashBars(g: Grid): Int = if (2 * g.barSec >= (if (g.bpm >= 140) 6 else 4)) 2 else 4

    internal fun musicEnd(a: ForkV2Analysis): ForkV2Analysis {
        val end = a.contentEndSec
        if (!(end > 0) || end >= a.durationSec - 1) return a
        val o = a.copy()
        o.durationSec = max(end, a.durationSec - 20)
        return o
    }

    internal fun capIncomingSkip(next: ForkV2Analysis, toGrid: Grid, mixIn: Double): Double {
        val cap = if (next.durationSec > 0) next.durationSec * MAX_IN_SKIP else Double.POSITIVE_INFINITY
        if (mixIn <= cap) return mixIn
        return max(toGrid.downbeatSec, mixIn - ceil((mixIn - cap) / toGrid.barSec) * toGrid.barSec)
    }

    /** Sung or not at song second t: 1/0 from lyric spans, else the mask; NaN where neither knows. */
    internal fun vocalAt(a: ForkV2Analysis, t: Double, spans: Array<DoubleArray>?): Double {
        if (spans != null) {
            for (x in spans) if (t >= x[0] && t < x[1]) return 1.0
            return 0.0
        }
        for (w in a.vocals) {
            val i = jround((t - w.startSec) * w.fps)
            if (i >= 0 && i < w.curve.size) return w.curve[i.toInt()].toDouble()
        }
        return Double.NaN
    }

    /** The share of the blend where both sing; NaN when the sources cover under half of it. */
    internal fun vocalOverlap(cur: ForkV2Analysis, next: ForkV2Analysis, mixOut: Double, outSpan: Double, mixIn: Double, inPerOut: Double, sung: Sung): Double {
        var both = 0
        var n = 0
        var steps = 0
        var tNext = mixOut
        while (tNext < mixOut + outSpan) {
            val t = tNext
            tNext += 0.05
            steps++
            val a = vocalAt(cur, t, sung.out)
            val b = vocalAt(next, mixIn + (t - mixOut) * inPerOut, sung.`in`)
            if (a.isNaN() || b.isNaN()) continue
            n++
            if (a >= VOCAL_SUNG && b >= VOCAL_SUNG) both++
        }
        return if (steps > 0 && n >= steps * 0.5) both / n.toDouble() else Double.NaN
    }

    internal class Reroute {
        var mixOut = 0.0
        var mixIn = 0.0
        var clash = 0.0
        var rerouted: String? = null
        var by: String? = null
    }

    internal fun rerouteForVocals(
        cur: ForkV2Analysis, next: ForkV2Analysis, fromGrid: Grid, toGrid: Grid, mixOut: Double, mixIn: Double,
        outSpan: Double, inPerOut: Double, earliestOut: Double, sung: Sung,
    ): Reroute {
        val by = (if (sung.out != null) "lyrics" else "mask") + "/" + (if (sung.`in` != null) "lyrics" else "mask")
        val primary = vocalOverlap(cur, next, mixOut, outSpan, mixIn, inPerOut, sung)
        val keep = Reroute()
        keep.mixOut = mixOut
        keep.mixIn = mixIn
        keep.clash = if (primary.isNaN()) 0.0 else primary
        keep.by = by
        if (primary.isNaN() || primary <= REROUTE_OVERLAP) return keep
        val inCap = if (next.durationSec > 0) next.durationSec * MAX_IN_SKIP else Double.POSITIVE_INFINITY
        var bestO = Double.NaN
        var bestCost = 0.0
        var bestMo = 0.0
        var bestMi = 0.0
        var bestK = 0
        var bestJ = 0
        var kNext = 0
        while (kNext * fromGrid.barSec <= MAX_DISCARD_SEC) {
            val k = kNext++
            for (j in ENTRY_SHIFTS) {
                if (k == 0 && j == 0) continue
                val mi = mixIn + j * toGrid.barSec
                if (mi < toGrid.downbeatSec - 1e-6 || mi > inCap) continue
                val mo = mixOut - k * fromGrid.barSec
                val o = vocalOverlap(cur, next, mo, outSpan, mi, inPerOut, sung)
                if (o.isNaN()) continue
                if (mo < earliestOut) continue
                val cost = k + abs(j) / 2.0
                if (bestO.isNaN() || o < bestO - 1e-9 || (abs(o - bestO) < 1e-9 && cost < bestCost)) {
                    bestO = o
                    bestCost = cost
                    bestMo = mo
                    bestMi = mi
                    bestK = k
                    bestJ = j
                }
            }
        }
        if (bestO.isNaN() || bestO > primary * REROUTE_GAIN) {
            keep.rerouted = "both sing " + pct(primary) + " of it, no cleaner point"
            return keep
        }
        val r = Reroute()
        r.mixOut = bestMo
        r.mixIn = bestMi
        r.clash = bestO
        r.by = by
        r.rerouted = "both sang " + pct(primary) + " of it, moved to " + pct(bestO) + " (exit " + bestK + " bars earlier, cue " +
            (if (bestJ >= 0) "+" else "") + bestJ + " bars)"
        return r
    }

    private fun pct(x: Double): String = jround(x * 100).toString() + "%"

    /** AutoMix's fallback: a plain 12 s equal-power fade that ends with the song. */
    internal fun fallback(cur: ForkV2Analysis, next: ForkV2Analysis, earliest: Double, reason: String): Plan {
        val target = cur.durationSec - 0.3 - FALLBACK_SEC
        val mixOut = max(earliest, target)
        val dur = max(0.0, min(FALLBACK_SEC, cur.durationSec - 0.3 - mixOut))
        val p = Plan()
        p.style = "crossfade"
        p.fallback = true
        p.reason = reason
        p.mixOutTrackTime = mixOut
        p.durationSec = dur
        p.mixInTrackTime = if (next.introEndSec.isNaN()) 0.0 else next.introEndSec
        p.rate = 1.0
        p.bars = 0
        p.swapDb = 0.0
        p.barSec = 0.0
        return p
    }

    fun gridOk(a: ForkV2Analysis): Boolean = gridOf(a) != null && a.gridConfidence >= MIN_GRID_CONFIDENCE

    // ───────── Automix v2 (version 3): Orchard's rules, automix.js `version === 3` ─────────
    internal const val V2_MAX_STRETCH = 0.08
    internal const val V2_MAX_CONSTANT = 0.04
    internal const val V2_SUB_BASS_VETO = 0.9
    const val V2_SWITCH_SEC = 1.5
    internal const val SWAP_SINGING = 0.5
    internal const val SWAP_CLEAR = 0.3
    internal const val V2_CLASH_MAX = 0.3
    internal const val DROP_IN_BARS = 2
    internal const val MUFFLE_LEAD_BEATS = 8
    internal const val MUFFLE_MIN_LEAD_BEATS = 4
    internal const val MUFFLE_EXIT_BEATS = 2
    internal const val MUFFLE_MIN_BODY = 0.3
    internal const val MUFFLE_MIN_OPENING_SEC = 2.0
    internal const val BRIDGE_MIN_SEC = 4.0
    internal const val BRIDGE_MAX_SEC = 12.0
    internal const val BRIDGE_MAX_SKIP_SEC = 16.0
    internal const val BEATLESS = 0.3
    internal const val TRUSTED_KEY = 0.65
    internal const val CLASH_CORRELATION = -0.3
    internal const val CLASH_CONFIDENCE = 0.5
    internal const val KEY_CONTEXT_SEC = 24.0
    internal const val TRIM_TOLERANCE_DB = 1.0
    internal const val TRIM_MAX_DB = 6.0
    internal const val LOOP_MARGIN = 0.02

    /** The speed-up a pair needs, however it is split. */
    internal fun stretchOf(ratio: Double): Double = max(ratio, 1 / ratio) - 1

    /** A glide as the plan states it: the folded tempo ratio (next / playing), its beats, the playing song's beat. */
    class Glide internal constructor(val q: Double, val beats: Int, val beatOutSec: Double)

    /** glideSteps: each beat's rate on both songs, its output length and start, the next song's beat. */
    class GlideSteps {
        var outRate = DoubleArray(0)
        var inRate = DoubleArray(0)
        var dur = DoubleArray(0)
        var at = DoubleArray(0)
        var total = 0.0
        var beatInSec = 0.0
    }

    /**
     * Orchard's glide, one step per beat of the blend: the shared tempo moves geometrically from the
     * playing song's to the next one's; beat k plays the playing song at q^x and the next at
     * q^(x - 1), x = (k + 0.5) / beats, so both play one beat of their own in the same output time.
     */
    fun glideSteps(q: Double, beats: Int, beatOutSec: Double): GlideSteps {
        val g = GlideSteps()
        g.outRate = DoubleArray(beats)
        g.inRate = DoubleArray(beats)
        g.dur = DoubleArray(beats)
        g.at = DoubleArray(beats)
        var t = 0.0
        for (k in 0 until beats) {
            val x = (k + 0.5) / beats
            g.outRate[k] = q.pow(x)
            g.inRate[k] = q.pow(x - 1)
            g.at[k] = t
            g.dur[k] = beatOutSec / g.outRate[k]
            t += g.dur[k]
        }
        g.total = t
        g.beatInSec = beatOutSec / q
        return g
    }

    /** The two songs' sub-bass ratios apart, or NaN. */
    internal fun subBassContrast(a: ForkV2Analysis?, b: ForkV2Analysis?): Double {
        if (a == null || b == null || a.subBassRatio.isNaN() || b.subBassRatio.isNaN()) return Double.NaN
        return abs(a.subBassRatio - b.subBassRatio)
    }

    // keys (Orchard pairTempoHarmony)
    class Harmonic {
        var score = 0.0
        var confidence = 0.0
        var keyScore = Double.NaN
        var chromaScore = Double.NaN
        var severeClash = false
        var shortBlend = false
    }

    private val KEY_NAMES = arrayOf("C", "B#", "C#", "DB", "D", "D#", "EB", "E", "FB", "F", "E#", "F#", "GB", "G", "G#", "AB", "A", "A#", "BB", "B", "CB")
    private val KEY_ROOTS = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 10, 10, 11, 11)
    private val KEY_PATTERN = Regex("^(\\S+)\\s+(major|minor)$", RegexOption.IGNORE_CASE)

    /** {root, minor ? 1 : 0}, or null. */
    internal fun parsedKey(name: String?): IntArray? {
        if (name == null) return null
        val m = KEY_PATTERN.matchEntire(name.trim { it <= ' ' }) ?: return null
        val root = m.groupValues[1].uppercase().replaceFirst("♯", "#").replaceFirst("♭", "B")
        for (i in KEY_NAMES.indices) if (KEY_NAMES[i] == root) return intArrayOf(KEY_ROOTS[i], if (m.groupValues[2].equals("minor", ignoreCase = true)) 1 else 0)
        return null
    }

    internal fun keyRelationship(a: IntArray?, b: IntArray?): Double {
        if (a == null || b == null) return Double.NaN
        val cw = (b[0] - a[0] + 12) % 12
        val d = min(cw, 12 - cw)
        if (a[1] != b[1]) {
            if ((a[1] == 0 && cw == 9) || (a[1] == 1 && cw == 3)) return 0.95
            return if (d == 0) 0.7 else if (d == 5) 0.55 else if (d == 6) 0.05 else 0.35
        }
        return if (d == 0) 1.0 else if (d == 5) 0.85 else if (d == 2) 0.65 else if (d == 1) 0.3 else if (d == 6) 0.0 else 0.45
    }

    internal fun cosine(a: DoubleArray?, b: DoubleArray?): Double {
        if (a == null || a.size != 12 || b == null || b.size != 12) return Double.NaN
        var dot = 0.0
        var pa = 0.0
        var pb = 0.0
        for (k in 0 until 12) {
            dot += a[k] * b[k]
            pa += a[k] * a[k]
            pb += b[k] * b[k]
        }
        return if (pa > 0 && pb > 0) clamp(dot / sqrt(pa * pb), 0.0, 1.0) else Double.NaN
    }

    /** The keys on Orchard's table (0.7) and the whole-song pitch profiles (0.3); severe under 0.2, short under 0.55. */
    internal fun harmonicEvidence(a: ForkV2Analysis, b: ForkV2Analysis): Harmonic {
        val keyScore = keyRelationship(parsedKey(a.keyName), parsedKey(b.keyName))
        val chromaScore = cosine(a.chroma, b.chroma)
        val score = if (!keyScore.isNaN() && !chromaScore.isNaN()) keyScore * 0.7 + chromaScore * 0.3
        else if (!keyScore.isNaN()) keyScore else if (!chromaScore.isNaN()) chromaScore else 0.5
        val confidence = sqrt(clamp(a.keyConfidence, 0.0, 1.0) * clamp(b.keyConfidence, 0.0, 1.0))
        val h = Harmonic()
        h.score = r3(score)
        h.confidence = r3(confidence)
        h.keyScore = r3(keyScore)
        h.chromaScore = r3(chromaScore)
        h.severeClash = confidence >= TRUSTED_KEY && score < 0.2
        h.shortBlend = confidence >= TRUSTED_KEY && score < 0.55
        return h
    }

    /** {profile[12], mean weight} over [from, to], or null. */
    internal fun chromaProfile(a: ForkV2Analysis?, from: Double, to: Double): Array<DoubleArray>? {
        val c = a?.chromaCurve
        if (c == null || c.values.isEmpty() || !(to >= from)) return null
        val profile = DoubleArray(12)
        var weight = 0.0
        var frames = 0
        for (i in c.values.indices) {
            val t = c.start + i * c.step
            val f = c.values[i]
            if (t < from || t > to || f == null) continue
            for (k in 0 until 12) profile[k] += f[k] * c.weights[i]
            weight += c.weights[i]
            frames++
        }
        return if (frames > 0) arrayOf(profile, doubleArrayOf(weight / frames)) else null
    }

    internal fun pearson(a: DoubleArray, b: DoubleArray): Double {
        var ma = 0.0
        var mb = 0.0
        for (v in a) ma += v
        for (v in b) mb += v
        ma /= a.size
        mb /= b.size
        var cov = 0.0
        var pa = 0.0
        var pb = 0.0
        for (k in a.indices) {
            cov += (a[k] - ma) * (b[k] - mb)
            pa += (a[k] - ma) * (a[k] - ma)
            pb += (b[k] - mb) * (b[k] - mb)
        }
        return if (pa > 0 && pb > 0) cov / sqrt(pa * pb) else 0.0
    }

    internal fun tonality(side: Array<DoubleArray>): Double {
        var total = 0.0
        var mx = Double.NEGATIVE_INFINITY
        for (v in side[0]) {
            total += v
            mx = max(mx, v)
        }
        if (!(total > 0)) return 0.0
        return clamp((mx / (total / 12) - 1.3) / 0.4, 0.0, 1.0) * clamp(side[1][0], 0.0, 1.0)
    }

    /** Where the two songs meet: {correlation, confidence, severe ? 1 : 0}, or null (no chroma). */
    internal fun overlapHarmony(cur: ForkV2Analysis, next: ForkV2Analysis, outFrom: Double, outTo: Double, inFrom: Double, inTo: Double): DoubleArray? {
        val left = chromaProfile(cur, min(outFrom, outTo - KEY_CONTEXT_SEC), outTo)
        val right = chromaProfile(next, inFrom, max(inTo, inFrom + KEY_CONTEXT_SEC))
        if (left == null || right == null) return null
        val confidence = min(tonality(left), tonality(right))
        val correlation = pearson(left[0], right[0])
        return doubleArrayOf(r3(correlation), r3(confidence), if (correlation <= CLASH_CORRELATION && confidence >= CLASH_CONFIDENCE) 1.0 else 0.0)
    }

    // loudness (Orchard loudness.rs, strategy.rs loudness_trim)
    class Trim internal constructor(var diffDb: Double, var outDb: Double, var inDb: Double)

    /** A region's EBU R128 gated loudness from the song's 100 ms levels, or NaN. */
    internal fun regionLoudness(a: ForkV2Analysis?, from: Double, dur: Double): Double {
        val c = a?.loudnessCurve
        if (c == null || c.values.isEmpty() || !(dur > 0)) return Double.NaN
        val i0 = max(0L, jround((from - c.start) / c.step)).toInt()
        val i1 = min(c.values.size.toLong(), jround((from + dur - c.start) / c.step)).toInt()
        if (i1 <= i0) return Double.NaN
        val p = DoubleArray(i1 - i0)
        for (i in i0 until i1) p[i - i0] = 10.0.pow(c.values[i] / 10)
        val loud = ArrayList<Double>()
        var i = 0
        while (i + 4 <= p.size) {
            val x = (p[i] + p[i + 1] + p[i + 2] + p[i + 3]) / 4
            if (lufs(x) > -70) loud.add(x)
            i++
        }
        if (loud.isEmpty()) {
            var s = 0.0
            for (v in p) s += v
            return max(-70.0, lufs(s / p.size))
        }
        var s = 0.0
        for (x in loud) s += x
        val rel = lufs(s / loud.size) - 10
        var t = 0.0
        var n = 0
        for (x in loud) {
            if (lufs(x) > rel) {
                t += x
                n++
            }
        }
        return lufs(t / n)
    }

    private fun lufs(x: Double): Double = -0.691 + 10 * log10(x + 1e-30)

    /** Each region against its whole song; more than 1 dB apart, the louder side is trimmed (at most 6 dB). */
    internal fun loudnessTrim(cur: ForkV2Analysis, outFrom: Double, outDur: Double, next: ForkV2Analysis, inFrom: Double, inDur: Double): Trim? {
        val o = regionLoudness(cur, outFrom, outDur)
        val i = regionLoudness(next, inFrom, inDur)
        val ow = regionLoudness(cur, 0.0, cur.durationSec)
        val iw = regionLoudness(next, 0.0, next.durationSec)
        if (o.isNaN() || i.isNaN() || ow.isNaN() || iw.isNaN()) return null
        val diff = (o - ow) - (i - iw)
        if (abs(diff) <= TRIM_TOLERANCE_DB) return Trim(r1(diff), 0.0, 0.0)
        val t = r1(min(abs(diff), TRIM_MAX_DB))
        return if (diff > 0) Trim(r1(diff), -t, 0.0) else Trim(r1(diff), 0.0, -t)
    }

    // the hand-over and the beatless bridge (Orchard's boundary hand-off, beatlessBridge)

    /** "Beatmatch or do nothing": a beatless bridge when both edges have one, else the playing song runs out with a short fade. */
    internal fun handoff(cur: ForkV2Analysis, next: ForkV2Analysis, earliest: Double, reason: String, sung: Sung): Plan {
        val b = DoubleArray(3)
        val none = beatlessBridge(cur, next, sung, b)
        if (none == null && b[0] >= earliest) {
            val p = Plan()
            p.style = "crossfade"
            p.bridge = true
            p.reason = reason + ": a beatless bridge (" + forkV2Format("%.1f", b[1]) + " s, no beat on either side)"
            p.mixOutTrackTime = b[0]
            p.durationSec = b[1]
            p.mixInTrackTime = b[2]
            p.rate = 1.0
            p.trim = loudnessTrim(cur, b[0], b[1], next, b[2], b[1])
            return p
        }
        val end = min(musicEnd(cur).durationSec, cur.durationSec - 0.3)
        val mixOut = max(earliest, end - V2_SWITCH_SEC)
        val p = Plan()
        p.style = "crossfade"
        p.handoff = true
        p.reason = reason + ", so a straight hand-over (no beatless bridge: " + (none ?: "past the playhead") + ")"
        p.mixOutTrackTime = mixOut
        p.durationSec = max(0.0, end - mixOut)
        p.mixInTrackTime = 0.0
        p.rate = 1.0
        return p
    }

    /** The bass curve's mean over [from, to], or NaN. */
    internal fun bassMean(a: ForkV2Analysis?, from: Double, to: Double): Double {
        val c = a?.bassCurve
        if (c == null || c.values.isEmpty()) return Double.NaN
        val i0 = max(0.0, floor((from - c.start) / c.step)).toInt()
        val i1 = min((c.values.size - 1).toDouble(), floor((to - c.start) / c.step)).toInt()
        if (i1 < i0) return Double.NaN
        var s = 0.0
        for (i in i0..i1) s += c.values[i]
        return s / (i1 - i0 + 1)
    }

    /** Orchard beatPresence: 0 in a beatless passage, 1 once kick and bass are in; NaN unmeasured. */
    internal fun beatPresence(a: ForkV2Analysis, t: Double): Double {
        val m = bassMean(a, t - 0.5, t + 0.5)
        return if (m.isNaN()) Double.NaN else clamp((m - 0.1) / 0.4, 0.0, 1.0)
    }

    internal fun beatlessRun(a: ForkV2Analysis, from: Double, limit: Double, step: Double): Double {
        var run = 0.0
        var t = from
        while (if (step > 0) t < limit else t > limit) {
            val p = beatPresence(a, t)
            if (p.isNaN() || p >= BEATLESS) break
            run += abs(step)
            t += step
        }
        return run
    }

    /** The bridge into [into] {mixOut, dur, mixIn}; returns null, or why there is none. */
    internal fun beatlessBridge(cur: ForkV2Analysis, next: ForkV2Analysis, sung: Sung, into: DoubleArray): String? {
        if (cur.bassCurve == null || next.bassCurve == null) return "no bass curves"
        if (subBassContrast(cur, next) >= V2_SUB_BASS_VETO) return "the productions clash"
        val end = min(musicEnd(cur).durationSec, cur.durationSec - 0.3)
        val outRun = beatlessRun(cur, end - 0.125, end - BRIDGE_MAX_SEC, -0.25)
        val nextDur = if (next.durationSec > 0) next.durationSec else 60.0
        val inRun = beatlessRun(next, 0.125, min(nextDur, 60.0), 0.25)
        if (min(outRun, inRun) < BRIDGE_MIN_SEC) return "beatless ending " + forkV2Js(outRun) + " s, opening " + forkV2Js(inRun) + " s"
        val beat = if (cur.beatIntervalSec > 0) cur.beatIntervalSec else 0.5
        val dur = floor(min(min(outRun, inRun), BRIDGE_MAX_SEC) / beat) * beat
        if (dur < BRIDGE_MIN_SEC) return "beatless edges too short"
        val skip = min(max(0.0, inRun - dur), BRIDGE_MAX_SKIP_SEC)
        val both = vocalOverlap(cur, next, end - dur, dur, skip, 1.0, sung)
        if ((if (both.isNaN()) 0.0 else both) > 0.25) return "voices meet over the bridge"
        val risk = voiceRisk(next, skip - 0.5, skip + 1, sung.`in`)
        if ((if (risk.isNaN()) 0.0 else risk) >= 0.5) return "the next song would fade in mid-line"
        val keys = overlapHarmony(cur, next, end - dur, end, skip, skip + dur)
        if (keys != null && keys[2] > 0) return "the keys clash where they meet (" + forkV2Js(keys[0]) + ")"
        into[0] = end - dur
        into[1] = dur
        into[2] = skip
        return null
    }

    /** Orchard's voice risk over [from, to): the vocal mask mapped to 0..1 (lyric lines: sung or not); NaN unknown. */
    internal fun voiceRisk(a: ForkV2Analysis, from: Double, to: Double, spans: Array<DoubleArray>?): Double {
        var sum = 0.0
        var n = 0
        var tNext = from
        while (tNext < to) {
            val t = tNext
            tNext += 0.05
            val v = vocalAt(a, t, spans)
            if (v.isNaN()) continue
            sum += if (spans != null) v else clamp((v - 0.15) / 0.45, 0.0, 1.0)
            n++
        }
        return if (n > 0) sum / n else Double.NaN
    }

    internal class Swap {
        var at = 0.0
        var moved: String? = null
    }

    /** Orchard chooseBassSwap by the playing song's voice: the bar nearest the middle, moved off a sung line. */
    internal fun vocalSafeSwap(cur: ForkV2Analysis, mixOut: Double, outSpan: Double, bars: Int, spans: Array<DoubleArray>?): Swap {
        val s = Swap()
        val mid = clamp(jround(bars / 2.0).toDouble(), 1.0, (bars - 1).toDouble()) / bars
        val here = voiceRisk(cur, mixOut + mid * outSpan - 0.5, mixOut + mid * outSpan + 0.5, spans)
        s.at = mid
        if (here.isNaN() || here < SWAP_SINGING) return s
        val moves = ArrayList<Double>()
        for (k in 1 until bars) {
            val f = k / bars.toDouble()
            if (f < 0.25 || f > 0.75 || abs(f - mid) < 1e-9) continue
            val r = voiceRisk(cur, mixOut + f * outSpan - 0.5, mixOut + f * outSpan + 0.5, spans)
            if (!r.isNaN() && r < SWAP_CLEAR) moves.add(f)
        }
        moves.sortWith(Comparator { x, y ->
            val c = abs(x - 0.5).compareTo(abs(y - 0.5))
            if (c != 0) c else x.compareTo(y)
        })
        if (moves.isEmpty()) {
            s.moved = "sung at the middle, no clear bar"
            return s
        }
        val by = jround((moves[0] - mid) * bars)
        s.at = moves[0]
        s.moved = "off a sung line, " + (if (by > 0) "+" else "") + by + " bar" + (if (abs(by) == 1L) "" else "s")
        return s
    }

    /** What plays: the loop mix or the blend (automix.js loopOrBlend), with why under Automix v2. */
    class Choice internal constructor(val loop: Boolean, val why: String?) {
        companion object {
            /** The editor's Loop preset: the loop mix whenever one was found (crossfade.js loopPicked). */
            internal fun picked(found: Boolean): Choice = Choice(found, "Loop picked for these two")
        }
    }

    /**
     * Loop mix or blend. Before Automix v2 a loop mix that found bars always plays. Under v2 a blend
     * that fell back never beats it; otherwise both are scored as Orchard does on what differs
     * (both singing, the keys, the length) and the blend has to win by LOOP_MARGIN.
     */
    fun loopOrBlend(version: Int, blend: Plan?, loop: LoopPlan?): Choice {
        if (version != 3 || loop == null || loop.none != null) return Choice(loop != null && loop.none == null, null)
        if (blend == null || blend.fallback || "blend" != blend.style || blend.shape != null) return Choice(true, "the blend fell back")
        val b = choiceScore(blend.clash, blend.harmonic?.score ?: 0.5, blend.bars * 4)
        val l = choiceScore(min(loop.out!!.vocal, loop.`in`!!.vocal), 1.0, loop.totalBars * 4)
        return Choice(b < l + LOOP_MARGIN, "loop " + forkV2Js(r4(l)) + " vs blend " + forkV2Js(r4(b)) + " (the blend needs " + forkV2Js(LOOP_MARGIN) + " more)")
    }

    private fun choiceScore(clash: Double, keys: Double, beats: Int): Double {
        val vocal = 1 - min(1.0, clash * 1.15) * 0.72
        val length = if (beats == 16) 1.0 else if (beats == 8) 0.85 else if (beats == 32) 0.75 else 0.65
        return 0.82 * (0.15 * vocal + 0.13 * keys + 0.02 * length)
    }

    internal fun clamp(x: Double, lo: Double, hi: Double): Double = min(hi, max(lo, x))

    private fun r1(v: Double): Double = jround(v * 10) / 10.0

    private fun r3(v: Double): Double = if (v.isNaN()) Double.NaN else jround(v * 1000) / 1000.0

    private fun r4(v: Double): Double = jround(v * 1e4) / 1e4

    /** A number as JavaScript prints it in a template string. */
    internal fun js(v: Double): String = forkV2Js(v)

    // ───────── the loop mix (BitChord engine/loopMix.ts) ─────────
    internal const val LOOP_BARS = 4
    internal val LOOP_LENGTHS = intArrayOf(4, 2, 1)
    internal const val ENTER_BARS = 4
    internal const val SETTLE_BARS = 2
    internal const val MAX_BPM_PER_BAR = 2.5
    internal const val MIN_RAMP_BARS = 2
    internal const val MAX_RAMP_BARS = 24
    internal const val VOCAL_ACTIVE = 0.25
    internal const val MAX_SUNG_SHARE = 0.03
    internal const val MAX_SUNG_RUN_SEC = 0.2
    internal const val LYRIC_GUARD_SEC = 0.3
    const val LYRIC_LEAD_SEC = 0.15
    const val LYRIC_TAIL_SEC = 0.35
    internal const val MIN_LOUDNESS = 0.6
    internal const val OUT_SEARCH_SEC = 60.0
    internal const val IN_SEARCH_SEC = 45.0
    const val OUT_REGION_SEC = OUT_SEARCH_SEC + 5
    const val IN_REGION_SEC = IN_SEARCH_SEC + 40
    const val LOOP_SETUP_SEC = 15.0
    const val HANDOVER_SEC = 0.025
    const val BASS_CUT_DB = -18.0

    /** A decoded song for the loop search: its loudness (channel 0, the extension's rms) and length. */
    interface Audio {
        fun duration(): Double

        fun rms(from: Double, to: Double): Double
    }

    class LoopSide {
        var key: String? = null
        var loopStart = 0.0
        var loopEnd = 0.0
        var bars = 0
        var beatSec = 0.0
        var bpm = 0.0
        var vocal = 0.0
        var checkedBy: String? = null
    }

    class LoopPlan {
        var out: LoopSide? = null
        var `in`: LoopSide? = null
        var fold = 0.0
        var enterBars = 0
        var rampBars = 0
        var totalBars = 0
        var exitBars = 0.0

        /** Set when a side has no instrumental bars. */
        var none: String? = null

        /** What each deck does over the loop mix (spec/automix/plan.schema.json "curves"). */
        var curves: ForkV2Curves? = null

        override fun toString(): String {
            if (none != null) return "no loop mix: $none"
            val o = out!!
            val i = `in`!!
            return forkV2Format(
                "loop mix: %d-bar loop at %.2f s (%.1f BPM, %s) into a %d-bar loop at %.2f s (%.1f BPM, %s), ramp %d bars, %d bars in all",
                o.bars, o.loopStart, o.bpm, if ("lyrics" == o.checkedBy) "no lyric line" else forkV2Format("sung %.1f%%", o.vocal * 100),
                i.bars, i.loopStart, i.bpm, if ("lyrics" == i.checkedBy) "no lyric line" else forkV2Format("sung %.1f%%", i.vocal * 100),
                rampBars, totalBars,
            )
        }
    }

    /** null when there is no usable grid or a music video; a plan with [none] when a side has no instrumental bars. */
    fun planLoopMix(out: ForkV2Analysis, outBuffer: Audio, inn: ForkV2Analysis, inBuffer: Audio, earliestOutSec: Double, sung: Sung?): LoopPlan? =
        planLoopMix(1, out, outBuffer, inn, inBuffer, earliestOutSec, sung)

    /** The same for an engine version: Automix v2 (3) adds its gates (tempo cap, sub-bass, keys). */
    fun planLoopMix(version: Int, out: ForkV2Analysis, outBuffer: Audio, inn: ForkV2Analysis, inBuffer: Audio, earliestOutSec: Double, sungIn: Sung?): LoopPlan? {
        if (!(out.beatIntervalSec > 0) || out.firstDownbeatSec.isNaN() || !(out.bpm > 0)) return null
        if (!(inn.beatIntervalSec > 0) || inn.firstDownbeatSec.isNaN() || !(inn.bpm > 0)) return null
        if (out.musicVideo || inn.musicVideo) return null
        val sung = sungIn ?: Sung()
        val outBar = out.beatIntervalSec * 4
        val inBar = inn.beatIntervalSec * 4

        val outEnd = min(out.durationSec - 0.5, outBuffer.duration() - 0.1)
        val outFirst = max(earliestOutSec, out.durationSec - OUT_SEARCH_SEC)
        val outPick = firstClean({ bars ->
            val l = loopsBetween(out.tailBeats, out.firstDownbeatSec, outBar, outFirst, outEnd, bars)
            l.reverse()
            l
        }, outBuffer, out.vocals, sung.out, out.beatIntervalSec) ?: return none("no instrumental bars near the end of the playing song")
        val inEnd = min(IN_SEARCH_SEC + LOOP_BARS * inBar, inBuffer.duration() - 20)
        val inPick = firstClean({ bars -> loopsBetween(inn.headBeats, inn.firstDownbeatSec, inBar, 0.0, inEnd, bars) },
            inBuffer, inn.vocals, sung.`in`, inn.beatIntervalSec) ?: return none("no instrumental bars in the opening of the next song")
        val outBeat = (outPick.end - outPick.start) / (outPick.bars * 4)
        val inBeat = (inPick.end - inPick.start) / (inPick.bars * 4)
        val outBpm = 60 / outBeat
        val inBpm = 60 / inBeat
        var fold = 1.0
        for (f in doubleArrayOf(2.0, 0.5)) {
            if (abs(ln(inBpm / f / outBpm)) < abs(ln(inBpm / fold / outBpm))) fold = f
        }
        val target = inBpm / fold
        // v2: the loop ramps the tempo too, so the same cap (a wider gap: a blend or hand-over instead)
        if (version == 3 && stretchOf(target / outBpm) > V2_MAX_STRETCH) {
            return none("tempos " + jround(stretchOf(target / outBpm) * 100) + "% apart (v2 stretches at most " + jround(V2_MAX_STRETCH * 100) + "%)")
        }
        val contrast = subBassContrast(out, inn)
        if (version == 3 && contrast >= V2_SUB_BASS_VETO) return none("different sub-bass styles (" + forkV2Format("%.2f", contrast) + " apart)")
        val harmonic = if (version == 3) harmonicEvidence(out, inn) else null
        if (harmonic != null && harmonic.severeClash) return none("the keys clash (" + out.keyName + " → " + inn.keyName + ", " + forkV2Js(harmonic.score) + ")")
        val rampBars = min(MAX_RAMP_BARS.toDouble(), max(MIN_RAMP_BARS.toDouble(), ceil(abs(target - outBpm) / MAX_BPM_PER_BAR))).toInt()
        val inLoopOutBars = inPick.bars / fold
        val totalBars = ceil((ENTER_BARS + rampBars + 2) / inLoopOutBars) * inLoopOutBars
        val p = LoopPlan()
        p.out = side(out.fileKey, outPick, outBeat, outBpm)
        p.`in` = side(inn.fileKey, inPick, inBeat, inBpm)
        p.fold = fold
        p.enterBars = ENTER_BARS
        p.rampBars = rampBars
        p.totalBars = jround(totalBars).toInt()
        p.exitBars = totalBars - ENTER_BARS - rampBars
        p.curves = ForkV2Curves.loop(p)
        return p
    }

    private fun none(why: String): LoopPlan {
        val p = LoopPlan()
        p.none = why
        return p
    }

    private fun side(key: String?, c: Clean, beatSec: Double, bpm: Double): LoopSide {
        val s = LoopSide()
        s.key = key
        s.loopStart = c.start
        s.loopEnd = c.end
        s.bars = c.bars
        s.beatSec = beatSec
        s.bpm = bpm
        s.vocal = c.vocal
        s.checkedBy = c.checkedBy
        return s
    }

    /** [bars]-bar loops in [from, to]: on the model's own downbeats where it has them, else the constant grid. */
    internal fun loopsBetween(side: ForkV2Analysis.BeatSide?, anchor: Double, bar: Double, from: Double, to: Double, bars: Int): MutableList<DoubleArray> {
        val loops = ArrayList<DoubleArray>()
        if (side != null && side.downbeats.size >= bars + 1) {
            val beat = 60 / side.bpm
            val downs = side.downbeats
            for (start in downs) {
                if (start < from) continue
                val target = start + bars * 4 * beat
                var end = downs[0]
                for (d in downs) if (abs(d - target) < abs(end - target)) end = d
                if (abs(end - target) > beat / 4 || end > to) continue
                loops.add(doubleArrayOf(start, end))
            }
            if (loops.isNotEmpty()) return loops
        }
        if (to < from) return loops
        val len = bars * bar
        var t = anchor + ceil((from - anchor) / bar) * bar
        while (t + len <= to) {
            loops.add(doubleArrayOf(t, t + len))
            t += bar
        }
        return loops
    }

    internal class Clean {
        var start = 0.0
        var end = 0.0
        var vocal = 0.0
        var bars = 0
        var checkedBy: String? = null
    }

    internal fun firstClean(
        candidates: (Int) -> List<DoubleArray>, buffer: Audio, vocals: List<ForkV2Analysis.VocalWindow>, sung: Array<DoubleArray>?, beatSec: Double,
    ): Clean? {
        for (bars in LOOP_LENGTHS) {
            val loops = candidates(bars)
            if (loops.isEmpty()) continue
            val louds = DoubleArray(loops.size)
            for (i in louds.indices) {
                val l = loops[i]
                var mn = Double.POSITIVE_INFINITY
                for (b in 0 until bars) {
                    mn = min(mn, buffer.rms(l[0] + (b * (l[1] - l[0])) / bars, l[0] + ((b + 1) * (l[1] - l[0])) / bars))
                }
                louds[i] = mn
            }
            val sorted = louds.copyOf()
            sorted.sort()
            val at = floor(louds.size * 0.6).toInt()
            val typical = if (at < sorted.size) sorted[at] else 0.0
            for (i in loops.indices) {
                val l = loops[i]
                if (louds[i] < typical * MIN_LOUDNESS || louds[i] <= 0.01) continue
                if (sung != null) {
                    val from = l[0] - LYRIC_GUARD_SEC
                    var hit = false
                    for (x in sung) if (x[0] < l[1] && x[1] > from) { hit = true; break }
                    if (hit) continue
                    val c = Clean()
                    c.start = l[0]
                    c.end = l[1]
                    c.bars = bars
                    c.vocal = 0.0
                    c.checkedBy = "lyrics"
                    return c
                }
                val m = maskStats(vocals, l[0] - beatSec, l[1])
                if (m == null || m[0] > MAX_SUNG_SHARE || m[1] > MAX_SUNG_RUN_SEC) continue
                val c = Clean()
                c.start = l[0]
                c.end = l[1]
                c.bars = bars
                c.vocal = m[0]
                c.checkedBy = "mask"
                return c
            }
        }
        return null
    }

    /** {share sung, longest sung run s}, or null when the mask covers under 80% of [from, to). */
    internal fun maskStats(windows: List<ForkV2Analysis.VocalWindow>, from: Double, to: Double): DoubleArray? {
        var sung = 0
        var n = 0
        var run = 0
        var longest = 0
        var covered = 0.0
        var fps = 1.0
        for (w in windows) {
            val a = max(0.0, ceil((from - w.startSec) * w.fps)).toInt()
            val b = min(w.curve.size.toDouble(), floor((to - w.startSec) * w.fps)).toInt()
            for (i in a until b) {
                n++
                if (w.curve[i] > VOCAL_ACTIVE) {
                    sung++
                    run++
                    longest = max(longest, run)
                } else {
                    run = 0
                }
            }
            if (b > a) {
                covered += (b - a) / w.fps
                fps = w.fps
            }
        }
        return if (n > 0 && covered >= (to - from) * 0.8) doubleArrayOf(sung / n.toDouble(), longest / fps) else null
    }

    /** One deck tempo/position change (the extension's LoopDeck schedule entry). */
    class Change internal constructor(
        val output: Double,
        val input: Double,
        val rate: Double,
        val active: Boolean,
        val loop: DoubleArray?,
    )

    class LoopSchedule {
        var t0 = 0.0
        var beatTimes = DoubleArray(0)
        val outChanges: MutableList<Change> = ArrayList()
        val inChanges: MutableList<Change> = ArrayList()
        var releaseAt = 0.0
        var elementAt = 0.0
        var elementTrackTime = 0.0
        var enterEnd = 0.0
        var rampEnd = 0.0
        var mid = 0.0
        var bar = 0.0
        var handoffAt = 0.0
        var beats = 0
        var settleBeats = 0
        var rampFrom = 0
        var rampTo = 0
    }

    /** The whole loop mix on one beat clock from [t0] (the audio time the outgoing song reaches its loop start). */
    fun loopSchedule(plan: LoopPlan, t0: Double): LoopSchedule {
        val out = plan.out!!
        val inn = plan.`in`!!
        val fold = plan.fold
        val beats = plan.totalBars * 4
        val settleBeats = jround((SETTLE_BARS * 4) / fold).toInt()
        val rampFrom = plan.enterBars * 4
        val rampTo = (plan.enterBars + plan.rampBars) * 4
        val target = inn.bpm / fold
        val beatTimes = DoubleArray(beats + settleBeats + 1)
        beatTimes[0] = t0
        for (k in 0 until beats + settleBeats) beatTimes[k + 1] = beatTimes[k] + 60 / tempo(k, rampFrom, rampTo, out.bpm, target)
        val outLoopBeats = out.bars * 4
        val inLoopBeats = inn.bars * 4
        val inPerBeat = fold * inn.beatSec
        val x = LoopSchedule()
        val outLoop = doubleArrayOf(out.loopStart, out.loopEnd)
        val inLoop = doubleArrayOf(inn.loopStart, inn.loopEnd)
        for (k in 0 until beats) {
            val t = beatTimes[k]
            val T = tempo(k, rampFrom, rampTo, out.bpm, target)
            x.outChanges.add(Change(t, out.loopStart + (k % outLoopBeats) * out.beatSec, (T * out.beatSec) / 60, true, outLoop))
            x.inChanges.add(Change(t, inn.loopStart + ((k * fold) % inLoopBeats) * inn.beatSec, (T * inPerBeat) / 60, true, inLoop))
        }
        val releaseAt = beatTimes[beats]
        x.outChanges.add(Change(releaseAt + 0.05, out.loopStart, 1.0, false, null))
        x.inChanges.add(Change(releaseAt, inn.loopEnd, 1.0, true, null))
        val elementAt = beatTimes[beats + settleBeats]
        val elementTrackTime = inn.loopEnd + (elementAt - releaseAt)
        x.inChanges.add(Change(elementAt + 0.1, elementTrackTime + 0.1, 1.0, false, null))
        val midBeat = jround((rampFrom + rampTo) / 2.0).toInt()
        x.t0 = t0
        x.beatTimes = beatTimes
        x.releaseAt = releaseAt
        x.elementAt = elementAt
        x.elementTrackTime = elementTrackTime
        x.enterEnd = beatTimes[rampFrom]
        x.rampEnd = beatTimes[rampTo]
        x.mid = beatTimes[midBeat]
        x.bar = 4 * (60 / tempo(midBeat, rampFrom, rampTo, out.bpm, target))
        x.handoffAt = beatTimes[midBeat]
        x.beats = beats
        x.settleBeats = settleBeats
        x.rampFrom = rampFrom
        x.rampTo = rampTo
        return x
    }

    internal fun tempo(k: Int, rampFrom: Int, rampTo: Int, outBpm: Double, target: Double): Double =
        if (k < rampFrom) outBpm else if (k >= rampTo) target else outBpm + ((target - outBpm) * (k - rampFrom + 0.5)) / (rampTo - rampFrom)
}
