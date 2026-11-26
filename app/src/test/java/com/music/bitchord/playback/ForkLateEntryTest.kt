package com.music.bitchord.playback

import com.music.bitchord.playback.smart.automixv2.ForkV2Analysis
import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import com.music.bitchord.playback.smart.automixv2.ForkV2Json
import com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B's late entry on the phone (mix-file.md rule 2): the blend on A's clock while B is held, then B's own. */
class ForkLateEntryTest {

    @Test
    fun noDelayIsBsOwnClockAndBPlaysAtOnce() {
        val e = ForkLateEntry()
        assertTrue(e.begin(0.0, 1.0, 100_000.0))
        assertFalse(e.holding)
        assertEquals(1234.0, e.elapsed(1234.0, 0.0, 1.0), 0.0)
        assertFalse(e.takeStart())
    }

    @Test
    fun bIsHeldUntilTheDelayOnAsClockThenCountsOnFromIt() {
        val e = ForkLateEntry()
        // 3 s late, B at x1.02, A at x1.01: incoming over outgoing 1.02 / 1.01
        val ratio = 1.02 / 1.01
        assertFalse(e.begin(3.0, 1.02, 100_000.0))
        assertTrue(e.holding)
        // B hasn't moved (held at its cue): the blend runs on A's media time
        assertEquals(1000.0 * ratio, e.elapsed(0.0, 101_000.0, ratio), 1e-9)
        assertFalse(e.takeStart())
        // A's 3 s at x1.01 is 2.97 s of wall time: B still held
        assertEquals(2970.0 * ratio, e.elapsed(0.0, 102_970.0, ratio), 1e-9)
        assertTrue(e.holding)
        // the hold is 3 s of wall time, 3060 ms of B's media
        val at = e.elapsed(0.0, 103_040.0, ratio)
        assertEquals(3040.0 * ratio, at, 1e-9)
        assertFalse(e.holding)
        assertTrue(e.takeStart())
        assertFalse(e.takeStart())
        // B's own from there
        assertEquals(at + 500.0, e.elapsed(500.0, 999_999.0, ratio), 1e-9)
    }

    @Test
    fun aHandOverBeforeTheDelayStartsBOnce() {
        val e = ForkLateEntry()
        e.begin(5.0, 1.0, 0.0)
        e.elapsed(0.0, 1000.0, 1.0)
        assertTrue(e.release())
        assertFalse(e.release())
        assertFalse(e.holding)
        e.reset()
        assertFalse(e.holding)
    }

    private fun song(id: String, dur: Double) = ForkV2Analysis().apply {
        fileKey = id
        durationSec = dur
        bpm = 120.0
        beatIntervalSec = 0.5
        firstDownbeatSec = 0.5
        gridConfidence = 0.9
        tailGrid = ForkV2Analysis.LocalGrid().also {
            it.bpm = 120.0
            it.beatIntervalSec = 0.5
            it.firstDownbeatSec = 0.5
            it.gridConfidence = 0.9
        }
        energy = 0.5
        introEndSec = 8.0
    }

    @Test
    fun aLateBWithAMixInPastItsStartIsCuedThereAndHeld() {
        val ov = ForkV2MixOverride.fromJson(ForkV2Json.parse("""{"preset":"smooth","window":{"in":{"sec":30,"delaySec":3}}}"""))
        val t = ForkV2Automix.planBlend(8, "a", "b", song("a", 200.0), song("b", 180.0), 200.0, 60.0, ov)
        val plan = ForkV2Automix.toTransitionPlan(t, t.plan.mixOutTrackTime)
        // the incoming player is loaded at its mix-in (setMediaItems at the cue) and stays there through the hold
        assertEquals(30.0, plan.incomingCueTime, 1e-12)
        val e = ForkLateEntry()
        assertFalse(e.begin(plan, 150_000.0))
        assertTrue(e.holding)
        // 2 s into the blend: B unstarted, its position (the cue) unread
        assertEquals(2000.0 * plan.incomingPlaybackRate, e.elapsed(0.0, 152_000.0 * 1.0, plan.incomingPlaybackRate), 1e-6)
        assertFalse(e.takeStart())
    }
}
