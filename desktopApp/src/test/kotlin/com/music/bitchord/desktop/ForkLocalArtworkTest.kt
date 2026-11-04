package com.music.bitchord.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class ForkLocalArtworkTest {

    private val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 0, 5)

    private fun file(name: String, bytes: ByteArray): Path =
        Files.createTempDirectory("bitchord-art").resolve(name).also { Files.write(it, bytes) }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()

    @Test
    fun `a FLAC picture block after other blocks`() {
        val picture = bytes {
            writeInt(3); writeInt(10); write("image/jpeg".toByteArray()); writeInt(4); write("Desc".toByteArray())
            writeInt(500); writeInt(500); writeInt(24); writeInt(0); writeInt(image.size); write(image)
        }
        val flac = bytes {
            write("fLaC".toByteArray())
            // STREAMINFO (34 bytes), not last
            writeByte(0); writeByte(0); writeByte(0); writeByte(34); write(ByteArray(34))
            // PICTURE, last
            writeByte(0x80 or 6); writeByte(picture.size shr 16); writeByte(picture.size shr 8 and 0xFF); writeByte(picture.size and 0xFF)
            write(picture)
        }
        assertContentEquals(image, ForkLocalArtwork.embedded(file("a.flac", flac)))
    }

    @Test
    fun `a FLAC without a picture has none`() {
        val flac = bytes { write("fLaC".toByteArray()); writeByte(0x80); writeByte(0); writeByte(0); writeByte(34); write(ByteArray(34)) }
        assertNull(ForkLocalArtwork.embedded(file("a.flac", flac)))
    }

    private fun synchsafe(value: Int) = byteArrayOf(
        (value shr 21 and 0x7F).toByte(), (value shr 14 and 0x7F).toByte(), (value shr 7 and 0x7F).toByte(), (value and 0x7F).toByte(),
    )

    @Test
    fun `an ID3v2_3 APIC after a text frame`() {
        val title = bytes { writeByte(0); write("Song".toByteArray()) }
        val apic = bytes { writeByte(0); write("image/jpeg".toByteArray()); writeByte(0); writeByte(3); write("cover".toByteArray()); writeByte(0); write(image) }
        val frames = bytes {
            write("TIT2".toByteArray()); writeInt(title.size); writeShort(0); write(title)
            write("APIC".toByteArray()); writeInt(apic.size); writeShort(0); write(apic)
            write(ByteArray(16)) // padding
        }
        val mp3 = bytes { write("ID3".toByteArray()); writeByte(3); writeByte(0); writeByte(0); write(synchsafe(frames.size)); write(frames); write(ByteArray(100)) }
        assertContentEquals(image, ForkLocalArtwork.embedded(file("a.mp3", mp3)))
    }

    @Test
    fun `an ID3v2_4 APIC with a UTF-16 description`() {
        val description = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'c'.code.toByte(), 0, 0, 0)
        val apic = bytes { writeByte(1); write("image/png".toByteArray()); writeByte(0); writeByte(3); write(description); write(image) }
        val frames = bytes { write("APIC".toByteArray()); write(synchsafe(apic.size)); writeShort(0); write(apic) }
        val mp3 = bytes { write("ID3".toByteArray()); writeByte(4); writeByte(0); writeByte(0); write(synchsafe(frames.size)); write(frames) }
        assertContentEquals(image, ForkLocalArtwork.embedded(file("a.mp3", mp3)))
    }

    @Test
    fun `an ID3v2_2 PIC`() {
        val pic = bytes { writeByte(0); write("JPG".toByteArray()); writeByte(3); writeByte(0); write(image) }
        val frames = bytes { write("PIC".toByteArray()); writeByte(0); writeByte(pic.size shr 8); writeByte(pic.size and 0xFF); write(pic) }
        val mp3 = bytes { write("ID3".toByteArray()); writeByte(2); writeByte(0); writeByte(0); write(synchsafe(frames.size)); write(frames) }
        assertContentEquals(image, ForkLocalArtwork.embedded(file("a.mp3", mp3)))
    }

    private fun box(type: String, body: ByteArray) = bytes { writeInt(8 + body.size); write(type.toByteArray()); write(body) }

    @Test
    fun `an M4A cover with the moov after the audio`() {
        val data = box("data", bytes { writeInt(13); writeInt(0); write(image) })
        val ilst = box("ilst", box("xnam", ByteArray(4)) + box("covr", data))
        val meta = box("meta", ByteArray(4) + box("hdlr", ByteArray(20)) + ilst)
        val moov = box("moov", box("mvhd", ByteArray(100)) + box("udta", meta))
        val m4a = box("ftyp", "M4A ".toByteArray() + ByteArray(4)) + box("mdat", ByteArray(500)) + moov
        assertContentEquals(image, ForkLocalArtwork.embedded(file("a.m4a", m4a)))
    }

    @Test
    fun `a folder cover and the cached copy`() {
        val dir = Files.createTempDirectory("bitchord-art")
        val song = dir.resolve("Artist - Song.ogg").also { Files.write(it, ByteArray(10)) }
        assertNull(ForkLocalArtwork.embedded(song))
        Files.write(dir.resolve("cover.jpg"), image)
        val uri = ForkLocalArtwork.artworkUri(song)!!
        assertContentEquals(image, Files.readAllBytes(Path.of(java.net.URI(uri))))
    }
}
