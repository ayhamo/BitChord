package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** Fork: partial writes (from Desktop Revived 337653d8). */
class ForkPcmWriteTest {

    @Test
    fun aShortWriteNeverDropsOrRepeatsAudio() {
        for (bytes in listOf(2, 4)) {
            val samples = FloatArray(48) { it.toFloat() }
            val heard = mutableListOf<Float>()
            var frames = 0
            val written = forkWritePcmFully(samples, samples.size, 2, bytes, { pending, count ->
                val taken = minOf(count, 6)
                repeat(taken) { heard += pending[it] }
                taken * bytes
            }, { frames += it })
            assertEquals(samples.toList(), heard)
            assertEquals(24, frames)
            assertEquals(24, written)
        }
    }

    @Test
    fun aWriteThatTakesNothingNeverAdvancesTheClock() {
        for (invalid in listOf(0, -1, 1, 3)) {
            var frames = 0
            var waits = 0
            val written = forkWritePcmFully(FloatArray(20), 20, 2, 2, { _, _ -> invalid }, { frames += it }, { waits++ })
            assertEquals(0, frames)
            assertEquals(0, written)
            assertEquals(ForkPcmWrite.ZERO_RETRIES, waits)
        }
    }

    @Test
    fun moreThanOfferedCountsOnlyWhatWasOffered() {
        var frames = 0
        forkWritePcmFully(FloatArray(20), 20, 2, 2, { _, _ -> 1000 }, { frames += it })
        assertEquals(10, frames)
    }
}
