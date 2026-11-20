package com.music.bitchord.data.sources

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shared addon rulebook's window check (YT Music Extension `spec/addons/offset-map.md`, "Window
 * check" and "Input"; a port of `src/addons/offset-fit.js` `fitWindow` and the parts of it it uses).
 * Before an addon copy replaces YouTube's mid-song, 12 s of the copy, read at YouTube's position, is
 * fitted against YouTube's own audio. No match: the song stays on what plays (2 of the 9 copies the
 * phone measured were other recordings or edits that the length rule let through). A match: its
 * offset is applied to the swap position (Poker Face's lossless copy sits 117.2 ms off YouTube's, a
 * jump at every swap without it).
 *
 * Pure Kotlin on mono float PCM at [FIT_RATE], so the phone, the PC and the tests share it. The
 * numbers follow the JS exactly (same grids, same rounding), because the spec's cases check every
 * offset to 1 ms against what the JS measured.
 *
 * Offsets use the map's sign: `addonTime = youtubeTime + offsetSec`.
 */
object ForkWindowFit {

    const val FIT_RATE = 22_050

    /** What the audition buffers past the playhead and the check reads (protocol.md §8.3.1). */
    const val WINDOW_SEC = 12.0

    private const val ENV_WIN_SEC = 0.01
    const val COARSE_LAG_SEC = 15.0
    const val MIN_SCORE = 0.8
    private const val MIN_OVERLAP = 0.5
    private const val FINE_RATE = 4_000
    private const val FINE_CUTOFF = 1_000.0
    private const val FINE_LAG_SEC = 0.02

    /** A match carries [offsetSec]; a refusal has it null, with the scores reached. */
    data class Result(val offsetSec: Double?, val coarseScore: Double?, val score: Double?) {
        val match: Boolean get() = offsetSec != null
        val offsetMs: Long? get() = offsetSec?.let { Math.round(it * 1000.0) }

        fun describe(): String = buildString {
            append(if (match) "match %.1f ms".format(java.util.Locale.ROOT, offsetSec!! * 1000) else "no match")
            append(" (coarse ").append(coarseScore?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "-")
            append(", fine ").append(score?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "-").append(')')
        }
    }

    /**
     * The window check: [win] is the addon's audio (mono, [FIT_RATE]) read from addon time [posSec],
     * [yt] YouTube's audio on the same clock (index 0 = time 0). The window less 1 s each end is the
     * template, slid over YouTube's copy: coarse ±15 s on the 10 ms envelope, then fine ±20 ms at
     * 4 kHz. Sliding YouTube's span under the window instead loses part of the span once the offset
     * passes about a second, and then locks onto the wrong beat (spec: a −2.3 s shift was refused).
     */
    fun fitWindow(yt: FloatArray, win: FloatArray, posSec: Double): Result {
        val winSec = win.size.toDouble() / FIT_RATE
        val a0 = Math.round(posSec * FIT_RATE).toInt()
        val ad = FloatArray(a0 + win.size)
        win.copyInto(ad, a0)
        val span = winSec - 2
        val start = posSec + 1
        if (span <= 0) return Result(null, null, null)
        // coarse() and fine() slide their second signal under the first: here YouTube's under the
        // window's, so they return YouTube time − addon time, the negative of offsetSec
        val c = coarse(logEnv(rmsWindows(ad)), logEnv(rmsWindows(yt)), start, span, 0.0, COARSE_LAG_SEC)
        if (c == null || c.second < MIN_SCORE) return Result(null, c?.second, null)
        val f = fine(ad, yt, start, span, c.first)
        if (f == null || f.second < MIN_SCORE) return Result(null, c.second, f?.second)
        return Result(-f.first + 0.0, c.second, f.second)
    }

