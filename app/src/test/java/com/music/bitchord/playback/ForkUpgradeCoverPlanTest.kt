package com.music.bitchord.playback

import com.music.bitchord.playback.ForkUpgradeCoverPlan.Inputs
import com.music.bitchord.playback.ForkUpgradeCoverPlan.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkUpgradeCoverPlanTest {

    private val playing = Inputs(
        hasStandby = true,
        sameSong = true,
        playing = true,
        inParty = false,
        exclusiveOutput = false,
        onSpeaker = false,
        transitioning = false,
        speedMatches = true,
        playingUpgradedCopy = false,
        positionMs = 60_000L,
        plannedTransitionMs = null,
    )

    private fun cuts(inputs: Inputs) = ForkUpgradeCoverPlan.decide(inputs) is Verdict.Cut

    @Test
    fun `a song playing alone on normal output is covered`() {
        assertEquals(Verdict.Cover, ForkUpgradeCoverPlan.decide(playing))
    }

    @Test
    fun `every case the standby path cannot run falls back to the cut`() {
        assertTrue(cuts(playing.copy(hasStandby = false)))
        assertTrue(cuts(playing.copy(sameSong = false)))
        assertTrue(cuts(playing.copy(playing = false)))
        assertTrue(cuts(playing.copy(inParty = true)))
        assertTrue(cuts(playing.copy(exclusiveOutput = true)))
        assertTrue(cuts(playing.copy(onSpeaker = true)))
        assertTrue(cuts(playing.copy(transitioning = true)))
        assertTrue(cuts(playing.copy(speedMatches = false)))
        assertTrue(cuts(playing.copy(playingUpgradedCopy = true)))
    }

    @Test
    fun `bit-perfect output says why`() {
        val verdict = ForkUpgradeCoverPlan.decide(playing.copy(exclusiveOutput = true))
        assertEquals(Verdict.Cut("bit-perfect output carries one stream"), verdict)
    }

    @Test
    fun `a planned transition close ahead keeps the standby free`() {
        val margin = ForkUpgradeCoverPlan.TRANSITION_MARGIN_MS
        assertTrue(cuts(playing.copy(plannedTransitionMs = 60_000L + margin - 1)))
        assertEquals(Verdict.Cover, ForkUpgradeCoverPlan.decide(playing.copy(plannedTransitionMs = 60_000L + margin)))
        // A transition already behind the playhead (planned before a seek back) is not ahead.
        assertTrue(cuts(playing.copy(plannedTransitionMs = 30_000L)))
    }

    @Test
    fun `only a hifi marker resolves to the parked stream`() {
        assertTrue(ForkUpgradeCoverPlan.isUpgradedCopy("hifi"))
        assertTrue(ForkUpgradeCoverPlan.isUpgradedCopy("hifi-2"))
        assertFalse(ForkUpgradeCoverPlan.isUpgradedCopy("original"))
        assertFalse(ForkUpgradeCoverPlan.isUpgradedCopy(null))
    }

    @Test
    fun `the upgraded uri is what the cover refuses to read as the old copy`() {
        val upgraded = QualityUpgrade.upgradedUri("bitchord://watch?v=abc")
        val marker = upgraded.substringAfter("&${QualityUpgrade.MARKER}=").substringBefore('&')
        assertTrue(ForkUpgradeCoverPlan.isUpgradedCopy(marker))
    }

    @Test
    fun `fades are equal gain and clamp`() {
        assertEquals(0f, ForkUpgradeCoverPlan.progress(-5, 550), 0f)
        assertEquals(0.5f, ForkUpgradeCoverPlan.progress(275, 550), 1e-6f)
        assertEquals(1f, ForkUpgradeCoverPlan.progress(900, 550), 0f)
        assertEquals(1f, ForkUpgradeCoverPlan.progress(0, 0), 0f)
        for (step in 0..10) {
            val inGain = ForkUpgradeCoverPlan.inGain(step / 10f)
            assertEquals(1f, inGain + (1f - inGain), 1e-6f)
        }
        assertEquals(1f, ForkUpgradeCoverPlan.inGain(1.5f), 0f)
    }

    @Test
    fun `timings fit inside the margin kept from a transition`() {
        val longest = ForkUpgradeCoverPlan.RAISE_READY_TIMEOUT_MS + ForkUpgradeCoverPlan.START_TIMEOUT_MS +
            ForkUpgradeCoverPlan.RESYNC_TIMEOUT_MS * 2 + ForkUpgradeCoverPlan.COVER_IN_MS +
            ForkUpgradeCoverPlan.LAND_READY_TIMEOUT_MS + ForkUpgradeCoverPlan.LAND_FADE_MS
        assertTrue(longest < ForkUpgradeCoverPlan.TRANSITION_MARGIN_MS)
        // Upstream's swap wants 20 s left in the song; the cover fits in that too.
        assertTrue(longest < 20_000L)
    }
}
