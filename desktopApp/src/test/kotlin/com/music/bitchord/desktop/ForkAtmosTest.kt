package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: Atmos first and the downmix make-up (from PC-Continued be257dad / 18b6b3ae). */
class ForkAtmosTest {

    @Test
    fun makeUpOnlyForAMultichannelSourceFoldedToStereoWithAtmosOn() {
        assertEquals(ForkDownmixMakeup.GAIN, ForkDownmixMakeup.gainFor(6, 2, atmosEnabled = true))
        assertEquals(1f, ForkDownmixMakeup.gainFor(6, 2, atmosEnabled = false))
        assertEquals(1f, ForkDownmixMakeup.gainFor(2, 2, atmosEnabled = true))
        // The analyser asks for mono: it measures the track as served.
        assertEquals(1f, ForkDownmixMakeup.gainFor(6, 1, atmosEnabled = true))
    }

    @Test
    fun makeUpNeverPushesABlockPastTheCeiling() {
        val quiet = floatArrayOf(0.1f, -0.2f, 0.3f, -0.1f)
        ForkDownmixMakeup.apply(quiet, quiet.size, ForkDownmixMakeup.GAIN)
        assertEquals(0.6f, quiet[2], 1e-6f)
        val loud = floatArrayOf(0.7f, -0.2f)
        ForkDownmixMakeup.apply(loud, loud.size, ForkDownmixMakeup.GAIN)
        assertEquals(ForkDownmixMakeup.CEILING, loud[0], 1e-6f)
    }

    @Test
    fun anAtmosCopyIsWorthItAndOnePlayingIsKept() {
        val flac = DesktopStreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24)
        val atmos = DesktopStreamFormat(codec = "eac3", kbps = 768)
        assertTrue(DesktopSourceRegistry.worthSwapping(atmos, flac))
        assertFalse(DesktopSourceRegistry.worthSwapping(flac, atmos))
    }
}
