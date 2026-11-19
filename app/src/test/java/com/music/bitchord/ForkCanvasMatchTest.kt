package com.music.bitchord

import com.music.bitchord.fork.ForkCanvas
import com.music.bitchord.fork.ForkSpotifyMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkCanvasMatchTest {

    private fun items(vararg tracks: Triple<String, String, List<String>>) = Json.parseToJsonElement(
        tracks.joinToString(",", "[", "]") { (uri, name, artists) ->
            """{"item":{"data":{"uri":"$uri","name":"$name","artists":{"items":[${
                artists.joinToString(",") { """{"profile":{"name":"$it"}}""" }
            }]},"albumOfTrack":{"name":"Album"}}}}"""
        },
    ).jsonArray

    @Test
    fun theWrongFirstHitIsSkipped() {
        val answer = items(
            Triple("spotify:track:cover", "Levels", listOf("Some Cover Band")),
            Triple("spotify:track:real", "Levels", listOf("Avicii")),
        )
        assertEquals("spotify:track:real", ForkSpotifyMatch.pick(answer, "Levels", "Avicii")?.uri)
    }

    @Test
    fun noMatchingHitMeansNone() {
        val answer = items(Triple("spotify:track:x", "Other Song", listOf("Avicii")))
        assertNull(ForkSpotifyMatch.pick(answer, "Levels", "Avicii"))
        assertTrue(ForkSpotifyMatch.carriesNames(answer))
    }

    /**
     * The canvas rulebook's match (rule 4.4): title words shared at least 0.75. A guest named only in
     * the title or a " - Remastered 2011" tail is a word share under that, so those hits are no longer
     * taken (the fork's own check took them); the rulebook's cases are the reference.
     */
    @Test
    fun theRulebookWordShareDecides() {
        val answer = items(Triple("spotify:track:a", "Sad Girls", listOf("Bebe Rexha", "David Guetta")))
        assertEquals("spotify:track:a", ForkSpotifyMatch.pick(answer, "Sad Girls", "Bebe Rexha & David Guetta")?.uri)
        assertNull(ForkSpotifyMatch.pick(answer, "Sad Girls (feat. Someone)", "Bebe Rexha & David Guetta"))
        val remaster = items(Triple("spotify:track:b", "Song - Remastered 2011", listOf("Band")))
        assertNull(ForkSpotifyMatch.pick(remaster, "Song", "Band"))
        // a guest Spotify credits and the song doesn't: the same primary artist is enough
        val guest = items(Triple("spotify:track:g", "Monody", listOf("TheFatRat", "Laura Brehm")))
        assertEquals("spotify:track:g", ForkSpotifyMatch.pick(guest, "Monody", "TheFatRat")?.uri)
    }

    @Test
    fun aRemixRankedFirstIsNotTheOriginal() {
        // Spotify ranked the remix first: "Monody (Remix)" shares one word of two with "Monody".
        val answer = items(
            Triple("spotify:track:remix", "Monody (Remix)", listOf("TheFatRat")),
            Triple("spotify:track:original", "Monody", listOf("TheFatRat")),
        )
        assertEquals("spotify:track:original", ForkSpotifyMatch.pick(answer, "Monody", "TheFatRat")?.uri)
        assertEquals("spotify:track:remix", ForkSpotifyMatch.pick(answer, "Monody (Remix)", "TheFatRat")?.uri)
        // A wrong artist's exact name is never taken.
        val cover = items(
            Triple("spotify:track:cover", "Monody", listOf("Some Cover Band")),
            Triple("spotify:track:real", "Monody", listOf("TheFatRat", "Laura Brehm")),
        )
        assertEquals("spotify:track:real", ForkSpotifyMatch.pick(cover, "Monody", "TheFatRat")?.uri)
    }

    @Test
    fun aDifferentAlbumIsAnotherRecording() {
        val answer = items(Triple("spotify:track:a", "Levels", listOf("Avicii")))
        // the helper's album is "Album"
        assertNull(ForkSpotifyMatch.pick(answer, "Levels", "Avicii", "True"))
        assertEquals("spotify:track:a", ForkSpotifyMatch.pick(answer, "Levels", "Avicii", "Album")?.uri)
    }

    @Test
    fun aServerTitleFrontIsSplitLikeTheArtistTag() {
        assertEquals("Monody", ForkCanvas.withoutArtistPrefix("TheFatRat feat. Laura Brehm - Monody", "TheFatRat, Laura Brehm"))
        assertEquals("Monody", ForkCanvas.withoutArtistPrefix("TheFatRat x Laura Brehm - Monody", "TheFatRat & Laura Brehm"))
        // A front naming someone the tag doesn't stays a title.
        assertEquals("TheFatRat & Someone - Monody", ForkCanvas.withoutArtistPrefix("TheFatRat & Someone - Monody", "TheFatRat"))
    }

    @Test
    fun anAnswerWithoutNamesIsLeftToTheOldPath() {
        val bare = Json.parseToJsonElement("""[{"item":{"data":{"uri":"spotify:track:z"}}}]""").jsonArray
        assertNull(ForkSpotifyMatch.pick(bare, "Levels", "Avicii"))
        assertFalse(ForkSpotifyMatch.carriesNames(bare))
    }

    @Test
    fun aServerTitleLosesTheArtistInFront() {
        assertEquals("Monody (New Epic Orchestra Remix)", ForkCanvas.withoutArtistPrefix("TheFatRat - Monody (New Epic Orchestra Remix)", "TheFatRat"))
        assertEquals("Fly Away", ForkCanvas.withoutArtistPrefix("TheFatRat & Anjulie - Fly Away", "TheFatRat, Anjulie"))
        // A dash that isn't the artist stays.
        assertEquals("Song - Live", ForkCanvas.withoutArtistPrefix("Song - Live", "Band"))
        // "feat" only as a word: "Feather" used to split into "her" and never match its own name.
        assertEquals("Song", ForkCanvas.withoutArtistPrefix("Feather & Sia - Song", "Feather, Sia"))
    }

    @Test
    fun creditsSplitOnWholeWords() {
        assertEquals(listOf("feather", "sia"), ForkSpotifyMatch.split("Feather feat. Sia"))
        val answer = items(Triple("spotify:track:f", "Dog Days Are Over", listOf("Florence + The Machine")))
        assertEquals("spotify:track:f", ForkSpotifyMatch.pick(answer, "Dog Days Are Over", "Florence + The Machine")?.uri)
    }
}
