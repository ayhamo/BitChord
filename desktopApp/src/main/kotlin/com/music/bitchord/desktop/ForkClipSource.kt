package com.music.bitchord.desktop

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: where the PC's cover clip is read from, and when a clip that failed is tried again.
 *
 * **The bug (2026-11-04).** Apple Music's motion covers are HLS. Upstream handed the manifest's
 * address straight to FFmpeg (a manifest can't be saved as one file the way an MP4 is), so the clip
 * was streamed over HTTPS while it played. The decoder reads at the clip's frame rate and stops
 * reading altogether while the song is paused or a mix holds the sleeve, so the connection sat
 * half-read; Apple's CDN closes it, and FFmpeg logged `Stream ends prematurely at 331296, should be
 * 4787788` and `[tls] IO error: End of file`. The HLS demuxer then carried on into the next byte
 * range with the cut-short fragment's sample table, so the H.264 decoder got bytes that were not a
 * sample: the ~135 `Invalid NAL unit size` / `Error splitting the input into NAL units` lines, and
 * the clip that played for a few seconds and broke. It also re-fetched the manifest and its segments
 * on every pass of the loop (FFmpeg can't seek back in an HLS stream, so each pass reopens it) and
 * every time the player was opened again.
 *
 * **Now** the chosen rendition is fetched once, whole, into the clip cache as one file (init
 * segment, then the fragments in order: a valid fragmented MP4, or a TS stream), and played from
 * disk like the other providers' MP4s: it loops by seeking, and a reopened player starts from the
 * file. The phone doesn't have the problem: ExoPlayer loads HLS segments into its own buffer ahead
 * of playback and retries a failed load.
 *
 * A clip that can't be fetched or decoded is not tried again for [RETRY_MS] (the still cover shows),
 * so a player opened and closed repeatedly doesn't refetch a broken clip each time.
 */
internal object ForkClipSource {

    /** How long a clip that failed is left alone. */
    const val RETRY_MS = 60_000L

    /** What the decoder should open for a clip address. */
    sealed interface Source {
        /** The clip on disk. */
        data class Local(val path: String) : Source

        /** A manifest this can't save (encrypted, live): streamed by FFmpeg as before. */
        data class Stream(val url: String) : Source

        /** Fetching failed, or it failed within [RETRY_MS]: nothing to open. */
        data object Failed : Source
    }

    val failures = ForkClipRetry(RETRY_MS)

    /** One fetch per address at a time: the sleeve and a second surface would both write its `.part`. */
    private val locks = ConcurrentHashMap<String, Any>()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Opens the first of [candidates] that [decoder] can play, with the same contract as the code
     * it replaces in `DesktopCanvasVideo`. Every candidate that fails is remembered in [failures].
     */
    fun open(decoder: DesktopCanvasDecoder, candidates: List<String>): Result<Unit> {
        var last: Result<Unit> = Result.failure(IllegalStateException("the clip could not be fetched"))
        for (candidate in candidates) {
            if (!failures.allows(candidate)) {
                last = Result.failure(IllegalStateException("the clip failed under a minute ago; the still cover stays"))
                continue
            }
            val source = when (val found = sourceFor(candidate)) {
                is Source.Local -> found.path
                is Source.Stream -> found.url
                Source.Failed -> {
                    failures.failed(candidate)
                    continue
                }
            }
            last = decoder.open(source)
            if (last.isSuccess) {
                failures.succeeded(candidate)
                return last
            }
            failures.failed(candidate)
            // A cached file the decoder refuses is not kept: the next try fetches it again.
            if (source != candidate) runCatching { Files.deleteIfExists(Path.of(source)) }
        }
        return last
    }

    /** Where [url]'s clip can be read from; fetches it into the cache first. */
    fun sourceFor(url: String): Source = synchronized(locks.getOrPut(url) { Any() }) { fetchLocked(url) }

    private fun fetchLocked(url: String): Source {
        if (!isManifest(url)) {
            return DesktopCanvasCache.fileFor(url)?.let { Source.Local(it.toAbsolutePath().toString()) } ?: Source.Failed
        }
        val extension = ForkClipCache.extension(url)
        DesktopMediaCache.existing(url, extension)?.let { return Source.Local(it.toAbsolutePath().toString()) }
        val target = DesktopMediaCache.pathFor(url, extension)
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        return try {
            val plan = try {
                plan(url)
            } catch (unsupported: ForkClipHls.Unsupported) {
                DesktopTrackLog.log("canvas: the clip's playlist can't be saved (${unsupported.message}); streaming it")
                return Source.Stream(url)
            }
            Files.newOutputStream(part).buffered().use { out ->
                for (piece in ForkClipHls.merged(plan)) fetch(piece, out)
            }
            check(Files.size(part) > 0) { "the clip came back empty" }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            DesktopMediaCache.trim()
            Source.Local(target.toAbsolutePath().toString())
        } catch (e: Exception) {
            DesktopTrackLog.log("canvas: could not fetch the clip's segments — ${e.message}")
            Source.Failed
        } finally {
            runCatching { Files.deleteIfExists(part) }
        }
    }

    /** The master playlist's chosen rendition (or the playlist itself), as pieces to fetch. */
    private fun plan(url: String): ForkClipHls.Plan {
        val first = text(url)
        val variants = ForkClipHls.variants(first)
        if (variants.isEmpty()) return ForkClipHls.media(first, url)
        val chosen = ForkClipHls.choose(variants) ?: throw ForkClipHls.Unsupported("no rendition")
        val mediaUrl = URI(url).resolve(chosen.uri).toString()
        return ForkClipHls.media(text(mediaUrl), mediaUrl)
    }

    private fun text(url: String): String {
        val response = client.send(request(url, null), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "playlist HTTP ${response.statusCode()}" }
        return response.body()
    }

    /** One piece into [out]; a server that ignores the range sends the whole file, which is cut down here. */
    private fun fetch(piece: ForkClipHls.Piece, out: OutputStream) {
        val response = client.send(request(piece.uri, piece), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            val status = response.statusCode()
            check(status in 200..299) { "segment HTTP $status" }
            val length = piece.length
            if (length == null) {
                body.copyTo(out)
                return
            }
            if (status == 200) body.skipExactly(piece.offset)
            check(body.copyExactly(out, length) == length) { "a segment ended early" }
        }
    }

    private fun request(url: String, piece: ForkClipHls.Piece?): HttpRequest =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", CLIP_UA)
            .header("Accept", "*/*")
            .apply {
                if (piece?.length != null) header("Range", "bytes=${piece.offset}-${piece.offset + piece.length - 1}")
            }
            .GET()
            .build()

    private fun InputStream.skipExactly(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) error("a segment ended early")
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun InputStream.copyExactly(out: OutputStream, count: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (done < count) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), count - done).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            done += read
        }
        return done
    }

    private const val CLIP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
}

/**
 * Fork: a failed clip address is left alone for [windowMs] (the still cover shows), then tried once
 * more. A success clears it. [now] is a parameter so the tests can move the clock.
 */
internal class ForkClipRetry(private val windowMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun allows(url: String): Boolean {
        val at = failedAt[url] ?: return true
        if (now() - at < windowMs) return false
        failedAt.remove(url, at)
        return true
    }

    fun failed(url: String) {
        failedAt[url] = now()
    }

    fun succeeded(url: String) {
        failedAt.remove(url)
    }
}

/**
 * Fork: the little of HLS a motion cover needs, to save one rendition as one file. Pure, so it is
 * unit tested (`ForkClipSourceTest`).
 */
internal object ForkClipHls {

    /** Not saveable as one file; the caller streams it instead. */
    class Unsupported(message: String) : Exception(message)

    data class Variant(val uri: String, val width: Int, val height: Int, val codecs: String, val bandwidth: Long) {
        val edge: Int get() = maxOf(width, height)
    }

    /** A URI, whole ([length] null) or the byte range [offset]..[offset]+[length]. */
    data class Piece(val uri: String, val offset: Long = 0L, val length: Long? = null)

    /** The init segment (if any) then the media segments, as absolute URIs, in play order. */
    data class Plan(val init: Piece?, val segments: List<Piece>)

    /**
     * The longest edge the PC decodes a cover clip at (`DesktopCanvasDecoder.MAX_EDGE`, which is
     * private): the smallest rendition reaching it is enough.
     */
    const val MAX_EDGE = 540

    /** A master playlist's renditions; empty for a media playlist. */
    fun variants(master: String): List<Variant> {
        val lines = master.lines().map(String::trim)
        val out = mutableListOf<Variant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val attributes = attributes(line.substringAfter(':'))
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val (w, h) = attributes["RESOLUTION"]?.split('x', 'X')?.mapNotNull(String::toIntOrNull)
                ?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (0 to 0)
            out += Variant(
                uri = uri,
                width = w,
                height = h,
                codecs = attributes["CODECS"].orEmpty(),
                bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
            )
        }
        return out
    }

    /**
     * The rendition to save: H.264 when there is one (cheapest to decode in software; Apple lists
     * HEVC and Dolby Vision ones too), then the smallest reaching [MAX_EDGE], or the largest when
     * none does, the rule `DesktopCanvasDecoder.forkSmallestFitting` applied to the streamed master.
     * Without sizes, the lowest bandwidth.
     */
    fun choose(variants: List<Variant>): Variant? {
        if (variants.isEmpty()) return null
        val family = variants.filter { it.codecs.lowercase(Locale.ROOT).contains("avc1") }.ifEmpty {
            variants.filter { it.codecs.lowercase(Locale.ROOT).let { c -> c.contains("hvc1") || c.contains("hev1") } }
        }.ifEmpty { variants }
        val sized = family.filter { it.edge > 0 }
        if (sized.isEmpty()) return family.minByOrNull { it.bandwidth }
        return sized.filter { it.edge >= MAX_EDGE }.minWithOrNull(compareBy({ it.edge }, { it.bandwidth }))
            ?: sized.maxWithOrNull(compareBy({ it.edge }, { -it.bandwidth }))
    }

    /** A media playlist at [baseUrl] as pieces to fetch; [Unsupported] for what one file can't hold. */
    fun media(playlist: String, baseUrl: String): Plan {
        if (!playlist.trimStart().startsWith("#EXTM3U")) throw Unsupported("not a playlist")
        val base = URI(baseUrl)
        fun resolve(ref: String) = base.resolve(ref).toString()
        var init: Piece? = null
        val segments = mutableListOf<Piece>()
        var range: Pair<Long, Long?>? = null // length, offset (null: after the previous piece)
        var lastEnd = 0L
        var lastUri: String? = null
        var ended = false
        for (raw in playlist.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attributes(line.substringAfter(':'))["METHOD"]
                    if (method != null && !method.equals("NONE", ignoreCase = true)) throw Unsupported("encrypted")
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    if (init != null || segments.isNotEmpty()) throw Unsupported("more than one init segment")
                    val attributes = attributes(line.substringAfter(':'))
                    val uri = resolve(attributes["URI"] ?: throw Unsupported("init segment without a URI"))
                    init = attributes["BYTERANGE"]?.let { spec ->
                        val (length, offset) = byteRange(spec)
                        Piece(uri, offset ?: 0L, length)
                    } ?: Piece(uri)
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> range = byteRange(line.substringAfter(':'))
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#") -> Unit
                else -> {
                    val uri = resolve(line)
                    val piece = range?.let { (length, offset) ->
                        val start = offset ?: if (uri == lastUri) lastEnd else 0L
                        Piece(uri, start, length).also { lastEnd = start + length }
                    } ?: Piece(uri)
                    lastUri = uri
                    range = null
                    segments += piece
                }
            }
        }
        if (!ended) throw Unsupported("a live playlist")
        if (segments.isEmpty()) throw Unsupported("no segments")
        return Plan(init, segments)
    }

    /**
     * [plan]'s pieces, with neighbouring ranges of the same file joined into one request: Apple
     * serves a rendition as one MP4 addressed by byte ranges, which is then one or two requests.
     */
    fun merged(plan: Plan): List<Piece> {
        val out = mutableListOf<Piece>()
        for (piece in listOfNotNull(plan.init) + plan.segments) {
            val previous = out.lastOrNull()
            val previousLength = previous?.length
            val length = piece.length
            if (previous != null && previousLength != null && length != null &&
                previous.uri == piece.uri && previous.offset + previousLength == piece.offset
            ) {
                out[out.lastIndex] = previous.copy(length = previousLength + length)
            } else {
                out += piece
            }
        }
        return out
    }

    /** `n[@o]` into (length, offset or null). */
    private fun byteRange(spec: String): Pair<Long, Long?> {
        val clean = spec.trim().trim('"')
        val length = clean.substringBefore('@').toLongOrNull()?.takeIf { it > 0 } ?: throw Unsupported("bad byte range")
        val offset = if ('@' in clean) clean.substringAfter('@').toLongOrNull() ?: throw Unsupported("bad byte range") else null
        return length to offset
    }

    /** `KEY=value,KEY="quoted, value"` into a map. */
    private fun attributes(list: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(list).forEach { match ->
            out[match.groupValues[1]] = match.groupValues[2].trim('"')
        }
        return out
    }
}
