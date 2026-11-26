package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.rmsFromLevel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fork: the loop mix's planner for the Automix style in force. Both apps call [plan] where they
 * called upstream's [LoopMix.plan] (same arguments, same answer type), so their renderers
 * (the phone's `LoopMixSession` / `LoopMixRenderer`, the PC's `ForkLoopMixSession` /
 * `ForkLoopMixRenderer`) play whatever comes back unchanged.
 *
 * - Both styles, since the owner's baseline (2026-11-24): [LoopMix.plan] itself, the engine of the
 *   Nov 8 recording. Picks in the overview still refuse a loop through the apps' refusal rules.
 * - [decide] keeps the port's Automix v2 loop planning (version 3's gates, the loop on the blend's
 *   bars, [ForkV2Planner.loopOrBlend]) for the extension's fixtures (`ForkV2AutomixPairTest`) only.
 *
 * The port's [ForkV2Planner.LoopPlan] converts to upstream's [LoopMix.Plan] field for field
 * ([toLoopMixPlan]): the same loops, fold, enter, ramp and total bars. Upstream's [LoopMix.Clock]
 * lays them out with the same beat formula as the port's [ForkV2Planner.loopSchedule] (tempo per
 * beat, the ramp's midpoint, the release and the settle), and the renderers' curves (gain 1 to 0.6
 * to 0, -18 dB bass swap over a bar at the ramp's middle, the outgoing high-pass 20 Hz to 1.5 kHz
 * after the ramp) are the port's [ForkV2Curves.loop], so nothing is lost in the conversion.
 *
 * What differs from upstream's loop search in [decide] (the extension's, kept as ported):
 * - Each loop's loudness comes from the analysis's 10 fps level windows (the only whole-song level
 *   either app keeps), read as the extension reads its decoded buffer: no data reads as silence,
 *   so a bar outside the analysed windows is never picked ([levelAudio]).
 * - Sung spans come from the same synced lines under versions.md "Where a song is sung" ([ForkV2Sung]:
 *   a line about as long as its words take, then carved by the vocal mask), not upstream's spans (a
 *   line without an end sung up to the next one); upstream's stand in only for a song whose lines
 *   [ForkV2Sung] hasn't been given.
 */
object ForkV2Loop {

    /**
     * Upstream's [LoopMix.plan] signature, and upstream's plan under both styles: the owner's baseline
     * (research/automix-baseline-2026-11-08.md, the extension at d26dc16, which is BitChord web's
     * loopMix.ts and AutoDJ, the same engine as [LoopMix]). "No hacks to gain it on that song, the
     * automix has to get it automatically alone": the latest instrumental loop near A's end, the
     * earliest in B's opening, on the model's own downbeats, 4 enter bars, a ramp of 2.5 BPM a bar,
     * whole incoming loops. Automix v2's own loop planning ([decide]: its gates, the loop on the blend's
     * bars, the loop-or-blend score) is kept for the extension's fixtures only; the players never get it.
     */
    fun plan(
        out: TrackAnalysis,
        outDuration: Double,
        inn: TrackAnalysis,
        inDuration: Double,
        earliestOutSec: Double,
        sungOut: List<LoopMix.Span>? = null,
        sungIn: List<LoopMix.Span>? = null,
        outVideo: Boolean = false,
        inVideo: Boolean = false,
        why: StringBuilder? = null,
    ): LoopMix.Plan? {
        return LoopMix.plan(out, outDuration, inn, inDuration, earliestOutSec, sungOut, sungIn, outVideo, inVideo, why)
    }

    /** What [decide] found: the loop mix to play, or null and why the blend plays. */
    class Decision(val plan: LoopMix.Plan?, val loop: ForkV2Planner.LoopPlan?, val choice: ForkV2Planner.Choice?, val why: String)

