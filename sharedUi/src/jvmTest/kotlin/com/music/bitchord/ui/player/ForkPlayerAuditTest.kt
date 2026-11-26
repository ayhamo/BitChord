package com.music.bitchord.ui.player

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.playback.AutoMixVisualPhase
import com.music.bitchord.playback.AutoMixVisualState
import com.music.bitchord.playback.AutoMixVisualTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player findings of audit 2026-11-26 part 2a (#2, #3, #6) that live in sharedUi. */
class ForkPlayerAuditTest {

    /** 2a #2: no NaN or Infinity stops at zero width, and the fade's end never past the right edge. */
    @Test
    fun theLandscapeFadeHasFiniteStopsInsideTheBox() {
        assertNull(forkFadeEnd(0f, 140f))
        assertNull(forkFadeEnd(Float.NaN, 140f))
        assertEquals(1f, forkFadeEnd(200f, 140f)!!, 0f) // narrower than twice the fade: clamped
        assertEquals(0.5f + 140f / 1000f, forkFadeEnd(1000f, 140f)!!, 1e-6f)
    }

    private fun mix(generation: Long, phase: AutoMixVisualPhase) = AutoMixVisualState(
        generation = generation,
        outgoing = AutoMixVisualTrack(QueueEntryIdentity("a", null, 0), 0, null),
        incoming = AutoMixVisualTrack(QueueEntryIdentity("b", null, 0), 1, null),
        phase = phase,
    )

    /** 2a #3: the fade stands down only for a change the art transition really carries. */
    @Test
    fun coverFadeStandsDownOnlyForAMixTheArtTransitionTakes() {
        val gate = AutoMixArtworkGate(enabled = true, skipThrough = 4)
        // A mix the gate allows, still blending: the transition has it.
        assertTrue(forkCoverFadeStandsDown(false, forkMixGateOn(gate, mix(5, AutoMixVisualPhase.MIXING)), forkMixRunning(true, mix(5, AutoMixVisualPhase.MIXING))))
        // The transition switched off for this mix (skipThrough): no frames, so the fade runs.
        assertFalse(forkCoverFadeStandsDown(false, forkMixGateOn(gate, mix(4, AutoMixVisualPhase.MIXING)), forkMixRunning(true, mix(4, AutoMixVisualPhase.MIXING))))
        // A manual skip cancelled the blend while the flag hadn't dropped yet: the fade runs.
        assertFalse(forkCoverFadeStandsDown(false, forkMixGateOn(gate, mix(5, AutoMixVisualPhase.CANCELLED)), forkMixRunning(true, mix(5, AutoMixVisualPhase.CANCELLED))))
        // A platform with no visual state keeps the flag and the switch, as before.
        assertTrue(forkCoverFadeStandsDown(false, forkMixGateOn(gate, null), forkMixRunning(true, null)))
        assertFalse(forkCoverFadeStandsDown(false, forkMixGateOn(gate, null), forkMixRunning(false, null)))
    }

    /** 2a #6: the highlight and scroll lead follow "real timing" without waiting for the clock. */
    @Test
    fun realTimingIsReadAsComposeState() {
        val before = forkRealTimingSeen.value
        try {
            Snapshot.withMutableSnapshot { forkRealTimingSeen.value = false }
            val lead = derivedStateOf { forkScrollLead(500L) }
            assertEquals(500L, lead.value)
            Snapshot.withMutableSnapshot { forkRealTimingSeen.value = true }
            assertEquals(0L, lead.value)
        } finally {
            Snapshot.withMutableSnapshot { forkRealTimingSeen.value = before }
        }
    }
}
