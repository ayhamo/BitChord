package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Automix v2's kick lock (the extension's automix.js kickLock, from Orchard lock.rs). Beat
 * trackers put a beat tens of ms off its kick, differently per song, and two kicks 30 ms apart
 * flam. Over the blend and 8 beats either side, each song's kick onsets (35-140 Hz level, 10 ms
 * smoothing, its rise over 10 ms, every 1 ms) are folded into 256 bins of the playing song's beat;
 * the next song's cue moves by the shift (under an eighth of a beat) that lines its profile up,
 * when the two match well (0.5) and much better than as planned (by 0.2). Pure Kotlin; a port of
 * the phone's KickLock.java.
 */
object ForkV2KickLock {
    internal const val HOP = 0.001
    internal const val BINS = 256
    internal const val CONTEXT_BEATS = 8
    internal const val LIMIT = BINS / 8
    internal const val MIN_MATCH = 0.5
    internal const val MIN_GAIN = 0.2

    /** Mono samples, their rate, the song second of data[0]. */
    class Side(val data: FloatArray, val rate: Double, val origin: Double)

    /** The cue shift (seconds of the next song; positive: its kick comes late), or why none. */
    class Result {
        var none: String? = null
        var shift = Double.NaN
        var score = Double.NaN
        var gain = Double.NaN

        override fun toString(): String =
            if (none != null) "kick lock: " + none + (if (score.isNaN()) "" else " (match $score, gain $gain)")
            else forkV2Format("kick lock: next song's cue %+.1f ms (match %.3f, gain %.3f)", shift * 1000, score, gain)
    }

