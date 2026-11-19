package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Automix v2's evidence from the mono song (the extension's host.js lowEnd, from Orchard
 * evidence.rs, chroma.rs and loudness.rs): the sub-bass ratio, the bass curve, the pitch profile
 * (whole song and every 0.5 s) and the 100 ms K-weighted loudness. Pure Kotlin; a port of the
 * phone's Evidence.java.
 */
object ForkV2Evidence {
    /** Fills [a]'s subBassRatio, bassCurve, chroma, chromaCurve and loudnessCurve from mono [x] at [sr]. */
    fun measure(x: FloatArray, sr: Double, a: ForkV2Analysis) {
        val c = lowCopy(x, sr)
        a.subBassRatio = subBassRatio(c)
        a.bassCurve = bassCurve(c)
        chroma(x, sr, a)
        a.loudnessCurve = loudnessCurve(x, sr)
    }

    internal fun biquad(x: FloatArray, lowpass: Boolean, f: Double, sr: Double): FloatArray = ForkV2KickLock.biquad(x, lowpass, f, sr)

    /** A band: two high-passes, two low-passes (Orchard's band()). */
    internal fun band(x: FloatArray, lo: Double, hi: Double, r: Double): FloatArray =
        biquad(biquad(biquad(biquad(x, false, lo, r), false, lo, r), true, hi, r), true, hi, r)

    internal class Low(val low: FloatArray, val r: Double)

    /** The song low-passed under 400 Hz and kept at about 2756 Hz (every 16th sample at 44.1 kHz). */
    internal fun lowCopy(mono: FloatArray, sr: Double): Low {
        val pre = biquad(biquad(mono, true, 400.0, sr), true, 400.0, sr)
        val step = max(1L, jround(sr / 2756.25)).toInt()
        val low = FloatArray(pre.size / step) { pre[it * step] }
        return Low(low, sr / step)
    }

    /** log10 of the energy at 25-60 Hz over 60-250 Hz in the song's middle 60%; NaN when silent. */
    internal fun subBassRatio(c: Low): Double {
        val body = c.low.copyOfRange(c.low.size / 5, floor(c.low.size * 4 / 5.0).toInt())
        val sub = energy(band(body, 25.0, 60.0, c.r))
        val bass = energy(band(body, 60.0, 250.0, c.r))
        return if (sub > 0 && bass > 0) log10(sub / bass) else Double.NaN
    }

    private fun energy(x: FloatArray): Double {
        var e = 0.0
        for (v in x) e += v.toDouble() * v
        return e
    }

    internal const val BASS_STEP = 0.25

    /** 35-140 Hz RMS every 0.25 s, 1 = the song's usual level (75th percentile of its per-second means). */
    internal fun bassCurve(c: Low): ForkV2Analysis.Curve? {
        val f = band(c.low, 35.0, 140.0, c.r)
        val levels = ArrayList<Double>()
        var i = 0
        while (edge(i + 1, c.r) <= f.size) {
            val a = edge(i, c.r)
            val b = edge(i + 1, c.r)
            var ss = 0.0
            for (k in a until b) ss += f[k.toInt()].toDouble() * f[k.toInt()]
            levels.add(sqrt(ss / max(1L, b - a)))
            i++
        }
        val perSec = jround(1 / BASS_STEP).toInt()
        val seconds = ArrayList<Double>()
        i = 0
        while (i < levels.size) {
            val e = min(levels.size, i + perSec)
            var s = 0.0
            for (k in i until e) s += levels[k]
            seconds.add(s / (e - i))
            i += perSec
        }
        if (seconds.isEmpty()) return null
        val sorted = seconds.toDoubleArray()
        sorted.sort()
        val ref = max(sorted[jround((sorted.size - 1) * 0.75).toInt()], 1e-6)
        val curve = ForkV2Analysis.Curve()
        curve.start = 0.0
        curve.step = BASS_STEP
        curve.values = DoubleArray(levels.size) { jround(levels[it] / ref * 1000) / 1000.0 }
        return curve
    }

    private fun edge(i: Int, r: Double): Long = jround(i * BASS_STEP * r)

    internal const val CHROMA_STEP = 0.5
    internal const val CHROMA_LOW = 65.0
    internal const val CHROMA_HIGH = 2100.0

