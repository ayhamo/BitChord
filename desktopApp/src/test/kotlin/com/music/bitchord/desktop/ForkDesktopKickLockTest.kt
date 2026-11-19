package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import com.music.bitchord.playback.smart.automixv2.ForkV2Planner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the PC measures Automix v2's kick lock, over what, and that it answers from memory. */
class ForkDesktopKickLockTest {

    @AfterTest
    fun tearDown() {
        ForkDesktopKickLock.forget()
        ForkV2Automix.kickLock = null
    }

    private fun blend() = ForkV2Planner.Plan().apply {
        style = "blend"
        beatSec = 0.5
        mixOutTrackTime = 180.0
        rampSongSec = 4.0
        mixInTrackTime = 12.0
        durationSec = 16.0
    }

    @Test
    fun `measured from 40 s before the ramp until the plan is latched`() {
        val p = blend() // ramp at 176 s, latched from 171.5 s
        assertFalse(ForkDesktopKickLock.due(p, 135.0))
        assertTrue(ForkDesktopKickLock.due(p, 136.0))
        assertTrue(ForkDesktopKickLock.due(p, 171.0))
        assertFalse(ForkDesktopKickLock.due(p, 171.5), "a result now would be for a plan already held")
    }

    @Test
    fun `the regions are the phone's`() {
        val p = blend()
        assertEquals(174.0 to 180.0 + 16 * 1.2 + 6, ForkDesktopKickLock.regionOf(p, p.mixOutTrackTime))
        assertEquals(6.0 to 12.0 + 16 * 1.2 + 6, ForkDesktopKickLock.regionOf(p, p.mixInTrackTime))
        assertEquals(0.0, ForkDesktopKickLock.regionOf(p, 3.0).first, "not before the song")
    }

    @Test
    fun `a plan moved by the planner is a new measurement`() {
        val p = blend()
        val moved = blend().apply { mixOutTrackTime = 182.0 }
        assertNotEquals(ForkDesktopKickLock.keyOf("a", "b", p), ForkDesktopKickLock.keyOf("a", "b", moved))
        assertEquals(ForkDesktopKickLock.keyOf("a", "b", p), ForkDesktopKickLock.keyOf("a", "b", blend()))
    }

    @Test
    fun `nothing is measured or answered without the pair's decks`() {
        ForkDesktopKickLock.install()
        // Due, but the engine has not said which copies the decks play: no answer, nothing started.
        assertNull(ForkDesktopKickLock.resultFor("a", "b", blend(), 150.0))
        assertNull(ForkV2Automix.kickLock?.invoke("a", "b", blend(), 150.0))
    }
}
