package com.music.bitchord

import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.download.forkFreeName
import com.music.bitchord.download.forkTakeUtf8
import com.music.bitchord.download.inFlightOnlyIn
import com.music.bitchord.fork.OfflineServiceStep
import com.music.bitchord.fork.offlineServiceStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: the phone's download fixes of the 2026-11-05 audit that need no device. */
class ForkDownloadFixesTest {

    @Test
    fun `a long CJK title is cut by bytes, not characters`() {
        // 120 CJK characters are 360 bytes; the file system's ceiling is 255 bytes and the download failed.
        val stem = DownloadStore.forkTakeUtf8("日".repeat(120), 200)
        assertTrue(stem.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(66, stem.length)
        // Never half a surrogate pair.
        val emoji = DownloadStore.forkTakeUtf8("a" + "🎵".repeat(10), 7)
        assertEquals("a🎵", emoji)
        assertEquals("plain", DownloadStore.forkTakeUtf8("plain", 200))
    }

    @Test
    fun `a name another worker just used gets a numbered one`() {
        val taken = setOf("Intro.flac", "Intro (2).flac")
        assertEquals("Intro (3).flac", DownloadStore.forkFreeName("Intro.flac") { it in taken })
        assertEquals("Outro.flac", DownloadStore.forkFreeName("Outro.flac") { it in taken })
    }

    @Test
    fun `deleting a release calls off its queued and running tracks, not another release's`() {
        val collections = mapOf(
            "one" to SavedCollection(id = "one", title = "One", playlist = false, videoIds = listOf("a", "b", "c")),
            "two" to SavedCollection(id = "two", title = "Two", playlist = false, videoIds = listOf("c")),
        )
        val active = mapOf(
            "a" to DownloadState.Queued,
            "b" to DownloadState.Running(0.5f),
            "c" to DownloadState.Queued,
            "d" to DownloadState.Running(0.1f),
        )
        assertEquals(listOf("a", "b", "d"), Downloads.inFlightOnlyIn("one", collections, setOf("d"), active))
    }

    @Test
    fun `available offline waits for a network out of the foreground`() {
        assertEquals(OfflineServiceStep.LEAVE_FOREGROUND, offlineServiceStep(active = true, allowed = false, inForeground = true))
        assertEquals(OfflineServiceStep.PROMOTE, offlineServiceStep(active = true, allowed = true, inForeground = false))
        assertEquals(OfflineServiceStep.NOTIFY, offlineServiceStep(active = true, allowed = false, inForeground = false))
        assertEquals(OfflineServiceStep.FINISH, offlineServiceStep(active = false, allowed = true, inForeground = true))
    }
}
