package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fork: the PC's downloads tagged the phone's way ([ForkDownloadTags.embed] on a file, every
 * container the PC saves), and the words read back by the PC's own reader ([DesktopEmbeddedLyrics]).
 * Hand-built files, as in the phone's `MediaTaggerTest`: the containers' layout is stated exactly.
 */
class ForkDownloadTagsTest {

    private val scratch = Files.createTempDirectory("bitchord-tags")

    @AfterTest
    fun cleanUp() {
        scratch.toFile().deleteRecursively()
    }

    private val song = Song(videoId = "abc123", title = "Shayar Ü", artist = "An Artist", thumbnailUrl = null, albumName = "An Album")
    private val cover = ByteArray(300) { (it * 7).toByte() }
    private val plain = "[00:01.00]first line\n[00:02.00]second line"
    private val enhanced = "[00:01.00]<00:01.00>first <00:01.50>line"
    private val audio = ByteArray(4_000) { (it % 251).toByte() }

    private fun tagged(name: String, bytes: ByteArray, tags: ForkDownloadTags.Tags = ForkDownloadTags.Tags(cover, plain, enhanced)): ByteArray {
        val file = scratch.resolve(name)
        Files.write(file, bytes)
        ForkDownloadTags.embed(file, song, tags)
        return Files.readAllBytes(file)
    }

