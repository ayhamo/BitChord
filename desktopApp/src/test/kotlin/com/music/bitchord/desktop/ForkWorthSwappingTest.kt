package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: a swap interrupts the song, so `worthSwapping` only takes a copy that is really better. */
class ForkWorthSwappingTest {

    private val flac1644 = DesktopStreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16)
    private val opus = DesktopStreamFormat(codec = "opus", kbps = 160, sampleRateHz = 48_000)

    @Test
    fun losslessOverLossyIsWorthIt() {
        assertTrue(DesktopSourceRegistry.worthSwapping(flac1644, opus))
    }

    @Test
    fun theSameLosslessCopyIsNot() {
        assertFalse(DesktopSourceRegistry.worthSwapping(flac1644.copy(), flac1644))
        // Unmeasured depth and rate: no proof it is better, so no swap.
        assertFalse(DesktopSourceRegistry.worthSwapping(DesktopStreamFormat(codec = "flac"), flac1644))
    }

    @Test
    fun aHigherResolutionLosslessCopyIs() {
        assertTrue(DesktopSourceRegistry.worthSwapping(flac1644.copy(bitDepth = 24, sampleRateHz = 96_000), flac1644))
        // Deeper but at a lower rate trades one loss for a gain: not taken.
        assertFalse(DesktopSourceRegistry.worthSwapping(flac1644.copy(bitDepth = 24, sampleRateHz = 32_000), flac1644))
    }

    @Test
    fun lossyNeverReplacesLossless() {
        assertFalse(DesktopSourceRegistry.worthSwapping(opus, flac1644))
    }
}
