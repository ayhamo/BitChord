package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The song's key, found the way the extension (BitChord web) finds it: essentia's KeyExtractor
 * over the whole song, i.e. the key profile that best correlates with the song's pitch-class
 * profile, and that correlation as its strength (about 0.5-0.9 for most pop and EDM; the
 * key-clash rule needs 0.5 on both songs). BitChord's native analyser was off by a semitone on
 * "Levels" (C minor for C♯ minor) and gives only a best-to-second gap (0.00-0.1), so it is not
 * used for the key. Chroma over the whole song (log power weighted by the frame's loudness,
 * 55 Hz-5 kHz), Pearson correlation with the 24 Krumhansl profiles. A port of the phone's
 * KeyStrength.java.
 */
object ForkV2KeyStrength {
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** The native analyser's spelling, which [ForkV2BeatGrid.camelot] reads. */
    private val ROOTS = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    private const val N = 8192

    class Key internal constructor(val name: String, val strength: Double)

    /** The best of the 24 keys, or null when the audio gives no chroma. */
    fun detect(mono: ShortArray?, length: Int, rate: Int): Key? {
        if (mono == null || rate <= 0) return null
        val c = chroma(mono, length, rate) ?: return null
        var best: Key? = null
        for (root in 0 until 12) {
            for (m in 0 until 2) {
                val prof = if (m == 0) MAJOR else MINOR
                val p = DoubleArray(12) { i -> prof[(i + 12 - root) % 12] }
                val r = pearson(c, p)
                if (!r.isNaN() && (best == null || r > best.strength)) best = Key(ROOTS[root] + (if (m == 0) " major" else " minor"), r)
            }
        }
        return best
    }

    internal fun chroma(mono: ShortArray, length: Int, rate: Int): DoubleArray? {
        val pc = IntArray(N / 2)
        val lo = ceil(55.0 * N / rate).toInt()
        val hi = min(N / 2 - 1, floor(5000.0 * N / rate).toInt())
        for (k in lo..hi) {
            val f = k.toDouble() * rate / N
            val midi = jround(69 + 12 * ln(f / 440) / ln(2.0)).toInt()
            pc[k] = ((midi % 12) + 12) % 12
        }
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        val chroma = DoubleArray(12)
        var frames = 0
        var start = 0
        while (start + N <= length) {
            var sq = 0.0
            for (i in 0 until N) {
                val v = mono[start + i] / 32768.0
                sq += v * v
                re[i] = v * win[i]
                im[i] = 0.0
            }
            val rms = sqrt(sq / N)
            if (rms >= 1e-3) {
                fft(re, im)
                for (k in lo..hi) chroma[pc[k]] += ln1p(re[k] * re[k] + im[k] * im[k]) * rms
                frames++
            }
            start += N
        }
        return if (frames < 8) null else chroma
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until 12) {
            ma += a[i] / 12
            mb += b[i] / 12
        }
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until 12) {
            sab += (a[i] - ma) * (b[i] - mb)
            saa += (a[i] - ma) * (a[i] - ma)
            sbb += (b[i] - mb) * (b[i] - mb)
        }
        return if (saa <= 0 || sbb <= 0) Double.NaN else sab / sqrt(saa * sbb)
    }

    /** In-place radix-2 FFT (length a power of two). */
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
