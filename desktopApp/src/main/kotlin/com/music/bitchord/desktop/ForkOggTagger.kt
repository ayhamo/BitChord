package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.WORD_LYRICS_FIELD
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Fork: the comment header of an Ogg Opus or Ogg Vorbis file the PC downloaded (an addon or module
 * can serve one; the phone's taggers cover M4A, WebM and FLAC only), with the fields the phone's
 * [com.music.bitchord.download.FlacTagger] writes: TITLE, ARTIST, ALBUM, LYRICS, BITCHORD_LYRICS
 * and the cover as `METADATA_BLOCK_PICTURE` (a FLAC picture block in base64, which is how Ogg
 * carries one).
 *
 * The comment is the second packet of the stream. Both codecs start their audio on a fresh page,
 * so the header pages can be rebuilt on their own: the first page (the id header) is kept, the
 * comment (and for Vorbis the setup packet after it) is paginated again, and every later page is
 * copied with its sequence number shifted and its checksum recomputed. Anything else (several
 * streams interleaved, a header that doesn't end on a page) comes back unchanged, by reference.
 */
internal object ForkOggTagger {

    fun isOgg(head: ByteArray): Boolean = head.size >= 4 && head.copyOfRange(0, 4).contentEquals(CAPTURE)

    fun tag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
        wordLyrics: String? = null,
    ): ByteArray = runCatching { rewrite(bytes, title, artist, album, lyrics, cover, coverMime, wordLyrics) }.getOrNull() ?: bytes

    /**
     * The lyrics in an Ogg Opus/Vorbis comment header at the front of [head], for
     * [DesktopEmbeddedLyrics]: `BITCHORD_LYRICS` first, else `LYRICS`. Null when there are none.
     */
    fun lyrics(head: ByteArray): String? = runCatching {
        if (!isOgg(head)) return null
        val pages = mutableListOf<Page>()
        var at = 0
        var read: Packets? = null
        while (at < head.size && pages.size < MAX_HEADER_PAGES) {
            pages += readPage(head, at)?.also { at = it.end } ?: break
            val packets = packets(head, pages)
            if (packets.packets.size >= 2) { read = packets; break }
        }
        val comment = read?.packets?.get(1) ?: return null
        val opus = comment.startsWith("OpusTags".toByteArray())
        val vorbis = comment.size > 7 && comment[0].toInt() == 3 && comment.copyOfRange(1, 7).contentEquals("vorbis".toByteArray())
        if (!opus && !vorbis) return null
        val vendor = vendorOf(comment, opus) ?: return null
        var pos = (if (opus) 8 else 7) + 4 + vendor.size
        if (pos + 4 > comment.size) return null
        val count = readLe32(comment, pos)
        pos += 4
        var plain: String? = null
        repeat(count.coerceIn(0, 4_096)) {
            if (pos + 4 > comment.size) return plain
            val length = readLe32(comment, pos)
            pos += 4
            if (length < 0 || pos + length > comment.size) return plain
            val entry = String(comment, pos, length, Charsets.UTF_8)
            pos += length
            val name = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            if (name == WORD_LYRICS_FIELD && value.isNotBlank()) return value
            if (name == "LYRICS" && plain == null && value.isNotBlank()) plain = value
        }
        plain
    }.getOrNull()

    private const val MAX_HEADER_PAGES = 512

    private class Page(val start: Int, val headerType: Int, val serial: Int, val segments: IntArray, val bodyStart: Int, val end: Int)

    private fun rewrite(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
        wordLyrics: String?,
    ): ByteArray? {
        val pages = mutableListOf<Page>()
        var at = 0
        while (at < bytes.size) pages += readPage(bytes, at)?.also { at = it.end } ?: return null
        if (pages.size < 2) return null
        val serial = pages[0].serial
        if (pages.any { it.serial != serial }) return null // chained or multiplexed: not guessed at

        // The id header alone on the first page decides the codec and how many header packets follow.
        val first = packets(bytes, listOf(pages[0]))
        if (first.packets.size != 1 || first.open) return null
        val id = first.packets[0]
        val opus = id.startsWith("OpusHead".toByteArray())
        val vorbis = id.size > 7 && id[0].toInt() == 1 && id.copyOfRange(1, 7).contentEquals("vorbis".toByteArray())
        if (!opus && !vorbis) return null
        val headerPackets = if (opus) 1 else 2 // after the id header: comment (+ setup)

        // The pages holding the remaining headers; they have to end exactly at a page boundary.
        var headerEnd = 1
        var collected: Packets? = null
        while (headerEnd < pages.size) {
            headerEnd++
            val read = packets(bytes, pages.subList(1, headerEnd))
            if (read.packets.size >= headerPackets) {
                if (read.packets.size != headerPackets || read.open) return null
                collected = read
                break
            }
        }
        val headers = collected?.packets ?: return null
        val oldComment = headers[0]
        val vendor = vendorOf(oldComment, opus) ?: return null

        val comment = ByteArrayOutputStream()
        if (opus) comment.write("OpusTags".toByteArray()) else { comment.write(3); comment.write("vorbis".toByteArray()) }
        val fields = buildList {
            if (title.isNotBlank()) add("TITLE=$title")
            if (artist.isNotBlank()) add("ARTIST=$artist")
            if (!album.isNullOrBlank()) add("ALBUM=$album")
            if (!lyrics.isNullOrBlank()) add("LYRICS=$lyrics")
            if (!wordLyrics.isNullOrBlank()) add("$WORD_LYRICS_FIELD=$wordLyrics")
            if (cover != null && cover.isNotEmpty()) {
                add("METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(picture(cover, coverMime)))
            }
        }
        if (fields.isEmpty()) return null
        comment.writeLe(vendor.size)
        comment.write(vendor)
        comment.writeLe(fields.size)
        fields.forEach { field -> field.toByteArray(Charsets.UTF_8).let { comment.writeLe(it.size); comment.write(it) } }
        if (vorbis) comment.write(1) // framing bit

        val newHeaderPages = paginate(listOf(comment.toByteArray()) + headers.drop(1), serial, firstSequence = 1)
        val shift = newHeaderPages.size - (headerEnd - 1)

        val out = ByteArrayOutputStream(bytes.size + comment.size())
        out.write(bytes, pages[0].start, pages[0].end - pages[0].start)
        newHeaderPages.forEach(out::write)
        for (page in pages.drop(headerEnd)) {
            val copy = bytes.copyOfRange(page.start, page.end)
            val sequence = readLe32(copy, 18) + shift
            writeLe32(copy, 18, sequence)
            writeLe32(copy, 22, 0)
            writeLe32(copy, 22, crc(copy))
            out.write(copy)
        }
        return out.toByteArray()
    }

    private fun readPage(bytes: ByteArray, start: Int): Page? {
        if (start + 27 > bytes.size || !bytes.copyOfRange(start, start + 4).contentEquals(CAPTURE)) return null
        if (bytes[start + 4].toInt() != 0) return null
        val count = bytes[start + 26].toInt() and 0xFF
        val bodyStart = start + 27 + count
        if (bodyStart > bytes.size) return null
        val segments = IntArray(count) { bytes[start + 27 + it].toInt() and 0xFF }
        val end = bodyStart + segments.sum()
        if (end > bytes.size) return null
        return Page(start, bytes[start + 5].toInt() and 0xFF, readLe32(bytes, start + 14), segments, bodyStart, end)
    }

    private class Packets(val packets: List<ByteArray>, val open: Boolean)

    /** The packets in [pages], and whether the last one continues past them. */
    private fun packets(bytes: ByteArray, pages: List<Page>): Packets {
        val done = mutableListOf<ByteArray>()
        val current = ByteArrayOutputStream()
        var open = false
        for (page in pages) {
            var at = page.bodyStart
            for (lace in page.segments) {
                current.write(bytes, at, lace)
                at += lace
                open = lace == 255
                if (!open) { done += current.toByteArray(); current.reset() }
            }
        }
        return Packets(done, open)
    }

    /** Header packets laid out on pages of their own (granule position 0), up to 255 laces each. */
    private fun paginate(packets: List<ByteArray>, serial: Int, firstSequence: Int): List<ByteArray> {
        val laces = mutableListOf<Pair<Int, Int>>() // (packet, length) per lace
        val offsets = mutableListOf<Int>()
        packets.forEachIndexed { index, packet ->
            var left = packet.size
            var offset = 0
            while (true) {
                val lace = minOf(255, left)
                laces += index to lace
                offsets += offset
                offset += lace
                left -= lace
                if (lace < 255) break
            }
        }
        val pages = mutableListOf<ByteArray>()
        var lace = 0
        var continued = false
        while (lace < laces.size) {
            val take = minOf(255, laces.size - lace)
            val body = ByteArrayOutputStream()
            for (i in lace until lace + take) body.write(packets[laces[i].first], offsets[i], laces[i].second)
            val page = ByteArrayOutputStream()
            page.write(CAPTURE)
            page.write(0)
            page.write(if (continued) 1 else 0)
            repeat(8) { page.write(0) } // granule position 0 for headers
            page.writeLe(serial)
            page.writeLe(firstSequence + pages.size)
            page.writeLe(0) // checksum, filled in below
            page.write(take)
            for (i in lace until lace + take) page.write(laces[i].second)
            page.write(body.toByteArray())
            val bytes = page.toByteArray()
            writeLe32(bytes, 22, crc(bytes))
            pages += bytes
            continued = laces[lace + take - 1].second == 255
            lace += take
        }
        return pages
    }

    private fun vendorOf(comment: ByteArray, opus: Boolean): ByteArray? {
        val at = if (opus) 8 else 7
        if (comment.size < at + 4) return null
        val length = readLe32(comment, at)
        if (length < 0 || at + 4 + length > comment.size) return null
        return comment.copyOfRange(at + 4, at + 4 + length)
    }

    /** The FLAC `PICTURE` block body, as FlacTagger writes it. */
    private fun picture(cover: ByteArray, mime: String): ByteArray {
        val out = ByteArrayOutputStream(cover.size + 64)
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        out.writeBe(3)
        out.writeBe(mimeBytes.size)
        out.write(mimeBytes)
        repeat(5) { out.writeBe(0) } // description length, width, height, depth, colours
        out.writeBe(cover.size)
        out.write(cover)
        return out.toByteArray()
    }

    /** Ogg's CRC-32: polynomial 0x04C11DB7, not reflected, starting at 0. */
    internal fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun readLe32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(b: ByteArray, at: Int, value: Int) {
        for (i in 0..3) b[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun ByteArrayOutputStream.writeLe(value: Int) {
        for (i in 0..3) write((value ushr (8 * i)) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeBe(value: Int) {
        for (i in 3 downTo 0) write((value ushr (8 * i)) and 0xFF)
    }

    private val CAPTURE = "OggS".toByteArray()
}
