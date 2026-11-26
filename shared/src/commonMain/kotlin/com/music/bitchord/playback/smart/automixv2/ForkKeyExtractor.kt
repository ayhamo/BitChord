package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fork: the song's key the PC's way, the extension's analysis-cases.md "The key" [all] (107d0f51, extension commit
 * 1474937): Essentia's `KeyExtractor` with its defaults, step by step, ported from the extension's reference
 * `scripts/key_port.mjs` (which gives the PC's key on 323/323 benchmark songs). Frames, Hann window, magnitude
 * spectrum, `PeakDetection`, `SpectralWhitening`, `HPCP` (3 harmonics, cosine, 1 semitone), the mean profile, and
 * `Key` with the "bgate" tables; the strength is that profile's correlation, so [Key.strength] is on the PC's scale
 * (median about 0.87) that the v3 key rules and Advanced's key-aware length read.
 *
 * Any rate whose frame is a power of two: the spec's 4096 samples at 44 100 Hz is 4096·rate/44100 samples here,
 * hop the same, so every bin is the same 10.77 Hz and every frame the same 93 ms (the apps measure the evidence at
 * the analyser's 11 025 Hz: 1024). The window is scaled to sum to 2, so a peak's value doesn't depend on the frame
 * size and the 1e-4 peak floor holds. Peaks stop at 3500 Hz, under 11 025 Hz's Nyquist; only the whitening's top
 * envelope points (to 4200 Hz + 58 %) see fewer bins.
 */
object ForkKeyExtractor {

    class Key internal constructor(val name: String, val strength: Double)

    private const val FMIN = 25.0
    private const val FMAX = 3500.0
    private const val MAX_PEAKS = 60
    private const val PEAK_FLOOR = 1e-4

    private val MAJOR = doubleArrayOf(1.0, 0.0, 0.42, 0.0, 0.53, 0.37, 0.0, 0.77, 0.0, 0.38, 0.21, 0.30)
    private val MINOR = doubleArrayOf(1.0, 0.0, 0.36, 0.39, 0.0, 0.38, 0.0, 0.74, 0.27, 0.0, 0.42, 0.23)

    /** Bin 0 is A (440 Hz); the app's spelling, which [ForkV2BeatGrid.camelot] reads. */
    private val NOTES = arrayOf("A", "B♭", "B", "C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭")

    /** The 3 shifts a peak adds into, in semitones down, and their weights (a fifth and a third above). */
    private val HARMONICS = arrayOf(
        doubleArrayOf(0.0, 3.0),
        doubleArrayOf(12 * log2(3.0) - 12, 1.0),
        doubleArrayOf(12 * log2(5.0) - 24, 1 / max(1.0, 12 * log2(5.0) / 12 * 0.5)),
    )

    /** The frame for [rate] Hz (4096 at 44 100 Hz), or 0 when it is not a power of two. */
    internal fun frameSize(rate: Int): Int {
        if (rate <= 0) return 0
        val n = (4096.0 * rate / 44_100).roundToInt()
        return if (n >= 64 && (n and (n - 1)) == 0) n else 0
    }

    /** The key of the whole song, [mono] at [rate] Hz; null with no audio or a rate it can't frame. */
    fun detect(mono: FloatArray, rate: Int): Key? {
        val n = frameSize(rate)
        if (n == 0 || mono.isEmpty()) return null
        return key(profile(mono, rate, n) ?: return null)
    }

    /** Steps 1-7: the song's pitch-class profile, divided by its largest bin, bins under 0.2 set to 0. */
    internal fun profile(mono: FloatArray, rate: Int, n: Int): DoubleArray? {
        val half = n / 2
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val sum = win.sum()
        for (i in 0 until n) win[i] *= 2 / sum
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val mag = DoubleArray(half + 1)
        val acc = DoubleArray(12)
        var frames = 0
        var start = -half
        while (true) {
            for (k in 0 until n) {
                val j = (k + half) % n
                val s = start + j
                re[k] = (if (s >= 0 && s < mono.size) mono[s].toDouble() else 0.0) * win[j]
                im[k] = 0.0
            }
            fft(re, im)
            for (k in 0..half) mag[k] = hypot(re[k], im[k])
            hpcp(whiten(mag, peaks(mag, rate.toDouble() / n), rate), acc)
            frames++
            if (start + half >= mono.size) break
            start += n
        }
        val p = DoubleArray(12) { acc[it] / frames }
        val top = p.max()
        for (k in 0 until 12) {
            p[k] = if (top > 0) p[k] / top else 0.0
            if (p[k] < 0.2) p[k] = 0.0
        }
        return p
    }

    /** Step 4, Essentia's PeakDetection: (Hz, value) of the [MAX_PEAKS] largest peaks from 25 Hz up to the first past 3500 Hz. */
    internal fun peaks(mag: DoubleArray, scale: Double): List<DoubleArray> {
        val size = mag.size
        val out = ArrayList<DoubleArray>()
        var i = max(0, ceil(FMIN / scale).toInt())
        if (i + 1 < size && mag[i] > mag[i + 1] && mag[i] > PEAK_FLOOR) out += doubleArrayOf(i * scale, mag[i])
        while (true) {
            while (i + 1 < size - 1 && mag[i] >= mag[i + 1]) i++
            while (i + 1 < size - 1 && mag[i] < mag[i + 1]) i++
            var j = i
            while (j + 1 < size - 1 && mag[j] == mag[j + 1]) j++
            if (j + 1 < size - 1 && mag[j + 1] < mag[j] && mag[j] > PEAK_FLOOR) {
                val pos: Double
                val value: Double
                if (j != i) {
                    pos = (i + j) * 0.5
                    value = mag[i]
                } else {
                    val l = mag[j - 1]
                    val m = mag[j]
                    val r = mag[j + 1]
                    val d = 0.5 * ((l - r) / (l - 2 * m + r))
                    pos = j + d
                    value = m - 0.25 * (l - r) * d
                }
                if (pos * scale > FMAX) break
                out += doubleArrayOf(pos * scale, value)
            }
            i = j
            if (i + 1 >= size - 1) break
        }
        out.sortWith { a, b -> if (a[1] != b[1]) b[1].compareTo(a[1]) else a[0].compareTo(b[0]) }
        return if (out.size > MAX_PEAKS) out.subList(0, MAX_PEAKS) else out
    }

    /** Step 5, Essentia's SpectralWhitening (maxFrequency 3500, raised to 4200): each peak's new value. */
    internal fun whiten(spec: DoubleArray, peaks: List<DoubleArray>, rate: Int): List<DoubleArray> {
        val maxFreq = FMAX * 1.2
        val range = rate / 2.0
        val bins = spec.size
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var f = 0.0
        while (f <= maxFreq && f <= range) {
            val bf = f - max(50.0, f * 0.34)
            val ef = f + max(50.0, f * 0.58)
            val b = min(bins - 1, max(0, (bf / range * (bins - 1) + 0.5).toInt()))
            val e = min(bins, max((ef / range * (bins - 1) + 0.5).toInt(), b + 1))
            val c = b / 2.0 + e / 2.0
            val hw = e - c
            var avg = 0.0
            var total = 0.0
            for (i in b until e) {
                var w = 1 - abs(i - c) / hw
                w *= w
                w *= w
                val en = spec[i] * spec[i]
                w *= en
                avg += en * w
                total += w
            }
            if (total != 0.0) avg /= total
            xs += f
            ys += avg
            f += 100.0
        }
        ys[ys.size - 1] = ys[ys.size - 2]
        for (k in ys.indices) ys[k] = 2 * lin2db(sqrt(ys[k]))
        return peaks.map { (pf, m) ->
            val amp = 2 * lin2db(m)
            var w: Double
            if (pf > maxFreq - 100) {
                w = amp
            } else {
                val k = min(xs.size - 2, floor(pf / 100).toInt())
                val t = (pf - xs[k]) / (xs[k + 1] - xs[k])
                val env = ys[k] + t * (ys[k + 1] - ys[k])
                w = if (amp > env) 0.0 else if (amp > env - 30) amp - env else -200.0
                w -= 20 * pf / 4000
            }
            doubleArrayOf(pf, 10.0.pow(w / 20))
        }
    }

    /** Step 6, Essentia's HPCP: each whitened peak into [acc] at its 3 shifted frequencies. */
    internal fun hpcp(peaks: List<DoubleArray>, acc: DoubleArray) {
        for ((f0, m) in peaks) {
            if (f0 < FMIN || f0 > FMAX) continue
            for ((s, w) in HARMONICS) {
                val f = f0 * 2.0.pow(-s / 12)
                val bin = log2(f / 440) * 12
                var i = ceil(bin - 0.5).toInt()
                val last = floor(bin + 0.5).toInt()
                while (i <= last) {
                    acc[((i % 12) + 12) % 12] += cos(PI * abs(bin - i)) * m * m * w * w
                    i++
                }
            }
        }
    }

    /** Step 8, Essentia's Key ("bgate"): the best-correlated table and shift; a tie is minor. */
    internal fun key(p: DoubleArray): Key {
        val (pm, ps) = meanSpread(p)
        fun best(t: DoubleArray): Pair<Double, Int> {
            val (tm, ts) = meanSpread(t)
            var top = -1.0
            var at = 0
            for (sh in 0 until 12) {
                var r = 0.0
                if (ps != 0.0 && ts != 0.0) {
                    for (i in 0 until 12) r += (p[i] - pm) * (t[((i - sh) % 12 + 12) % 12] - tm)
                    r /= ps * ts
                }
                if (r > top) {
                    top = r
                    at = sh
                }
            }
            return top to at
        }
        val (a, ai) = best(MAJOR)
        val (b, bi) = best(MINOR)
        return if (a > b) Key("${NOTES[ai]} major", a) else Key("${NOTES[bi]} minor", b)
    }

    private fun meanSpread(v: DoubleArray): Pair<Double, Double> {
        val m = v.sum() / 12
        var s = 0.0
        for (x in v) s += (x - m) * (x - m)
        return m to sqrt(s)
    }

    private fun lin2db(x: Double): Double = if (x < 1e-10) -100.0 else 10 * log10(x)

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    /** In-place radix-2 FFT (length a power of two), as the reference's. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val t = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }
}
