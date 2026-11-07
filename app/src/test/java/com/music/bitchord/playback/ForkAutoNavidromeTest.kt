package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkAutoNavidromeTest {

    @Test
    fun `a song picked from a list names both`() {
        val id = ForkAutoNavidrome.inContext("nd:album:al-1", "navidrome:tr-9")
        assertEquals("nd:album:al-1" to "navidrome:tr-9", ForkAutoNavidrome.parseInContext(id))
    }

    @Test
    fun `other ids are not songs in a list`() {
        assertNull(ForkAutoNavidrome.parseInContext("nd:album:al-1"))
        assertNull(ForkAutoNavidrome.parseInContext("navidrome:tr-9"))
        assertNull(ForkAutoNavidrome.parseInContext("nd:in|nd:album:al-1|"))
        assertNull(ForkAutoNavidrome.parseInContext("nd:in||navidrome:tr-9"))
    }

    @Test
    fun `only the folder's own ids are taken, never a song's or upstream's`() {
        assertTrue(ForkAutoNavidrome.isOurs(ForkAutoNavidrome.ROOT))
        assertFalse(ForkAutoNavidrome.isOurs("navidrome:tr-9"))
        assertFalse(ForkAutoNavidrome.isOurs("playlist:PL1"))
        assertFalse(ForkAutoNavidrome.isOurs("root"))
        assertFalse(ForkAutoNavidrome.isOurs("msg:navidrome_empty|nd:liked"))
    }

    @Test
    fun `album lists and playlists are grids, songs are rows`() {
        assertTrue(ForkAutoNavidrome.isGrid("nd:albums:newest"))
        assertTrue(ForkAutoNavidrome.isGrid("nd:playlists"))
        assertFalse(ForkAutoNavidrome.isGrid("nd:album:al-1"))
        assertFalse(ForkAutoNavidrome.isGrid("nd:liked"))
        assertFalse(ForkAutoNavidrome.isGrid("playlists"))
    }

    @Test
    fun `a short list is queued whole from the pick`() {
        assertEquals((0..9).toList() to 4, ForkAutoNavidrome.window((0..9).toList(), 4))
        assertEquals((0..9).toList() to 9, ForkAutoNavidrome.window((0..9).toList(), 40))
        assertEquals(emptyList<Int>() to 0, ForkAutoNavidrome.window(emptyList<Int>(), 3))
    }

    @Test
    fun `a long list is cut around the pick, which stays the one playing`() {
        val songs = (0 until 2_000).toList()
        val max = ForkAutoNavidrome.MAX_QUEUE
        for (pick in listOf(0, 10, 1_000, 1_999)) {
            val (queue, at) = ForkAutoNavidrome.window(songs, pick)
            assertEquals(max, queue.size)
            assertEquals(pick, queue[at])
        }
    }
}
