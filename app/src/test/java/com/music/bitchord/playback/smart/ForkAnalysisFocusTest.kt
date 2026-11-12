package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkAnalysisFocusTest {

    /** After a skip, a queued pass for the song left behind is dropped; the new pair's run. */
    @Test
    fun onlyThePlayingAndNextSongsAreWanted() {
        ForkAnalysisFocus.set("apt", "golden")
        assertTrue(ForkAnalysisFocus.wanted("apt"))
        assertTrue(ForkAnalysisFocus.wanted("golden"))
        ForkAnalysisFocus.set("ophelia", "girls")
        assertFalse(ForkAnalysisFocus.wanted("apt"))
        assertFalse(ForkAnalysisFocus.wanted("golden"))
        assertTrue(ForkAnalysisFocus.wanted("ophelia"))
        assertTrue(ForkAnalysisFocus.wanted("girls"))
    }

    /** A running pass is abandoned once its song is moved past, and only on the thread running it. */
    @Test
    fun aRunningPassIsMovedPastOnItsOwnThreadOnly() {
        ForkAnalysisFocus.set("a", "b")
        val tried = mutableSetOf("old")
        ForkAnalysisFocus.enter("b", tried)
        try {
            assertFalse(ForkAnalysisFocus.movedPast())
            ForkAnalysisFocus.set("b", "c") // the next song started: b is now playing, still wanted
            assertFalse(ForkAnalysisFocus.movedPast())
            tried += "b-copy" // the pass marks the copy it reads
            ForkAnalysisFocus.set("x", "y") // skipped away
            assertTrue(ForkAnalysisFocus.movedPast())
            ForkAnalysisFocus.set("b", "c") // and straight back: the pass stays abandoned
            assertTrue(ForkAnalysisFocus.movedPast())
            ForkAnalysisFocus.untry(tried)
            assertEquals(setOf("old"), tried)
            var other = true
            Thread { other = ForkAnalysisFocus.movedPast() }.apply { start(); join() }
            assertFalse("another thread (the mix waveform) is never stopped", other)
        } finally {
            ForkAnalysisFocus.leave()
        }
        assertFalse(ForkAnalysisFocus.movedPast())
    }

    /** A pass that finished before the listener moved on is not counted as abandoned afterwards. */
    @Test
    fun aFinishedPassIsNotAbandonedLater() {
        ForkAnalysisFocus.set("a", "b")
        ForkAnalysisFocus.enter("b", emptySet())
        try {
            assertFalse(ForkAnalysisFocus.movedPast()) // read during the pass
            ForkAnalysisFocus.set("x", "y") // moved on after it recorded its result
            assertFalse(ForkAnalysisFocus.abandoned())
            assertTrue(ForkAnalysisFocus.movedPast())
            assertTrue(ForkAnalysisFocus.abandoned())
        } finally {
            ForkAnalysisFocus.leave()
        }
    }

    /** The last song in the queue has no next one: it alone is wanted. */
    @Test
    fun theEndOfTheQueueWantsTheLastSongOnly() {
        ForkAnalysisFocus.set("last", null)
        assertTrue(ForkAnalysisFocus.wanted("last"))
        assertFalse(ForkAnalysisFocus.wanted("other"))
    }
}
