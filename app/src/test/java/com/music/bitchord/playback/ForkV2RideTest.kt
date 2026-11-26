package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's Automix v2 ride: speeds set by what the player has. */
class ForkV2RideTest {

    @Test
    fun `a glide step is set when the player's own speed differs, not the last one set here`() {
        assertTrue(ForkV2Ride.needsSpeed(1.0f, 1.04f)) // the controller put the player back to 1x since the last glide
        assertFalse(ForkV2Ride.needsSpeed(1.04f, 1.04f))
        assertFalse(ForkV2Ride.needsSpeed(1.04f, 1.040001f))
    }
}
