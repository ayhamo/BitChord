package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: the mix out of a tail that has gone silent (from PC-Continued 18b6b3ae). */
class ForkSilentTailTest {

    private val rate = 44_100

    private fun block(level: Float, frames: Int = 4_410) = FloatArray(frames * 2) { level }

    @Test
    fun countsOnlyBlocksBelowMinusFiftyDb() {
        val meter = ForkSilentTail()
        meter.meter(block(0.001f), 8_820, 2)
        assertEquals(4_410L, meter.silentFrames)
        // -40 dBFS is quiet music, not silence: the count starts over.
        meter.meter(block(0.01f), 8_820, 2)
        assertEquals(0L, meter.silentFrames)
    }

    @Test
    fun aSecondOfSilenceInTheLastFifteenSecondsStartsTheMix() {
        val meter = ForkSilentTail()
        repeat(10) { meter.meter(block(0.0005f), 8_820, 2) }
        assertTrue(ForkSilentTail.mustLeave(8.0, positionMs = 190_000, durationMs = 200_000, meter.silentFrames, rate))
        // Earlier than the last 15 s it is a quiet passage.
        assertFalse(ForkSilentTail.mustLeave(8.0, positionMs = 100_000, durationMs = 200_000, meter.silentFrames, rate))
        // No fade, nothing to start.
        assertFalse(ForkSilentTail.mustLeave(0.0, positionMs = 190_000, durationMs = 200_000, meter.silentFrames, rate))
    }

    @Test
    fun lessThanASecondIsABreath() {
        val meter = ForkSilentTail()
        repeat(9) { meter.meter(block(0.0005f), 8_820, 2) }
        assertFalse(ForkSilentTail.mustLeave(8.0, positionMs = 190_000, durationMs = 200_000, meter.silentFrames, rate))
        meter.reset()
        assertEquals(0L, meter.silentFrames)
    }
}