    internal fun biquad(x: FloatArray, lowpass: Boolean, f: Double, sr: Double): FloatArray {
        val w = 2 * PI * f / sr
        val cw = cos(w)
        val al = sin(w) / (2 * sqrt(0.5))
        val b1 = if (lowpass) 1 - cw else -(1 + cw)
        val b0 = abs(b1) / 2
        val a0 = 1 + al
        val B0 = b0 / a0
        val B1 = b1 / a0
        val B2 = b0 / a0
        val A1 = -2 * cw / a0
        val A2 = (1 - al) / a0
        val y = FloatArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val v = B0 * x[i] + B1 * x1 + B2 * x2 - A1 * y1 - A2 * y2
            x2 = x1
            x1 = x[i].toDouble()
            y2 = y1
            y1 = v
            y[i] = v.toFloat()
        }
        return y
    }

    /** A side's kick onset strength by song second (NaN outside it), or null when under a second of audio. */
    internal fun kickOnsets(side: Side, from: Double, to: Double): ((Double) -> Double)? {
        val r = side.rate
        val a = max(0.0, floor((from - side.origin) * r)).toInt()
        val b = min(side.data.size.toDouble(), ceil((to - side.origin) * r)).toInt()
        if (b - a < r) return null
        var x = side.data.copyOfRange(a, b)
        x = biquad(x, false, 35.0, r)
        x = biquad(x, false, 35.0, r)
        x = biquad(x, true, 140.0, r)
        x = biquad(x, true, 140.0, r)
        val alpha = 1 - exp(-1 / (0.010 * r))
        val step = max(1L, jround(HOP * r)).toInt()
        val levels = DoubleArray((x.size + step - 1) / step)
        var n = 0
        var level = 0.0
        for (i in x.indices) {
            level += alpha * (x[i].toDouble() * x[i] - level)
            if (i % step == 0) levels[n++] = ln1p(level * 1e4)
        }
        val values = DoubleArray(n)
        for (i in 10 until n) values[i] = max(0.0, levels[i] - levels[i - 10])
        val origin = side.origin + a / r
        return { t ->
            val i = jround((t - origin) / HOP)
            if (i >= 0 && i < values.size) values[i.toInt()] else Double.NaN
        }
    }

    /**
     * A side from decoded channels: downmixed, low-passed under 400 Hz twice and kept every few
     * samples (about 3 kHz), so the next song's opening is cheap to hold until its blend.
     */
    fun kickSource(channels: Array<FloatArray>, rate: Double, origin: Double, from: Double, to: Double): Side {
        val a = max(0.0, floor(from * rate)).toInt()
        val b = min(channels[0].size.toDouble(), ceil(to * rate)).toInt()
        val n = max(0, b - a)
        val mono = FloatArray(n)
        for (c in channels) for (i in 0 until n) mono[i] = (mono[i] + c[a + i].toDouble() / channels.size).toFloat()
        val low = biquad(biquad(mono, true, 400.0, rate), true, 400.0, rate)
        val step = max(1.0, floor(rate / 3000)).toInt()
        val data = FloatArray(n / step) { low[it * step] }
        return Side(data, rate / step, origin + a / rate)
    }

    /** The two songs' second at output second t of a blend plan (before it: its first speed; after: its last). */
    internal class Clocks {
        var D = 0.0
        var inPerOut = 0.0
        lateinit var out: (Double) -> Double
        lateinit var `in`: (Double) -> Double
    }

    internal fun blendClocks(m: ForkV2Planner.Plan): Clocks {
        val c = Clocks()
        val gl = m.glide
        if (gl != null) {
            val G = ForkV2Planner.glideSteps(gl.q, gl.beats, gl.beatOutSec)
            val B = G.outRate.size
            c.D = G.total
            c.inPerOut = G.beatInSec / gl.beatOutSec
            c.out = { t -> along(G, t, m.mixOutTrackTime, gl.beatOutSec, G.outRate, G.outRate[B - 1]) }
            c.`in` = { t -> along(G, t, m.mixInTrackTime, G.beatInSec, G.inRate, 1.0) }
            return c
        }
        val outRate = if (m.outRate > 0) m.outRate else 1.0
        val rate = if (m.rate > 0) m.rate else 1.0
        c.D = m.durationSec
        c.inPerOut = rate / outRate
        c.out = { t -> m.mixOutTrackTime + t * outRate }
        c.`in` = { t -> m.mixInTrackTime + t * rate }
        return c
    }

    private fun along(G: ForkV2Planner.GlideSteps, t: Double, base: Double, beatSec: Double, rates: DoubleArray, after: Double): Double {
        val B = rates.size
        if (t < 0) return base + t * rates[0]
        if (t >= G.total) return base + B * beatSec + (t - G.total) * after
        var k = 0
        while (k + 1 < B && G.at[k + 1] <= t) k++
        return base + k * beatSec + (t - G.at[k]) * rates[k]
    }

    /** null for a plan that isn't a beatmatched blend. */
    fun kickLock(m: ForkV2Planner.Plan, outSide: Side, inSide: Side): Result? {
        val interval = m.beatSec
        if (m.windowIn) {
            // a mix-in placed in the editor: a shift of up to an eighth of a beat would undo a nudge
            val r = Result()
            r.none = "the mix-in was placed in the editor"
            return r
        }
        if (!ForkV2Planner.kickLockAllowed(m) || !(interval > 0)) return null
        val ck = blendClocks(m)
        val context = CONTEXT_BEATS * interval
        val outAt = kickOnsets(outSide, ck.out(-context) - 0.05, ck.out(ck.D + context) + 0.05)
        val inAt = kickOnsets(inSide, ck.`in`(-context) - 0.05, ck.`in`(ck.D + context) + 0.05)
        val r = Result()
        if (outAt == null || inAt == null) {
            r.none = "not enough audio"
            return r
        }
        val sums = arrayOf(DoubleArray(BINS), DoubleArray(BINS))
        val counts = arrayOf(IntArray(BINS), IntArray(BINS))
        val steps = floor((ck.D + 2 * context) / HOP).toLong()
        var s = 0L
        while (s < steps) {
            val t = -context + s * HOP
            val o = ck.out(t)
            val ph = (o - m.mixOutTrackTime) / interval
            val bin = min((BINS - 1).toDouble(), floor((ph - floor(ph)) * BINS)).toInt()
            val vo = outAt(o)
            val vi = inAt(ck.`in`(t))
            if (!vo.isNaN()) {
                sums[0][bin] += vo
                counts[0][bin]++
            }
            if (!vi.isNaN()) {
                sums[1][bin] += vi
                counts[1][bin]++
            }
            s++
        }
        val L = profile(sums[0], counts[0])
        val R = profile(sums[1], counts[1])
        if (L == null || R == null) {
            r.none = "no clear kick"
            return r
        }
        var ll = 0.0
        var rr = 0.0
        for (b in 0 until BINS) {
            ll += L[b] * L[b]
            rr += R[b] * R[b]
        }
        val norm = sqrt(ll * rr)
        if (!(norm > 0)) {
            r.none = "no clear kick"
            return r
        }
        var best = 0
        for (sh in -LIMIT..LIMIT) if (score(L, R, sh, norm) > score(L, R, best, norm)) best = sh
        r.score = jround(score(L, R, best, norm) * 1000) / 1000.0
        r.gain = jround((score(L, R, best, norm) - score(L, R, 0, norm)) * 1000) / 1000.0
        if (abs(best) >= LIMIT || r.score < MIN_MATCH || r.gain < MIN_GAIN) {
            r.none = "the kicks already line up, or match too loosely"
            return r
        }
        r.shift = (best / BINS.toDouble()) * interval * ck.inPerOut
        return r
    }

    private fun profile(sums: DoubleArray, counts: IntArray): DoubleArray? {
        val means = DoubleArray(BINS)
        var mean = 0.0
        var peak = Double.NEGATIVE_INFINITY
        var all = true
        for (i in 0 until BINS) {
            means[i] = sums[i] / max(1, counts[i])
            mean += means[i]
            peak = max(peak, means[i])
            if (counts[i] == 0) all = false
        }
        mean /= BINS
        if (!all || !(mean > 0) || !(peak > 2 * mean)) return null
        for (i in 0 until BINS) means[i] -= mean
        return means
    }

    private fun score(L: DoubleArray, R: DoubleArray, sh: Int, norm: Double): Double {
        var s = 0.0
        for (b in 0 until BINS) s += L[b] * R[(((b + sh) % BINS) + BINS) % BINS]
        return s / norm
    }
}
