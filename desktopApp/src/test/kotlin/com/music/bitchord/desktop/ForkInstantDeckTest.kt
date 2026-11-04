package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: which open next decks Next may start at once (from Desktop Revived 88dcc5d0). */
class ForkInstantDeckTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000_000_000L

    @Test
    fun aWindowedDeckIsGoodUntilItsAddressExpires() {
        val stream = DesktopStream(url = "https://rr1.googlevideo.com/videoplayback?itag=251&expire=${now / 1000 + 3600}", windowedReads = true)
        assertTrue(ForkInstantDeck.fresh(stream, openedAtNs = 0L, nowNs = 10 * minute, nowMs = now))
        val expiring = stream.copy(url = "https://rr1.googlevideo.com/videoplayback?expire=${now / 1000 + 2}")
        assertFalse(ForkInstantDeck.fresh(expiring, openedAtNs = 0L, nowNs = 0L, nowMs = now))
    }

    @Test
    fun aFileIsAlwaysGood() {
        assertTrue(ForkInstantDeck.fresh(DesktopStream(url = "C:/Music/song.flac"), 0L, 10 * minute, now))
    }

    @Test
    fun anOpenConnectionOnlyWithinAMinute() {
        val stream = DesktopStream(url = "https://music.example/rest/stream?id=1")
        assertTrue(ForkInstantDeck.fresh(stream, openedAtNs = 0L, nowNs = minute / 2, nowMs = now))
        assertFalse(ForkInstantDeck.fresh(stream, openedAtNs = 0L, nowNs = 2 * minute, nowMs = now))
    }
}
