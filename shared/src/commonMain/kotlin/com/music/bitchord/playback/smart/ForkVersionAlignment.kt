package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fork: the phone's "Smart audio alignment" maths, for the PC's version switch (video and audio cuts of
 * one song). Taken unchanged from upstream's `app/.../playback/smart/VersionAudioAligner.kt` (RMS
 * energy envelope at 10 ms hops over 40 ms windows, FFT cross-correlation with prefix-sum
 * normalisation, a 0.40 confidence floor, a peak pinned to the search edge refused). There it is
 * private to the Android aligner, which also opens the phone's cache; here it is plain Kotlin so the
 * desktop can feed it the two openings it decodes. The phone keeps its own copy (no upstream file
 * edited); a change to the phone's maths is a change to make here too.
 */
object ForkVersionAlignment {

    /** 100 frames per second (10 ms resolution). */
    const val HOP_MS = 10.0
    /** 40 ms analysis window. */
    const val WIN_MS = 40.0
    /** Searched up to ±40 s of intro or drift. */
    const val MAX_LAG_SECONDS = 40.0
    /** Minimum normalised correlation to believe. */
    const val MIN_CONFIDENCE = 0.40
    /** How much of each cut's opening the phone decodes (`decodeRegion(…, 0.0, 45.0)`). */
    const val HEAD_SECONDS = 45.0

    /**
     * A peak found by [crossCorrelate], with the lag range it was found in: a lag on either edge is a
     * lag the window was too short to move past, not a measurement (see [offsetMs]).
     */
    data class Correlation(val lagFrames: Int, val confidence: Double, val floor: Int, val ceiling: Int)

    /**
     * Where the target cut stands against the source: target time = source time + the result, in ms.
     * Null when it could not be measured (nothing decoded, too weak a match, a peak pinned to the edge);
     * never folded into 0, which is a real answer (both cuts open on the same second).
     */
    fun offsetMs(
        source: FloatArray,
        sourceRate: Double,
        target: FloatArray,
        targetRate: Double,
        sourceCount: Int = source.size,
        targetCount: Int = target.size,
    ): Long? {
        val sourceEnv = envelope(source, sourceRate, sourceCount)
        val targetEnv = envelope(target, targetRate, targetCount)
        if (sourceEnv.isEmpty() || targetEnv.isEmpty()) return null
        return offsetMs(crossCorrelate(sourceEnv, targetEnv))
    }

    /** The phone's verdict on a [Correlation]: the confidence floor, then the edge check. */
    fun offsetMs(correlation: Correlation): Long? {
        if (correlation.confidence < MIN_CONFIDENCE) return null
        if (correlation.lagFrames == correlation.floor || correlation.lagFrames == correlation.ceiling) return null
        return (correlation.lagFrames * HOP_MS).toLong()
    }

