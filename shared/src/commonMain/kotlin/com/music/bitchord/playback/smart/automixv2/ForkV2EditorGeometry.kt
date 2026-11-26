package com.music.bitchord.playback.smart.automixv2

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * The transition editor's arithmetic (spec/automix/editor.md §2, §6, §7), kept apart from the
 * Compose code so both apps' editor (sharedUi `ForkTransitionEditor`) and the tests read one copy.
 *
 * Ported from the extension (src/automix/automix.js `windowLimits` and `editorGrid`, ~:434-451;
 * src/automix-overview.js `positionOf`, `limitsOf`, `dragMove`, `windowWith`, `nudge`, `pick`,
 * `autoName`, `describe`, `readout`) and the phone patches (Planner.java `windowLimits`,
 * MixOverview.java). The fork had the override model and the planner's clamps
 * ([ForkV2MixOverride], [ForkV2Planner.clampWindow]) but not the limits a drag is held at, which
 * the planner never needed: it clamps a saved window when it plans, while the editor has to stop a
 * drag at the same place *before* it is saved, and say why ("held: …").
 */
object ForkV2EditorGeometry {

    /** One nudge, and how far nudges reach (override.schema.json nudgeMs). */
    const val NUDGE_MS = 10
    const val NUDGE_MAX_MS = 2000

    /** The phone's pinch zoom stops at 8x (MixOverview.java MAX_ZOOM). */
    const val MAX_ZOOM = 8.0

    /** A hold this long before the drag moves snaps to beats instead of downbeats (MixOverview.java LONG_PRESS_MS). */
    const val LONG_PRESS_MS = 400L

    /**
     * Shown after "held:" when a drag runs into a limit, low side first (automix-overview.js LIMIT_WHY,
     * word for word).
     */
    val LIMIT_WHY_OUT = arrayOf("A has already played up to there", "the window has to end before A does")
    val LIMIT_WHY_IN = arrayOf("that is B's start", "the window has to end before B does")

    /** The presets in the order the chip row shows them, with their names (editor.md §2; Echo out is reserved, §5). */
    val PRESET_NAMES = linkedMapOf(
        "auto" to "Auto", "custom" to "Custom", "smooth" to "Fade", "rise" to "Rise", "wave" to "Wave", "blend" to "Blend",
        "slam" to "Slam", "melt" to "Melt", "afterglow" to "Afterglow", "surge" to "Surge", "dissolve" to "Dissolve",
        "none" to "No transition", "loop" to "Loop",
    )

    /** The lanes' shapes as the extension's dropdowns name them (automix-overview.js LANE_OPTIONS). */
    /** The dropdowns' names: Spotify's, as its editor shows them (editor.md §2, spotify-mixes.md rule 4). */
    val SHAPE_NAMES = mapOf(
        "smooth" to "Smooth crossfade", "crossfade" to "Crossfade", "overlap" to "Overlap", "cutInFadeOut" to "Cut in fade out",
        "fadeInCutOut" to "Fade in cut out", "centerCut" to "Center cut", "fadeInFadeOut" to "Fade in fade out",
        "fadeInFastOut" to "Fade in fast out", "fadeIn1BarCutOut" to "Fade in 1 bar cut out", "overlap1BarCutOut" to "Overlap 1 bar cut out",
        "centerBassSwap" to "Center bass swap", "endBassSwap" to "End bass swap", "startBassSwap" to "Start bass swap",
        "threeBandFade" to "3-band fade", "quickBassCut" to "Quick bass cut", "longBassCut" to "Long bass cut", "bassFadeOut" to "Bass fade out", "none" to "None",
        "lpOut" to "Low pass filter out", "lpIn" to "Low pass filter in", "hpOut" to "High pass filter out",
        "hpIn" to "High pass filter in", "lpBoth" to "LP in LP out", "hpBoth" to "HP in HP out",
        "lpInHpOut" to "LP in HP out", "hpInLpOut" to "HP in LP out", "hpHalfOut" to "High-pass filter half out",
        "noiseOutEnd" to "Noise out end",
        "echo1CutEnd" to "Echo 1 cut end", "echo34CutEnd" to "Echo ¾ cut end", "echoHalfCutEnd" to "Echo ½ cut end",
        "echo1OutEnd" to "Echo 1 out end", "echo34OutEnd" to "Echo ¾ out end", "echoHalfOutEnd" to "Echo ½ out end",
        "delay34CutEnd" to "Delay ¾ cut end", "delayHalfCutEnd" to "Delay ½ cut end", "reverbCutEnd" to "Reverb cut end",
        "reverbOutEnd" to "Reverb out end", "reverbOutCenter" to "Reverb out center",
        "loop1" to "1 beat loop", "loop2" to "2 beat loop", "loop4" to "4 beat loop", "loop8" to "8 beat loop",
        "loop16" to "16 beat loop", ForkV2MixOverride.DRAW to "Draw…",
    )

