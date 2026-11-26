package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A song queued without a length is still analysed ahead of its transition, its length read off the audio. */
class ForkAnalysisLengthTest {

    @Test
    fun aSongWithoutALengthIsQueuedAndMeasuredFromItsAudio() {
        assertEquals(0.0, "".durationSeconds(), "a song queued without a length")
        assertTrue(ForkAnalysisLength.asks("".durationSeconds()), "queued all the same, not left until it is armed")
        assertTrue(ForkAnalysisLength.asks("3:29".durationSeconds()))
        assertFalse(ForkAnalysisLength.asks(-1.0))
        assertFalse(ForkAnalysisLength.asks(Double.NaN))

        assertEquals(209.0, ForkAnalysisLength.seconds(0.0, 209 * 22_050, 22_050.0), "the decoded audio's length")
        assertEquals(209.0, ForkAnalysisLength.seconds(209.0, 100, 22_050.0), "a known length is kept")
        assertEquals(0.0, ForkAnalysisLength.seconds(0.0, 100, 0.0))
    }
}