    /**
     * The loop-or-blend choice on the port's own types, as the extension's fixtures plan a pair
     * (scripts/automix_fixtures.mjs): the loop only when both grids pass, version 3's gates, then
     * [ForkV2Planner.loopOrBlend] against [blend] (a Loop pick: [ForkV2Planner.Choice.picked]).
     */
    fun decide(
        from: ForkV2Analysis,
        outAudio: ForkV2Planner.Audio,
        to: ForkV2Analysis,
        inAudio: ForkV2Planner.Audio,
        earliestOutSec: Double,
        sung: ForkV2Planner.Sung?,
        blend: ForkV2Planner.Plan?,
        picked: Boolean,
    ): Decision {
        val cur = ForkV2Planner.asOutgoing(from)
        if (!ForkV2Planner.gridOk(cur) || !ForkV2Planner.gridOk(to)) return Decision(null, null, null, "beat grid too weak")
        val lp = ForkV2Planner.planLoopMix(3, cur, outAudio, to, inAudio, earliestOutSec, sung)
            ?: return Decision(null, null, null, "no grid for a loop mix")
        lp.none?.let { return Decision(null, lp, null, it) }
        val choice = if (picked) ForkV2Planner.Choice.picked(true) else ForkV2Planner.loopOrBlend(3, blend, lp)
        if (!choice.loop) return Decision(null, lp, choice, "Automix v2 plays the blend: " + (choice.why ?: "it scored better"))
        return Decision(toLoopMixPlan(lp), lp, choice, choice.why ?: "loop")
    }

    /** The port's loop plan as upstream's, which both apps' loop renderers play. */
    fun toLoopMixPlan(lp: ForkV2Planner.LoopPlan): LoopMix.Plan? {
        val o = lp.out ?: return null
        val i = lp.`in` ?: return null
        if (lp.none != null) return null
        return LoopMix.Plan(
            out = pick(o),
            inn = pick(i),
            fold = lp.fold,
            enterBars = lp.enterBars,
            rampBars = lp.rampBars,
            exitBars = lp.totalBars - lp.enterBars - lp.rampBars,
            totalBars = lp.totalBars,
        )
    }

    private fun pick(s: ForkV2Planner.LoopSide) = LoopMix.Pick(
        loopStart = s.loopStart,
        loopEnd = s.loopEnd,
        bars = s.bars,
        beatSec = s.beatSec,
        bpm = s.bpm,
        vocal = s.vocal,
        checkedBy = if (s.checkedBy == "lyrics") LoopMix.Check.LYRICS else LoopMix.Check.MASK,
    )

    /** Upstream's lyric spans as the port's (null: no synced lyrics, the vocal mask decides). */
    fun sung(out: List<LoopMix.Span>?, inn: List<LoopMix.Span>?): ForkV2Planner.Sung {
        val s = ForkV2Planner.Sung()
        s.out = out?.let { l -> Array(l.size) { doubleArrayOf(l[it].start, l[it].end) } }
        s.`in` = inn?.let { l -> Array(l.size) { doubleArrayOf(l[it].start, l[it].end) } }
        return s
    }

    /**
     * A song's loudness for the loop search from its level windows (10 fps, 0..1 over -60..0 dB):
     * the RMS of the frames in [from, to), 0 where no window reaches (the extension's buffer reads 0
     * past its decoded audio, and a loop there fails its loudness floor). [durationSec] stands for
     * the decoded length.
     */
    fun levelAudio(windows: List<CurveWindow>, durationSec: Double): ForkV2Planner.Audio = object : ForkV2Planner.Audio {
        override fun duration(): Double = durationSec

        override fun rms(from: Double, to: Double): Double {
            var sum = 0.0
            var n = 0
            for (w in windows) {
                val a = max(0, ceil((from - w.startTime) * w.frameRate).toInt())
                val b = min(w.values.size, floor((to - w.startTime) * w.frameRate).toInt())
                for (k in a until b) {
                    val r = rmsFromLevel(w.values[k].toDouble())
                    sum += r * r
                    n++
                }
            }
            return if (n > 0) sqrt(sum / n) else 0.0
        }
    }
}
