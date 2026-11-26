package com.music.bitchord.playback

import com.music.bitchord.playback.smart.automixv2.ForkV2Analysis
import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import com.music.bitchord.playback.smart.automixv2.ForkV2Json
import com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride
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

    /**
     * The Looping lane starts once per blend, and again when the same latched blend is played again after a seek back
     * ([ForkV2Ride.reset] from the controller's finish): it stayed off on the replay (audit 2026-11-26 part 1b #5).
     */
    @Test
    fun `a replayed blend loops again after its finish`() {
        val ov = ForkV2MixOverride.fromJson(ForkV2Json.parse("""{"v":1,"preset":"custom","lanes":{"volume":"overlap","eq":"centerBassSwap","effect":"none","looping":"loop4"}}"""))
        val t = ForkV2Automix.planBlend(8, "a", "b", song("a", 200.0), song("b", 180.0), 200.0, 60.0, ov)
        assertTrue("the plan has a Looping lane: ${t.loopSongSec}", t.loopSongSec > 0)
        ForkV2Ride.reset()
        assertFalse(ForkV2Ride.loopDue(t, -0.1f))
        assertTrue(ForkV2Ride.loopDue(t, 0f))
        assertFalse(ForkV2Ride.loopDue(t, 0.2f))
        ForkV2Ride.reset() // the blend bailed
        assertTrue(ForkV2Ride.loopDue(t, 0f))
        ForkV2Ride.reset()
    }
}
