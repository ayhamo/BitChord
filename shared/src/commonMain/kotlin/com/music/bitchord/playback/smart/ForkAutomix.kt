package com.music.bitchord.playback.smart

/**
 * Fork: Automix fixes found by comparing the phone with upstream's web app (2026-11-03), whose
 * mixes the owner heard as far better on the same songs. The web engine (`web/app/src/playback/
 * automix/`) keeps a song's opening and ending grids apart and refuses to loop on a weak grid;
 * the phone did neither.
 *
 * An analysis states one tempo ([TrackAnalysis.bpm], [TrackAnalysis.beatInterval],
 * [TrackAnalysis.beatConfidence]), taken from the song's *ending* (the tail grid "takes
 * precedence", `TrackAnalyzer`), which is right for the outgoing side of a mix and wrong for the
 * incoming one: a song that speeds up, slows down or changes feel was beatmatched into from the
 * tempo it ends on. [TrackAnalysis.headBeats] and [TrackAnalysis.tailBeats] hold both grids
 * already; these pick the one for the side a song is on.
 */
object ForkAutomix {

    /**
     * Below this grid confidence the web won't loop a song (`autodj.ts` `MIN_GRID_CONFIDENCE`).
     * On the phone the model's grids rarely score under 0.35 once they have enough beats, and the
     * head and tail share one averaged score (`seamBeats`), so in practice this turns away an
     * empty or DSP-only grid, not a merely uncertain one.
     */
    const val MIN_LOOP_CONFIDENCE = 0.2

    /** The song as the incoming side of a mix sees it: its opening's tempo (and that side's confidence). */
    fun incoming(analysis: TrackAnalysis): TrackAnalysis = analysis.withGrid(analysis.headBeats)

    /** The song as the outgoing side sees it: its ending's tempo and confidence. */
    fun outgoing(analysis: TrackAnalysis): TrackAnalysis = analysis.withGrid(analysis.tailBeats)

    private fun TrackAnalysis.withGrid(side: BeatSide?): TrackAnalysis {
        if (side == null || !(side.bpm > 0) || !side.bpm.isFinite()) return this
        if (side.bpm == bpm && side.confidence == beatConfidence) return this
        return copy(bpm = side.bpm, beatInterval = 60 / side.bpm, beatConfidence = side.confidence)
    }

    /** How sure the grid on [side] of [analysis] is: that side's own, or the analysis's one figure without it. */
    fun confidence(analysis: TrackAnalysis, side: BeatSide?): Double =
        side?.confidence?.takeIf { it.isFinite() } ?: analysis.beatConfidence

    /**
     * The fallback grid's beat length and bar line for [side], when the model found too few
     * downbeats there to loop on its own: that side's tempo, and the downbeat nearest [near] (where
     * the loop is looked for), so the bars are counted from that stretch of the song and not
     * minutes away from it with another stretch's tempo. Null without a usable tempo.
     */
    fun grid(analysis: TrackAnalysis, side: BeatSide?, near: Double): Pair<Double, Double>? {
        val beat = side?.bpm?.takeIf { it.isFinite() && it > 0 }?.let { 60 / it }
            ?: analysis.beatInterval.takeIf { it.isFinite() && it > 0 }
            ?: analysis.bpm.takeIf { it.isFinite() && it > 0 }?.let { 60 / it }
            ?: return null
        val downs = side?.downbeats?.takeIf { it.isNotEmpty() } ?: analysis.downbeats
        val anchor = downs.filter { it.isFinite() }.minByOrNull { kotlin.math.abs(it - near) } ?: analysis.firstBeat
        if (!anchor.isFinite()) return null
        return beat to anchor
    }

    class Gains(val outTimes: DoubleArray, val outGains: FloatArray, val inTimes: DoubleArray, val inGains: FloatArray)