    /**
     * The YouTube span a window at [posSec] can reach: the template ±[COARSE_LAG_SEC], plus the fine
     * stage's few ms. Starts on a whole second so a caller that decodes only this span and passes
     * `posSec − fromSec` keeps every grid (10 ms windows, 4 kHz) where it would be on the whole song.
     */
    fun youtubeSpan(posSec: Double): Pair<Double, Double> {
        val from = max(0.0, floor(posSec + 1 - COARSE_LAG_SEC) - 1)
        return from to posSec + WINDOW_SEC - 1 + COARSE_LAG_SEC + 1
    }

    /** The mean of [channels] interleaved channels (the spec's downmix). */
    fun mono(interleaved: FloatArray, count: Int, channels: Int): FloatArray {
        if (channels <= 1) return interleaved.copyOf(count)
        val frames = count / channels
        return FloatArray(frames) { i ->
            var s = 0.0
            for (c in 0 until channels) s += interleaved[i * channels + c]
            (s / channels).toFloat()
        }
    }

    /**
     * [mono] (at [rate], its first sample at [firstSampleSec]) put on the [FIT_RATE] grid from
     * [fromSec] for [seconds], band-limited to the fit rate's Nyquist. Times the decode didn't
     * reach are silence.
     */
    fun toFitRate(mono: FloatArray, rate: Double, firstSampleSec: Double, fromSec: Double, seconds: Double): FloatArray {
        val count = max(0, Math.round(seconds * FIT_RATE).toInt())
        if (rate == FIT_RATE.toDouble() && floor((fromSec - firstSampleSec) * rate) == (fromSec - firstSampleSec) * rate) {
            val skip = ((fromSec - firstSampleSec) * rate).toInt()
            return FloatArray(count) { i -> mono.getOrElse(skip + i) { 0f } }
        }
        return resample(mono, rate, FIT_RATE.toDouble(), FIT_RATE / 2.0, (fromSec - firstSampleSec) * FIT_RATE, count)
    }

    // ── the JS, function for function ─────────────────────────────────────

    /**
     * Band-limited resampling: `y[n] = Σ x[k]·h(t_n − k)`, `t_n = (start + n)·from/to` input samples
     * (start may be fractional or negative; samples outside x are 0), h a Hann-windowed sinc.
     */
    fun resample(x: FloatArray, fromRate: Double, toRate: Double, cutoffHz: Double, start: Double, count: Int): FloatArray {
        val fc = min(cutoffHz, 0.5 * min(fromRate, toRate))
        val w = ceil((4 * fromRate) / fc)
        val g = (2 * fc) / fromRate
        val out = FloatArray(max(0, count))
        val step = fromRate / toRate
        for (n in out.indices) {
            val t = (start + n) * step
            val k0 = max(0.0, ceil(t - w)).toInt()
            val k1 = min((x.size - 1).toDouble(), floor(t + w)).toInt()
            var s = 0.0
            for (k in k0..k1) {
                val u = t - k
                val a = PI * g * u
                val sinc = if (u == 0.0) 1.0 else sin(a) / a
                s += x[k] * g * sinc * (0.5 + 0.5 * cos((PI * u) / w))
            }
            out[n] = s.toFloat()
        }
        return out
    }

    /** Window i covers samples `[round(i·220.5), round((i+1)·220.5))`. */
    fun rmsWindows(x: FloatArray): FloatArray {
        val w = FIT_RATE * ENV_WIN_SEC
        val n = floor(x.size / w).toInt()
        val rms = FloatArray(n)
        for (i in 0 until n) {
            val a = Math.round(i * w).toInt()
            val b = Math.round((i + 1) * w).toInt()
            var s = 0.0
            for (k in a until b) s += x[k].toDouble() * x[k]
            rms[i] = sqrt(s / (b - a)).toFloat()
        }
        return rms
    }

    fun logEnv(rms: FloatArray): FloatArray = FloatArray(rms.size) { ln1p(rms[it] * 100.0).toFloat() }

