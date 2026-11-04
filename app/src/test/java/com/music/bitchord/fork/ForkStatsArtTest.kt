package com.music.bitchord.fork

import com.music.bitchord.data.stats.NameEntry
import com.music.bitchord.data.stats.StoredBucket
import com.music.bitchord.data.stats.TrackEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ForkStatsArtTest {
    private val signed = "https://music.example.org/rest/getCoverArt?id=al-1&size=600&u=me&t=abc&s=salt&v=1.16.1&c=BitChord"

    @Test
    fun `older covers lose the login, and the month is written back once`() {
        val dir = Files.createTempDirectory("stats").toFile()
        try {
            val file = dir.resolve("2026-10.json").apply { writeText("old") }
            val bucket = StoredBucket(
                month = "2026-10",
                tracks = listOf(TrackEntry(id = "navidrome:1", art = signed)),
                albums = listOf(NameEntry(name = "Album", art = signed)),
                artists = listOf(NameEntry(name = "Artist", art = "https://i.ytimg.com/vi/x/hq.jpg")),
            )
            val clean = ForkStatsArt.scrub(bucket, file) { "clean" }
            listOf(clean.tracks[0].art, clean.albums[0].art).forEach { art ->
                assertFalse(art!!, "t=" in art || "u=" in art || "s=salt" in art)
                assertTrue(art, "id=al-1" in art)
            }
            assertEquals("https://i.ytimg.com/vi/x/hq.jpg", clean.artists[0].art)
            assertEquals("clean", file.readText())
            // Already clean: left alone, nothing written.
            file.writeText("untouched")
            assertSame(clean, ForkStatsArt.scrub(clean, file) { "again" })
            assertEquals("untouched", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