    /**
     * The loop mix's two gain curves (piecewise linear, holding their end values), with both scaled by
     * `1 / sqrt(out + in)` wherever they add up past 1.
     *
     * The renderer sums both songs into one stream, and its curves overlap at up to 1.6 (the outgoing
     * deck 1 to 0.6 while the incoming loop rises 0.55 to 1). On loud masters that went well past full
     * scale; a 16-bit output clipped it, heard as crackling during Automix. The limiter now runs for
     * the mix too (`DspChain.forkMixing`), but holding 4 dB down for bars at a time is the "pumping and
     * grit" upstream measured on blends, so this is their blend's trim: about 1.9 dB down where the two
     * overlap most (a sum of 1.55 to 1.6), none where they don't, and the limiter catches what is left.
     */
    fun trimmedGains(outTimes: DoubleArray, outGains: FloatArray, inTimes: DoubleArray, inGains: FloatArray): Gains {
        val times = (outTimes.asList() + inTimes.asList()).filter { it.isFinite() }.distinct().sorted().toDoubleArray()
        val out = FloatArray(times.size)
        val inn = FloatArray(times.size)
        for ((k, t) in times.withIndex()) {
            val o = valueAt(outTimes, outGains, t)
            val i = valueAt(inTimes, inGains, t)
            val sum = o + i
            val trim = if (sum > 1.0) 1.0 / kotlin.math.sqrt(sum) else 1.0
            out[k] = (o * trim).toFloat()
            inn[k] = (i * trim).toFloat()
        }
        return Gains(times, out, times.copyOf(), inn)
    }

    /**
     * A loop mix's two gain curves, on the mix clock (0 = the incoming loop's entry), as the owner's baseline has
     * them (research/automix-baseline-2026-11-08.md, the extension at d26dc16, BitChord web's loopMix.ts): the
     * outgoing deck takes over from its song over [handoverSec] before 0, holds 1 through the enter bars, falls to
     * 0.6 over the ramp and to 0 at the release; the incoming one rises 0 to 0.55 over the enter bars and to 1 over
     * the ramp. No -3 dB trim, no equal-power scaling ([trimmedGains]): the baseline has neither.
     */
    fun loopGains(enterEnd: Double, rampEnd: Double, releaseAt: Double, handoverSec: Double): Gains = Gains(
        doubleArrayOf(-handoverSec, 0.0, enterEnd, rampEnd, releaseAt), floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
        doubleArrayOf(0.0, enterEnd, rampEnd), floatArrayOf(0f, 0.55f, 1f),
    )

    /** A loop mix's bass swap: each deck's 250 Hz low-shelf gain (dB) at its knots. */
    class BassSwap(val times: DoubleArray, val outDb: FloatArray, val inDb: FloatArray)

    /**
     * The bass swap that goes with rule 7 (the extension's study keeps the -3 dB only with it): a
     * [LoopMix.BASS_CUT_DB] low shelf at 250 Hz on both decks, swapped over one [bar] centred on the
     * ramp's middle ([handoffAt]). The incoming loop enters cut and the outgoing one ends cut, so the
     * two beatmatched kicks never sum in phase. Both apps' renderers read it.
     */
    fun loopBassSwap(handoffAt: Double, bar: Double): BassSwap {
        val cut = LoopMix.BASS_CUT_DB.toFloat()
        return BassSwap(doubleArrayOf(handoffAt - bar / 2, handoffAt + bar / 2), floatArrayOf(0f, cut), floatArrayOf(cut, 0f))
    }

    private fun valueAt(times: DoubleArray, values: FloatArray, t: Double): Double {
        if (times.isEmpty()) return 0.0
        if (t <= times.first()) return values.first().toDouble()
        if (t >= times.last()) return values.last().toDouble()
        val k = (1 until times.size).first { times[it] >= t }
        val span = times[k] - times[k - 1]
        if (span <= 0) return values[k].toDouble()
        return values[k - 1] + (values[k] - values[k - 1]) * (t - times[k - 1]) / span
    }

    /**
     * Whether [analysis] has the beat model's grid, not only the DSP fallback's. Every analysis is
     * saved again (upstream's rule); a saved one without the model's grid is measured once more
     * instead of restored (`ForkAnalysisRedo` on the phone), then kept.
     */
    fun worthStoring(analysis: TrackAnalysis): Boolean = analysis.headBeats != null || analysis.tailBeats != null
}
