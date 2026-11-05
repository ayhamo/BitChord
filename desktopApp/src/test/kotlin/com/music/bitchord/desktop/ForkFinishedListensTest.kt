package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ForkFinishedListens]: a song that ran out into the next one is reported, once, as the play it was. */
class ForkFinishedListensTest {

    private val a = ForkFinishedListens.Play("a", startedAtMs = 1_000L, lastFmDone = true)
    private val b = ForkFinishedListens.Play("b", startedAtMs = 200_000L, lastFmDone = false)

    @Test
    fun aHandOffReportedBeforeThePlayerMovesOnIsTheActivePlay() {
        val listens = ForkFinishedListens()
        val ended = listens.ended("a", active = a, now = 200_000L)!!
        assertEquals(a, ended.play)
        assertTrue(ended.isActive)
    }

    @Test
    fun aHandOffReportedAfterThePlayerMovedOnIsThePlayBefore() {
        val listens = ForkFinishedListens()
        listens.songChanged(a)
        val ended = listens.ended("a", active = b, now = 200_500L)!!
        // Its own start and Last.fm state, not the next song's; and the next song's timer is left alone.
        assertEquals(a, ended.play)
        assertFalse(ended.isActive)
    }

    @Test
    fun aPlayIsReportedOnceWhicheverPathComesFirst() {
        val listens = ForkFinishedListens()
        assertTrue(listens.ended("a", active = a, now = 200_000L) != null) // the hand-off
        listens.songChanged(a.copy(lastFmDone = true))
        assertNull(listens.ended("a", active = b, now = 200_100L)) // the queue's end, for the same play
        // A second play of the same song (repeat) is a new listen.
        val again = a.copy(startedAtMs = 400_000L)
        assertTrue(listens.ended("a", active = again, now = 600_000L) != null)
    }

    @Test
    fun aSkipMixIsNotAFinishedListenButEveryOtherHandOffIs() {
        assertFalse(ForkFinishedListens.endedNaturally(skipMixInto = "b", incomingId = "b"))
        assertTrue(ForkFinishedListens.endedNaturally(skipMixInto = null, incomingId = "b")) // a crossfade, Automix, a gapless start
        assertTrue(ForkFinishedListens.endedNaturally(skipMixInto = "c", incomingId = "b")) // a skip-mix that never reached its song
    }
}
