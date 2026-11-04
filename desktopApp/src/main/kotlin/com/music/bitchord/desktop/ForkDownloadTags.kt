package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.toEnhancedLrc
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.download.FlacTagger
import com.music.bitchord.download.Mp4Tagger
import com.music.bitchord.download.WebmTagger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Fork: the phone's download tags on the PC. Title, artist, album, cover and lyrics go into the
 * file the way the phone's `MediaTagger` puts them there, through the same taggers (moved to
 * `:shared`, `com.music.bitchord.download`): FLAC streamed (only the metadata region is held),
 * M4A and WebM in memory. MP3 ([ForkId3Tagger]) and Ogg ([ForkOggTagger]) are the PC's own, since
 * addons and modules can serve those; WAV keeps no tags, as on the phone.
 *
 * The cover (the 1200 px artwork, scaled to 1000 px, JPEG 92) and the lyrics (the player's own
 * lookup, so the same switch and sources; LRC capped as the phone caps it) are fetched while the
 * audio downloads ([start]) and written into the finished `.part` before it takes its real name
 * ([write]), so the file appears tagged. Best effort: a failure costs the tags, never the file.
 */
internal object ForkDownloadTags {

    class Tags(val cover: ByteArray?, val plain: String?, val enhanced: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One rewrite at a time, as on the phone: a large file is held whole for M4A and WebM. */
    private val lock = Mutex()

    /** Starts the cover and lyrics lookups for [song]; they run while the audio downloads. */
    fun start(song: Song): Deferred<Tags> = scope.async {
        val cover = async { runCatching { cover(song) }.getOrNull() }
        val words = withTimeoutOrNull(LOOKUP_MS) { runCatching { lyrics(song) }.getOrNull() }
        Tags(cover.await(), words?.first, words?.second)
    }

    /** Tags [file] (the finished download of [song]) with what [tags] found. */
    suspend fun write(tags: Deferred<Tags>, file: Path, song: Song) {
        val found = withTimeoutOrNull(LOOKUP_MS) { tags.await() } ?: Tags(null, null, null)
        runCatching { lock.withLock { withContext(Dispatchers.IO) { embed(file, song, found) } } }
            .onFailure { DesktopTrackLog.log("could not tag the download of '${song.title}': ${it.message}") }
    }

    /** The rewrite itself, by what the file is rather than by its name. */
    internal fun embed(file: Path, song: Song, tags: Tags) {
        val head = Files.newInputStream(file).use { it.readNBytes(FLAC_HEAD_BYTES) }
        val mime = "image/jpeg"
        when {
            head.startsWith(FLAC) -> {
                // Short of the cap means the whole file was read.
                val rewrite = FlacTagger.header(head, song.title, song.artist, song.albumName, tags.plain, tags.cover, mime, tags.enhanced)
                    ?: run {
                        // The phone's fallback (MediaTagger.tagFlacStreaming): a metadata region
                        // longer than the head is tagged whole; a refusal of the whole file is final.
                        if (head.size >= FLAC_HEAD_BYTES) rewriteWhole(file) {
                            FlacTagger.tag(it, song.title, song.artist, song.albumName, tags.plain, tags.cover, mime, wordLyrics = tags.enhanced)
                        }
                        return
                    }
                replace(file) { sink ->
                    sink.write(rewrite.metadata)
                    Files.newInputStream(file).use { it.skipFully(rewrite.audioStart.toLong()); it.copyTo(sink, COPY_BUFFER) }
                }
            }
            head.isMp4() -> rewriteWhole(file) {
                Mp4Tagger.tag(it, song.title, song.artist, song.albumName, tags.plain, tags.cover, coverIsPng = false, wordLyrics = tags.enhanced)
            }
            head.startsWith(EBML) -> rewriteWhole(file) {
                WebmTagger.tag(it, song.title, song.artist, song.albumName, tags.plain, tags.cover, mime, wordLyrics = tags.enhanced)
            }
            ForkOggTagger.isOgg(head) -> rewriteWhole(file) {
                ForkOggTagger.tag(it, song.title, song.artist, song.albumName, tags.plain, tags.cover, mime, tags.enhanced)
            }
            ForkId3Tagger.isMp3(head) -> {
                val skip = ForkId3Tagger.existingTagLength(head) ?: return
                val tag = ForkId3Tagger.tag(song.title, song.artist, song.albumName, tags.plain, tags.cover, mime, tags.enhanced)
                    ?: return
                if (skip > Files.size(file)) return
                replace(file) { sink ->
                    sink.write(tag)
                    Files.newInputStream(file).use { it.skipFully(skip.toLong()); it.copyTo(sink, COPY_BUFFER) }
                }
            }
            else -> Unit // WAV and anything unknown keep what they have
        }
    }

    private fun rewriteWhole(file: Path, tagger: (ByteArray) -> ByteArray) {
        val original = Files.readAllBytes(file)
        val tagged = tagger(original)
        // Every tagger hands back the same array when there was nothing safe to do.
        if (tagged === original) return
        replace(file) { it.write(tagged) }
    }

    /** Writes a new copy beside [file] and moves it over, so the file is never half-written. */
    private fun replace(file: Path, write: (java.io.OutputStream) -> Unit) {
        val temp = file.resolveSibling(file.fileName.toString() + ".tagging")
        try {
            Files.newOutputStream(temp).buffered().use(write)
            runCatching { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .getOrElse { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** The phone's `LyricsTag.forTrack`, through the PC's lookup and settings. */
    private suspend fun lyrics(song: Song): Pair<String, String?>? {
        val found = DesktopLyricsClient.lookup(song).getOrNull() ?: return null
        if (found.lines.none { it.text.isNotBlank() }) return null
        val lrc = found.lines.toLrc()
        if (lrc.isBlank() || lrc.length > MAX_LRC_CHARS) return null
        val enhanced = found.lines.toEnhancedLrc().takeIf { it.isNotBlank() && it.length <= MAX_LRC_CHARS * 2 }
        return lrc to enhanced
    }

    /** The phone's `MediaTagger.fetchCover`: the 1200 px artwork, at most 1000 px, as JPEG 92. */
    private fun cover(song: Song): ByteArray? {
        val url = song.artworkAt(1200) ?: song.thumbnailUrl?.takeIf(String::isNotBlank) ?: return null
        val raw = DesktopDownloadHttp.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.bytes()
        }
        return jpeg(raw)
    }

    internal fun jpeg(raw: ByteArray, maxSide: Int = COVER_MAX_SIDE): ByteArray? {
        val image = ImageIO.read(raw.inputStream()) ?: return null
        val longest = maxOf(image.width, image.height)
        val scale = if (longest > maxSide) maxSide.toDouble() / longest else 1.0
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        // RGB: the JPEG writer refuses an alpha channel.
        val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(image, 0, 0, width, height, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.92f
                }
                writer.write(null, IIOImage(rgb, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) { left -= skipped; continue }
            if (read() == -1) error("file ended ${left}B early")
            left--
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun ByteArray.isMp4() = size >= 8 && copyOfRange(4, 8).contentEquals("ftyp".toByteArray())

    private val FLAC = "fLaC".toByteArray()
    private val EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    /** The phone's figures (`MediaTagger`, `LyricsTag`). */
    private const val FLAC_HEAD_BYTES = 16 * 1024 * 1024
    private const val COPY_BUFFER = 64 * 1024
    private const val COVER_MAX_SIDE = 1000
    private const val MAX_LRC_CHARS = 64_000
    private const val LOOKUP_MS = 30_000L
}
