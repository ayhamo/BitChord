package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: where the PC's downloads go, and when its startup check may forget records. */
class ForkDownloadFilesTest {

    private val dir = Path.of("downloads")

    @Test
    fun `a name another track owns gets a numbered one`() {
        // Keyed by name alone, the second "Artist - Title" was "already downloaded" as the first.
        val owners = mapOf(dir.resolve("A - T.flac") to "one", dir.resolve("A - T (2).flac") to "two")
        assertEquals(dir.resolve("A - T (3).flac"), forkDownloadTarget(dir, "A - T", "flac", "three") { owners[it] })
        assertEquals(dir.resolve("A - T (2).flac"), forkDownloadTarget(dir, "A - T", "flac", "two") { owners[it] })
    }

    @Test
    fun `a free name or one nobody owns is used as it is`() {
        assertEquals(dir.resolve("A - T.m4a"), forkDownloadTarget(dir, "A - T", "m4a", "one") { null })
    }

    private fun song(id: String) = Song(videoId = id, title = id, artist = "a", thumbnailUrl = "")

    @Test
    fun `a missing folder or every record failing forgets nothing`() {
        val recorded = listOf(song("a"), song("b"))
        assertFalse(forkMayPruneDownloads(recorded, emptyList(), folderThere = true))
        assertFalse(forkMayPruneDownloads(recorded, listOf(song("a")), folderThere = false))
        assertTrue(forkMayPruneDownloads(recorded, listOf(song("a")), folderThere = true))
    }
}
