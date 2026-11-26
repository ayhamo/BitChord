package com.music.bitchord.fork

import com.music.bitchord.playback.DirectSourceGate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ForkReadAhead]'s hold (rulebook 8.1.3) and where it must stand aside (audit 2026-11-26 2b #2). */
class ForkReadAheadTest {

    @Test
    fun theHoldIsNormalModesOnlyWithAnAddonAndNeverInAParty() {
        assertTrue(ForkReadAhead.holds(directActive = false, inParty = false, addons = true))
        assertFalse(ForkReadAhead.holds(directActive = true, inParty = false, addons = true)) // Direct mode holds its own
        assertFalse(ForkReadAhead.holds(directActive = false, inParty = false, addons = false))
        assertFalse(ForkReadAhead.holds(directActive = false, inParty = true, addons = true)) // a party seeks everyone at once
    }

    /** A Cast receiver's resolve (inside `ungated`) is for a song this player isn't on: it never waits. */
    @Test
    fun aCastResolveIsNeverHeld() {
        DirectSourceGate.ungated {
            runBlocking { withTimeout(2_000) { ForkReadAhead.awaitOpen("cast-only-song") } }
            runBlocking { withTimeout(2_000) { ForkReadAhead.awaitOpen("cast-only-row", row = true) } }
        }
        assertFalse(DirectSourceGate.forkUngated())
    }
}
