package com.music.bitchord.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ---- Desktop Revived 1.9.1 follow-ups (#107) ----

    private fun picture(type: Int, data: ByteArray, mime: String = "image/jpeg") = bytes {
        writeInt(type); writeInt(mime.length); write(mime.toByteArray()); writeInt(0)
        writeInt(1); writeInt(1); writeInt(24); writeInt(0); writeInt(data.size); write(data)
    }

    private fun flacOf(vararg blocks: ByteArray) = bytes {
        write("fLaC".toByteArray())
        writeByte(0); writeByte(0); writeByte(0); writeByte(34); write(ByteArray(34))
        blocks.forEachIndexed { i, block ->
            writeByte((if (i == blocks.lastIndex) 0x80 else 0) or 6)
            writeByte(block.size shr 16); writeByte(block.size shr 8 and 0xFF); writeByte(block.size and 0xFF)
            write(block)
        }
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)
    private val gif = "GIF89a....".toByteArray()

    @Test
    fun `a FLAC with several pictures gives the front cover, not the first`() {
        val flac = flacOf(picture(4, png, "image/png"), picture(3, image))
        assertContentEquals(image, ForkLocalArtwork.embedded(file("a.flac", flac)))
    }

    @Test
    fun `a FLAC's GIF icon is passed over for the next picture`() {
        val flac = flacOf(picture(1, gif, "image/gif"), picture(0, png, "image/png"))
        assertContentEquals(png, ForkLocalArtwork.embedded(file("a.flac", flac)))
    }

    @Test
    fun `a FLAC picture whose image size runs past its block is skipped, not read into the next`() {
        val lying = picture(3, image).also { block ->
            // The image length field (the last 4 bytes before the image) says 1000.
            val at = block.size - image.size - 4
            block[at] = 0; block[at + 1] = 0; block[at + 2] = 0x03; block[at + 3] = 0xE8.toByte()
        }
        val flac = flacOf(lying, picture(0, png, "image/png"))
        assertContentEquals(png, ForkLocalArtwork.embedded(file("a.flac", flac)))
    }

    @Test
    fun `folder cover names in any case, images only`() {
        assertTrue(ForkLocalArtwork.isFolderCoverName("cover.jpg"))
        assertTrue(ForkLocalArtwork.isFolderCoverName("Folder.PNG"))
        assertTrue(ForkLocalArtwork.isFolderCoverName("front.jpeg"))
        assertFalse(ForkLocalArtwork.isFolderCoverName("cover.txt"))
        assertFalse(ForkLocalArtwork.isFolderCoverName("song.jpg"))
        assertFalse(ForkLocalArtwork.isFolderCoverName("cover"))
    }

    @Test
    fun `a cover added or replaced after the first scan shows`() {
        val dir = Files.createTempDirectory("bitchord-art")
        val cache = Files.createTempDirectory("bitchord-art-cache")
        val song = dir.resolve("Song.mp3").also { Files.write(it, ByteArray(10)) }
        assertNull(ForkLocalArtwork.artworkUri(song, cache), "no cover yet: an empty marker is kept")

        Files.write(dir.resolve("Cover.JPG"), image)
        ForkLocalArtwork.forgetFolderCovers() // what the watcher does on seeing it
        val first = ForkLocalArtwork.artworkUri(song, cache)!!
        assertContentEquals(image, Files.readAllBytes(Path.of(java.net.URI(first))))

        val replaced = image + byteArrayOf(9, 9)
        Files.write(dir.resolve("Cover.JPG"), replaced)
        Files.setLastModifiedTime(dir.resolve("Cover.JPG"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        ForkLocalArtwork.forgetFolderCovers()
        val second = ForkLocalArtwork.artworkUri(song, cache)!!
        assertNotEquals(first, second, "a new address, so the image loader doesn't show its old copy")
        assertContentEquals(replaced, Files.readAllBytes(Path.of(java.net.URI(second))))
    }

    @Test
    fun `an embedded picture that isn't an image falls back to the folder cover`() {
        val dir = Files.createTempDirectory("bitchord-art")
        val cache = Files.createTempDirectory("bitchord-art-cache")
        val apic = bytes { writeByte(0); write("image/jpeg".toByteArray()); writeByte(0); writeByte(3); writeByte(0); write(gif) }
        val frames = bytes { write("APIC".toByteArray()); writeInt(apic.size); writeShort(0); write(apic) }
        val mp3 = bytes { write("ID3".toByteArray()); writeByte(3); writeByte(0); writeByte(0); write(synchsafe(frames.size)); write(frames) }
        val song = dir.resolve("Song.mp3").also { Files.write(it, mp3) }
        Files.write(dir.resolve("folder.png"), png)
        ForkLocalArtwork.forgetFolderCovers()
        val uri = ForkLocalArtwork.artworkUri(song, cache)!!
        assertContentEquals(png, Files.readAllBytes(Path.of(java.net.URI(uri))))
    }
}
