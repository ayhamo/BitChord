package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec/automix/playback.md, "The queue during a mix" rule 2, for the phone's loop plan cache. */
class ForkLoopReplayTest {

    @Test
    fun `playing on keeps the plan`() {
        val r = ForkLoopReplay { true }
        r.planned(30_000L)
        for (pos in 30_000L..180_000L step 250L) assertTrue(r.keeps(pos))
    }

    @Test
    fun `the song played again from its start plans again`() {
        val r = ForkLoopReplay { true }
        r.planned(20_000L)
        assertTrue(r.keeps(150_000L)) // up to the mix, then cut short or played out
        assertFalse(r.keeps(0L))
        r.planned(0L)
        assertTrue(r.keeps(250L))
    }

    @Test
    fun `more than 5 s back is playing again, 5 s or less is not`() {
        val r = ForkLoopReplay { true }
        r.planned(100_000L)
        assertTrue(r.keeps(95_000L))
        assertFalse(r.keeps(94_999L))
        assertTrue(ForkLoopReplay.keeps(100_000L, 95_000L))
        assertFalse(ForkLoopReplay.keeps(100_000L, 94_999L))
    }

    @Test
    fun `measured from the furthest point used, not where it was planned`() {
        // A plan of "no loop mix" made at 2 s, reused up to the loop point, then the song again from 0:
        // measured from where it was planned that was only 2 s back, and the null stuck.
        val r = ForkLoopReplay { true }
        r.planned(2_000L)
        assertTrue(r.keeps(120_000L))
        assertFalse(r.keeps(0L))
    }

    @Test
    fun `a pause or a nudge back of a few seconds keeps it`() {
        val r = ForkLoopReplay { true }
        r.planned(60_000L)
        assertTrue(r.keeps(60_000L))
        assertTrue(r.keeps(57_000L))
        assertTrue(r.keeps(61_000L))
    }

    @Test
    fun `under BitChord's own style the cache keeps upstream's rule`() {
        val r = ForkLoopReplay { false }
        r.planned(20_000L)
        assertTrue(r.keeps(150_000L))
        assertTrue(r.keeps(0L))
    }
}
