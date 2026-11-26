package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.automixv2.ForkMixPause.Keep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** playback.md "The queue during a mix" rule 5 (the extension's e9697cc7): the listener's pause during a mix. */
class ForkMixPauseTest {

    @Test
    fun aPauseLeavesThePlayerOnTheSongTheMixHadReached() {
        // before the hand-over: the outgoing song; after it: the incoming one
        assertEquals(Keep.OUTGOING, ForkMixPause.keep(heard = true, switched = false))
        assertEquals(Keep.INCOMING, ForkMixPause.keep(heard = true, switched = true))
        // a mix armed but not yet heard: an ordinary pause, nothing to call off
        assertEquals(Keep.AS_IS, ForkMixPause.keep(heard = false, switched = false))
    }

    @Test
    fun playOrPauseDuringAMixIsAPauseAndNeverStartsThePlayer() {
        // pause, from any surface, in any phase
        assertTrue(ForkMixPause.pressPauses(mixRunning = true, play = false, playerPlaying = true))
        assertTrue(ForkMixPause.pressPauses(mixRunning = true, play = false, playerPlaying = false))
        // play on a player held paused (the phone's hold at the outgoing song's end, its button showing play): a pause too
        assertTrue(ForkMixPause.pressPauses(mixRunning = true, play = true, playerPlaying = false))
        // play on a player already playing changes nothing, and leaves the mix alone
        assertFalse(ForkMixPause.pressPauses(mixRunning = true, play = true, playerPlaying = true))
    }

    @Test
    fun outsideAMixPlayAndPauseAreOrdinary() {
        assertFalse(ForkMixPause.pressPauses(mixRunning = false, play = false, playerPlaying = true))
        assertFalse(ForkMixPause.pressPauses(mixRunning = false, play = true, playerPlaying = false))
    }
}