    // ---------------------------------------------------------------------------------------------
    // the limits and the grids (automix.js windowLimits / editorGrid)

    /** The window's limits in song seconds: A's mix-out within [outLo, outHi], B's mix-in within [inLo, inHi]. */
    class Limits(val outLo: Double, val outHi: Double, val inLo: Double, val inHi: Double) {
        fun lo(out: Boolean) = if (out) outLo else inLo
        fun hi(out: Boolean) = if (out) outHi else inHi
    }

    /**
     * The window's limits as the planner clamps them (editor.md §6), in song seconds: A's mix-out from
     * the earliest it can still start (with the tempo ramp before a blend) to where the window ends 0.3 s
     * before A does; B's mix-in from 0 to where the window ends 0.3 s before B does. [from]: A's
     * analysis with the playing length as its duration; [earliest]: the playhead plus the hand-over
     * lead (what ForkV2Automix passes the planner).
     */
    fun windowLimits(m: ForkV2Planner.Plan?, from: ForkV2Analysis?, to: ForkV2Analysis?, earliest: Double): Limits {
        val blend = m != null && m.style == "blend" && m.beatSec > 0 && m.bars > 0
        val outSpan = if (blend) m!!.bars * 4 * m.beatSec else (m?.durationSec?.takeIf { it > 0 } ?: 0.0)
        val inSpan = if (blend) {
            val g = m!!.glide
            outSpan * (if (g != null && g.q > 0) 1 / g.q else (if (m.rate > 0) m.rate else 1.0) / (if (m.outRate > 0) m.outRate else 1.0))
        } else {
            outSpan
        }
        val dur = from?.durationSec?.takeIf { it > 0 } ?: 0.0
        val lo = max(0.0, if (earliest > 0) earliest else 0.0) + (if (blend && m!!.rampSongSec > 0) m.rampSongSec else 0.0)
        // A's end is its length − 0.3, not its music end: a placed window may run through A's fading outro (editor.md §6,
        // ruled 2026-11-26 [all], on Spotify's Lush Life → Lean On)
        val hi = dur - 0.3 - outSpan
        val toDur = to?.durationSec?.takeIf { it > 0 } ?: 0.0
        return Limits(lo, max(lo, hi), 0.0, max(0.0, toDur - 0.3 - inSpan)) // B's window ends 0.3 s before B does (§6, 2026-11-26; was 10 s)
    }

    /** A song's grid as the editor snaps to it. */
    class EditorGrid(val beatSec: Double, val downbeatSec: Double, val bpm: Double)

    /** The playing song's end grid ([out]: its tail grid), the next song's opening grid; null: no grid. */
    fun editorGrid(a: ForkV2Analysis?, out: Boolean): EditorGrid? {
        if (a == null) return null
        val g = ForkV2Planner.gridOf(if (out) ForkV2Planner.asOutgoing(a) else a) ?: return null
        return EditorGrid(g.beatSec, g.downbeatSec, g.bpm)
    }

    /** Song seconds as a saved position on [grid] (snapped to downbeats, or beats); no grid: bar 0 and the seconds. */
    fun positionOf(sec: Double, grid: EditorGrid?, snap: Boolean, beats: Boolean): ForkV2MixOverride.Position =
        if (grid == null) ForkV2MixOverride.Position(0, 0, 0, round(sec * 1e3) / 1e3)
        else ForkV2MixOverride.positionOf(sec, grid.downbeatSec, grid.beatSec, snap, beats)