    private fun ByteArray.has(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private fun ByteArray.endsWith(tail: ByteArray) = size >= tail.size && copyOfRange(size - tail.size, size).contentEquals(tail)

    private fun assertTagged(bytes: ByteArray) {
        assertTrue(bytes.has(song.title.toByteArray(Charsets.UTF_8)), "title")
        assertTrue(bytes.has(song.artist.toByteArray(Charsets.UTF_8)), "artist")
        assertTrue(bytes.has(song.albumName!!.toByteArray(Charsets.UTF_8)), "album")
        assertTrue(bytes.has(plain.toByteArray(Charsets.UTF_8)), "lyrics")
    }

    // ── FLAC ─────────────────────────────────────────────────────────────

    private fun flac(): ByteArray = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(34) { 1 } + audio

    @Test
    fun `a FLAC gets the phone's comment and picture and keeps its frames`() {
        val bytes = tagged("a.flac", flac())
        assertTagged(bytes)
        assertTrue(bytes.has(cover), "cover")
        assertTrue(bytes.endsWith(audio), "frames copied through")
        assertEquals(enhanced, DesktopEmbeddedLyrics.fromBytes(bytes))
    }

    // ── M4A ──────────────────────────────────────────────────────────────

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    @Test
    fun `an M4A gets the phone's ilst and keeps its media`() {
        val original = box("ftyp", "M4A ".toByteArray() + ByteArray(4)) + box("moov", box("mvhd", ByteArray(100))) + box("mdat", audio)
        val bytes = tagged("a.m4a", original)
        assertTagged(bytes)
        assertTrue(bytes.has(cover), "cover")
        assertTrue(bytes.endsWith(audio), "media copied through")
        assertEquals(enhanced, DesktopEmbeddedLyrics.fromBytes(bytes))
    }

    // ── WebM ─────────────────────────────────────────────────────────────

    @Test
    fun `a WebM gets the phone's tags after an unknown-size segment`() {
        val header = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x84.toByte()) + ByteArray(4)
        val segment = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01) + ByteArray(7) { 0xFF.toByte() }
        val original = header + segment + audio
        val bytes = tagged("a.webm", original)
        assertTagged(bytes)
        assertTrue(bytes.has(cover), "cover")
        assertTrue(bytes.copyOfRange(0, original.size).contentEquals(original), "stream untouched")
        assertEquals(enhanced, DesktopEmbeddedLyrics.fromBytes(bytes))
    }

    // ── Ogg Opus ─────────────────────────────────────────────────────────

    private fun oggPage(sequence: Int, headerType: Int, packet: ByteArray, granule: Long = 0): ByteArray {
        val laces = ArrayList<Int>()
        var left = packet.size
        while (left >= 255) { laces += 255; left -= 255 }
        laces += left
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(0)
        out.write(headerType)
        for (i in 0 until 8) out.write(((granule ushr (8 * i)) and 0xFF).toInt())
        for (i in 0 until 4) out.write((0x1234 ushr (8 * i)) and 0xFF) // serial
        for (i in 0 until 4) out.write((sequence ushr (8 * i)) and 0xFF)
        repeat(4) { out.write(0) }
        out.write(laces.size)
        laces.forEach(out::write)
        out.write(packet)
        val page = out.toByteArray()
        val crc = ForkOggTagger.crc(page)
        for (i in 0 until 4) page[22 + i] = (crc ushr (8 * i)).toByte()
        return page
    }

    private fun le32(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }

    private fun opus(): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val vendor = "test".toByteArray()
        val tags = "OpusTags".toByteArray() + le32(vendor.size) + vendor + le32(1) + le32(9) + "TITLE=old".toByteArray()
        return oggPage(0, 2, head) + oggPage(1, 0, tags) + oggPage(2, 4, audio.copyOf(200), granule = 960)
    }

    @Test
    fun `an Ogg Opus file gets a new comment header and its pages renumbered`() {
        val bytes = tagged("a.ogg", opus())
        assertTagged(bytes)
        assertTrue(!bytes.has("TITLE=old".toByteArray()), "old comment replaced")
        assertEquals(enhanced, DesktopEmbeddedLyrics.fromBytes(bytes))
        // Every page still carries a valid checksum and they count up from 0.
        var at = 0
        var sequence = 0
        while (at < bytes.size) {
            val segments = bytes[at + 26].toInt() and 0xFF
            val length = 27 + segments + (0 until segments).sumOf { bytes[at + 27 + it].toInt() and 0xFF }
            val page = bytes.copyOfRange(at, at + length)
            val stored = (0 until 4).fold(0) { acc, i -> acc or ((page[22 + i].toInt() and 0xFF) shl (8 * i)) }
            for (i in 22 until 26) page[i] = 0
            assertEquals(ForkOggTagger.crc(page), stored, "crc of page $sequence")
            val seq = (0 until 4).fold(0) { acc, i -> acc or ((bytes[at + 18 + i].toInt() and 0xFF) shl (8 * i)) }
            assertEquals(sequence, seq)
            sequence++
            at += length
        }
        assertTrue(bytes.endsWith(audio.copyOf(200)), "audio packet unchanged")
    }

    // ── MP3 ──────────────────────────────────────────────────────────────

    @Test
    fun `an MP3 gets an ID3v2 tag in front, an old one replaced`() {
        val frames = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + audio
        val old = ForkId3Tagger.tag("Old title", "Old", null, null, null, "image/jpeg")!!
        val bytes = tagged("a.mp3", old + frames)
        assertTagged(bytes)
        assertTrue(bytes.has(cover), "cover")
        assertTrue(!bytes.has("Old title".toByteArray()), "old tag dropped")
        assertTrue(bytes.endsWith(frames), "frames copied through")
        assertEquals(enhanced, DesktopEmbeddedLyrics.fromBytes(bytes))
        // Without the word-synced form, USLT is what is read.
        val plainOnly = tagged("b.mp3", frames, ForkDownloadTags.Tags(null, plain, null))
        assertEquals(plain, DesktopEmbeddedLyrics.fromBytes(plainOnly))
    }

    @Test
    fun `a WAV keeps no tags, as on the phone`() {
        val wav = "RIFF".toByteArray() + le32(4 + audio.size) + "WAVE".toByteArray() + audio
        assertTrue(tagged("a.wav", wav).contentEquals(wav))
    }

    @Test
    fun `a cancel while tagging is not swallowed`() = kotlinx.coroutines.runBlocking {
        // Swallowed, the cancelled download went on to its real name and was recorded as done.
        val file = scratch.resolve("cancelled.wav")
        Files.write(file, "RIFF".toByteArray() + le32(4 + audio.size) + "WAVE".toByteArray() + audio)
        var carriedOn = false
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            ForkDownloadTags.write(kotlinx.coroutines.CompletableDeferred(ForkDownloadTags.Tags(null, null, null)), file, song)
            carriedOn = true
        }
        job.join()
        assertTrue(!carriedOn, "the cancel reached the download")
    }
}
