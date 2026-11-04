package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.WORD_LYRICS_FIELD
import java.io.ByteArrayOutputStream

/**
 * Fork: an ID3v2.4 tag for an MP3 the PC downloaded (an addon or module can serve one; the phone's
 * taggers cover M4A, WebM and FLAC only). The same fields the phone's taggers write: title, artist,
 * album, lyrics (`USLT`, LRC as the phone puts it in `©lyr`/`LYRICS`), the word-synced form under
 * this app's own name (`TXXX:BITCHORD_LYRICS`) and the cover (`APIC`, front cover).
 *
 * The tag goes in front of the frames; an ID3v2 tag the file already starts with is dropped (a
 * second one would be read by some players and not others). Nothing in an MP3 points at an
 * absolute offset, so the audio is copied through unchanged.
 */
internal object ForkId3Tagger {

    /** Whether [head] looks like an MP3: an ID3v2 tag or an MPEG audio frame sync. */
    fun isMp3(head: ByteArray): Boolean =
        (head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) ||
            (head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0)

    /**
     * How many bytes at the front of the file are an existing ID3v2 tag (0 when none), read from
     * its 10-byte header. Null when the header is cut short or malformed.
     */
    fun existingTagLength(head: ByteArray): Int? {
        if (head.size < 3 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        if (head.size < 10) return null
        val sizeBytes = (6..9).map { head[it].toInt() and 0xFF }
        if (sizeBytes.any { it and 0x80 != 0 }) return null
        val size = sizeBytes.fold(0) { acc, b -> (acc shl 7) or b }
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    /** The new tag, or null when there is nothing to write. */
    fun tag(
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
        wordLyrics: String? = null,
    ): ByteArray? {
        val frames = ByteArrayOutputStream()
        if (title.isNotBlank()) frames.write(textFrame("TIT2", title))
        if (artist.isNotBlank()) frames.write(textFrame("TPE1", artist))
        if (!album.isNullOrBlank()) frames.write(textFrame("TALB", album))
        if (!lyrics.isNullOrBlank()) {
            // Encoding, language ("XXX": unknown), an empty description, the text.
            frames.write(frame("USLT", byteArrayOf(UTF8) + "XXX".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + lyrics.toByteArray(Charsets.UTF_8)))
        }
        if (!wordLyrics.isNullOrBlank()) {
            frames.write(frame("TXXX", byteArrayOf(UTF8) + WORD_LYRICS_FIELD.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + wordLyrics.toByteArray(Charsets.UTF_8)))
        }
        if (cover != null && cover.isNotEmpty()) {
            // Encoding, mime (Latin-1, terminated), picture type 3 (front cover), empty description.
            frames.write(frame("APIC", byteArrayOf(UTF8) + coverMime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + cover))
        }
        val body = frames.toByteArray()
        if (body.isEmpty()) return null
        val out = ByteArrayOutputStream(body.size + 10)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(4, 0, 0)) // v2.4.0, no flags
        out.write(syncsafe(body.size))
        out.write(body)
        return out.toByteArray()
    }

    /**
     * The lyrics in an ID3v2.3/2.4 tag at the front of [head], for [DesktopEmbeddedLyrics]: this
     * app's word-synced `TXXX:BITCHORD_LYRICS` first, else `USLT`. Null when there are none.
     */
    fun lyrics(head: ByteArray): String? = runCatching { readLyrics(head) }.getOrNull() // a corrupt tag costs its lyrics, never the lookup

    private fun readLyrics(head: ByteArray): String? {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
        val major = head[3].toInt()
        if (major != 3 && major != 4) return null
        val end = minOf(head.size, existingTagLength(head) ?: return null)
        var at = 10
        if (head[5].toInt() and 0x40 != 0) { // an extended header: skipped
            if (at + 4 > end) return null
            val size = if (major == 4) syncsafeAt(head, at) else u32At(head, at)
            at += if (major == 4) size else size + 4
            if (at < 10) return null // a size past Int's range
        }
        var plain: String? = null
        while (at + 10 <= end) {
            val id = String(head, at, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break // padding
            val size = if (major == 4) syncsafeAt(head, at + 4) else u32At(head, at + 4)
            val body = at + 10
            if (size < 0 || body + size > end) break
            val payload = head.copyOfRange(body, body + size)
            when (id) {
                "TXXX" -> textPair(payload, skip = 0)?.let { (name, value) ->
                    if (name == WORD_LYRICS_FIELD && value.isNotBlank()) return value
                }
                "USLT" -> if (plain == null) textPair(payload, skip = 3)?.second?.takeIf(String::isNotBlank)?.let { plain = it }
            }
            at = body + size
        }
        return plain
    }

    /** A frame's "encoding, [skip] bytes, description, value" (TXXX, USLT), decoded. */
    private fun textPair(payload: ByteArray, skip: Int): Pair<String, String>? {
        if (payload.size < 1 + skip) return null
        val encoding = payload[0].toInt()
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        val wide = encoding == 1 || encoding == 2
        val start = 1 + skip
        var split = -1
        var i = start
        while (i < payload.size) {
            if (!wide && payload[i].toInt() == 0) { split = i; break }
            if (wide && i + 1 < payload.size && payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 && (i - start) % 2 == 0) { split = i; break }
            i += if (wide) 2 else 1
        }
        if (split < 0) return null
        val terminator = if (wide) 2 else 1
        val description = String(payload, start, split - start, charset)
        var value = String(payload, split + terminator, payload.size - split - terminator, charset)
        value = value.trimEnd('\u0000')
        return description to value
    }

    private fun syncsafeAt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun u32At(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun textFrame(id: String, text: String): ByteArray =
        frame(id, byteArrayOf(UTF8) + text.toByteArray(Charsets.UTF_8))

    private fun frame(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + syncsafe(payload.size) + byteArrayOf(0, 0) + payload

    /** v2.4 sizes: 28 bits in four bytes of seven. */
    private fun syncsafe(value: Int): ByteArray {
        require(value in 0..0x0FFFFFFF) { "ID3 frame too large" }
        return byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
    }

    private const val UTF8: Byte = 3
}
