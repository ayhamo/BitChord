package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The song cache's trim leaves a clip that is still being fetched alone. */
class ForkClipCacheTest {

    @Test
    fun `a clip's part being written is not cache, a stale one and a finished clip are`() {
        val dir = Files.createTempDirectory("clipcache")
        try {
            val now = System.currentTimeMillis()
            val part = Files.write(dir.resolve("abc.mp4.part"), byteArrayOf(1))
            Files.setLastModifiedTime(part, FileTime.fromMillis(now - 5_000))
            assertTrue(ForkClipCache.writing(part, now))
            Files.setLastModifiedTime(part, FileTime.fromMillis(now - 2 * 3_600_000L))
            assertFalse(ForkClipCache.writing(part, now), "a crash's leftover is evictable")
            val clip = Files.write(dir.resolve("abc.mp4"), byteArrayOf(1))
            assertFalse(ForkClipCache.writing(clip, now))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
