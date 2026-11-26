package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.AutomixStyle
import com.music.bitchord.playback.smart.ForkMixPicks
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionTrackInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * spec/automix/playback.md, "The queue during a mix" rule 2, for Automix v2's plan memo and latch
 * ([ForkV2Automix.planTransition], which the phone and the PC both plan through): a song whose mix
 * began and then ended, or was cut short, is planned again when it plays again from more than 5 s
 * before its mix point. Nothing in the hook keeps a "done" flag; the latch holds only while the
 * playhead is inside its window (from [ForkV2Automix.LATCH_LEAD_SEC] + 0.5 s before the ramp, the
 * spec's 5 s, to the blend's end), so a replay from before it plans afresh.
 */
class ForkV2ReplayTest {

    @AfterTest
    fun reset() {
        ForkMixPicks.reset()
        ForkMixPicks.style = AutomixStyle.BITCHORD
        ForkV2Automix.forget()
    }

    private val analysis = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "a", bpm = 120.0, duration = 200.0)

    private fun hook(at: Double, seconds: Long = 200L) = ForkV2Automix.planTransition(
        AutomixStyle.V2, analysis, analysis.copy(trackId = "b"),
        TransitionTrackInfo(id = "a", durationMs = seconds * 1000, title = "A"), TransitionTrackInfo(id = "b", durationMs = 200_000L, title = "B"),
        at, seconds.toDouble(), false,
    )

    private fun armed() = ForkMixPicks.planned.value!!.armed

    /** The ramp's start in the outgoing song: what the latch counts its 5 s from. */
    private fun mixPoint(at: Double): Double {
        val t = assertNotNull(hook(at)?.forkV2)
        return t.plan.mixOutTrackTime - t.plan.rampSongSec
    }

    @Test
    fun theSongPlayedAgainFromItsStartIsPlannedAgain() {
        ForkMixPicks.style = AutomixStyle.V2
        val point = mixPoint(20.0)
        val held = assertNotNull(hook(point - 1.0)).forkV2
        assertTrue(armed(), "the mix is armed")
        hook(point + 2.0) // the mix runs; then Previous, or the song queued again
        val again = assertNotNull(hook(0.0)).forkV2
        assertFalse(armed(), "not left armed or done")
        assertNotSame(held, again, "planned afresh")
    }

    @Test
    fun moreThan5sBeforeTheMixPointPlansAgainAndWithinItTheArmedPlanHolds() {
        ForkMixPicks.style = AutomixStyle.V2
        val point = mixPoint(20.0)
        val held = assertNotNull(hook(point - 1.0)).forkV2
        assertTrue(armed())
        // Back to 4 s before the mix point: the armed plan holds (and still mixes).
        assertSame(held, assertNotNull(hook(point - 4.0)).forkV2)
        assertTrue(armed())
        // Back to 6 s before it: planned again.
        assertNotSame(held, assertNotNull(hook(point - 6.0)).forkV2)
        assertFalse(armed())
    }

    @Test
    fun aMixCutShortIsPlannedAgainToo() {
        ForkMixPicks.style = AutomixStyle.V2
        val point = mixPoint(20.0)
        val held = assertNotNull(hook(point + 0.5)).forkV2 // the blend under way, then cut short (going back)
        assertTrue(armed())
        assertNotSame(held, assertNotNull(hook(30.0)).forkV2)
        assertFalse(armed())
    }

    @Test
    fun anotherLengthIsNotTheArmedSong() {
        ForkMixPicks.style = AutomixStyle.V2
        val point = mixPoint(20.0)
        val held = assertNotNull(hook(point - 1.0)).forkV2
        assertTrue(armed())
        assertNotSame(held, assertNotNull(hook(point - 1.0, seconds = 203L)).forkV2)
        assertFalse(armed())
    }
}
