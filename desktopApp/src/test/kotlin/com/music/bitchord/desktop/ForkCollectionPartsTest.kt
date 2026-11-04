package com.music.bitchord.desktop

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fork: the phone's rules ported for the PC's release and artist pages: the artist's top release
 * (ForkArtistTopRelease.kt), "Suggested" kept apart from a playlist's rows (ForkCollectionExtras.kt)
 * and which download records a release's "Delete download" removes (ForkCollectionActions.kt).
 */
class ForkCollectionPartsTest {

    private fun card(title: String, subtitle: String, id: String?) = ShelfItem(title, subtitle, null, null, id)
    private fun song(id: String, path: String? = null) = Song(videoId = id, title = id, artist = "A", thumbnailUrl = null, localPath = path)

    @Test
    fun theNewestReleaseWinsByItsYear() {
        val shelves = listOf(
            HomeShelf("Albums", listOf(card("Old", "Album • 2015", "MPREb_old"), card("New", "Album • 2021", "MPREb_new"))),
            HomeShelf("Singles & EPs", listOf(card("Later single", "Single • 2023", "MPREb_single"))),
        )
        assertEquals("Later single", ForkArtistTopRelease.pick(shelves)?.title)
    }

    @Test
    fun aTieKeepsYouTubesOrderSoTheAlbumLeads() {
        val shelves = listOf(
            HomeShelf("Albums", listOf(card("Album", "Album • 2022", "MPREb_a"))),
            HomeShelf("Singles", listOf(card("Single", "Single • 2022", "MPREb_s"))),
        )
        assertEquals("Album", ForkArtistTopRelease.pick(shelves)?.title)
    }

    @Test
    fun onlyAnMpreIdIsARelease() {
        val shelves = listOf(
            HomeShelf("Playlists", listOf(card("Playlist", "Playlist • 2024", "VLPL1"))),
            HomeShelf("Videos", listOf(card("Video", "2024", null))),
            HomeShelf("Fans might also like", listOf(card("Someone", "Artist", "UCx"))),
        )
        assertNull(ForkArtistTopRelease.pick(shelves))
        assertNull(ForkArtistTopRelease.pick(emptyList()))
    }

    @Test
    fun releasesWithNoYearKeepTheirOrder() {
        val shelves = listOf(HomeShelf("Albums", listOf(card("First", "Album", "MPREb_1"), card("Second", "Album", "MPREb_2"))))
        assertEquals("First", ForkArtistTopRelease.pick(shelves)?.title)
    }

    @Test
    fun theLabelFollowsWhatTheCardSays() {
        assertEquals("recent_single", ForkArtistTopRelease.labelKey(card("x", "Single • 2024", "MPREb_x")))
        assertEquals("recent_ep", ForkArtistTopRelease.labelKey(card("x", "EP • 2024", "MPREb_x")))
        // "ep" inside a word is not an EP.
        assertEquals("recent_album", ForkArtistTopRelease.labelKey(card("x", "Deep Album • 2024", "MPREb_x")))
        assertEquals("recent_album", ForkArtistTopRelease.labelKey(card("x", "Album • 2024", "MPREb_x")))
    }

    private val page = DesktopCollection("VLPL1", "Mine", "", "cover", BrowseType.PLAYLIST, listOf(song("a"), song("b")))

    @Test
    fun laterSuggestionsSkipRowsAndRepeats() {
        val first = page.forkWithSuggested(listOf(song("c"), song("a")))
        assertEquals(listOf("c"), first.forkSuggested.map { it.videoId })
        val second = first.forkWithSuggested(listOf(song("c"), song("d")))
        assertEquals(listOf("c", "d"), second.forkSuggested.map { it.videoId })
        assertEquals(page, page.forkWithSuggested(emptyList()))
    }

    @Test
    fun anAddedSuggestionMovesToTheEndOfTheRows() {
        val withSuggestions = page.copy(forkSuggested = listOf(song("c"), song("d")))
        val added = withSuggestions.withSuggestionAdded(song("c"), "SET1")
        assertEquals(listOf("a", "b", "c"), added.songs.map { it.videoId })
        assertEquals("SET1", added.songs.last().setVideoId)
        assertEquals("cover", added.songs.last().thumbnailUrl)
        assertEquals(listOf("d"), added.forkSuggested.map { it.videoId })
        // Already a row (a double click): no second row, only out of Suggested.
        val again = withSuggestions.copy(songs = added.songs).withSuggestionAdded(song("c"), "SET2")
        assertEquals(3, again.songs.size)
    }

    @Test
    fun deleteDownloadTakesThePagesRecordsButLeavesAnotherPlaylists() {
        val downloads = listOf(song("a", "/m/a.flac"), song("b", "/m/b.flac"), song("c", "/m/c.flac"), song("z", "/m/z.flac"))
        val doomed = forkDownloadsToDelete(setOf("a", "b", "c", "x"), downloads, keptIds = setOf("b"), keptFiles = setOf("/m/c.flac"))
        assertEquals(listOf("a"), doomed.map { it.videoId })
        // The records handed back are the window's, which carry the file.
        assertEquals("/m/a.flac", doomed.single().localPath)
        assertEquals(emptyList(), forkDownloadsToDelete(setOf("x"), downloads, emptySet(), emptySet()))
    }
}
