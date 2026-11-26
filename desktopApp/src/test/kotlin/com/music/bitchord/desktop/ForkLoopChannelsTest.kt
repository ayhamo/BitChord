package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The loop mix on a multichannel output (ForkLoopChannels, owner 2026-11-25): stereo on the front pair, the rest silent. */
class ForkLoopChannelsTest {

    private fun format(channels: Int) = DesktopPcmFormat(48_000, channels, 4, isFloat = true)

    /** Two frames of stereo: (0.5, -0.25), (0.75, 0.125). */
    private val stereo = floatArrayOf(0.5f, -0.25f, 0.75f, 0.125f)

    @Test
    fun aStereoOutputTakesTheRenderersBlockAsItIs() {
        val c = ForkLoopChannels()
        assertSame(stereo, c.spread(stereo, 4, format(2)))
        assertEquals(4, c.count)
        assertSame(stereo, c.fold(stereo, 4, format(2)))
    }

    @Test
    fun fiveOneAndSevenOneGetLeftAndRightOnTheFrontPairAndSilenceElsewhere() {
        for (channels in listOf(6, 8)) {
            val c = ForkLoopChannels()
            val out = c.spread(stereo, 4, format(channels))
            assertEquals(2 * channels, c.count, "$channels channels")
            for (frame in 0 until 2) {
                // WAVEFORMATEXTENSIBLE order: front left 0, front right 1, then centre, LFE, the backs and sides
                assertEquals(stereo[2 * frame], out[frame * channels], "$channels ch frame $frame FL")
                assertEquals(stereo[2 * frame + 1], out[frame * channels + 1], "$channels ch frame $frame FR")
                for (ch in 2 until channels) assertEquals(0f, out[frame * channels + ch], "$channels ch frame $frame ch $ch")
            }
        }
    }

    @Test
    fun aStereoSongPaddedOntoTheFrontPairFoldsBackToItself() {
        for (channels in listOf(6, 8)) {
            val c = ForkLoopChannels()
            val padded = c.spread(stereo, 4, format(channels)).copyOf(2 * channels)
            val back = c.fold(padded, 2 * channels, format(channels))
            assertEquals(4, c.count)
            for (i in 0 until 4) assertEquals(stereo[i], back[i], "$channels ch sample $i")
        }
    }

    @Test
    fun onlyAnOutputWithAFrontPairTakesTheMix() {
        assertTrue(ForkLoopChannels.frontPair(2))
        assertTrue(ForkLoopChannels.frontPair(6))
        assertTrue(ForkLoopChannels.frontPair(8))
        assertFalse(ForkLoopChannels.frontPair(1))
        assertFalse(ForkLoopChannels.frontPair(0))
        // a three-channel layout of centre, LFE and back centre: no front pair
        assertFalse(ForkLoopChannels.frontPair(3, ForkChannelLayout.FC or ForkChannelLayout.LFE or ForkChannelLayout.BC))
    }
}
