package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.ForkHomeShelves
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkDesktopHomeTest {
    private fun shelf(title: String, items: Int = 1) =
        HomeShelf(title, List(items) { ShelfItem(title = "$title $it", subtitle = "", thumbnailUrl = null, videoId = null, browseId = "$title:$it") })

    private val server = listOf(shelf("Recently added"), shelf("Playlists"))
    private val kept = shelf("Available offline")

    @Test
    fun `online the server's shelves, offline only what is kept`() {
        assertEquals(server, ForkDesktopHome.navidromeShelves(server, kept, shown = true, offline = false))
        assertEquals(listOf(kept), ForkDesktopHome.navidromeShelves(server, kept, shown = true, offline = true))
        assertTrue(ForkDesktopHome.navidromeShelves(server, shelf("Available offline", 0), shown = true, offline = true).isEmpty())
    }

    @Test
    fun `Navidrome on Home off leaves no section`() {
        assertTrue(ForkDesktopHome.navidromeShelves(server, kept, shown = false, offline = false).isEmpty())
        assertTrue(ForkDesktopHome.navidromeShelves(server, kept, shown = false, offline = true).isEmpty())
    }

    @Test
    fun `the PC's feed is cut into the phone's sections`() {
        val feed = UiState.Success(listOf(shelf("Quick picks"), shelf("Recently played")))
        val merged = ForkHomeShelves.merge(feed, server, listOf(shelf("Made for you"))) as UiState.Success
        val list = merged.data as ForkHomeShelves
        assertEquals(2, list.navidrome)
        assertEquals(1, list.spotify)
        assertEquals(listOf("Recently added", "Playlists", "Quick picks", "Recently played", "Made for you"), list.map { it.title })
    }
}