    // ---------------------------------------------------------------------------------------------
    // a drag, a nudge, the window saved (automix-overview.js dragMove, nudge, windowWith)

    /** Where a drag puts a song: the position it would save and, when held at a limit, why. */
    class DragAt(val pos: ForkV2MixOverride.Position, val limit: String?)

    /**
     * A drag that wants song second [want] for one side ([out]: A's mix-out): held inside
     * [lo, hi], snapped to [grid] (downbeats, [beats]: beats; not at all when [snap] is false, beat
     * match Off), and a snap that lands past a limit moved one step back inside it.
     */
    fun dragTo(want: Double, lo: Double, hi: Double, grid: EditorGrid?, snap: Boolean, beats: Boolean, out: Boolean): DragAt {
        val sec = max(lo, min(want, hi))
        var pos = positionOf(sec, grid, snap, beats)
        if (snap && grid != null && (pos.sec < lo - 1e-6 || pos.sec > hi + 1e-6)) {
            val step = grid.beatSec * (if (beats) 1 else 4)
            val inside = positionOf(pos.sec + (if (pos.sec < lo) step else -step), grid, snap, beats)
            if (inside.sec >= lo - 1e-6 && inside.sec <= hi + 1e-6) pos = inside
        }
        val why = if (out) LIMIT_WHY_OUT else LIMIT_WHY_IN
        val limit = if (want < lo - 0.01) why[0] else if (want > hi + 0.01) why[1] else null
        return DragAt(pos, limit)
    }

    /** The song second a drag wants: the song moves with the pointer, so what was left of the frame comes under it. */
    fun dragWant(base: Double, dxPx: Double, widthPx: Double, spanSec: Double): Double =
        if (widthPx > 0) base - (dxPx / widthPx) * spanSec else base

    /**
     * [ov]'s window with [side] set to [pos] ([out]: A's mix-out). The other side is saved too, where
     * the plan has it now ([planOut], [planIn] song seconds), unsnapped: with one side saved the
     * planner would still move the other (B's skip cap, the vocal re-route), so B's mix-in could jump
     * bars under a 10 ms nudge of A (editor.md §6). Returns (out, in).
     */
    fun windowWith(
        ov: ForkV2MixOverride?, out: Boolean, pos: ForkV2MixOverride.Position,
        planOut: Double, planIn: Double, gridOut: EditorGrid?, gridIn: EditorGrid?,
    ): Pair<ForkV2MixOverride.Position, ForkV2MixOverride.Position> {
        val o = if (out) pos else ov?.out ?: positionOf(planOut, gridOut, false, false)
        // (moving the mix-in keeps B's late entry: mix-file.md rule 2)
        val i = if (!out) pos.also { if (it.delaySec == 0.0) it.delaySec = ov?.`in`?.delaySec ?: 0.0 } else ov?.`in` ?: positionOf(planIn, gridIn, false, false)
        return o to i
    }

    /**
     * [now] nudged by one step ([dir] -1 or 1): its nudge and its seconds move 10 ms; null past
     * [NUDGE_MAX_MS] (the editor says "Nudges reach 2 s at most: drag the song instead").
     */
    fun nudged(now: ForkV2MixOverride.Position, dir: Int): ForkV2MixOverride.Position? {
        val n = now.nudgeMs + dir * NUDGE_MS
        if (abs(n) > NUDGE_MAX_MS) return null
        return ForkV2MixOverride.Position(now.bar, now.beat, n, round((now.sec + dir * NUDGE_MS / 1000.0) * 1000) / 1000, now.delaySec)
    }

    /** A nudge's outcome: the position to save, or the line the editor shows instead ([NUDGE_REFUSED], "Held: …"). */
    class Nudge(val pos: ForkV2MixOverride.Position?, val refused: String?)

