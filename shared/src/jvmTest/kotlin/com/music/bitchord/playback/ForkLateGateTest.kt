package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B's late entry on the phone's sink (mix-file.md rule 2, extension a35021a): 0 until the delay ends, then the curve within 10 ms. */
class ForkLateGateTest {

    private fun ones(frames: Int) = FloatArray(frames * 2) { 1f }

    @Test
    fun shutItIsSilentWhateverPlaysThenItRisesWithinTenMilliseconds() {
        val s = ForkMixShelf()
        s.lateHold()
        // a player started early stays under the 0
        val early = ones(256)
        s.process(early, early.size, 2, 48_000)
        assertTrue(early.all { it == 0f })
        s.lateOpen()
        val first = ones(1024)
        s.process(first, first.size, 2, 48_000)
        // 10 ms at 48 kHz: 480 frames from 0, both channels alike, then the curve (here 1) untouched
        assertEquals(0f, first[0])
        assertEquals(240f / 480, first[480], 1e-6f)
        assertEquals(first[480], first[481])
        assertTrue((1 until 480).all { first[it * 2] > first[(it - 1) * 2] })
        assertTrue((480 until 1024).all { first[it * 2] == 1f && first[it * 2 + 1] == 1f })
        // and the gate is out of the way after it
        val later = ones(64)
        s.process(later, later.size, 2, 48_000)
        assertTrue(later.all { it == 1f })
    }

    @Test
    fun withoutAHoldNothingChanges() {
        val s = ForkMixShelf()
        s.lateOpen() // (only a held gate opens)
        val x = ones(128)
        s.process(x, x.size, 2, 48_000)
        assertTrue(x.all { it == 1f })
        s.lateHold()
        s.lateOff()
        s.process(x, x.size, 2, 48_000)
        assertTrue(x.all { it == 1f })
    }
}
