package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork: the Cached songs index (ForkCachedSongs.kt) and the Spotify-first order (ForkSpotifyCanvasFirst.kt). */
class ForkCachedSongsTest {

    private val dir = Files.createTempDirectory("fork-cached-songs")
    private val window = 4
    private val spans = ForkAudioRangeCache({ dir })
    private val song = Song(videoId = "abc123", title = "Monody", artist = "TheFatRat", thumbnailUrl = null, durationText = "4:50", albumName = "Monody")
    private val url = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=99&id=o-AB&itag=251&lmt=1700&clen=10&mime=audio%2Fwebm&sig=SECRET"

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun index() = ForkCachedSongIndex({ dir }, window)

    private fun keep(index: ForkCachedSongIndex, start: Long) {
        val stream = ForkCachedSongIndex.streamOf(url)!!
        val size = minOf(window.toLong(), 10 - start).toInt()
        spans.write(index.keyOf(stream), start, ByteArray(size) { 1 }, 10, System.currentTimeMillis() + 60_000)
    }

    @Test
    fun `a song is listed once its windows are on disk, and whole once all are`() {
        val index = index()
        assertTrue(index.record(song, url))
        assertFalse(index.record(song, url), "the same stream again writes nothing")
        keep(index, 0)
        keep(index, 4)
        val partial = index.list().single()
        assertEquals("Monody", partial.song.title)
        assertEquals("TheFatRat", partial.song.artist)
        assertEquals("Monody", partial.song.albumName)
        assertEquals("4:50", partial.song.durationText)
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", partial.song.thumbnailUrl)
        assertFalse(partial.complete)
        assertNull(index.keptUrl(song.videoId) { _, _ -> true }, "a partial copy is never offered offline")
        keep(index, 8)
        assertTrue(index.list().single().complete)
        assertTrue(index.holds(song.videoId))
    }

    @Test
    fun `the offline address makes the range reader's own keys`() {
        val index = index()
        index.record(song, url)
        listOf(0L, 4L, 8L).forEach { keep(index, it) }
        val (kept, container) = assertNotNull(index.keptUrl(song.videoId) { _, _ -> true })
        assertEquals("webm", container)
        assertFalse(kept.contains("SECRET"))
        val stream = ForkCachedSongIndex.streamOf(url)!!
        assertEquals(index.keyOf(stream), ForkAudioRangeCache.keyFor(kept, emptyMap(), window))
        assertEquals(ForkAudioRangeCache.keyFor(url, emptyMap(), window), ForkAudioRangeCache.keyFor(kept, emptyMap(), window))
        assertNull(index.keptUrl(song.videoId) { _, start -> start != 4L }, "an expired window keeps it from playing offline")
        assertTrue(spans.fresh(index.keyOf(stream), 0))
        assertFalse(spans.fresh(index.keyOf(stream), 12), "no window there")
    }

    @Test
    fun `the index survives a restart and keeps no address`() {
        index().record(song, url)
        keep(index(), 0)
        val text = Files.readString(dir.resolve(ForkCachedSongIndex.FILE))
        assertFalse(text.contains("SECRET") || text.contains("expire") || text.contains("googlevideo"))
        assertEquals("Monody", index().list().single().song.title)
    }

    @Test
    fun `evicted windows take the song out of the index`() {
        val index = index()
        index.record(song, url)
        keep(index, 0)
        assertEquals(1, index.list().size)
        Files.list(dir).use { files -> files.filter { it.fileName.toString().endsWith(".span") }.toList() }.forEach(Files::delete)
        assertTrue(index.list().isEmpty())
        assertFalse(Files.readString(dir.resolve(ForkCachedSongIndex.FILE)).contains("abc123"))
    }

    @Test
    fun `remove deletes the song's windows and its row`() {
        val index = index()
        index.record(song, url)
        keep(index, 0)
        keep(index, 4)
        assertTrue(index.remove(song.videoId)!! > 8L, "both windows, headers included")
        assertTrue(Files.list(dir).use { files -> files.noneMatch { it.fileName.toString().endsWith(".span") } })
        assertNull(index.remove(song.videoId))
        assertTrue(index.list().isEmpty())
        assertFalse(index.holds(song.videoId))
    }

    @Test
    fun `only YouTube songs on a kept address are recorded`() {
        val index = index()
        assertFalse(index.record(song.copy(videoId = "navidrome:42"), url))
        assertFalse(index.record(song.copy(videoId = "jiosaavn:x"), url))
        assertFalse(index.record(song, "https://aac.saavncdn.com/x.mp4"))
        assertFalse(index.record(song, "https://rr1.googlevideo.com/videoplayback?id=o-AB&itag=251"))
    }

    @Test
    fun `durations read as the phone writes them`() {
        assertEquals(290, forkDurationSeconds("4:50"))
        assertEquals(3723, forkDurationSeconds("1:02:03"))
        assertNull(forkDurationSeconds("live"))
    }

    @Test
    fun `Spotify first moves the last provider to the front and keys the cache on the order`() {
        val sources = listOf("apple", "tidal", "community", "spotify")
        assertEquals(sources, ForkSpotifyCanvasFirst.ordered(sources, spotifyFirst = false))
        assertEquals(listOf("spotify", "apple", "tidal", "community"), ForkSpotifyCanvasFirst.ordered(sources, spotifyFirst = true))
        assertEquals("song|x|spotifyFirst=true", ForkSpotifyCanvasFirst.key("song|x", spotifyFirst = true))
        assertTrue(ForkSpotifyCanvasFirst.key("song|x", true) != ForkSpotifyCanvasFirst.key("song|x", false))
    }
}
