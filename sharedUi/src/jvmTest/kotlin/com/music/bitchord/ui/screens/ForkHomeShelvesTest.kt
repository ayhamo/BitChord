package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ForkHomeShelvesTest {
    private fun shelf(title: String) = HomeShelf(title, emptyList())
    private val server = listOf(shelf("Recently added"), shelf("Random albums"))
    private val mixes = listOf(shelf("Made for you"))

    /** Flows saying nothing (the frame before they catch up): the list's own sizes cut the sections. */
    private val stale = ForkHomeState(0, 0, navidromeOpen = true, spotifyOpen = true, youtubeOpen = true)

    @Test
    fun `no shelves of the fork's own leave the feed as it is`() {
        val feed = UiState.Error("offline")
        assertSame(feed, ForkHomeShelves.merge(feed, emptyList(), emptyList()))
    }

    @Test
    fun `a failed feed keeps its error beside the server's shelves`() {
        val merged = ForkHomeShelves.merge(UiState.Error("Could not load Home"), server, mixes) as UiState.Success
        val sections = stale.sections(merged.data)!!
        assertEquals(listOf("navidrome", "youtube", "spotify"), sections.map { it.key })
        assertEquals(emptyList<HomeShelf>(), sections[1].shelves)
        assertEquals("Could not load Home", stale.errorOf(merged.data))
    }

    @Test
    fun `a loading feed shows no error and no empty YouTube section`() {
        val merged = ForkHomeShelves.merge(UiState.Loading, server, emptyList()) as UiState.Success
        assertEquals(listOf("navidrome"), stale.sections(merged.data)!!.map { it.key })
        assertNull(stale.errorOf(merged.data))
    }

    @Test
    fun `sections are cut with the sizes the list carries, not the flows'`() {
        val feed = UiState.Success(listOf(shelf("Quick picks"), shelf("Listen again"), shelf("Trending")))
        val merged = ForkHomeShelves.merge(feed, server, mixes) as UiState.Success
        // Flows still saying 4 server shelves and none of Spotify's, from an earlier list.
        val behind = ForkHomeState(4, 0, navidromeOpen = true, spotifyOpen = true, youtubeOpen = true)
        val sections = behind.sections(merged.data)!!
        assertEquals(server, sections[0].shelves)
        assertEquals(feed.data, sections[1].shelves)
        assertEquals(mixes, sections[2].shelves)
    }

    @Test
    fun `the app's error stands in when the list carries none`() {
        val pc = ForkHomeState(0, 0, navidromeOpen = true, spotifyOpen = true, youtubeOpen = true, youtubeError = "HTTP 500")
        assertEquals("HTTP 500", pc.errorOf(server))
        assertNull(pc.sections(server))
    }

    @Test
    fun `equal lists with different sizes are not equal`() {
        assertEquals(ForkHomeShelves(server, 2, 0), ForkHomeShelves(server, 2, 0))
        assert(ForkHomeShelves(server, 2, 0) != ForkHomeShelves(server, 1, 0))
    }
}
