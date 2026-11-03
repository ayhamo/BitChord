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
    }
}
