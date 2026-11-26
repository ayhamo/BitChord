package com.music.bitchord.playback.smart

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fork: Beat This! run the way the phone's Morphe analyser runs it (yt music patches, Analyzer.java
 * analyzeDecoded and Models.beats, 2026-11-25; owner via master: "Port the model now"). Same model
 * ([BeatTracker.MODEL_ASSET]) and the same peak picking, tempo, confidence and chunking as
 * [BeatTracker.track]; what differed was the audio the model was given.
 *
 * Before: the beat model ran on each of the vocal model's ~22 s windows (two at the head, three at the
 * tail) and their beats were stitched at the overlaps' midpoints. Beat This! reads its bars from up to
 * 30 s of context (one 1500-frame chunk), so a 22 s window cut it short, and the head side ended at
 * 43 s. On the Spotify bench (323 songs) BitChord's downbeats were on Spotify's for a median 0.78 of
 * bars against the phone's 0.96 and the PC extension's 0.93, with the same weights.
 *
 * Now, as the phone: one 59 s span from the start ([SPAN_SEC]) and one ending at the song's end, each
 * tracked whole ([BeatTracker.track] chunks it, 1500 frames with a 6-frame border); a song no longer
 * than a span has its head reused as its tail. The audio is the phone's `Fold`: left and right
 * averaged, at 44.1 kHz and up every two frames averaged into one, held as 16-bit, then resampled to
 * the mel's rate. The vocal model keeps its own windows.
 */
object ForkBeatSpans {

    /** Saved with each analysis ([AnalysisStore]); one saved without it is analysed again. */
    const val VERSION = 1

    /** The phone's BEAT_WINDOW_SEC. */
    const val SPAN_SEC = 59.0

    /** [from, to] of the head span and of the tail span (null: the song fits one span, the head is the tail too). */
    fun spans(duration: Double): Pair<Pair<Double, Double>, Pair<Double, Double>?> {
        val head = 0.0 to min(duration, SPAN_SEC)
        val tail = if (duration > SPAN_SEC) (duration - SPAN_SEC) to duration else null
        return head to tail
    }

    /** The phone's Fold over a stereo decode at [rate]: the mono and its rate. */
    fun fold(left: FloatArray, right: FloatArray, rate: Int): Pair<FloatArray, Int> {
        val step = if (rate >= 44_100) 2 else 1
        val n = min(left.size, right.size) / step
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (k in 0 until step) {
                val j = i * step + k
                acc += (left[j] + right[j]) * 0.5f
            }
            out[i] = clip(acc / step) / 32768f
        }
        return out to rate / step
    }

    /** Analyzer.clip: to 16-bit. */
    internal fun clip(x: Float): Short {
        val v = (x * 32767f).roundToInt()
        return max(-32768, min(32767, v)).toShort()
    }

    /** The model's beats over a span decoded in stereo at [rate] from [offsetSec] of the song, or null. */
    fun track(tracker: BeatTracker, left: FloatArray, right: FloatArray, rate: Int, offsetSec: Double): BeatSide? {
        val (mono, monoRate) = fold(left, right, rate)
        if (mono.isEmpty()) return null
        val pcm = if (monoRate.toDouble() == MelSpectrogram.sampleRate) mono
        else MelSpectrogram.resample(mono, monoRate.toDouble(), MelSpectrogram.sampleRate) ?: return null
        return side(tracker.track(pcm, offsetSec))
    }

    fun side(g: BeatTracker.Grid?): BeatSide? =
        g?.let { BeatSide(beats = it.beats, downbeats = it.downbeats, bpm = it.bpm, confidence = it.beatConfidence) }
}
