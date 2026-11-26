package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkCopyAheadTest {

    private val next = Song("vNQjKyEMJxk", "SI NO ES CONTIGO", "Cris Mj", null)
    private val flac = DesktopStream("https://addon.example/snec.flac", sourceId = "addon")

    @Test
    fun theNextSongsCopyIsMeasuredUnderItsOwnKeyWhileItIsNext() = runBlocking {
        val measured = ArrayList<String>()
        val copy = ForkCopyAhead.run(next, automix = true, stillNext = { true }, find = { flac }) { s, c ->
            measured += ForkCopyAnalysis.key(s, c)
        }
        assertEquals(flac, copy)
        assertEquals(listOf("vNQjKyEMJxk@addon"), measured)
        // the race's copy from the same source at arming finds that analysis
        assertEquals("vNQjKyEMJxk@addon", ForkCopyAnalysis.key(next, DesktopStream("https://addon.example/snec-armed.flac", sourceId = "addon")))
    }

    @Test
    fun nothingIsMeasuredForYouTubeNoCopyAnotherNextOrAutomixOff() = runBlocking {
        var measured = 0
        val count: (Song, DesktopStream) -> Unit = { _, _ -> measured++ }
        assertNull(ForkCopyAhead.run(next, true, { true }, { DesktopStream("https://yt.example/a", sourceId = "youtube") }, count))
        assertNull(ForkCopyAhead.run(next, true, { true }, { null }, count))
        assertNull(ForkCopyAhead.run(next, true, { false }, { flac }, count))
        assertNull(ForkCopyAhead.run(next, false, { true }, { flac }, count))
        assertEquals(0, measured)
    }

    /**
     * A 3-minute A with the times measured on the Nov 25 run: the add-on answers in about 1 s; the analyser
     * first finishes A's YouTube pass (13 s) and A's own copy (21 s); B's FLAC takes 26 s. B is armed 24.8 s
     * before the transition, which on a 3-min A is no later than 155.2 s in.
     */
    @Test
    fun onAThreeMinuteSongTheCopyIsReadyBeforeArming() {
        val armAt = 180.0 - 24.8
        val ahead = ForkCopyAhead.readyAtSec(askedAtSec = 0.0, lookupSec = 1.2, queuedSec = listOf(13.0, 21.0), copySec = 26.0)
        assertEquals(60.0, ahead, 1e-9)
        assertTrue(ahead < armAt, "measured ahead: ready at $ahead s, armed at $armAt s")
        // asked even 90 s into A (a song queued late) it is still ready in time
        assertTrue(ForkCopyAhead.readyAtSec(90.0, 1.2, listOf(13.0, 21.0), 26.0) < armAt)
        // the old way, measured from the deck's open at arming: too late for the loop
        val atOpen = ForkCopyAhead.readyAtSec(askedAtSec = armAt, lookupSec = 0.0, queuedSec = emptyList(), copySec = 26.0)
        assertTrue(atOpen > 180.0, "at deck open: ready at $atOpen s, past the transition at 180 s")
    }
}
