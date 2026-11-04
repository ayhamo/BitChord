package com.music.bitchord.desktop

import com.music.bitchord.data.model.ArtistCredit
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fork: where the top bar's title and credits go, and its scrubber's clock (ForkTopBarLinks.kt). */
class ForkTopBarLinksTest {

    private val youtube = Song(videoId = "dQw4w9WgXcQ", title = "Song", artist = "A & B", thumbnailUrl = null)
    private val server = Song(
        videoId = NavidromeSettings.ID_PREFIX + "abc",
        title = "Track",
        artist = "Lead, Guest",
        thumbnailUrl = null,
        artistId = NavidromeSettings.artistPageId("Lead, Guest"),
        albumId = NavidromeSettings.albumPageId("Record"),
        albumName = "Record",
    )

    @Test
    fun theTitleOpensTheAlbumTheRowCarries() {
        assertEquals(ForkLinkTarget.Album("MPREb_x"), ForkTopBarRoutes.album(youtube.copy(albumId = "MPREb_x")))
        assertEquals(ForkLinkTarget.Album(NavidromeSettings.albumPageId("Record")), ForkTopBarRoutes.album(server))
    }

    @Test
    fun withoutAnAlbumYouTubeIsAskedAndTheServerUsesItsAlbumName() {
        assertEquals(ForkLinkTarget.FindAlbum("dQw4w9WgXcQ", "Song A & B"), ForkTopBarRoutes.album(youtube))
        // A Navidrome song never goes to YouTube for its album.
        assertEquals(
            ForkLinkTarget.Album(NavidromeSettings.albumPageId("Record")),
            ForkTopBarRoutes.album(server.copy(albumId = null)),
        )
        assertEquals(ForkLinkTarget.Search("Track Lead, Guest"), ForkTopBarRoutes.album(server.copy(albumId = null, albumName = null)))
        // Neither a YouTube id nor a server song (a local file): a search.
        assertEquals(ForkLinkTarget.Search("Song A & B"), ForkTopBarRoutes.album(youtube.copy(videoId = "local:/music/a.flac")))
    }

    @Test
    fun aStationLinksNowhere() {
        val station = youtube.copy(videoId = "radio:42")
        assertEquals(ForkLinkTarget.None, ForkTopBarRoutes.album(station))
        assertEquals(ForkLinkTarget.None, ForkTopBarRoutes.artist(station, ArtistCredit("A", "UC1")))
    }

    @Test
    fun aCreditOpensItsChannelOrIsFoundByName() {
        assertEquals(ForkLinkTarget.Artist("UC1", "A"), ForkTopBarRoutes.artist(youtube, ArtistCredit("A", "UC1")))
        assertEquals(ForkLinkTarget.FindArtist("dQw4w9WgXcQ", "B"), ForkTopBarRoutes.artist(youtube, ArtistCredit("B")))
        assertEquals(ForkLinkTarget.FindArtist(null, "B"), ForkTopBarRoutes.artist(youtube.copy(videoId = "local:x"), ArtistCredit("B")))
    }

    @Test
    fun aServerCreditOpensTheLibrarysPageForThatName() {
        assertEquals(
            ForkLinkTarget.Artist(NavidromeSettings.artistPageId("Guest"), "Guest"),
            ForkTopBarRoutes.artist(server, ArtistCredit("Guest")),
        )
    }

    @Test
    fun theBylineKeepsItsTextAndMarksEachName() {
        val segments = ForkTopBarRoutes.credits(
            youtube.copy(artists = listOf(ArtistRef("A", "UC1"), ArtistRef("B", "UC2"))),
        )
        assertEquals(listOf("A", " & ", "B"), segments.map { it.text })
        assertEquals(listOf("UC1", null, "UC2"), segments.map { it.credit?.browseId })
        // No stated credits: split on the joiners, the lead keeps the row's own artist id.
        val split = ForkTopBarRoutes.credits(server)
        assertEquals(listOf("Lead", ", ", "Guest"), split.map { it.text })
        assertEquals(NavidromeSettings.artistPageId("Lead, Guest"), split.first().credit?.browseId)
        assertEquals(null, split.last().credit?.browseId)
    }

    @Test
    fun theClockReadsLikeThePlayers() {
        assertEquals("0:00", ForkTopBarRoutes.clock(-5))
        assertEquals("3:07", ForkTopBarRoutes.clock(187_900))
        assertEquals("1:02:03", ForkTopBarRoutes.clock(3_723_000))
    }
}