    /** RMS energy over sliding windows, one value per [hopMs]. */
    fun envelope(
        samples: FloatArray,
        sampleRate: Double,
        count: Int = samples.size,
        hopMs: Double = HOP_MS,
        winMs: Double = WIN_MS,
    ): FloatArray {
        val size = count.coerceIn(0, samples.size)
        val hopSamples = max(1, (sampleRate * (hopMs / 1000.0)).toInt())
        val winSamples = max(hopSamples, (sampleRate * (winMs / 1000.0)).toInt())
        val numFrames = max(0, (size - winSamples) / hopSamples)
        if (numFrames <= 0) return FloatArray(0)
        val env = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            val start = i * hopSamples
            val end = min(size, start + winSamples)
            var sumSq = 0.0
            for (j in start until end) {
                val s = samples[j]
                sumSq += s * s
            }
            env[i] = sqrt(sumSq / (end - start)).toFloat()
        }
        return env
    }

    /**
     * Normalised cross-correlation of the two envelopes: the best lag in frames (positive: the target's
     * event comes later, target = source + lag), its confidence, and the range searched. At least
     * 3 s of overlap per lag. Per-lag means and variances from prefix sums, the cross terms from one
     * FFT pass, as on the phone.
     */
    fun crossCorrelate(
        sourceEnv: FloatArray,
        targetEnv: FloatArray,
        hopMs: Double = HOP_MS,
        maxLagSec: Double = MAX_LAG_SECONDS,
    ): Correlation {
        if (sourceEnv.isEmpty() || targetEnv.isEmpty()) return Correlation(0, 0.0, 0, 0)

        val maxLagFrames = (maxLagSec * 1000.0 / hopMs).toInt()
        val minOverlapFrames = (3.0 * 1000.0 / hopMs).toInt()

        var bestLag = 0
        var bestCorrelation = -1.0

        val minLag = max(-maxLagFrames, -(sourceEnv.size - minOverlapFrames))
        val maxLag = min(maxLagFrames, targetEnv.size - minOverlapFrames)

        val crossTerms = crossTermsByLag(sourceEnv, targetEnv)

        val srcPrefix = DoubleArray(sourceEnv.size + 1)
        val srcSqPrefix = DoubleArray(sourceEnv.size + 1)
        for (i in sourceEnv.indices) {
            val v = sourceEnv[i].toDouble()
            srcPrefix[i + 1] = srcPrefix[i] + v
            srcSqPrefix[i + 1] = srcSqPrefix[i] + v * v
        }
        val tgtPrefix = DoubleArray(targetEnv.size + 1)
        val tgtSqPrefix = DoubleArray(targetEnv.size + 1)
        for (i in targetEnv.indices) {
            val v = targetEnv[i].toDouble()
            tgtPrefix[i + 1] = tgtPrefix[i] + v
            tgtSqPrefix[i + 1] = tgtSqPrefix[i] + v * v
        }

        for (lag in minLag..maxLag) {
            val srcStart = max(0, -lag)
            val tgtStart = max(0, lag)
            val overlapLen = min(sourceEnv.size - srcStart, targetEnv.size - tgtStart)
            if (overlapLen < minOverlapFrames) continue

            val sumSrc = srcPrefix[srcStart + overlapLen] - srcPrefix[srcStart]
            val sumTgt = tgtPrefix[tgtStart + overlapLen] - tgtPrefix[tgtStart]
            val sumSrcSq = srcSqPrefix[srcStart + overlapLen] - srcSqPrefix[srcStart]
            val sumTgtSq = tgtSqPrefix[tgtStart + overlapLen] - tgtSqPrefix[tgtStart]

            // Centred: Σ(x−x̄)(y−ȳ) = Σxy − (Σx)(Σy)/n, so the confidence can't pass 1.
            val num = crossTerms[lag + sourceEnv.size - 1] - sumSrc * sumTgt / overlapLen
            val denSrc = sumSrcSq - sumSrc * sumSrc / overlapLen
            val denTgt = sumTgtSq - sumTgt * sumTgt / overlapLen

            val denom = sqrt(denSrc * denTgt)
            if (denom > 1e-6) {
                val r = num / denom
                if (r > bestCorrelation) {
                    bestCorrelation = r
                    bestLag = lag
                }
            }
        }

        return Correlation(bestLag, bestCorrelation, minLag, maxLag)
    }

    /** Σ source[i]·target[i+lag] for every lag, indexed `result[lag + source.size - 1]`; one zero-padded FFT pair. */
    private fun crossTermsByLag(source: FloatArray, target: FloatArray): DoubleArray {
        val n = source.size
        val m = target.size
        var fftLen = 1
        while (fftLen < n + m) fftLen = fftLen shl 1

        val srcRe = DoubleArray(fftLen)
        val srcIm = DoubleArray(fftLen)
        for (i in 0 until n) srcRe[i] = source[i].toDouble()
        val tgtRe = DoubleArray(fftLen)
        val tgtIm = DoubleArray(fftLen)
        for (i in 0 until m) tgtRe[i] = target[i].toDouble()

        fft(srcRe, srcIm)
        fft(tgtRe, tgtIm)

        // conj(X) · Y: the cross-correlation, the conjugate keeping the lag's sign as written.
        for (i in 0 until fftLen) {
            val a = srcRe[i]
            val b = -srcIm[i]
            val c = tgtRe[i]
            val d = tgtIm[i]
            srcRe[i] = a * c - b * d
            srcIm[i] = a * d + b * c
        }
        fft(srcRe, srcIm, inverse = true)

        val result = DoubleArray(n + m - 1)
        for (lag in -(n - 1) until m) {
            val bin = ((lag % fftLen) + fftLen) % fftLen
            result[lag + n - 1] = srcRe[bin]
        }
        return result
    }

    /** In-place iterative radix-2 FFT; a power-of-two length. [inverse] divides by the length. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            for (start in 0 until n step len) {
                var twiddleRe = 1.0
                var twiddleIm = 0.0
                for (k in 0 until len / 2) {
                    val evenRe = re[start + k]
                    val evenIm = im[start + k]
                    val oddRe = re[start + k + len / 2] * twiddleRe - im[start + k + len / 2] * twiddleIm
                    val oddIm = re[start + k + len / 2] * twiddleIm + im[start + k + len / 2] * twiddleRe
                    re[start + k] = evenRe + oddRe
                    im[start + k] = evenIm + oddIm
                    re[start + k + len / 2] = evenRe - oddRe
                    im[start + k + len / 2] = evenIm - oddIm
                    val nextRe = twiddleRe * stepRe - twiddleIm * stepIm
                    twiddleIm = twiddleRe * stepIm + twiddleIm * stepRe
                    twiddleRe = nextRe
                }
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
