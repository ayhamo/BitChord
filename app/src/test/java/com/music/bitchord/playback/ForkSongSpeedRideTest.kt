package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A song's playlist speed between mixes (spotify-mixes.md rule 10): the listener's own speed is never overridden. */
class ForkSongSpeedRideTest {

    @Test
    fun `a song's speed goes on a player at x1 and comes off when the next has none`() {
        val on = ForkSongSpeedRide.held(want = 1.08f, cur = 1f, set = 0f, listener = 1f)
        assertEquals(1.08f, on.speed)
        assertEquals(1.08f, on.set)
        val off = ForkSongSpeedRide.held(want = 1f, cur = 1.08f, set = 1.08f, listener = 1f)
        assertEquals(1f, off.speed)
        assertEquals(0f, off.set)
    }

    @Test
    fun `a listener's speed that matches the last playlist speed is left alone`() {
        // the song played at its x1.08, then the listener set x1.08 by hand: it put the player back to x1 before
        val h = ForkSongSpeedRide.held(want = 1f, cur = 1.08f, set = 1.08f, listener = 1.08f)
        assertNull(h.speed)
        assertEquals(0f, h.set)
    }

    @Test
    fun `a speed set by hand is never replaced by a song's`() {
        val h = ForkSongSpeedRide.held(want = 1.03f, cur = 1.25f, set = 0f, listener = 1f)
        assertNull(h.speed)
    }
}
