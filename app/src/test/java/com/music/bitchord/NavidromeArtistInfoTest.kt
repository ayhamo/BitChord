package com.music.bitchord

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeArtistInfo
import com.music.bitchord.data.navidrome.NavidromeClient
import com.music.bitchord.data.navidrome.NavidromeSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fork: a Navidrome artist's radio and "Similar artists" row, from the server's answers. */
class NavidromeArtistInfoTest {

    private fun response(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun row(artist: String?, artistId: String?, artists: List<Pair<String, String>> = emptyList()) = NavidromeClient.SongRow(
        id = "s", title = "T", artist = artist, album = null, albumId = null, coverArt = null, track = null, disc = null,
        year = null, durationSec = null, suffix = null, bitDepth = null, samplingRate = null,
        artistId = artistId, artists = artists,
    )

    @Test
    fun similarArtistsInTheServersOrder() {
        val rows = NavidromeClient.parseSimilarArtists(
            response(
                """{"status":"ok","artistInfo2":{"biography":"…","similarArtist":[
                    {"id":"a2","name":"Beyoncé","coverArt":"ar-a2_0","albumCount":3},
                    {"id":"a3","name":"Rihanna"},
                    {"name":"No id"}]}}""",
            ),
        )
        assertEquals(listOf("Beyoncé", "Rihanna"), rows.map { it.name })
        assertEquals("ar-a2_0", rows[0].coverArt)
        assertNull(rows[1].coverArt)
    }

    @Test
    fun oneSimilarArtistIsABareObjectAndNoneIsEmpty() {
        assertEquals(
            listOf("a2"),
            NavidromeClient.parseSimilarArtists(response("""{"artistInfo2":{"similarArtist":{"id":"a2","name":"Beyoncé"}}}""")).map { it.id },
        )
        assertEquals(emptyList<NavidromeClient.ArtistRow>(), NavidromeClient.parseSimilarArtists(response("""{"artistInfo2":{}}""")))
        assertEquals(emptyList<NavidromeClient.ArtistRow>(), NavidromeClient.parseSimilarArtists(response("""{"status":"ok"}""")))
    }

    @Test
    fun cardsOpenEachArtistsNavidromePageWithItsServerPicture() {
        val rows = listOf(
            NavidromeClient.ArtistRow("a2", "Beyoncé", "ar-a2_0"),
            NavidromeClient.ArtistRow("a3", "Rihanna", null),
            NavidromeClient.ArtistRow("a1", "Lady Gaga", null), // the page's own artist
            NavidromeClient.ArtistRow("a4", "Beyoncé", null), // a second entry of the same name
        )
        val items = NavidromeArtistInfo.shelfItems("Lady Gaga", rows) { "cover:$it" }
        assertEquals(listOf("Beyoncé", "Rihanna"), items.map { it.title })
        assertEquals(listOf(NavidromeSettings.artistPageId("Beyoncé"), NavidromeSettings.artistPageId("Rihanna")), items.map { it.browseId })
        assertEquals(listOf("cover:ar-a2_0", "cover:ar-a3"), items.map { it.thumbnailUrl })
        assertEquals(listOf(null, null), items.map { it.videoId })
    }

    @Test
    fun artistIdsFromTheSongs() {
        // OpenSubsonic: every credited artist with its id; the display credit is no artist of its own.
        assertEquals(
            listOf("Lady Gaga" to "a1", "Beyoncé" to "a2"),
            NavidromeArtistInfo.idsOf(row("Lady Gaga feat. Beyoncé", "a1", listOf("a1" to "Lady Gaga", "a2" to "Beyoncé"))),
        )
        // An older server: only the one artist and its id.
        assertEquals(listOf("Lady Gaga" to "a1"), NavidromeArtistInfo.idsOf(row("Lady Gaga", "a1")))
        assertEquals(emptyList<Pair<String, String>>(), NavidromeArtistInfo.idsOf(row("Lady Gaga", null)))
    }

    @Test
    fun songArtistsParsed() {
        val artists = NavidromeClient.parseArtists(Json.parseToJsonElement("""[{"id":"a1","name":"Lady Gaga"},{"id":"a2","name":"Beyoncé"}]"""))
        assertEquals(listOf("a1" to "Lady Gaga", "a2" to "Beyoncé"), artists.map { it.id to it.name })
    }

    @Test
    fun searchKeepsOnlyTheArtistItself() {
        val hits = listOf(
            NavidromeClient.ArtistRow("x", "Lady Gaga & Tony Bennett", null),
            NavidromeClient.ArtistRow("y", "lady gaga", null),
            NavidromeClient.ArtistRow("z", "Lady Gaga", null),
        )
        assertEquals("z", NavidromeArtistInfo.pick("Lady Gaga", hits)?.id)
        assertEquals("y", NavidromeArtistInfo.pick("LADY GAGA", hits)?.id)
        assertNull(NavidromeArtistInfo.pick("Gaga", hits))
    }

    @Test
    fun stationKeepsTheServersOrderTheArtistsOwnSongsAndOneCopyEach() {
        fun song(id: String, title: String, artist: String) = Song(videoId = "navidrome:$id", title = title, artist = artist, thumbnailUrl = null)
        val own = song("1", "Poker Face", "Lady Gaga")
        val other = song("2", "Halo", "Beyoncé")
        val copy = song("3", "Poker Face", "Lady Gaga") // the opus copy of the FLAC
        val station = NavidromeArtistInfo.station("Lady Gaga", listOf(own, other, copy))
        assertEquals(listOf(own.videoId, other.videoId), station.map { it.videoId })
        assertEquals(listOf("Lady Gaga", "Lady Gaga"), station.map { it.radioName })
        assertEquals(PlaybackSourceType.BROWSE, station.first().playbackSourceType)
        assertEquals(NavidromeSettings.artistPageId("Lady Gaga"), station.first().playbackSourceId)
        assertEquals(emptyList<Song>(), NavidromeArtistInfo.station("Lady Gaga", emptyList()))
    }

    @Test
    fun onlyNavidromeArtistPagesHaveAName() {
        assertEquals("Lady Gaga", NavidromeArtistInfo.nameOf(NavidromeSettings.artistPageId("Lady Gaga")))
        assertNull(NavidromeArtistInfo.nameOf(NavidromeSettings.albumPageId("The Fame")))
        assertNull(NavidromeArtistInfo.nameOf("UCabc"))
        assertNull(NavidromeArtistInfo.nameOf(null))
    }
}
