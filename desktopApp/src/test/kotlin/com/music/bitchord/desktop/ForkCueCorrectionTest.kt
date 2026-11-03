package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForkCueCorrectionTest {

    @Test
    fun `a landing is the frame's stamp plus what the seek trim cut`() {
        assertEquals(30_010_000L, ForkCueCorrection.landing(frameUs = 30_000_000L, skipFrames = 480, sampleRate = 48_000, askedUs = 30_010_000L))
        // No stamp to say: the ask is all there is.
        assertEquals(42_000_000L, ForkCueCorrection.landing(frameUs = null, skipFrames = 0, sampleRate = 48_000, askedUs = 42_000_000L))
    }

    @Test
    fun `a cue is moved only when its stream landed well away from it`() {
        assertNull(ForkCueCorrection.correctedStart(askedUs = 60_000_000L, landedUs = null))
        assertNull(ForkCueCorrection.correctedStart(askedUs = 60_000_000L, landedUs = 60_100_000L))
        // An untrimmed landing on the index point 4 s before the ask.
        assertEquals(56_000_000L, ForkCueCorrection.correctedStart(askedUs = 60_000_000L, landedUs = 56_000_000L))
    }

    @Test
    fun `the blend's count is checked against the deck's own clock`() {
        // Counted 30 s + 8 s mixed; the deck handed over up to 38.3 s, of which 0.3 s is still in its stretch buffer.
        assertNull(ForkCueCorrection.drift(startUs = 30_000_000L, elapsedUs = 8_000_000L, deckEndUs = 38_300_000L, heldUs = 300_000L))
        // Its stretch buffer held 2 s of audio from before the cue: counted 2 s ahead of the singing.
        assertEquals(
            2_000_000L,
            ForkCueCorrection.drift(startUs = 30_000_000L, elapsedUs = 8_000_000L, deckEndUs = 36_300_000L, heldUs = 300_000L),
        )
        assertNull(ForkCueCorrection.drift(startUs = 30_000_000L, elapsedUs = 8_000_000L, deckEndUs = null, heldUs = 0L))
        // Further out than any cue lands: the stamps are what is wrong.
        assertNull(ForkCueCorrection.drift(startUs = 90_000_000L, elapsedUs = 0L, deckEndUs = 1_000_000L, heldUs = 0L))
    }

    @Test
    fun `a quality upgrade lines up on its landing when it has one`() {
        assertEquals(10_000_000L, ForkCueCorrection.swapStart(armedAtUs = 10_000_000L, landedUs = null))
        assertEquals(9_200_000L, ForkCueCorrection.swapStart(armedAtUs = 10_000_000L, landedUs = 9_200_000L))
    }

    @Test
    fun `the deck clock dates every block from the landing, block after block`() {
        val clock = ForkDeckClock()
        clock.seeked()
        clock.appended(frames = 960, sampleRate = 48_000, sourceLandedUs = 5_000_000L)
        // Later blocks are dated from the one before, whatever the decoder says.
        clock.appended(frames = 960, sampleRate = 48_000, sourceLandedUs = 5_000_000L)
        assertEquals(5_000_000L, clock.landedUs)
        assertNull(clock.takenEndUs)
        clock.taken()
        assertEquals(5_020_000L, clock.takenEndUs)
        clock.taken()
        assertEquals(5_040_000L, clock.takenEndUs)
        // The reader caught up; the next block still follows on, not back at the landing.
        clock.appended(frames = 480, sampleRate = 48_000, sourceLandedUs = 5_000_000L)
        clock.taken()
        assertEquals(5_050_000L, clock.takenEndUs)
        clock.seeked()
        assertNull(clock.landedUs)
        assertNull(clock.takenEndUs)
        clock.appended(frames = 480, sampleRate = 48_000, sourceLandedUs = 90_000_000L)
        clock.taken()
        assertEquals(90_010_000L, clock.takenEndUs)
    }
}
