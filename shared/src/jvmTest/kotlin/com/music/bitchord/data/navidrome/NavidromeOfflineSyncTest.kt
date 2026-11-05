package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [NavidromeOffline.syncedSongs]: an automatic sync of a kept playlist never lets a kept song go.
 * A server mid-rescan listed a 100-song playlist as 60, and the sweep after the re-keep deleted
 * 40 files that only a new download could bring back.
 */
class NavidromeOfflineSyncTest {
    private fun songs(vararg ids: String) = ids.map { Song(videoId = it, title = it, artist = "a", thumbnailUrl = "") }
    private fun ids(list: List<Song>?) = list?.map { it.videoId }

    @Test
    fun `a playlist listed shorter keeps its songs and is not kept again`() {
        assertNull(NavidromeOffline.syncedSongs(songs("a", "b", "c", "d"), songs("a", "c"), renamed = false))
    }

    @Test
    fun `new songs are fetched while the songs the server stopped listing stay`() {
        val next = NavidromeOffline.syncedSongs(songs("a", "b", "c"), songs("a", "x"), renamed = false)
        assertEquals(listOf("a", "x", "b", "c"), ids(next))
    }

    @Test
    fun `a held-back song does not re-keep the playlist on every sync`() {
        val kept = NavidromeOffline.syncedSongs(songs("a", "b", "c"), songs("a", "x"), renamed = false)!!
        assertNull(NavidromeOffline.syncedSongs(kept, songs("a", "x"), renamed = false))
    }

    @Test
    fun `a playlist listing one song twice is not kept again on every sync`() {
        val kept = NavidromeOffline.syncedSongs(songs("a", "b", "c"), songs("a", "x", "a"), renamed = false)!!
        assertEquals(listOf("a", "x", "a", "b", "c"), ids(kept))
        assertNull(NavidromeOffline.syncedSongs(kept, songs("a", "x", "a"), renamed = false))
        assertNull(NavidromeOffline.syncedSongs(songs("a", "x", "a"), songs("a", "x", "a"), renamed = false))
    }

    @Test
    fun `the server listing every song again takes its order`() {
        assertEquals(listOf("c", "b", "a"), ids(NavidromeOffline.syncedSongs(songs("a", "b", "c"), songs("c", "b", "a"), renamed = false)))
    }

    @Test
    fun `an empty listing changes nothing and a rename keeps it again`() {
        assertNull(NavidromeOffline.syncedSongs(songs("a"), emptyList(), renamed = true))
        assertEquals(listOf("a", "b"), ids(NavidromeOffline.syncedSongs(songs("a", "b"), songs("a"), renamed = true)))
    }
}
