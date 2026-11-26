package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The analysis maths the extension runs in its host page, from BitChord web (models.ts pickPeaks /
 * tempoFromBeats / gridConfidence, beatgrid.ts fitGrid / normalizeTempo, analysis.worker.ts
 * localGrid, energy.ts, camelot.ts) and the extension's host.js contentEnd. Pure Kotlin; a port of
 * the phone's BeatGrid.java.
 */
object ForkV2BeatGrid {
    const val BEAT_FPS = 50.0
    internal const val PEAK_WINDOW = 7
    internal const val MIN_BEATS = 8

    /** Local maxima over seven frames with a positive logit, ties collapsed, refined by a parabola. */
    fun pickPeaks(logits: FloatArray): DoubleArray {
        val half = PEAK_WINDOW shr 1
        val peaks = ArrayList<Int>()
        for (i in logits.indices) {
            if (logits[i] <= 0) continue
            var isMax = true
            var o = -half
            while (o <= half && isMax) {
                val n = i + o
                if (n >= 0 && n < logits.size && logits[n] > logits[i]) isMax = false
                o++
            }
            if (isMax) peaks.add(i)
        }
        val deduped = ArrayList<Int>()
        var i = 0
        while (i < peaks.size) {
            var mean = peaks[i].toDouble()
            var count = 1
            while (i + 1 < peaks.size && peaks[i + 1] - mean <= 1) {
                i++
                count++
                mean += (peaks[i] - mean) / count
            }
            deduped.add(jround(mean).toInt())
            i++
        }
        val out = DoubleArray(deduped.size)
        for (k in out.indices) {
            val f = deduped[k]
            if (f <= 0 || f + 1 >= logits.size) {
                out[k] = f.toDouble()
                continue
            }
            val l = logits[f - 1].toDouble()
            val c = logits[f].toDouble()
            val r = logits[f + 1].toDouble()
            val d = l - 2 * c + r
            out[k] = if (abs(d) <= 1e-9) f.toDouble() else f + max(-0.5, min(0.5, (0.5 * (l - r)) / d))
        }
        return out
    }

