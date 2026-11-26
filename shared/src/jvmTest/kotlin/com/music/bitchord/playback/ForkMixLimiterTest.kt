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
}
