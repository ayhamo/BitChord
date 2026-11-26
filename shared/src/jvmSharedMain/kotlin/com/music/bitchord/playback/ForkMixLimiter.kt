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
        lookahead(live, (lookaheadSec * sampleRate).toInt().coerceAtLeast(1))
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

    private var hull = IntArray(0)

    /**
     * Each frame's need lowered to the lowest of the ramps over it: the ramp of a frame `f` needing `n` reaches frame
     * `j = f - k` (1 <= k < [ahead]) at `n + (1 - n) * k / ahead`. Walking every ramp frame by frame was O(frames x
     * ahead) on the audio thread (960 frames a ramp at 192 kHz, on nearly every frame of a loud master: audit 2026-11-26
     * part 1b #8); this is the same lowest ramp in one backward pass.
     *
     * How: a ramp's reduction `1 - ramp` at frame `j` is the line `a * (j - s)` / ahead, with `a = 1 - n` and `s = f - ahead`
     * (it starts there at 0), live for `s < j < f`. Walking `j` down, ramps join at `j = f - 1` and leave at `j <= s`, both in
     * order of `f`, so the live ones sit in a queue ([hull]), newest at the front. A newer ramp at least as deep as an
     * older one (a >= a') beats it on every frame left to both, so the older goes; the queue then runs shallowest to
     * deepest, and the lines' upper hull is kept (a middle line never above both neighbours goes: once the deeper
     * neighbour leaves at its s, the shallower one is above the middle one anyway). As `j` falls the best line moves to
     * the shallower end, so a deepest line beaten by its neighbour goes for good, and the deepest left is the answer.
     */
    private fun lookahead(live: Int, ahead: Int) {
        if (live < 2) return
        if (hull.size < live) hull = IntArray(live)
        if (origNeed.size < live) origNeed = FloatArray(live)
        val q = hull
        // filled from the array's end towards its start: at most live - 1 ramps ever join, so the front never runs out
        var head = q.size // the front: the newest ramp (q[head] smallest f)
        var tail = q.size // one past the back: the oldest
        var origNext = need[live - 1]
        for (j in live - 2 downTo 0) {
            // the ramp of frame j + 1 joins (its need as measured, before this pass lowered it)
            val f = j + 1
            val nf = origNext
            origNext = need[j]
            if (nf < 1f) {
                val af = 1.0 - nf
                // older ramps no deeper than it: beaten on every frame left
                while (tail > head && 1.0 - need0(q[head]) <= af) head++
                // the front one never above both its neighbours: off the hull
                while (tail - head >= 2 && !onHull(f, af, q[head], q[head + 1], ahead)) head++
                q[--head] = f
                origNeed[f] = nf
            }
            // ramps that ended here (j <= f - ahead: back at 1)
            while (tail > head && q[tail - 1] - ahead >= j) tail--
            // the deepest line, beaten by its neighbour now, stays beaten as j falls
            while (tail - head >= 2 && lift(q[tail - 2], j, ahead) >= lift(q[tail - 1], j, ahead)) tail--
            if (tail > head) {
                val g = q[tail - 1]
                val n = need0(g)
                val ramp = n + (1f - n) * (g - j) / ahead
                if (ramp < need[j]) need[j] = ramp
            }
        }
    }

    // a ramp's frame's own need, as measured: [need] is lowered as the pass walks, so the queue keeps its own copy
    private var origNeed = FloatArray(0)

    private fun need0(f: Int): Float = origNeed[f]

    /** Ramp [f]'s reduction at frame [j], times ahead: (1 - n) * (j - (f - ahead)). */
    private fun lift(f: Int, j: Int, ahead: Int): Double = (1.0 - need0(f)) * (j - (f - ahead))

    /**
     * Whether line [m] stays on the upper hull between a new shallower line [l] (slope [al]) and [r]: some frame has it
     * above both, i.e. it crosses [l] before [r] crosses it.
     */
    private fun onHull(l: Int, al: Double, m: Int, r: Int, ahead: Int): Boolean {
        val am = 1.0 - need0(m)
        val ar = 1.0 - need0(r)
        val sl = (l - ahead).toDouble()
        val sm = (m - ahead).toDouble()
        val sr = (r - ahead).toDouble()
        // where l and m cross (m above to the right of it), and where m and r cross (r above to the right of it)
        val xlm = (al * sl - am * sm) / (al - am)
        val xmr = (am * sm - ar * sr) / (am - ar)
        return xlm < xmr
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
