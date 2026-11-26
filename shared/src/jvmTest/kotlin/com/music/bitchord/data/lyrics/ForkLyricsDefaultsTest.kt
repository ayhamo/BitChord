package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The owner's lyrics-provider order (ForkLyricsDefaults): Genius last of the ticked ones, set once more over a saved order. */
class ForkLyricsDefaultsTest {

    @Test
    fun geniusIsLastAfterSpotify() {
        val t = ForkLyricsDefaults.ticked
        assertEquals(LyricsSource.GENIUS, t.last())
        assertEquals(LyricsSource.PAXSENIX_SPOTIFY, t[t.size - 2])
        assertEquals(
            listOf(
                LyricsSource.LRC_RED, LyricsSource.UNISON, LyricsSource.BETTER_LYRICS, LyricsSource.BINI_LYRICS,
                LyricsSource.MUSIXMATCH, LyricsSource.LRCLIB, LyricsSource.LYRICS_PLUS, LyricsSource.PAXSENIX,
                LyricsSource.SIMP_MUSIC, LyricsSource.YOUTUBE_TRANSCRIPT, LyricsSource.PAXSENIX_SPOTIFY, LyricsSource.GENIUS,
            ),
            t,
        )
        // the whole order starts with the ticked ones, every offered source once
        assertEquals(t, ForkLyricsDefaults.order.take(t.size))
        assertEquals(ForkLyricsDefaults.order.toSet().size, ForkLyricsDefaults.order.size)
    }

    @Test
    fun setOnceMoreOverAnOrderSavedByVersionOne() {
        assertTrue(ForkLyricsDefaults.due(0))
        assertTrue(ForkLyricsDefaults.due(1), "an order saved under version 1 gets Genius moved")
        assertFalse(ForkLyricsDefaults.due(ForkLyricsDefaults.VERSION), "then the owner's own changes stay")
    }
}
