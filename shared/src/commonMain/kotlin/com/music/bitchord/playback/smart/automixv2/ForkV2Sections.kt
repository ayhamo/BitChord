package com.music.bitchord.playback.smart.automixv2

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fork: a song's intro end and outro start the PC's way (the extension's analysis.worker.js, its `fi` and `pi`:
 * the song's RMS over 4-bar windows, normalised to the loudest; the intro ends at the first two windows in a
 * row at 0.6 of it or more, the outro starts after the last two; the intro at most a third in, the outro at
 * least halfway). Master, 2026-11-25: BitChord's Classic mix-in sat a median 32 beats from Spotify's against
 * the PC's 16 with the same planner; the planner matched the PC's on all 339 bench pairs, and the input that
 * differed was the intro (BitChord's came from upstream's DSP analyser, a median 12.9 s off the PC's).
 *
 * The evidence keeps the song's mean square every [BIN_SEC] ([power], measured with the rest of the evidence);
 * the windows are summed from it once the beat grid is known ([introOutro], in [ForkV2Automix]'s merge).
 */
object ForkV2Sections {

    /** The evidence's power bins (s); a window's edge is off the PC's by at most half of one. */
    const val BIN_SEC = 0.05

    /** The PC's threshold and window (4 bars of 4 beats; 4 s with no grid). */
    private const val LOUD = 0.6
    private const val BEATS = 16
    private const val NO_GRID_SEC = 4.0

    /** Mean square of [mono] every [BIN_SEC] (by whole samples) from the start; the last bin may be short. */
    fun power(mono: FloatArray, sr: Double): ForkV2Analysis.Curve? {
        if (mono.isEmpty() || !(sr > 0)) return null
        val n = max(1, kotlin.math.round(BIN_SEC * sr).toInt())
        val bins = (mono.size + n - 1) / n
        val v = DoubleArray(bins)
        for (b in 0 until bins) {
            val from = b * n
            val to = min(mono.size, from + n)
            var s = 0.0
            for (i in from until to) s += mono[i].toDouble() * mono[i]
            v[b] = s / (to - from)
        }
        val c = ForkV2Analysis.Curve()
        c.start = 0.0
        c.step = n / sr
        c.values = v
        return c
    }

    /** `fi`: the RMS of each [windowSec] window from the start (the last may be short), over the loudest. */
    fun windows(power: ForkV2Analysis.Curve, windowSec: Double): DoubleArray {
        val p = power.values
        val per = max(1, kotlin.math.round(windowSec / power.step).toInt())
        val count = (p.size + per - 1) / per
        val out = DoubleArray(count)
        for (w in 0 until count) {
            val from = w * per
            val to = min(p.size, from + per)
            var s = 0.0
            for (i in from until to) s += p[i]
            out[w] = sqrt(s / (to - from))
        }
        val peak = max(out.maxOrNull() ?: 0.0, 1e-9)
        for (i in out.indices) out[i] /= peak
        return out
    }

    /** `pi`: (introEndSec, outroStartSec) from the windows, or null when there is no power curve. */
    fun introOutro(power: ForkV2Analysis.Curve?, beatIntervalSec: Double, durationSec: Double): Pair<Double, Double>? {
        if (power == null || power.values.isEmpty() || !(durationSec > 0)) return null
        val window = if (beatIntervalSec > 0) beatIntervalSec * BEATS else NO_GRID_SEC
        val e = windows(power, window)
        var intro = 0.0
        for (n in 0 until e.size - 1) if (e[n] >= LOUD && e[n + 1] >= LOUD) { intro = n * window; break }
        var outro = durationSec
        for (i in e.size - 2 downTo 0) if (e[i] >= LOUD && e[i + 1] >= LOUD) { outro = min(durationSec, (i + 2) * window); break }
        return min(intro, durationSec / 3) to max(outro, durationSec / 2)
    }
}