    internal fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val s = v.copyOf()
        s.sort()
        return s[s.size shr 1]
    }

    internal fun gaps(beats: DoubleArray): DoubleArray = DoubleArray(max(0, beats.size - 1)) { beats[it + 1] - beats[it] }

    fun tempoFromBeats(beats: DoubleArray): Double {
        if (beats.size < MIN_BEATS) return 0.0
        val gaps = gaps(beats)
        val rough = median(gaps)
        if (rough <= 0) return 0.0
        val kept = gaps.filter { abs(it - rough) <= rough * 0.2 }.toDoubleArray()
        val interval = median(if (kept.size >= 4) kept else gaps)
        if (interval <= 0) return 0.0
        val bpm = 60 / interval
        return if (bpm >= 40 && bpm <= 220) bpm else 0.0
    }

    /** Regularity of the spacing and decisiveness of the peaks, capped at 0.95 (BeatTracker.gridConfidence). */
    fun gridConfidence(beats: DoubleArray, peakLogits: DoubleArray): Double {
        if (beats.size < MIN_BEATS) return 0.0
        val gaps = gaps(beats)
        val interval = median(gaps)
        if (interval <= 0) return 0.0
        var regular = 0
        for (g in gaps) if (abs(g - interval) <= interval * 0.1) regular++
        val strength = 1 / (1 + exp(-(median(peakLogits) - 0.5)))
        return max(0.0, min(0.95, 0.35 + 0.4 * regular / gaps.size + 0.25 * strength))
    }

    /** models.ts trackBeats after the model: beats and downbeats (song seconds) from the two logit tracks. */
    fun trackBeats(beatLogits: FloatArray, downLogits: FloatArray, offsetSec: Double): ForkV2Analysis.BeatSide? {
        val beatFrames = pickPeaks(beatLogits)
        val beats = DoubleArray(beatFrames.size) { beatFrames[it] / BEAT_FPS + offsetSec }
        if (beats.size < MIN_BEATS) return null
        val bpm = tempoFromBeats(beats)
        if (bpm <= 0) return null
        val downFrames = pickPeaks(downLogits)
        val downs = HashSet<Double>()
        for (f in downFrames) {
            val t = f / BEAT_FPS + offsetSec
            var best = beats[0]
            for (b in beats) if (abs(b - t) < abs(best - t)) best = b
            downs.add(best)
        }
        val peakLogits = DoubleArray(beatFrames.size) { i ->
            val f = jround(beatFrames[i]).toInt()
            if (f >= 0 && f < beatLogits.size) beatLogits[f].toDouble() else 0.0
        }
        val s = ForkV2Analysis.BeatSide()
        s.beats = beats
        s.downbeats = downs.sorted().toDoubleArray()
        s.bpm = bpm
        s.confidence = gridConfidence(beats, peakLogits)
        return s
    }

    /** beatgrid.ts fitGrid: the beat period on which the ticks' phases line up best; {interval, offset} or null. */
    fun fitGrid(ticks: DoubleArray): DoubleArray? {
        val n = ticks.size
        if (n < 8) return null
        val g = gaps(ticks)
        g.sort()
        val median = g[g.size shr 1]
        if (!(median > 0)) return null
        val best = doubleArrayOf(median, -1.0, 0.0) // interval, coherence, phase
        scan(ticks, median * 0.96, median * 1.04, 400, best)
        val step = best[0] * 0.0002
        scan(ticks, best[0] - step, best[0] + step, 40, best)
        val interval = best[0]
        val offset = ((((best[2] / (2 * PI)) % 1) + 1) % 1) * interval
        return doubleArrayOf(interval, offset)
    }

    private fun scan(ticks: DoubleArray, lo: Double, hi: Double, steps: Int, best: DoubleArray) {
        val n = ticks.size
        for (k in 0..steps) {
            val interval = lo + ((hi - lo) * k) / steps
            var c = 0.0
            var s = 0.0
            for (t in ticks) {
                val a = (2 * PI * t) / interval
                c += cos(a)
                s += sin(a)
            }
            val coherence = hypot(c, s) / n
            if (coherence > best[1]) {
                best[0] = interval
                best[1] = coherence
                best[2] = atan2(s, c)
            }
        }
    }

    /** normalizeTempo: the beat interval folded into 70-180 BPM. */
    internal fun normalizeInterval(beatIntervalSec0: Double): Double {
        // 0, a negative, an infinite or a subnormal interval never folds into 70-180 (the bpm stays 0, infinite or
        // negative) and looped forever: no tempo, as NaN is
        if (!(beatIntervalSec0 > 0) || !beatIntervalSec0.isFinite() || !(60 / beatIntervalSec0).isFinite()) return Double.NaN
        var beatIntervalSec = beatIntervalSec0
        var bpm = 60 / beatIntervalSec
        while (bpm < 70) {
            bpm *= 2
            beatIntervalSec /= 2
        }
        while (bpm > 180) {
            bpm /= 2
            beatIntervalSec *= 2
        }
        return beatIntervalSec
    }

    /** analysis.worker.ts localGrid: a model grid as AutoMix's constant grid (confidence 0.35-0.95 onto 0-1). */
    fun localGrid(side: ForkV2Analysis.BeatSide): ForkV2Analysis.LocalGrid {
        val fit = fitGrid(side.beats)
        val beatIntervalSec = if (fit != null) normalizeInterval(fit[0]) else 60 / side.bpm
        val g = ForkV2Analysis.LocalGrid()
        g.bpm = 60 / beatIntervalSec
        g.beatIntervalSec = beatIntervalSec
        g.firstDownbeatSec = if (side.downbeats.isNotEmpty()) side.downbeats[0] else side.beats[0]
        g.gridConfidence = max(0.0, min(1.0, (side.confidence - 0.35) / 0.6))
        return g
    }

    // ---- energy.ts ----

    /** Mono PCM, however it is stored. */
    interface Samples {
        fun length(): Int

        fun get(i: Int): Float
    }

    fun of(a: FloatArray): Samples = object : Samples {
        override fun length(): Int = a.size

        override fun get(i: Int): Float = a[i]
    }

    fun of(a: ShortArray, length: Int): Samples = object : Samples {
        override fun length(): Int = length

        override fun get(i: Int): Float = a[i] / 32768f
    }

    /** Per-window RMS (normalized to peak = 1) of mono PCM. */
    fun energyCurve(samples: Samples, sampleRate: Double, windowSec: Double): DoubleArray {
        val win = max(1L, jround(windowSec * sampleRate)).toInt()
        val len = samples.length()
        val n = (len + win - 1) / win
        val curve = DoubleArray(n)
        var peak = 1e-9
        for (w in 0 until n) {
            val start = w * win
            val end = min(len, start + win)
            var acc = 0.0
            for (i in start until end) {
                val x = samples.get(i)
                acc += x * x
            }
            curve[w] = sqrt(acc / (end - start))
            peak = max(peak, curve[w])
        }
        for (w in 0 until n) curve[w] /= peak
        return curve
    }

    /** {introEndSec, outroStartSec}: full energy is two windows in a row at 60% of the peak or more. */
    fun introOutro(curve: DoubleArray, windowSec: Double, durationSec: Double): DoubleArray {
        val THRESH = 0.6
        var introEnd = 0.0
        for (i in 0 until curve.size - 1) {
            if (curve[i] >= THRESH && curve[i + 1] >= THRESH) {
                introEnd = i * windowSec
                break
            }
        }
        var outroStart = durationSec
        for (i in curve.size - 2 downTo 0) {
            if (curve[i] >= THRESH && curve[i + 1] >= THRESH) {
                outroStart = min(durationSec, (i + 2) * windowSec)
                break
            }
        }
        return doubleArrayOf(min(introEnd, durationSec / 3), max(outroStart, durationSec / 2))
    }

    /**
     * host.js contentEnd (BitChord phone audio_analysis.cpp): where the music ends, from RMS in 0.25 s
     * windows; NaN when unmeasurable.
     */
    fun contentEnd(mono: Samples, sr: Double): Double {
        val win = jround(0.25 * sr).toInt()
        val n = mono.length() / win
        if (n < 12) return Double.NaN
        val env = FloatArray(n)
        for (w in 0 until n) {
            var ss = 0.0
            var c = 0
            var i = w * win
            val e = i + win
            while (i < e) {
                val x = mono.get(i).toDouble()
                ss += x * x
                c++
                i += 4
            }
            env[w] = sqrt(ss / c).toFloat()
        }
        val sorted = env.copyOf()
        sorted.sort()
        val ref = sorted[floor(n * 0.85).toInt()].toDouble()
        val noise = sorted[floor(n * 0.05).toInt()].toDouble()
        if (!(ref > 0)) return Double.NaN
        val thr = max(0.0025, max(min(noise * 2.6, ref * 0.28), ref * 0.1))
        val silence = max(0.0015, min(thr * 0.25, ref * 0.04))
        var w = n
        while (w > 0 && env[w - 1] < silence) w--
        if ((n - w) * 0.25 >= 0.35) return w * 0.25
        for (b in n - 6 downTo 0) {
            var on = 0
            var sum = 0.0
            for (k in b until b + 6) {
                if (env[k] >= thr) on++
                sum += env[k]
            }
            if (on >= 3 && sum / 6 >= 0.85 * thr) return (b + 6) * 0.25
        }
        return Double.NaN
    }

    // ---- camelot.ts ----

    private val MAJOR: Map<String, Int> = mapOf(
        "C" to 8, "G" to 9, "D" to 10, "A" to 11, "E" to 12, "B" to 1, "F#" to 2, "Gb" to 2, "C#" to 3, "Db" to 3, "G#" to 4, "Ab" to 4,
        "D#" to 5, "Eb" to 5, "A#" to 6, "Bb" to 6, "F" to 7,
    )
    private val MINOR: Map<String, Int> = mapOf(
        "A" to 8, "E" to 9, "B" to 10, "F#" to 11, "Gb" to 11, "C#" to 12, "Db" to 12, "G#" to 1, "Ab" to 1, "D#" to 2, "Eb" to 2,
        "A#" to 3, "Bb" to 3, "F" to 4, "C" to 5, "G" to 6, "D" to 7,
    )

    /** A key name such as "E♭ minor" or "F# major" into the analysis' Camelot fields; false if unknown. */
    fun camelot(keyName: String?, a: ForkV2Analysis): Boolean {
        if (keyName == null) return false
        val parts = keyName.trim { it <= ' ' }.replace('♯', '#').replace('♭', 'b').split(Regex("\\s+"))
        if (parts.size < 2) return false
        val major = parts[1].lowercase().startsWith("maj")
        val num = (if (major) MAJOR else MINOR)[parts[0]] ?: return false
        a.camelotNum = num
        a.camelotLetter = if (major) 'B' else 'A'
        return true
    }
}
