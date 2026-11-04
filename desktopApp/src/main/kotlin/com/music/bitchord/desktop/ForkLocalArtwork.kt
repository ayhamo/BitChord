package com.music.bitchord.desktop

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Fork: covers for Local Music (ours gave every local song `thumbnailUrl = null`). From Desktop
 * Revived 9a8d8445's `DesktopLocalArtwork` (FLAC PICTURE, ID3v2 APIC, a folder image), with
 * ID3v2.2 `PIC`, an ID3 extended header and M4A's `covr` added, and the cached copy keyed by the
 * file's size and date too, so a re-tagged file gets its new cover.
 *
 * The scan runs on every start and every folder change, so a file is read once: the image goes to
 * `.cache/bitchord/local-artwork` under `user.home` (in the portable app, inside `data\`) and its
 * `file:` address becomes the song's cover, which [DesktopArtworkCache] and the shared player's
 * Coil loader read like any other. A file with nothing gets an empty marker, so it isn't opened
 * again either. Everything is best-effort: a file whose cover can't be read is listed without one.
 */
internal object ForkLocalArtwork {

    private val directory: Path by lazy {
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("user.home")}/.cache"
        Path.of(base, "bitchord", "local-artwork").also { runCatching { Files.createDirectories(it) } }
    }

    /** Names, in order, that music folders use for a cover beside the tracks. */
    private val FOLDER_COVER_NAMES = listOf("cover", "folder", "front", "album", "artwork")
    private val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png")

    /** A `file:` address of [audioPath]'s cover, or null when it has none and its folder neither. */
    fun artworkUri(audioPath: Path): String? = runCatching {
        val absolute = audioPath.toAbsolutePath().normalize()
        val stamp = runCatching { "${Files.size(absolute)}|${Files.getLastModifiedTime(absolute).toMillis()}" }.getOrDefault("")
        val cached = directory.resolve("${digest("$absolute|$stamp")}.jpg")
        if (Files.isRegularFile(cached)) {
            return@runCatching if (Files.size(cached) > 0) cached.toUri().toString() else null
        }
        val bytes = embedded(absolute) ?: folderCover(absolute)
        Files.write(cached, bytes ?: ByteArray(0))
        if (bytes != null) cached.toUri().toString() else null
    }.getOrNull()

    /** Every cached cover, thrown away: a new Local Music folder starts again (Revived's rule). */
    fun clearCache() {
        runCatching { Files.list(directory).use { files -> files.forEach { runCatching { Files.deleteIfExists(it) } } } }
    }

    internal fun embedded(path: Path): ByteArray? = when (path.extension.lowercase(Locale.ROOT)) {
        "flac" -> flac(path)
        "mp3", "aac", "wav" -> id3(path) // an ADTS or WAV file can carry an ID3 tag at the front too
        "m4a", "mp4" -> m4a(path)
        else -> null
    }

    // ---- FLAC -------------------------------------------------------------

    /** The first `PICTURE` block of the metadata at the front; the audio is never read. */
    private fun flac(path: Path): ByteArray? = runCatching {
        RandomAccessFile(path.toFile(), "r").use(::flacPicture)
    }.getOrNull()

    private fun flacPicture(raf: RandomAccessFile): ByteArray? {
        val magic = ByteArray(4)
        if (raf.read(magic) != 4 || magic.decodeToString() != "fLaC") return null
        while (true) {
            val flags = raf.read()
            if (flags == -1) return null
            val length = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
            if (length < 0) return null
            if (flags and 0x7F == FLAC_PICTURE) return flacPictureBlock(raf)
            if (raf.skipBytes(length) < length) return null
            if (flags and 0x80 != 0) return null // the last metadata block
        }
    }

    /** Type, MIME, description, 16 bytes of size and colour, then the image. */
    private fun flacPictureBlock(raf: RandomAccessFile): ByteArray? {
        raf.readInt() // picture type: the first picture whatever it is
        val mime = raf.readInt()
        if (mime !in 0..256) return null
        raf.skipBytes(mime)
        val description = raf.readInt()
        if (description !in 0..65_536) return null
        raf.skipBytes(description)
        raf.skipBytes(16)
        val size = raf.readInt()
        if (size !in 1..MAX_IMAGE_BYTES) return null
        return ByteArray(size).also(raf::readFully)
    }

    // ---- ID3v2 ------------------------------------------------------------

    /** The first `APIC` (v2.3, v2.4) or `PIC` (v2.2) frame of an ID3v2 tag at the front. */
    private fun id3(path: Path): ByteArray? = runCatching {
        RandomAccessFile(path.toFile(), "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) != 10 || header.decodeToString(0, 3) != "ID3") return@runCatching null
            val major = header[3].toInt() and 0xFF
            if (major !in 2..4) return@runCatching null
            val size = synchsafe(header, 6)
            if (size !in 1..MAX_TAG_BYTES) return@runCatching null
            val tag = ByteArray(size)
            raf.readFully(tag)
            id3Picture(tag, major, extendedHeader = header[5].toInt() and 0x40 != 0)
        }
    }.getOrNull()

    internal fun id3Picture(tag: ByteArray, major: Int, extendedHeader: Boolean = false): ByteArray? {
        var offset = 0
        if (extendedHeader && major >= 3 && tag.size >= 4) {
            offset = if (major == 4) synchsafe(tag, 0) else 4 + bigEndian(tag, 0, 4)
        }
        val idLength = if (major == 2) 3 else 4
        val headerLength = if (major == 2) 6 else 10
        while (offset + headerLength <= tag.size) {
            if (tag[offset].toInt() == 0) break // padding
            val id = tag.decodeToString(offset, offset + idLength)
            val frameSize = when (major) {
                2 -> bigEndian(tag, offset + 3, 3)
                4 -> synchsafe(tag, offset + 4)
                else -> bigEndian(tag, offset + 4, 4)
            }
            val start = offset + headerLength
            if (frameSize <= 0 || start + frameSize > tag.size) break
            if (id == "APIC" || id == "PIC") return picturePayload(tag, start, start + frameSize, major == 2)
            offset = start + frameSize
        }
        return null
    }

    /**
     * Encoding byte, the MIME type (v2.2: three letters), the picture type, the description in
     * the frame's encoding, then the image.
     */
    private fun picturePayload(tag: ByteArray, start: Int, end: Int, v22: Boolean): ByteArray? {
        val encoding = tag[start].toInt() and 0xFF
        var pos = start + 1
        if (v22) {
            pos += 3
        } else {
            while (pos < end && tag[pos].toInt() != 0) pos++
            pos++
        }
        pos++ // picture type
        if (encoding == 1 || encoding == 2) {
            // UTF-16: two zero bytes on an even step end the description.
            while (pos + 1 < end && !(tag[pos].toInt() == 0 && tag[pos + 1].toInt() == 0)) pos += 2
            pos += 2
        } else {
            while (pos < end && tag[pos].toInt() != 0) pos++
            pos++
        }
        if (pos >= end) return null
        return tag.copyOfRange(pos, end)
    }

    private fun synchsafe(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0x7F) shl 21) or ((bytes[at + 1].toInt() and 0x7F) shl 14) or
            ((bytes[at + 2].toInt() and 0x7F) shl 7) or (bytes[at + 3].toInt() and 0x7F)

    private fun bigEndian(bytes: ByteArray, at: Int, count: Int): Int {
        var value = 0
        for (i in 0 until count) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }

    // ---- MP4 / M4A ----------------------------------------------------------

    /** `moov/udta/meta/ilst/covr/data`, walked by seeking, so a `moov` after the audio costs nothing. */
    private fun m4a(path: Path): ByteArray? = runCatching {
        RandomAccessFile(path.toFile(), "r").use { raf ->
            var range = 0L until raf.length()
            for (name in listOf("moov", "udta", "meta", "ilst", "covr", "data")) {
                val box = findBox(raf, range, name) ?: return@runCatching null
                // `meta` is a full box (4 bytes of version and flags); `data` has 8 of type and locale.
                val skip = when (name) {
                    "meta" -> 4
                    "data" -> 8
                    else -> 0
                }
                range = (box.first + skip) until box.last + 1
            }
            val size = (range.last + 1 - range.first)
            if (size !in 1..MAX_IMAGE_BYTES.toLong()) return@runCatching null
            raf.seek(range.first)
            ByteArray(size.toInt()).also(raf::readFully)
        }
    }.getOrNull()

    /** The contents of the first box called [name] directly inside [range]. */
    private fun findBox(raf: RandomAccessFile, range: LongRange, name: String): LongRange? {
        var at = range.first
        val end = range.last + 1
        while (at + 8 <= end) {
            raf.seek(at)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also(raf::readFully).decodeToString()
            var header = 8L
            if (size == 1L) {
                size = raf.readLong()
                header = 16L
            } else if (size == 0L) {
                size = end - at
            }
            if (size < header || at + size > end) return null
            if (type == name) return (at + header) until (at + size)
            at += size
        }
        return null
    }

    // ---- Folder image -------------------------------------------------------

    /** `cover.jpg` and the like beside the file, the convention music managers have long used. */
    private fun folderCover(audioPath: Path): ByteArray? {
        val dir = audioPath.parent ?: return null
        for (name in FOLDER_COVER_NAMES) {
            for (ext in IMAGE_EXTENSIONS) {
                val candidate = dir.resolve("$name.$ext")
                if (candidate.isRegularFile()) {
                    return runCatching {
                        Files.readAllBytes(candidate).takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
                    }.getOrNull()
                }
            }
        }
        return null
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)

    private const val FLAC_PICTURE = 6
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    private const val MAX_TAG_BYTES = 32 * 1024 * 1024
}
