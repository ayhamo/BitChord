package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.launch
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

    @Test
    fun `a resume appends only a 206 that starts where the part ends`() {
        assertEquals(true, forkResumeAppends(206, "bytes 1000-4095/4096", 1000))
        assertEquals(null, forkResumeAppends(206, "bytes 0-4095/4096", 1000), "a 206 from 0 to a resume used to double the start")
        assertEquals(null, forkResumeAppends(206, "bytes 2000-4095/4096", 1000))
        assertEquals(null, forkResumeAppends(206, null, 1000), "no range stated: not appended blind")
        assertEquals(false, forkResumeAppends(200, null, 1000), "a whole file starts afresh")
        assertEquals(false, forkResumeAppends(206, "bytes 0-4095/4096", 0))
    }

    @Test
    fun `a cancel that lands with the move takes the landed file too`() {
        val dir = java.nio.file.Files.createTempDirectory("fork-land")
        try {
            val part = java.nio.file.Files.write(dir.resolve("a.part"), byteArrayOf(1, 2, 3))
            val target = dir.resolve("a.flac")
            kotlinx.coroutines.runBlocking {
                val job = launch {
                    coroutineContext[kotlinx.coroutines.Job]!!.cancel() // called off while it moves
                    forkLand(part, target)
                }
                job.join()
                assertTrue(job.isCancelled)
            }
            assertFalse(java.nio.file.Files.exists(target), "no file without a record")
            assertFalse(java.nio.file.Files.exists(part))

            val part2 = java.nio.file.Files.write(dir.resolve("b.part"), byteArrayOf(1))
            kotlinx.coroutines.runBlocking { forkLand(part2, dir.resolve("b.flac")) }
            assertTrue(java.nio.file.Files.exists(dir.resolve("b.flac")), "a move nobody called off lands")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