    /**
     * Pitch classes every 0.5 s: a ~2.7 Hz-bin spectrum; each peak from 65 Hz to 2.1 kHz over twice
     * the frame's median, parabolic on log magnitude, adds its square root to its two nearest
     * pitch classes. Each frame summed to 1 with its weight against the median frame; the whole
     * song's profile summed to 1.
     */
    internal fun chroma(mono: FloatArray, sr: Double, a: ForkV2Analysis) {
        val size = 1 shl ceil(ln(max(512.0, sr / 2.7)) / ln(2.0)).toInt()
        val hop = max(1L, jround(CHROMA_STEP * sr)).toInt()
        if (mono.size < size) return
        val win = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
        val low = max(2.0, ceil(CHROMA_LOW * size / sr)).toInt()
        val high = min((size / 2 - 2).toDouble(), floor(min(CHROMA_HIGH, sr * 0.45) * size / sr)).toInt()
        val fft = Fft(size)
        val frame = DoubleArray(size)
        val band = DoubleArray(high - low + 1)
        val binHz = sr / size
        val frames = ArrayList<DoubleArray>()
        var begin = 0
        while (begin + size <= mono.size) {
            for (i in 0 until size) frame[i] = mono[begin + i] * win[i]
            val m = fft.magnitudes(frame)
            m.copyInto(band, 0, low, low + band.size)
            band.sort()
            val floorLevel = band[band.size shr 1] * 2
            val c = DoubleArray(12)
            for (bin in low..high) {
                val l = m[bin - 1]
                val mid = m[bin]
                val r = m[bin + 1]
                if (mid <= floorLevel || mid <= l || mid < r) continue
                val la = ln(l + 1e-12)
                val lb = ln(mid + 1e-12)
                val lc = ln(r + 1e-12)
                val curv = la - 2 * lb + lc
                val off = if (curv < 0) max(-0.5, min(0.5, 0.5 * (la - lc) / curv)) else 0.0
                val pitch = 69 + 12 * (ln(((bin + off) * binHz) / 440) / ln(2.0))
                val lower = floor(pitch).toInt()
                val near = cos((pitch - lower) * PI / 2).pow(2)
                val w = sqrt(mid)
                c[((lower % 12) + 12) % 12] += w * near
                c[(((lower + 1) % 12) + 12) % 12] += w * (1 - near)
            }
            frames.add(c)
            begin += hop
        }
        val totals = DoubleArray(frames.size)
        for (i in totals.indices) for (v in frames[i]) totals[i] += v
        val sorted = totals.copyOf()
        sorted.sort()
        val typical = max(if (sorted.isNotEmpty()) sorted[sorted.size / 2] else 0.0, 1e-9)
        val sum = DoubleArray(12)
        for (f in frames) for (k in 0 until 12) sum[k] += f[k]
        var all = 0.0
        for (v in sum) all += v
        if (!(all > 0)) return
        a.chroma = DoubleArray(12) { k -> jround(sum[k] / all * 1e4) / 1e4 }
        val cc = ForkV2Analysis.ChromaCurve()
        cc.start = r3(size / 2.0 / sr)
        cc.step = hop / sr
        cc.values = Array(frames.size) { i ->
            val f = frames[i]
            DoubleArray(12) { k -> r3(f[k] / max(totals[i], 1e-12)) }
        }
        cc.weights = DoubleArray(frames.size) { i -> r3(min(totals[i] / typical, 4.0)) }
        a.chromaCurve = cc
    }

    private fun r3(v: Double): Double = jround(v * 1000) / 1000.0

    /** A radix-2 FFT's magnitudes (bins 0..size/2), its buffers reused. */
    internal class Fft(val size: Int) {
        private val rev = IntArray(size)
        private val cosT = DoubleArray(size / 2)
        private val sinT = DoubleArray(size / 2)
        private val re = DoubleArray(size)
        private val im = DoubleArray(size)
        private val mag = DoubleArray(size / 2 + 1)

        init {
            val bits = size.countTrailingZeroBits()
            for (i in 0 until size) rev[i] = (rev[i shr 1] shr 1) or ((i and 1) shl (bits - 1))
            for (i in 0 until size / 2) {
                cosT[i] = cos(2 * PI * i / size)
                sinT[i] = -sin(2 * PI * i / size)
            }
        }

        fun magnitudes(frame: DoubleArray): DoubleArray {
            for (i in 0 until size) {
                re[rev[i]] = frame[i]
                im[rev[i]] = 0.0
            }
            var len = 2
            while (len <= size) {
                val half = len shr 1
                val step = size / len
                var i = 0
                while (i < size) {
                    var k = 0
                    for (j in 0 until half) {
                        val a = i + j
                        val b = a + half
                        val tr = re[b] * cosT[k] - im[b] * sinT[k]
                        val ti = re[b] * sinT[k] + im[b] * cosT[k]
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                        k += step
                    }
                    i += len
                }
                len = len shl 1
            }
            for (i in 0..size / 2) mag[i] = hypot(re[i], im[i])
            return mag
        }
    }

    internal const val LOUDNESS_STEP = 0.1

    private fun kStage(x: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): FloatArray {
        val y = FloatArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x[i].toDouble()
            y2 = y1
            y1 = v
            y[i] = v.toFloat()
        }
        return y
    }

    /** ITU BS.1770 loudness every 100 ms: K-weighted (a high shelf, then a high-pass), its mean square in dB. */
    internal fun loudnessCurve(mono: FloatArray, sr: Double): ForkV2Analysis.Curve? {
        var K = tan(PI * 1681.974450955533 / sr)
        val Q1 = 0.7071752369554196
        val Vh = 10.0.pow(3.999843853973347 / 20)
        val Vb = Vh.pow(0.4996667741545416)
        var a0 = 1 + K / Q1 + K * K
        val shelf = kStage(
            mono, (Vh + Vb * K / Q1 + K * K) / a0, 2 * (K * K - Vh) / a0, (Vh - Vb * K / Q1 + K * K) / a0,
            2 * (K * K - 1) / a0, (1 - K / Q1 + K * K) / a0,
        )
        K = tan(PI * 38.13547087602444 / sr)
        val Q2 = 0.5003270373238773
        a0 = 1 + K / Q2 + K * K
        val z = kStage(shelf, 1.0, -2.0, 1.0, 2 * (K * K - 1) / a0, (1 - K / Q2 + K * K) / a0)
        val n = jround(LOUDNESS_STEP * sr).toInt()
        val count = if (n > 0) z.size / n else 0
        if (count == 0) return null
        val values = DoubleArray(count)
        for (j in 0 until count) {
            var ss = 0.0
            for (k in j * n until (j + 1) * n) ss += z[k].toDouble() * z[k]
            values[j] = max(-120.0, jround(10 * log10(ss / n + 1e-30) * 10) / 10.0)
        }
        val c = ForkV2Analysis.Curve()
        c.start = 0.0
        c.step = LOUDNESS_STEP
        c.values = values
        return c
    }
}