    /** Pearson of y[0..n) against a[lag..lag+n) over the overlap; null under [minOverlap] of y. */
    private fun pearsonAt(y: FloatArray, a: FloatArray, lag: Int, minOverlap: Double): Double? {
        val i0 = max(0, -lag)
        val i1 = min(y.size, a.size - lag)
        val n = i1 - i0
        if (n < max(2.0, minOverlap * y.size)) return null
        var sy = 0.0; var sa = 0.0; var syy = 0.0; var saa = 0.0; var sya = 0.0
        for (i in i0 until i1) {
            val p = y[i].toDouble()
            val q = a[i + lag].toDouble()
            sy += p; sa += q; syy += p * p; saa += q * q; sya += p * q
        }
        val cov = sya - (sy * sa) / n
        val vy = syy - (sy * sy) / n
        val va = saa - (sa * sa) / n
        return if (vy > 1e-12 && va > 1e-12) cov / sqrt(vy * va) else 0.0
    }

    /** Best lag in [lo, hi] with its score, refined parabolically (at). */
    private class Best(val lag: Int, val score: Double, val at: Double)

    private fun bestLag(y: FloatArray, a: FloatArray, lo: Int, hi: Int, minOverlap: Double = MIN_OVERLAP): Best? {
        if (hi < lo) return null
        val scores = arrayOfNulls<Double>(hi - lo + 1)
        var bestAt = -1
        for (lag in lo..hi) {
            val c = pearsonAt(y, a, lag, minOverlap) ?: continue
            scores[lag - lo] = c
            if (bestAt < 0 || c > scores[bestAt - lo]!!) bestAt = lag
        }
        if (bestAt < 0) return null
        val best = scores[bestAt - lo]!!
        val l = scores.getOrNull(bestAt - 1 - lo)
        val r = scores.getOrNull(bestAt + 1 - lo)
        var frac = 0.0
        if (l != null && r != null) {
            val d = l - 2 * best + r
            if (d < 0) frac = max(-0.5, min(0.5, (l - r) / (2 * d)))
        }
        return Best(bestAt, best, bestAt + frac)
    }

    /** Coarse over [startSec, startSec + spanSec) of the first, lags around [aroundSec] ±[lagSec]: (offsetSec, score). */
    private fun coarse(env1: FloatArray, env2: FloatArray, startSec: Double, spanSec: Double, aroundSec: Double, lagSec: Double): Pair<Double, Double>? {
        val i0 = Math.round(startSec / ENV_WIN_SEC).toInt()
        val end = min(env1.size, i0 + Math.round(spanSec / ENV_WIN_SEC).toInt())
        val y = if (i0 >= end) FloatArray(0) else env1.copyOfRange(i0, end)
        val c = Math.round(aroundSec / ENV_WIN_SEC).toInt()
        val l = Math.round(lagSec / ENV_WIN_SEC).toInt()
        val b = bestLag(y, env2, i0 + c - l, i0 + c + l) ?: return null // (env2's index = y's index + i0 + lag)
        return (b.lag - i0) * ENV_WIN_SEC to b.score
    }

    /** Both sides band-limited to 1 kHz at 4 kHz, ±20 ms around [aroundSec]: (offsetSec, score). */
    private fun fine(x1: FloatArray, x2: FloatArray, startSec: Double, spanSec: Double, aroundSec: Double): Pair<Double, Double>? {
        val m = Math.round(FINE_LAG_SEC * FINE_RATE).toInt()
        val pad = m + 4
        val n = Math.round(spanSec * FINE_RATE).toInt()
        val y = resample(x1, FIT_RATE.toDouble(), FINE_RATE.toDouble(), FINE_CUTOFF, Math.round(startSec * FINE_RATE).toDouble(), n)
        // the second on a 4 kHz grid starting at the first's start + the coarse offset − pad samples
        val aStart = (startSec + aroundSec) * FINE_RATE - pad
        val a = resample(x2, FIT_RATE.toDouble(), FINE_RATE.toDouble(), FINE_CUTOFF, aStart, n + 2 * pad)
        val b = bestLag(y, a, pad - m, pad + m, 1.0) ?: return null
        return aroundSec + (b.at - pad) / FINE_RATE to b.score
    }
}
