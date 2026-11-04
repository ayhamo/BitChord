package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkPreloadTest {
    private fun server(id: String) = Song(videoId = NavidromeSettings.ID_PREFIX + id, title = id, artist = "a", thumbnailUrl = null, fromAutoplay = false)
    private fun youtube(id: String) = Song(videoId = id, title = id, artist = "a", thumbnailUrl = null, fromAutoplay = false)
    private val station = Song(videoId = "radio:7", title = "station", artist = "", thumbnailUrl = null, fromAutoplay = false)

    @Test
    fun `reads the server's songs among the next count, keeping the playing one`() {
        val plan = ForkPreload.plan(server("now"), listOf(server("1"), youtube("yt"), server("2"), server("3")), 3, eligible = { true })
        assertEquals(listOf("1", "2"), plan.read)
        assertEquals(setOf("now", "1", "2"), plan.keep)
    }

    @Test
    fun `skips stations and kept songs but still keeps a kept one's file name`() {
        val plan = ForkPreload.plan(youtube("yt"), listOf(station, server("kept"), server("1")), 5, eligible = { !it.videoId.endsWith("kept") })
        assertEquals(listOf("1"), plan.read)
        assertEquals(setOf("kept", "1"), plan.keep)
    }

    @Test
    fun `nothing with a count of zero`() {
        val plan = ForkPreload.plan(server("now"), listOf(server("1")), 0, eligible = { true })
        assertTrue(plan.read.isEmpty())
    }

    @Test
    fun `trims oldest first down to the cap and never what is wanted`() {
        val files = listOf(
            ForkPreload.Entry("old", 40, usedAt = 1),
            ForkPreload.Entry("wanted", 40, usedAt = 0),
            ForkPreload.Entry("mid", 40, usedAt = 2),
            ForkPreload.Entry("new", 40, usedAt = 3),
        )
        assertEquals(setOf("old", "mid"), ForkPreload.evictions(files, keep = setOf("wanted"), cap = 80))
        assertEquals(emptySet(), ForkPreload.evictions(files, keep = emptySet(), cap = 160))
        // Over the cap with only wanted songs left: they stay.
        assertEquals(setOf("old", "mid", "new"), ForkPreload.evictions(files, keep = setOf("wanted"), cap = 10))
    }

    @Test
    fun `an id becomes a safe file name`() {
        assertEquals("abc-1_2", ForkPreload.fileName("abc-1/2"))
        assertEquals("a_b", ForkPreload.fileName("a:b"))
    }
}
