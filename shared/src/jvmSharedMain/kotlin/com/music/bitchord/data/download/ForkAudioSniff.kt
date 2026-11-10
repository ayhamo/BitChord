package com.music.bitchord.data.download

/**
 * Fork: what a download's opening bytes say it is, for the PC.
 *
 * A faithful copy of the phone's `Downloader.Container` / `Probe` / `sniff` and
 * `Downloads.estimatedKbps` (upstream be8c5d66), kept here rather than moved out of `app`: the phone
 * file is upstream's and changes often, and moving its body would turn every upstream edit to it
 * into a hand merge. `ForkAudioSniffDriftTest` in `app` feeds both the same bytes, so a change
 * upstream makes there fails a test instead of drifting apart in silence.
 *
 * Why the bytes and not the source's label: sources label loosely — `aac` for both raw ADTS and
 * AAC-in-MP4, `flac` on an HLS playlist, nothing at all on an extensionless URL — and a download has
 * to name the file before it is written. Filed under the wrong extension, a file either won't open or
 * opens as the wrong thing. The PC named every download from the label (`fileExtension()`), so an
 * addon's extensionless HLS labelled FLAC was fetched as a few KB of text called `.flac`, and a
 * server song with no stated codec became `.m4a` whatever it was.
 */
object ForkAudioSniff {

    /**
     * A container a source's file can be kept in, named for what its opening bytes say.
     *
     * AIFF is deliberately absent, as on the phone: neither player has an extractor for it, so a
     * saved AIFF would be a download that can never be played back.
     *
     * @param codec the codec this container settles on its own, or null when it can carry several
     *   and only the source's own word can say which.
     */
    enum class Container(val extension: String, val mimeType: String, val codec: String?) {
        FLAC("flac", "audio/flac", "flac"),
        WAV("wav", "audio/x-wav", "wav"),
        MP4("m4a", "audio/mp4", null),
        MP3("mp3", "audio/mpeg", "mp3"),
        ADTS("aac", "audio/aac", "aac"),
        OGG("ogg", "audio/ogg", null),
        WEBM("webm", "audio/webm", null),
    }

    /** What a probe found at the end of a source's URL. */
    sealed interface Probe {
        /** Audio, in [container], [totalBytes] long when the server said. */
        data class File(val container: Container, val totalBytes: Long?) : Probe

        /** An index of the audio rather than the audio — HLS, or DASH when [dash]. */
        data class Manifest(val dash: Boolean) : Probe
    }

    /** How much of a file to ask for: enough to see past an XML prolog to an `<MPD`, and far more than any magic number needs. */
    const val PROBE_BYTES = 512

    /**
     * The judgement on a file's first bytes, or null when it is nothing a download can keep.
     *
     * @param statedCodec what the source called it, which only breaks the tie for an ID3-prefixed
     *   file. The bytes decide everything else.
     */
    fun sniff(head: ByteArray, contentType: String, statedCodec: String?, totalBytes: Long?): Probe? {
        val type = contentType.lowercase()
        // Read byte for byte, so a UTF-8 byte-order mark arrives as its three Latin-1 characters
        // rather than as one.
        val text = String(head, Charsets.ISO_8859_1).removePrefix("ï»¿").trimStart()
        if ("mpegurl" in type || text.startsWith("#EXTM3U")) return Probe.Manifest(dash = false)
        if ("dash+xml" in type || "<MPD" in text) return Probe.Manifest(dash = true)
        return containerOf(head, statedCodec)?.let { Probe.File(it, totalBytes) }
    }

    private fun containerOf(head: ByteArray, statedCodec: String?): Container? {
        fun at(offset: Int, ascii: String) = head.size >= offset + ascii.length &&
            ascii.indices.all { head[offset + it] == ascii[it].code.toByte() }
        return when {
            at(0, "fLaC") -> Container.FLAC
            at(0, "RIFF") && at(8, "WAVE") -> Container.WAV
            at(4, "ftyp") -> Container.MP4
            at(0, "OggS") -> Container.OGG
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> Container.WEBM
            // An ID3 tag can run to megabytes of artwork, so what follows it is usually out of
            // reach. MP3 is what nearly always sits behind one; a source that said AAC gets the
            // benefit of the doubt.
            at(0, "ID3") -> if (statedCodec.equals("aac", ignoreCase = true)) Container.ADTS else Container.MP3
            head.size >= 2 && head[0] == 0xFF.toByte() -> {
                val second = head[1].toInt() and 0xFF
                when {
                    // ADTS: twelve sync bits, then layer bits that are always zero.
                    second and 0xF6 == 0xF0 -> Container.ADTS
                    // MPEG audio: eleven sync bits and a layer that isn't the reserved zero.
                    second and 0xE0 == 0xE0 && second and 0x06 != 0 -> Container.MP3
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * A file's average bitrate from its size and running time, when both are known.
     *
     * For a source that stated no bitrate: a download has to judge a lossy copy against YouTube's
     * before it is fetched, and an unstated rendition is nothing to judge, so the phone measures
     * it from the length the probe's `Content-Range` gave.
     */
    fun estimatedKbps(totalBytes: Long?, durationSec: Int?): Int? {
        if (totalBytes == null || durationSec == null || durationSec <= 0) return null
        return (totalBytes * 8 / 1000 / durationSec).toInt().takeIf { it > 0 }
    }

    /**
     * A 206's whole length (after the slash of `Content-Range`), or a 200's `Content-Length`: a
     * server that ignored the range is sending all of it. Null when neither says, or says nothing
     * positive. The phone's `Downloader.probe` reads it the same way.
     */
    fun totalBytes(code: Int, contentRange: String?, contentLength: String?): Long? =
        if (code == 206) {
            contentRange?.substringAfter('/', "")?.toLongOrNull()
        } else {
            contentLength?.toLongOrNull()
        }?.takeIf { it > 0 }
}
