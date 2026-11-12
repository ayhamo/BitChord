package com.music.bitchord.desktop

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

/**
 * Fork: titles, artists, albums and lengths for Local Music from the files' own tags (owner,
 * 2026-11-11: "basic is fine"). Ours named every song after its file ("Artist - Title", else the
 * whole name by "On This Computer") and showed no length, where the phone reads MediaStore's tags.
 *
 * Only the headers are read, never the audio, in the style of [ForkLocalArtwork]: FLAC
 * `VORBIS_COMMENT` + `STREAMINFO`, Ogg/Opus comment packets + the last page's granule, ID3v2 text
 * frames (+ `TLEN`, else the MP3's Xing/Info frame count or its constant bitrate), MP4 `ilst` +
 * `mvhd`, WAV `LIST/INFO` + `data`, AIFF `NAME` + `COMM`. Pure Kotlin rather than FFmpeg, so a
 * path with Arabic or CJK letters needs no native string conversion. Answers are kept per path,
 * size and date, so a rescan (every start, every folder change) opens a file again only when it
 * changed. Best-effort: anything unreadable falls back to the file name, as before.
 */
internal object ForkLocalTags {

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long? = null,
    )

    /** Formats FFmpeg plays that the scan didn't list. */
    val MORE_EXTENSIONS = setOf("opus", "aiff", "aif")

    private val known = ConcurrentHashMap<String, Tags>()

    /** [path]'s tags, or null when it has none this reader understands. Never throws. */
    fun read(path: Path): Tags? {
        val stamp = runCatching { "$path|${Files.size(path)}|${Files.getLastModifiedTime(path).toMillis()}" }.getOrNull()
            ?: return null
        known[stamp]?.let { return it.takeIf { it.hasAny() } }
        val tags = runCatching { parse(path) }.getOrNull() ?: Tags()
        if (known.size > MAX_KEPT) known.clear()
        known[stamp] = tags
        return tags.takeIf { it.hasAny() }
    }

    private fun Tags.hasAny() = title != null || artist != null || album != null || durationMs != null

    /** "3:51" / "1:02:07", as the other sources write [com.music.bitchord.data.model.Song.durationText]. */
    fun durationText(ms: Long?): String? {
        if (ms == null || ms <= 0) return null
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    internal fun parse(path: Path): Tags? = RandomAccessFile(path.toFile(), "r").use { raf ->
        when (path.extension.lowercase(Locale.ROOT)) {
            "flac" -> flac(raf)
            "ogg", "opus" -> ogg(raf)
            "m4a", "mp4" -> mp4(raf)
            "mp3" -> id3(raf, mp3 = true)
            "aac" -> id3(raf, mp3 = false) // ADTS: an ID3 tag at the front, no header that states a length
            "wav" -> wav(raf)
            "aiff", "aif" -> aiff(raf)
            else -> null
        }
    }

    // ---- Vorbis comments (FLAC, Ogg Vorbis, Opus) ----------------------------

    /** `KEY=value` list after the vendor string, little-endian lengths. */
    internal fun vorbisComments(bytes: ByteArray, start: Int): Tags {
        var at = start
        fun le32(): Int {
            if (at + 4 > bytes.size) error("short")
            val v = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return v
        }
        val vendor = le32()
        if (vendor < 0 || at + vendor > bytes.size) return Tags()
        at += vendor
        val count = le32()
        val values = HashMap<String, MutableList<String>>()
        repeat(count.coerceIn(0, 10_000)) {
            if (at + 4 > bytes.size) return@repeat
            val length = le32()
            if (length < 0 || at + length > bytes.size) return@repeat
            val entry = String(bytes, at, length, Charsets.UTF_8)
            at += length
            val eq = entry.indexOf('=')
            if (eq > 0) values.getOrPut(entry.substring(0, eq).uppercase(Locale.ROOT)) { mutableListOf() } += entry.substring(eq + 1)
        }
        fun first(vararg keys: String) = keys.firstNotNullOfOrNull { k -> values[k]?.mapNotNull { it.trim().ifBlank { null } }?.takeIf { it.isNotEmpty() }?.joinToString(", ") }
        return Tags(title = first("TITLE"), artist = first("ARTIST", "ALBUMARTIST"), album = first("ALBUM"))
    }

    private fun flac(raf: RandomAccessFile): Tags? {
        val magic = ByteArray(4)
        if (raf.read(magic) != 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        var tags = Tags()
        var durationMs: Long? = null
        while (raf.filePointer + 4 <= raf.length()) {
            val header = raf.readInt()
            val last = header ushr 31 != 0
            val type = (header ushr 24) and 0x7F
            val length = header and 0xFFFFFF
            val next = raf.filePointer + length
            when (type) {
                0 -> if (length >= 18) {
                    val info = ByteArray(18).also(raf::readFully)
                    val rate = ((info[10].toInt() and 0xFF) shl 12) or ((info[11].toInt() and 0xFF) shl 4) or ((info[12].toInt() and 0xF0) ushr 4)
                    val samples = ((info[13].toLong() and 0x0F) shl 32) or ((info[14].toLong() and 0xFF) shl 24) or
                        ((info[15].toLong() and 0xFF) shl 16) or ((info[16].toLong() and 0xFF) shl 8) or (info[17].toLong() and 0xFF)
                    if (rate > 0 && samples > 0) durationMs = samples * 1000 / rate
                }
                4 -> if (length in 1..MAX_TAG_BYTES) tags = vorbisComments(ByteArray(length).also(raf::readFully), 0)
            }
            if (last || next > raf.length()) break
            raf.seek(next)
        }
        return tags.copy(durationMs = durationMs)
    }

    /**
     * The page payloads of the first [HEAD_BYTES] joined in order (a comment packet may span pages),
     * the comment packet found by its signature; the length from the last page's granule position.
     */
    private fun ogg(raf: RandomAccessFile): Tags? {
        val head = ByteArray(minOf(raf.length(), HEAD_BYTES.toLong()).toInt()).also(raf::readFully)
        val payload = java.io.ByteArrayOutputStream()
        var at = 0
        while (at + 27 <= head.size && String(head, at, 4, Charsets.ISO_8859_1) == "OggS") {
            val segments = head[at + 26].toInt() and 0xFF
            if (at + 27 + segments > head.size) break
            var size = 0
            for (i in 0 until segments) size += head[at + 27 + i].toInt() and 0xFF
            val start = at + 27 + segments
            payload.write(head, start, minOf(size, head.size - start))
            at = start + size
        }
        val data = payload.toByteArray()
        val opus = indexOf(data, "OpusTags".toByteArray(Charsets.ISO_8859_1))
        val vorbis = indexOf(data, byteArrayOf(3) + "vorbis".toByteArray(Charsets.ISO_8859_1))
        val tags = when {
            opus >= 0 -> vorbisComments(data, opus + 8)
            vorbis >= 0 -> vorbisComments(data, vorbis + 7)
            else -> Tags()
        }
        val rate = if (opus >= 0) 48_000 else {
            val id = indexOf(data, byteArrayOf(1) + "vorbis".toByteArray(Charsets.ISO_8859_1))
            if (id >= 0 && id + 16 <= data.size) le32(data, id + 12) else 0
        }
        val preSkip = if (opus >= 0) {
            val id = indexOf(data, "OpusHead".toByteArray(Charsets.ISO_8859_1))
            if (id >= 0 && id + 12 <= data.size) (data[id + 10].toInt() and 0xFF) or ((data[id + 11].toInt() and 0xFF) shl 8) else 0
        } else 0
        val tailLength = minOf(raf.length(), TAIL_BYTES.toLong()).toInt()
        raf.seek(raf.length() - tailLength)
        val tail = ByteArray(tailLength).also(raf::readFully)
        var last = -1
        var i = tail.size - 27
        while (i >= 0) {
            if (tail[i] == 'O'.code.toByte() && String(tail, i, 4, Charsets.ISO_8859_1) == "OggS") { last = i; break }
            i--
        }
        val granule = if (last >= 0) le64(tail, last + 6) else -1L
        val durationMs = if (rate > 0 && granule > preSkip) (granule - preSkip) * 1000 / rate else null
        return tags.copy(durationMs = durationMs)
    }

    // ---- ID3v2 (MP3, ADTS) and the MP3 length ---------------------------------

    private fun id3(raf: RandomAccessFile, mp3: Boolean): Tags? {
        val header = ByteArray(10)
        var tags = Tags()
        var tagEnd = 0L
        var tlen: Long? = null
        if (raf.read(header) == 10 && String(header, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            val major = header[3].toInt() and 0xFF
            val size = synchsafe(header, 6)
            tagEnd = 10L + size + (if (header[5].toInt() and 0x10 != 0) 10 else 0)
            if (major in 2..4 && size in 1..MAX_TAG_BYTES) {
                val tag = ByteArray(size).also(raf::readFully)
                val frames = id3Text(tag, major, extendedHeader = header[5].toInt() and 0x40 != 0)
                tags = Tags(
                    title = frames["TIT2"] ?: frames["TT2"],
                    artist = frames["TPE1"] ?: frames["TP1"] ?: frames["TPE2"] ?: frames["TP2"],
                    album = frames["TALB"] ?: frames["TAL"],
                )
                tlen = (frames["TLEN"] ?: frames["TLE"])?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            }
        }
        val durationMs = tlen ?: if (mp3) mp3Length(raf, tagEnd) else null
        return tags.copy(durationMs = durationMs)
    }

    /** Text frames (`T…`, not `TXXX`) of an ID3v2 tag, values in their own encoding; several values joined. */
    internal fun id3Text(tag: ByteArray, major: Int, extendedHeader: Boolean = false): Map<String, String> {
        val out = HashMap<String, String>()
        var offset = 0
        if (extendedHeader && major >= 3 && tag.size >= 4) {
            offset = if (major == 4) synchsafe(tag, 0) else 4 + be(tag, 0, 4)
        }
        val idLength = if (major == 2) 3 else 4
        val headerLength = if (major == 2) 6 else 10
        while (offset in 0..(tag.size - headerLength)) {
            if (tag[offset].toInt() == 0) break
            val id = String(tag, offset, idLength, Charsets.ISO_8859_1)
            val frameSize = when (major) {
                2 -> be(tag, offset + 3, 3)
                4 -> synchsafe(tag, offset + 4)
                else -> be(tag, offset + 4, 4)
            }
            val start = offset + headerLength
            if (frameSize <= 0 || start + frameSize > tag.size) break
            if (id.startsWith("T") && id != "TXXX" && id != "TXX" && frameSize > 1) {
                decodeText(tag, start, start + frameSize)?.let { out.putIfAbsent(id, it) }
            }
            offset = start + frameSize
        }
        return out
    }

    private fun decodeText(bytes: ByteArray, start: Int, end: Int): String? {
        val charset = when (bytes[start].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        val text = String(bytes, start + 1, end - start - 1, charset)
        return text.split('\u0000').map { it.trim().removePrefix("﻿") }.filter { it.isNotBlank() }
            .distinct().joinToString(", ").ifBlank { null }
    }

    /**
     * The first MPEG audio frame after the tag: its Xing/Info frame count when it has one (a VBR
     * file), else the file's audio bytes at its bitrate (constant bitrate). Null if no frame is found.
     */
    private fun mp3Length(raf: RandomAccessFile, from: Long): Long? {
        raf.seek(from)
        val window = ByteArray(minOf(HEAD_BYTES.toLong(), raf.length() - from).toInt().coerceAtLeast(0)).also(raf::readFully)
        var at = 0
        while (at + 4 <= window.size) {
            if ((window[at].toInt() and 0xFF) == 0xFF && (window[at + 1].toInt() and 0xE0) == 0xE0) {
                val h = be(window, at, 4)
                val version = (h ushr 19) and 3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
                val layer = (h ushr 17) and 3 // 1 = layer III
                val bitrateIndex = (h ushr 12) and 0xF
                val rateIndex = (h ushr 10) and 3
                if (version != 1 && layer == 1 && bitrateIndex in 1..14 && rateIndex != 3) {
                    val mpeg1 = version == 3
                    val sampleRate = intArrayOf(44_100, 48_000, 32_000)[rateIndex] / (if (mpeg1) 1 else if (version == 2) 2 else 4)
                    val kbps = (if (mpeg1) MPEG1_L3 else MPEG2_L3)[bitrateIndex]
                    val samplesPerFrame = if (mpeg1) 1152 else 576
                    val mono = ((h ushr 6) and 3) == 3
                    val sideInfo = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
                    val xing = at + 4 + sideInfo
                    if (xing + 12 <= window.size) {
                        val tag = String(window, xing, 4, Charsets.ISO_8859_1)
                        if ((tag == "Xing" || tag == "Info") && be(window, xing + 4, 4) and 1 != 0) {
                            val frames = be(window, xing + 8, 4).toLong() and 0xFFFFFFFFL
                            if (frames > 0) return frames * samplesPerFrame * 1000 / sampleRate
                        }
                    }
                    val audioBytes = raf.length() - from - at
                    return if (kbps > 0) audioBytes * 8 / kbps else null
                }
            }
            at++
        }
        return null
    }

    // ---- MP4 / M4A ------------------------------------------------------------

    private fun mp4(raf: RandomAccessFile): Tags? {
        val moov = box(raf, 0L until raf.length(), "moov") ?: return null
        var durationMs: Long? = null
        box(raf, moov, "mvhd")?.let { mvhd ->
            raf.seek(mvhd.first)
            val version = raf.read()
            if (version == 1) {
                raf.skipBytes(3 + 16)
                val scale = raf.readInt().toLong() and 0xFFFFFFFFL
                val length = raf.readLong()
                if (scale > 0 && length > 0) durationMs = length * 1000 / scale
            } else {
                raf.skipBytes(3 + 8)
                val scale = raf.readInt().toLong() and 0xFFFFFFFFL
                val length = raf.readInt().toLong() and 0xFFFFFFFFL
                if (scale > 0 && length > 0) durationMs = length * 1000 / scale
            }
        }
        val ilst = box(raf, moov, "udta")
            ?.let { box(raf, it, "meta") }
            ?.let { box(raf, (it.first + 4)..it.last, "ilst") }
        fun text(name: String): String? {
            val item = ilst?.let { box(raf, it, name) } ?: return null
            val data = box(raf, item, "data") ?: return null
            val size = data.last + 1 - data.first - 8
            if (size !in 1..MAX_TEXT_BYTES) return null
            raf.seek(data.first + 8)
            return String(ByteArray(size.toInt()).also(raf::readFully), Charsets.UTF_8).trim().ifBlank { null }
        }
        return Tags(
            title = text("©nam"),
            artist = text("©ART") ?: text("aART"),
            album = text("©alb"),
            durationMs = durationMs,
        )
    }

    /** The contents of the first box called [name] (Latin-1, so `©nam` matches) directly inside [range]. */
    private fun box(raf: RandomAccessFile, range: LongRange, name: String): LongRange? {
        var at = range.first
        val end = range.last + 1
        while (at + 8 <= end) {
            raf.seek(at)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also(raf::readFully), Charsets.ISO_8859_1)
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

    // ---- WAV / AIFF -------------------------------------------------------------

    private fun wav(raf: RandomAccessFile): Tags? {
        val riff = ByteArray(12)
        if (raf.read(riff) != 12 || String(riff, 0, 4, Charsets.ISO_8859_1) != "RIFF") return null
        var byteRate = 0L
        var dataBytes = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        while (raf.filePointer + 8 <= raf.length()) {
            val id = String(ByteArray(4).also(raf::readFully), Charsets.ISO_8859_1)
            val size = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
            val next = raf.filePointer + size + (size and 1)
            when (id) {
                "fmt " -> if (size >= 12) {
                    raf.skipBytes(8)
                    byteRate = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
                }
                "data" -> dataBytes = size
                "LIST" -> if (size in 4..MAX_TAG_BYTES.toLong()) {
                    val list = ByteArray(size.toInt()).also(raf::readFully)
                    if (String(list, 0, 4, Charsets.ISO_8859_1) == "INFO") {
                        var at = 4
                        while (at + 8 <= list.size) {
                            val key = String(list, at, 4, Charsets.ISO_8859_1)
                            val length = le32(list, at + 4)
                            if (length < 0 || at + 8 + length > list.size) break
                            val value = String(list, at + 8, length, Charsets.UTF_8).trim('\u0000', ' ').ifBlank { null }
                            when (key) {
                                "INAM" -> title = value
                                "IART" -> artist = value
                                "IPRD" -> album = value
                            }
                            at += 8 + length + (length and 1)
                        }
                    }
                }
            }
            if (next > raf.length()) break
            raf.seek(next)
        }
        val durationMs = if (byteRate > 0 && dataBytes > 0) dataBytes * 1000 / byteRate else null
        return Tags(title, artist, album, durationMs)
    }

    private fun aiff(raf: RandomAccessFile): Tags? {
        val form = ByteArray(12)
        if (raf.read(form) != 12 || String(form, 0, 4, Charsets.ISO_8859_1) != "FORM") return null
        var durationMs: Long? = null
        var tags = Tags()
        var name: String? = null
        while (raf.filePointer + 8 <= raf.length()) {
            val id = String(ByteArray(4).also(raf::readFully), Charsets.ISO_8859_1)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val next = raf.filePointer + size + (size and 1)
            when (id) {
                "COMM" -> if (size >= 18) {
                    val comm = ByteArray(18).also(raf::readFully)
                    val frames = be(comm, 2, 4).toLong() and 0xFFFFFFFFL
                    val rate = extended80(comm, 8)
                    if (rate > 0) durationMs = (frames * 1000 / rate).toLong()
                }
                "NAME" -> if (size in 1..MAX_TEXT_BYTES) name = String(ByteArray(size.toInt()).also(raf::readFully), Charsets.ISO_8859_1).trim('\u0000', ' ').ifBlank { null }
                "ID3 " -> if (size in 10..MAX_TAG_BYTES.toLong()) {
                    val tag = ByteArray(size.toInt()).also(raf::readFully)
                    if (String(tag, 0, 3, Charsets.ISO_8859_1) == "ID3") {
                        val frames = id3Text(tag.copyOfRange(10, tag.size), tag[3].toInt() and 0xFF, tag[5].toInt() and 0x40 != 0)
                        tags = Tags(frames["TIT2"] ?: frames["TT2"], frames["TPE1"] ?: frames["TP1"], frames["TALB"] ?: frames["TAL"])
                    }
                }
            }
            if (next > raf.length()) break
            raf.seek(next)
        }
        return tags.copy(title = tags.title ?: name, durationMs = durationMs)
    }

    /** AIFF's sample rate: an 80-bit IEEE extended float. */
    private fun extended80(b: ByteArray, at: Int): Double {
        val exponent = ((b[at].toInt() and 0x7F) shl 8) or (b[at + 1].toInt() and 0xFF)
        var mantissa = 0L
        for (i in 0 until 8) mantissa = (mantissa shl 8) or (b[at + 2 + i].toLong() and 0xFF)
        if (exponent == 0 && mantissa == 0L) return 0.0
        return java.lang.Math.scalb((mantissa ushr 11).toDouble(), exponent - 16383 - 52)
    }

    // ---- bytes ----------------------------------------------------------------------

    private fun synchsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun be(b: ByteArray, at: Int, count: Int): Int {
        var v = 0
        for (i in 0 until count) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 64L * 1024
    private const val HEAD_BYTES = 256 * 1024
    private const val TAIL_BYTES = 64 * 1024
    private const val MAX_KEPT = 50_000
}
