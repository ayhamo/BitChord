package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: "Keep playlists updated" on the PC ([ForkPlaylistSync]): its walk and its schedule. */
class ForkPlaylistSyncTest {

    private fun songs(vararg ids: String) = ids.map { Song(videoId = it, title = it, artist = "a", thumbnailUrl = "") }

    @Test
    fun `a walk is complete only when a page comes back without a token`() = runBlocking {
        val pages = mapOf("t1" to (songs("c", "d") to "t2"), "t2" to (songs("e") to null))
        val (read, complete) = ForkPlaylistSync.walkPages(songs("a", "b"), "t1", more = { pages[it] })
        assertEquals(listOf("a", "b", "c", "d", "e"), read.map { it.videoId })
        assertTrue(complete)
    }

    @Test
    fun `a page with nothing new but a token still attached is not the end`() = runBlocking {
        // Taken as the end, the record was cut short and deleted tracks after it came back as new.
        val pages = mapOf("t1" to (songs("a") to "t2"))
        val (read, complete) = ForkPlaylistSync.walkPages(songs("a", "b"), "t1", more = { pages[it] })
        assertEquals(listOf("a", "b"), read.map { it.videoId })
        assertFalse(complete)
    }

    @Test
    fun `a failed page leaves the walk incomplete`() = runBlocking {
        val (_, complete) = ForkPlaylistSync.walkPages(songs("a"), "t1", more = { null })
        assertFalse(complete)
    }

    @Test
    fun `a check that read nothing is tried again in half an hour, not in twelve`() {
        val hour = 60L * 60 * 1000
        val failedAt = 1_000_000_000L
        val stamp = ForkPlaylistSync.attemptStamp(failedAt, readSomething = false)
        val sinceComplete = 24 * hour
        assertFalse(ForkPlaylistSync.checkDue(first = false, sinceComplete, failedAt + 20 * 60_000 - stamp))
        assertTrue(ForkPlaylistSync.checkDue(first = false, sinceComplete, failedAt + 30 * 60_000 - stamp))
        // One that read its playlists waits the whole period.
        val read = ForkPlaylistSync.attemptStamp(failedAt, readSomething = true)
        assertFalse(ForkPlaylistSync.checkDue(first = false, sinceComplete, failedAt + 11 * hour - read))
        assertTrue(ForkPlaylistSync.checkDue(first = false, sinceComplete, failedAt + 12 * hour - read))
    }
}