    /**
     * [nudged], held at the window's limits as a drag is (editor.md §6; automix.js nudgeWindow): a step that would take
     * the position below [lo] or past [hi] is refused with "Held: " and that side's reason ([out]: A's mix-out), a step
     * back towards them from outside is allowed. Without this the editor saved a 10 ms step past a limit (B's mix-in at
     * -0.01 s, A's past its music's end), which the planner then clamped back, so the nudge saved a position that never
     * played (audit 2026-11-26 part 1a #2).
     */
    fun nudge(now: ForkV2MixOverride.Position, dir: Int, lo: Double, hi: Double, out: Boolean): Nudge {
        val p = nudged(now, dir) ?: return Nudge(null, NUDGE_REFUSED)
        val why = if (out) LIMIT_WHY_OUT else LIMIT_WHY_IN
        if (dir < 0 && p.sec < lo - 1e-6) return Nudge(null, "Held: " + why[0])
        if (dir > 0 && p.sec > hi + 1e-6) return Nudge(null, "Held: " + why[1])
        return Nudge(p, null)
    }

    /**
     * The window with B's late entry set to [sec] (mix-file.md rule 2, the editor's "B in after N s"; 0 clears it;
     * automix-overview.js setLate): the mix-in as saved, or where the plan has it, with its delay, 0 to 60 s to 1 ms;
     * null when that is the delay already.
     */
    fun withLate(
        ov: ForkV2MixOverride?, sec: Double, planOut: Double, planIn: Double, gridOut: EditorGrid?, gridIn: EditorGrid?,
    ): Pair<ForkV2MixOverride.Position, ForkV2MixOverride.Position>? {
        if (!sec.isFinite()) return null
        val now = ov?.`in` ?: positionOf(planIn, gridIn, false, false)
        val want = round(min(max(sec, 0.0), 60.0) * 1000) / 1000
        if (want == now.delaySec) return null
        val o = ov?.out ?: positionOf(planOut, gridOut, false, false)
        return o to ForkV2MixOverride.Position(now.bar, now.beat, now.nudgeMs, now.sec, want)
    }

    const val NUDGE_REFUSED = "Nudges reach 2 s at most: drag the song instead"

    // ---------------------------------------------------------------------------------------------
    // an edit (automix-overview.js pick)

    /** The lanes a first change starts from: the custom ones, else the preset's, else Smooth's (lanesNow). */
    fun lanesNow(ov: ForkV2MixOverride?): Array<String> {
        val l = if (ov?.preset == "custom") ov.lanes() else ForkV2MixOverride.lanesOf(ov?.preset)
        val s = ForkV2MixOverride.lanesOf("smooth")!!
        return Array(ForkV2MixOverride.LANES.size) { k -> l?.get(k) ?: s[k]!! }
    }

    /**
     * [current] with [change] applied, as saved: picking a preset ([presetPicked]) drops a migrated
     * pick's family; lanes only with Custom; Loop has no length, window or beat match; Auto fields
     * go, and null when nothing but Auto is left (then the pair's override is deleted: Auto is the
     * reset). The lock, the engine and savedAt carry over.
     */
    fun edited(current: ForkV2MixOverride?, presetPicked: Boolean, change: ForkV2MixOverride.() -> Unit): ForkV2MixOverride? {
        val n = current?.copy() ?: ForkV2MixOverride()
        n.change()
        if (presetPicked) n.family = null
        if (n.preset != "custom") {
            n.volume = null
            n.eq = null
            n.effect = null
            n.fx = null
            n.looping = null
            n.drawings.clear()
        }
        if (n.loop()) {
            n.bars = 0
            n.out = null
            n.`in` = null
            n.beatMatch = null
        }
        n.migrated = false
        return ForkV2MixOverride.normalize(n)
    }

    /** Whether [ov] is an edit the "Custom" badge marks: any override (the phone's rule; editor.md §2 "an edited pair"). */
    fun badge(ov: ForkV2MixOverride?): Boolean = ov != null

    // ---------------------------------------------------------------------------------------------
    // what the header and the note say (editor.md §7; automix-overview.js autoName, describe, readout)

