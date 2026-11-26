package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** protocol.md §7's mashup test, past the matcher cases: no artist, nested brackets. */
class ForkMashupTest {

    @Test
    fun aTitleWithoutAnArtistIsRead() {
        assertTrue(ForkMashup.isMashup("Let Me Love You x Gata Only", null))
        assertTrue(ForkMashup.isMashup("Let Me Love You x Gata Only", ""))
        assertFalse(ForkMashup.isMashup("Came Here For Love", null))
        assertFalse(ForkMashup.isMashup(null, null))
    }

    @Test
    fun nestedBracketsGoWhole() {
        // the aside "(a x b (live))" is an aside, so its " x " isn't a mashup's
        assertFalse(ForkMashup.isMashup("Song (Remix x Edit (Live))", "Artist"))
        assertTrue(ForkMashup.isMashup("Levitating / Physical (Mashup (Live))", "Mashup Channel"))
    }

    @Test
    fun anAccentedCreditMedleysAndACutAsideAreRead() {
        assertFalse(ForkMashup.isMashup("Tiesto x Martin Garrix - The Only Way Is Up", "Tiësto, Martin Garrix"), "a credit, accents aside")
        assertTrue(ForkMashup.isMashup("90s Pop Medleys", "Some Channel"))
        assertFalse(ForkMashup.isMashup("Song (Remix x Edit", "Artist"), "a cut aside is an aside")
    }

    /**
     * The row's side (2B #3): a mashup row loses containment and the credit override, so it doesn't
     * stand in for one of its songs; the same row asked for by its own title still matches.
     */
    @Test
    fun aMashupRowDoesNotStandInForOneOfItsSongs() {
        val row = com.music.bitchord.data.model.Song(videoId = "r1", title = "Let Me Love You x Gata Only", artist = "Moonshine", thumbnailUrl = null, durationText = "3:25")
        kotlin.test.assertNull(TrackMatcher.score(row, TrackMatcher.Target("Let Me Love You", "DJ Snake", 205)))
        kotlin.test.assertNotNull(TrackMatcher.score(row, TrackMatcher.Target("Let Me Love You x Gata Only", "Moonshine", 205)))
        val plain = row.copy(title = "Let Me Love You", artist = "DJ Snake")
        kotlin.test.assertNotNull(TrackMatcher.score(plain, TrackMatcher.Target("Let Me Love You", "DJ Snake", 205)), "a plain row still matches")
    }
}
