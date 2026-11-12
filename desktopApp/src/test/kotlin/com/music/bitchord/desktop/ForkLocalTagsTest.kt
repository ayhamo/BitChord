package com.music.bitchord.desktop

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [ForkLocalTags]: Local Music's titles, artists, albums and lengths from synthetic headers. */
class ForkLocalTagsTest {

    private fun file(name: String, bytes: ByteArray): Path =
        Files.createTempDirectory("fork-tags").resolve(name).also { Files.write(it, bytes) }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun vorbis(vararg entries: String) = ByteArrayOutputStream().apply {
        val vendor = "test".toByteArray()
        write(le32(vendor.size)); write(vendor); write(le32(entries.size))
        entries.forEach { val e = it.toByteArray(); write(le32(e.size)); write(e) }
    }.toByteArray()

    @Test
    fun flacReadsVorbisCommentsAndStreamInfo() {
        val info = ByteArray(18)
        // 44100 Hz (20 bits at byte 10), 441000 samples (36 bits from byte 13): 10 s.
        info[10] = (44_100 shr 12).toByte(); info[11] = (44_100 shr 4).toByte(); info[12] = ((44_100 and 0xF) shl 4).toByte()
        val samples = 441_000L
        info[13] = ((samples shr 32) and 0x0F).toByte(); info[14] = (samples shr 24).toByte()
        info[15] = (samples shr 16).toByte(); info[16] = (samples shr 8).toByte(); info[17] = samples.toByte()
        val comments = vorbis("TITLE=أنا موانا", "ARTIST=Nesma Mahgoub", "album=Singles")
        val bytes = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            write(be32((0 shl 24) or 18)); write(info)
            write(be32((1 shl 31) or (4 shl 24) or comments.size)); write(comments)
        }.toByteArray()
        assertEquals(
            ForkLocalTags.Tags("أنا موانا", "Nesma Mahgoub", "Singles", 10_000),
            ForkLocalTags.parse(file("a.flac", bytes)),
        )
    }

    @Test
    fun mp3ReadsId3v24TextAndTlen() {
        fun frame(id: String, text: String): ByteArray {
            val body = byteArrayOf(3) + text.toByteArray()
            val size = body.size
            val synch = byteArrayOf((size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
            return id.toByteArray() + synch + byteArrayOf(0, 0) + body
        }
        val frames = frame("TIT2", "Starboy") + frame("TPE1", "The Weeknd\u0000Daft Punk") + frame("TALB", "Starboy") + frame("TLEN", "230000")
        val size = frames.size
        val header = "ID3".toByteArray() + byteArrayOf(4, 0, 0) +
            byteArrayOf((size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
        val tags = ForkLocalTags.parse(file("b.mp3", header + frames))
        assertEquals(ForkLocalTags.Tags("Starboy", "The Weeknd, Daft Punk", "Starboy", 230_000), tags)
    }

    @Test
    fun mp3WithoutTagGetsItsLengthFromTheBitrate() {
        // One MPEG1 layer III frame header at 128 kbps, 44.1 kHz, stereo, then 160 000 bytes: 10 s.
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        val tags = ForkLocalTags.parse(file("c.mp3", header + ByteArray(160_000 - 4)))
        assertEquals(10_000, tags?.durationMs)
        assertNull(tags?.title)
    }

    @Test
    fun m4aReadsIlstAndMvhd() {
        fun box(type: String, body: ByteArray) = be32(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
        fun item(type: String, text: String) = box(type, box("data", be32(1) + be32(0) + text.toByteArray()))
        val mvhd = box("mvhd", byteArrayOf(0, 0, 0, 0) + ByteArray(8) + be32(1000) + be32(185_000) + ByteArray(80))
        val ilst = box("ilst", item("©nam", "Die For You") + item("©ART", "The Weeknd") + item("©alb", "Starboy"))
        val meta = box("meta", byteArrayOf(0, 0, 0, 0) + ilst)
        val moov = box("moov", mvhd + box("udta", meta))
        val bytes = box("ftyp", "M4A ".toByteArray() + ByteArray(4)) + moov
        assertEquals(ForkLocalTags.Tags("Die For You", "The Weeknd", "Starboy", 185_000), ForkLocalTags.parse(file("d.m4a", bytes)))
    }

    @Test
    fun wavReadsInfoAndDataLength() {
        val fmt = ByteArray(16).also { System.arraycopy(le32(176_400), 0, it, 8, 4) }
        val info = "INFO".toByteArray() + "INAM".toByteArray() + le32(4) + "Song".toByteArray()
        val body = "WAVE".toByteArray() + "fmt ".toByteArray() + le32(16) + fmt +
            "LIST".toByteArray() + le32(info.size) + info + "data".toByteArray() + le32(352_800) + ByteArray(352_800)
        val bytes = "RIFF".toByteArray() + le32(body.size) + body
        assertEquals(ForkLocalTags.Tags(title = "Song", durationMs = 2_000), ForkLocalTags.parse(file("e.wav", bytes)))
    }

    @Test
    fun brokenOrUnknownFilesGiveNoTags() {
        assertNull(ForkLocalTags.read(file("f.flac", "not a flac".toByteArray())))
        assertNull(ForkLocalTags.read(file("g.webm", ByteArray(100))))
        assertEquals("3:51", ForkLocalTags.durationText(231_000))
        assertEquals("1:02:07", ForkLocalTags.durationText(3_727_000))
        assertNull(ForkLocalTags.durationText(0))
    }
}
