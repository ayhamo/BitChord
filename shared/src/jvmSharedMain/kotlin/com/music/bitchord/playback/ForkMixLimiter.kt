package com.music.bitchord.playback

import kotlin.math.abs
import kotlin.math.exp

/**
 * Fork: a soft limiter for where two sources sum into one stream during a mix: the PC's blend
 * (both decks summed in software, `DesktopAudioEngine.blend`) and, on the phone, an Automix v2 echo
 * out's catch, where for its 10 ms the dry cut still sounds under the repeat starting at full
 * ([ForkMixShelf]). The phones' playback found +5.4 dB peaks there (owner via the master,
 * 2026-11-24); a gain trim (`1 / sqrt(out + in)`, what both apps already apply) is sized on the
 * faders, not on the samples, and can't see two transients landing together.
 *
 * Ceiling −1 dBFS, [LOOKAHEAD_SEC] lookahead, [RELEASE_SEC] release. The lookahead is taken from the
 * block in hand rather than by delaying the audio, so a limiter that isn't limiting adds no latency
 * and no change: the gain only ever drops on the frames the caller marks as summing ([process]'s
 * `active`), and outside them it only recovers. A single deck playing alone is never touched.
 *
 * How: each frame's need is `ceiling / peak` (1 when under the ceiling); a backward pass starts the
 * gain falling a lookahead before each peak, linearly, so it reaches the need on the peak itself; a forward pass recovers towards 1 at the release rate and never above the need. The
 * output is therefore never above the ceiling on an active frame, even for a peak at a block's very
 * start (the gain then simply falls faster than the lookahead would have it).
 */
class ForkMixLimiter(
    private val ceiling: Float = CEILING,
    private val lookaheadSec: Double = LOOKAHEAD_SEC,
    private val releaseSec: Double = RELEASE_SEC,
) {
    private var gain = 1f
    private var need = FloatArray(0)

    /** True while the gain is back at 1: a call with no active frames would change nothing. */
    val idle: Boolean get() = gain >= 1f

    /**
     * Limits [samples] (interleaved, [sampleCount] samples of [channelCount] channels) in place. Only
     * the first [active] frames may pull the gain down (the frames where two sources sum); the rest
     * of the block only recovers. Default: the whole block.
     */
    fun process(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int, active: Int = Int.MAX_VALUE) {
        val channels = channelCount.coerceAtLeast(1)
        val frames = sampleCount / channels
        if (frames <= 0 || sampleRate <= 0 || samples.size < frames * channels) return
        val live = active.coerceIn(0, frames)
        if (live == 0 && idle) return
        if (need.size < frames) need = FloatArray(frames)
        for (f in 0 until frames) {
            if (f >= live) { need[f] = 1f; continue }
            var peak = 0f
            val base = f * channels
            for (c in 0 until channels) {
                val x = samples[base + c]
                // a NaN or infinite sample is silence: left in, it would hold the gain at 0 (or pass through as noise)
                if (!x.isFinite()) { samples[base + c] = 0f; continue }
                peak = maxOf(peak, abs(x))
            }
            need[f] = if (peak > ceiling) ceiling / peak else 1f
        }
        // lookahead: the gain starts down a lookahead before a peak, linearly to its need on the peak
        val ahead = (lookaheadSec * sampleRate).toInt().coerceAtLeast(1)
        // forwards, so a ramp only ever lands on frames already passed and never starts a ramp of its own
        for (f in 0 until live) {
            val n = need[f]
            if (n >= 1f) continue
            for (k in 1..minOf(ahead, f)) {
                val ramp = n + (1f - n) * k / ahead
                if (ramp < need[f - k]) need[f - k] = ramp
            }
        }
        // release towards 1, never above the need
        val recover = (1.0 - exp(-1.0 / (releaseSec * sampleRate))).toFloat()
        var g = gain
        for (f in 0 until frames) {
            g = minOf(need[f], g + (1f - g) * recover)
            if (g > RESTED) g = 1f
            if (g < 1f) {
                val base = f * channels
                for (c in 0 until channels) samples[base + c] *= g
            }
        }
        gain = g
    }

    /** Back to no reduction (a flush, a new song). */
    fun reset() {
        gain = 1f
    }

    companion object {
        /** −1 dBFS. */
        const val CEILING = 0.8912509f
        const val LOOKAHEAD_SEC = 0.005
        const val RELEASE_SEC = 0.1

        /** Within 0.001 dB of 1: called rested, so a long recovery tail doesn't keep the limiter busy. */
        private const val RESTED = 0.99988f
    }
}
