package com.music.bitchord.playback

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mix sum's limiter: −1 dBFS on the summing frames, a 5 ms lookahead, a 100 ms release. */
class ForkMixLimiterTest {

    private val rate = 1000 // 5 frames lookahead, 100 frames release

    @Test
    fun underTheCeilingNothingChanges() {
        val limiter = ForkMixLimiter()
        val block = FloatArray(200) { if (it % 2 == 0) 0.89f else -0.89f }
        limiter.process(block, block.size, 2, rate)
        assertTrue(block.all { abs(it) == 0.89f })
        assertTrue(limiter.idle)
    }

    @Test
    fun aPeakIsHeldAtTheCeilingAndTheGainStartsDownBeforeIt() {
        val limiter = ForkMixLimiter()
        val block = FloatArray(100) { 0.5f }
        block[50] = 1.8f
        limiter.process(block, block.size, 1, rate)
        assertEquals(ForkMixLimiter.CEILING, block[50], 1e-5f)
        assertTrue(block.all { it <= ForkMixLimiter.CEILING + 1e-6f })
        assertEquals(0.5f, block[40], 1e-6f) // before the lookahead
        assertTrue(block[47] < 0.5f) // inside it
        assertTrue(block[55] < 0.5f && block[99] > block[55]) // releasing
    }

    @Test
    fun aPeakOnTheBlocksFirstFrameIsStillCaught() {
        val limiter = ForkMixLimiter()
        val block = floatArrayOf(-2f, 2f, 0.3f, 0.3f)
        limiter.process(block, block.size, 2, rate)
        assertEquals(-ForkMixLimiter.CEILING, block[0], 1e-5f)
        assertEquals(ForkMixLimiter.CEILING, block[1], 1e-5f)
    }

    @Test
    fun framesPastTheSumOnlyRelease() {
        val limiter = ForkMixLimiter()
        val block = FloatArray(10) { 1.5f }
        limiter.process(block, block.size, 1, rate)
        val alone = FloatArray(2000) { 1.5f }
        limiter.process(alone, alone.size, 1, rate, active = 0)
        assertTrue(alone[0] < 1.5f) // still releasing, no jump
        assertEquals(1.5f, alone[1999], 1e-6f) // a single deck past the release is untouched, over the ceiling or not
        assertTrue(limiter.idle)
    }

    @Test
    fun aNanOrInfiniteSampleIsSilenceAndAnEmptyBlockIsNothing() {
        val limiter = ForkMixLimiter()
        val block = floatArrayOf(Float.NaN, 0.5f, Float.POSITIVE_INFINITY, 0.5f, Float.NEGATIVE_INFINITY, 0.5f)
        limiter.process(block, block.size, 2, rate)
        assertEquals(listOf(0f, 0.5f, 0f, 0.5f, 0f, 0.5f), block.toList())
        assertTrue(limiter.idle)
        limiter.process(FloatArray(0), 0, 2, rate)
        limiter.process(FloatArray(4), 4, 2, 0)
        assertTrue(limiter.idle)
    }

    /** The PC resets it on a seek and a new song (DesktopAudioEngine): the new position starts at full level. */
    @Test
    fun aResetStartsTheNextAudioAtFullLevel() {
        val limiter = ForkMixLimiter()
        val loud = FloatArray(20) { 1.8f }
        limiter.process(loud, loud.size, 1, rate)
        assertTrue(!limiter.idle)
        limiter.reset()
        val after = FloatArray(10) { 0.5f }
        limiter.process(after, after.size, 1, rate, active = 0)
        assertTrue(after.all { it == 0.5f })
    }

    /**
     * The one-pass lookahead gives the ramps the frame-by-frame walk gave (audit 2026-11-26 part 1b #8): loud random
     * blocks, sparse and dense peaks, mono and stereo, several lookaheads and active spans, against that walk.
     */
    @Test
    fun theOnePassLookaheadMatchesTheFrameByFrameWalk() {
        val rnd = java.util.Random(7)
        for (trial in 0 until 300) {
            val ahead = intArrayOf(1, 2, 5, 17, 64, 240)[trial % 6]
            val channels = 1 + trial % 2
            val frames = 1 + rnd.nextInt(700)
            val dense = trial % 3
            val x = FloatArray(frames * channels) {
                val loud = when (dense) { 0 -> rnd.nextInt(40) == 0; 1 -> rnd.nextInt(3) == 0; else -> true }
                (if (loud) 0.5f + 2.5f * rnd.nextFloat() else 0.8f * rnd.nextFloat()) * (if (rnd.nextBoolean()) 1f else -1f)
            }
            val active = if (trial % 4 == 0) rnd.nextInt(frames + 1) else Int.MAX_VALUE
            val got = x.copyOf()
            ForkMixLimiter(lookaheadSec = ahead / 1000.0).process(got, got.size, channels, 1000, active)
            val want = x.copyOf()
            walked(want, channels, ((ahead / 1000.0) * 1000).toInt().coerceAtLeast(1), active) // (the limiter's own rounding)
            for (i in got.indices) assertEquals(want[i], got[i], 2e-6f, "trial $trial (ahead $ahead, $channels ch) at $i")
        }
    }

    /** The limiter as it was, its lookahead walking each ramp frame by frame, from rest. */
    private fun walked(samples: FloatArray, channels: Int, ahead: Int, active: Int) {
        val frames = samples.size / channels
        val live = active.coerceIn(0, frames)
        val need = FloatArray(frames) { f ->
            if (f >= live) 1f else {
                var peak = 0f
                for (c in 0 until channels) peak = maxOf(peak, abs(samples[f * channels + c]))
                if (peak > ForkMixLimiter.CEILING) ForkMixLimiter.CEILING / peak else 1f
            }
        }
        for (f in 0 until live) {
            val n = need[f]
            if (n >= 1f) continue
            for (k in 1..minOf(ahead, f)) {
                val ramp = n + (1f - n) * k / ahead
                if (ramp < need[f - k]) need[f - k] = ramp
            }
        }
        val recover = (1.0 - kotlin.math.exp(-1.0 / (ForkMixLimiter.RELEASE_SEC * 1000))).toFloat()
        var g = 1f
        for (f in 0 until frames) {
            g = minOf(need[f], g + (1f - g) * recover)
            if (g > 0.99988f) g = 1f
            if (g < 1f) for (c in 0 until channels) samples[f * channels + c] *= g
        }
    }
}