    /** "Auto · <what the planner chose>" (editor.md §7). [loop]: the loop mix is what plays. */
    fun autoName(m: ForkV2Planner.Plan?, loop: Boolean): String = when {
        loop -> "Auto · Loop"
        m == null -> "Auto"
        m.style == "loop" -> "Auto · Loop"
        m.style == "filter" -> "Auto · Filter"
        m.style == "crossfade" -> if (m.handoff) "Auto · Hand-over" else if (m.bridge) "Auto · Bridge" else "Auto · Fade"
        m.shape == "dropin" -> "Auto · Drop-in"
        m.shape == "muffle" -> "Auto · Muffle"
        m.shape == "echo" -> "Auto · Echo out"
        m.glide != null -> "Auto · Glide"
        else -> "Auto · Blend"
    }

    /** The preset's name on screen: Auto's with what the planner chose, else the preset's. */
    fun presetName(ov: ForkV2MixOverride?, m: ForkV2Planner.Plan?, loop: Boolean): String {
        val p = ov?.preset ?: "auto"
        return if (p == "auto") autoName(m, loop) else PRESET_NAMES[p] ?: p
    }

    /**
     * The header: what the plan is, not which preset asked for it (editor.md §7's table, spec
     * 4d14863; automix-overview.js describe, word for word). A crossfade names its way and gives the
     * planner's reason; anything else its kind, bars, tempo and seconds.
     */
    fun headline(m: ForkV2Planner.Plan): String {
        val d = forkV2Format("%.1f s", m.durationSec)
        if (m.style == "crossfade") {
            val kind = if (m.bridge) "Beatless bridge" else if (m.handoff) "Hand-over" else "Fade"
            return "$kind · $d" + (m.reason?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
        }
        // as the extension: "out→in" when the incoming tempo rounds apart from the plan's own
        val bpm = if (m.inBpm > 0 && jround(m.inBpm) != jround(m.bpm)) {
            "${jround(if (m.outBpm > 0) m.outBpm else m.bpm)}→${jround(m.inBpm)} BPM"
        } else {
            "${jround(m.bpm)} BPM"
        }
        val kind = when {
            m.style == "filter" -> "Filter mix"
            m.shape == "dropin" -> "Drop-in"
            m.shape == "muffle" -> "Muffle"
            m.shape == "echo" -> "Echo out"
            m.glide != null -> "Glide blend"
            else -> "Blend"
        }
        return "$kind · ${m.bars} bars · $bpm · $d"
    }

    /** The header of a loop mix (editor.md §7): "Loop mix · {totalBars} bars · {A BPM}→{B BPM} BPM · {D} s". */
    fun loopHeadline(p: com.music.bitchord.playback.smart.LoopMix.Plan): String =
        "Loop mix · ${p.totalBars} bars · ${jround(p.out.bpm)}→${jround(p.inn.bpm)} BPM · " +
            forkV2Format("%.1f s", com.music.bitchord.playback.smart.LoopMix.Clock(p).releaseAt)

    /** "3:12.4" (automix-overview.js fmtSec). */
    fun fmtSec(t0: Double): String {
        val t = max(0.0, t0)
        val m = floor(t / 60).toInt()
        val s = t - m * 60
        val tenths = jround(s * 10)
        // 59.96 s rounds to 60.0: the next minute, as the extension's toFixed would not, but readable
        return if (tenths >= 600) "${m + 1}:00.0" else "$m:${if (tenths < 100) "0" else ""}${tenths / 10}.${tenths % 10}"
    }

    /** "1:05": whole seconds (automix-overview.js fmt). */
    fun clock(t: Double): String {
        val s = max(0L, jround(t))
        val r = s % 60
        return "${s / 60}:${if (r < 10) "0" else ""}$r"
    }

    /** "A mixes out at 3:12.4 (bar 81) · B enters at 0:32.4 (bar 17)": bars count from 1 on screen. */
    fun readout(out: ForkV2MixOverride.Position, `in`: ForkV2MixOverride.Position): String =
        "A mixes out at ${fmtSec(out.sec)}${barNo(out)} · B enters at ${fmtSec(`in`.sec)}${barNo(`in`)}" +
            (if (`in`.delaySec > 0) ", ${forkV2Js(`in`.delaySec)} s into the mix" else "") // B's late entry (mix-file.md rule 2)

    /** " (bar 81)"; nothing for a position in seconds only, until it is moved (editor.md §6 "Seconds only"). */
    private fun barNo(p: ForkV2MixOverride.Position): String = p.bar?.let { " (bar ${it + 1})" } ?: ""

    /** The mixing clock under the overview: "Mix in 1:05", "Mixing · 0:04 / 0:15", or nothing once it's over. [at]: mix seconds. */
    fun mixClock(at: Double, D: Double): String = when {
        at.isNaN() -> ""
        at < 0 -> "Mix in ${clock(-at)}"
        at <= D -> "Mixing · ${clock(at)} / ${clock(D)}"
        else -> ""
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * A curve's value at [t] (plan.schema.json): held before the first point and after the last,
     * linear between points, geometric for Hz ([geo]); [neutral] when there is no curve.
     */
    fun curveAt(pts: Array<DoubleArray>?, t: Double, geo: Boolean, neutral: Double): Double {
        if (pts == null || pts.isEmpty()) return neutral
        if (t <= pts[0][0]) return pts[0][1]
        for (i in 1 until pts.size) {
            if (t <= pts[i][0]) {
                val a = pts[i - 1]
                val b = pts[i]
                val f = (t - a[0]) / max(1e-6, b[0] - a[0])
                return if (geo && a[1] > 0 && b[1] > 0) a[1] * (b[1] / a[1]).pow(f) else a[1] + (b[1] - a[1]) * f
            }
        }
        return pts[pts.size - 1][1]
    }

    /**
     * The song second a deck plays at mix second [tm] (0 the window's start, [D] its end), for the overview's waveforms;
     * NaN for B before its late entry ([lateSec], mix-file.md rule 2: silent, not drawn). [base] is the deck's mix-out or
     * mix-in (a dragged one while dragging).
     *
     * Inside the window each deck runs at the plan's rate for any style: the planner sets `outRate`/`rate` to the songs'
     * playlist speeds for a crossfade or filter mix too (spotify-mixes.md rule 10), while only a blend read them here, so a
     * filter mix of songs at x1.25 drew both at x1 and the waveforms slid off what plays. A blend's glide steps its rate
     * every beat: A is drawn at x1 and B at its mean rate over the window, as before. Outside the window each song is at
     * its own speed (A at `speedOut` before, B at `speedIn` after: a glide back ends there, not at x1), as the extension's
     * automix-overview.js `songTimes` maps it; A after the window and B before it are drawn greyed at that speed too.
     */
    fun songAt(out: Boolean, tm: Double, D: Double, base: Double, lateSec: Double, plan: ForkV2Planner.Plan): Double {
        val glide = if (plan.style == "blend") plan.glide else null
        val speed = if (out) plan.speedOut else plan.speedIn
        val own = if (speed > 0) speed else 1.0
        if (out) {
            val rate = if (glide == null && plan.outRate > 0) plan.outRate else 1.0
            return when {
                tm < 0 -> base + tm * own
                tm <= D -> base + tm * rate
                else -> base + D * rate + (tm - D) * own
            }
        }
        val rate = when {
            glide != null && glide.q > 0 && plan.beatSec > 0 -> plan.bars * 4 * plan.beatSec / glide.q / D
            plan.rate > 0 -> plan.rate
            else -> 1.0
        }
        val tl = tm - lateSec
        return when {
            lateSec > 0 && tl < 0 -> Double.NaN
            tl < 0 -> base + tl * own
            tm <= D -> base + tl * rate
            else -> base + (D - lateSec) * rate + (tm - D) * own
        }
    }

    /** dB as a lane's height, 0..1: 0 dB at the top, [ForkV2Planner.BASS_CUT_DB] (−18) at the bottom. */
    fun dbHeight(db: Double): Double = 1 - min(1.0, max(0.0, db / ForkV2Planner.BASS_CUT_DB))

    /** A low-pass's opening as a lane's height (20 Hz at the bottom, 20 kHz at the top, log). */
    fun lowpassHeight(hz: Double): Double = ln(max(20.0, hz) / 20) / ln(1000.0)

    /** A high-pass's opening: the other way up (fully open, 20 Hz, at the top). */
    fun highpassHeight(hz: Double): Double = 1 - lowpassHeight(hz)
}
