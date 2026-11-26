package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: the addon rulebook's 8.1.6 (never a manifest in the audio cache, [ForkAudioRangeCache]) and
 * 8.1.4 (a refused copy passes to the next row or source, [ForkNextRowPc]) on the PC.
 */
class ForkAddonRefusalsPcTest {

    private val dir = Files.createTempDirectory("fork-addon-refusals")
    private val spans = ForkAudioRangeCache({ dir })
    private val key = ForkAudioRangeCache.identity("gv|test")
    private val later = System.currentTimeMillis() + 60_000

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `a playlist or manifest is not kept, by its bytes or its type`() {
        spans.write(key, 0, text("#EXTM3U\n#EXT-X-VERSION:3\n"), 100, later)
        assertNull(spans.read(key, 0), "an HLS playlist")
        spans.write(key, 0, text("﻿<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"), 100, later)
        assertNull(spans.read(key, 0), "a DASH manifest")
        spans.write(key, 0, ByteArray(64) { 1 }, 100, later, mime = "application/dash+xml")
        assertNull(spans.read(key, 0), "a manifest by its type")
        spans.write(key, 64, ByteArray(36) { 1 }, 100, later, mime = "application/vnd.apple.mpegurl")
        assertNull(spans.read(key, 64), "a later window by its type")
        spans.write(key, 0, text("fLaC" + "x".repeat(60)), 100, later, mime = "audio/flac")
        assertNotNull(spans.read(key, 0), "a FLAC file is kept")
    }

    @Test
    fun `a stored playlist is not served, and is dropped`() {
        val bytes = text("#EXTM3U\n")
        val file = dir.resolve("$key-0.span")
        DataOutputStream(Files.newOutputStream(file)).use { out ->
            out.writeInt(0x42434131)
            out.writeLong(later)
            out.writeLong(100)
            out.writeInt(bytes.size)
            out.write(MessageDigest.getInstance("SHA-256").digest(bytes))
            out.write(bytes)
        }
        assertTrue(Files.exists(file))
        assertNull(spans.read(key, 0))
        assertFalse(Files.exists(file))
    }

    private val song = Song(videoId = "refusedPc01", title = "Refused", artist = "X", thumbnailUrl = null, durationText = "3:20")
    private fun row(id: String) = Song(videoId = id, title = "Refused", artist = "X", thumbnailUrl = null, durationText = "3:20")

    @Test
    fun `a refused row is passed over and the next one is asked, up to four more`() = runBlocking {
        val book = ForkNextRowPc.book
        book.forget(song.videoId)
        val rows = listOf(row("idA-1"), row("idA-2"))
        ForkNextRowPc.opened("https://a.example/1", "idA", "idA-1")
        // not refused: no next row is asked
        var asked = 0
        assertNull(ForkNextRowPc.next(song, DesktopStream(url = "https://a.example/1")) { asked++; null })
        assertEquals(0, asked)
        book.refuse(song.videoId, "https://a.example/1")
        assertEquals(listOf("idA-2"), ForkNextRowPc.left(song, "idA", rows).map { it.videoId })
        assertEquals(listOf("idB-1"), ForkNextRowPc.left(song, "idB", listOf(row("idB-1"))).map { it.videoId })
        val next = ForkNextRowPc.next(song, DesktopStream(url = "https://a.example/1")) { asked++; DesktopStream(url = "https://a.example/2") }
        assertEquals("https://a.example/2", next?.url)
        assertEquals(1, asked)
        // four more refusals use up the song: the fifth asks nothing, and no row is opened
        (2..5).forEach { book.refuse(song.videoId, "https://a.example/$it") }
        assertNull(ForkNextRowPc.next(song, DesktopStream(url = "https://a.example/5")) { asked++; DesktopStream(url = "x") })
        assertEquals(1, asked)
        assertEquals(emptyList(), ForkNextRowPc.left(song, "idB", listOf(row("idB-1"))))
        book.forget(song.videoId)
    }

    /** 8.1.4's cap stops the searches (`upgradeFor` asks nothing); "Upgrade quality" (8.1.7) gives the rows back; a skipped song asks nothing. */
    @Test
    fun `an exhausted song is searched no more until upgraded by hand, and a skipped one asks nothing`() = runBlocking {
        val book = ForkNextRowPc.book
        book.forget(song.videoId)
        (1..5).forEach { book.refuse(song.videoId, "https://b.example/$it") }
        assertTrue(ForkNextRowPc.exhausted(song))
        assertNull(DesktopSourceRegistry.upgradeFor(song, null), "no search for a song that used up its rows")
        ForkNextRowPc.forget(song)
        assertFalse(ForkNextRowPc.exhausted(song))

        book.refuse(song.videoId, "https://b.example/1")
        var asked = 0
        assertNull(ForkNextRowPc.next(song, DesktopStream(url = "https://b.example/1"), current = { false }) { asked++; DesktopStream(url = "x") })
        assertEquals(0, asked, "the song no longer plays")
        book.forget(song.videoId)
    }
}
