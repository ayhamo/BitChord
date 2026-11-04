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

    @Test
    fun featAndRemasterDifferencesStillMatch() {
        val answer = items(Triple("spotify:track:a", "Sad Girls", listOf("Bebe Rexha", "David Guetta")))
        assertEquals("spotify:track:a", ForkSpotifyMatch.pick(answer, "Sad Girls (feat. Someone)", "Bebe Rexha & David Guetta")?.uri)
        val remaster = items(Triple("spotify:track:b", "Song - Remastered 2011", listOf("Band")))
        assertEquals("spotify:track:b", ForkSpotifyMatch.pick(remaster, "Song", "Band")?.uri)
    }

    @Test
    fun anExactlyNamedHitBeatsOneMatchingOnlyWithoutItsTail() {
        // Spotify ranked the remix first; its name only matches once the brackets are off.
        val answer = items(
            Triple("spotify:track:remix", "Monody (Remix)", listOf("TheFatRat")),
            Triple("spotify:track:original", "Monody", listOf("TheFatRat")),
        )
        assertEquals("spotify:track:original", ForkSpotifyMatch.pick(answer, "Monody", "TheFatRat")?.uri)
        assertEquals("spotify:track:remix", ForkSpotifyMatch.pick(answer, "Monody (Remix)", "TheFatRat")?.uri)
        // With no exact name anywhere, the first tail-less match still counts.
        val only = items(Triple("spotify:track:b", "Song - Remastered 2011", listOf("Band")))
        assertEquals("spotify:track:b", ForkSpotifyMatch.pick(only, "Song", "Band")?.uri)
        // A wrong artist's exact name is not an exact hit.
        val cover = items(
            Triple("spotify:track:cover", "Monody", listOf("Some Cover Band")),
            Triple("spotify:track:real", "Monody (feat. Laura Brehm)", listOf("TheFatRat", "Laura Brehm")),
        )
        assertEquals("spotify:track:real", ForkSpotifyMatch.pick(cover, "Monody", "TheFatRat")?.uri)
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
    fun creditsSplitOnWholeWordsAndPlus() {
        assertEquals(listOf("feather", "sia"), ForkSpotifyMatch.split("Feather feat. Sia"))
        val answer = items(Triple("spotify:track:f", "Dog Days Are Over", listOf("Florence + The Machine")))
        assertEquals("spotify:track:f", ForkSpotifyMatch.pick(answer, "Dog Days Are Over", "Florence and the Machine")?.uri)
    }
}
