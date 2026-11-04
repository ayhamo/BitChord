package com.music.bitchord.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer

/**
 * Fork: a music video's picture read the way googlevideo will serve it — a window at a time.
 *
 * Google's media servers answer a request for a whole file with 403 (see [DesktopStreamClient]),
 * and FFmpeg's own HTTP client asks for the whole thing; the audio reads through
 * [DesktopRangeStream] for that reason. The picture can't use it as it is: it fetches each window
 * only when the decoder reaches it, on the decoding thread, so every window (half a second of a
 * 1080p picture) would stall the picture for a round trip. [ForkVideoRangeReader] keeps the next
 * few windows in flight, as the phone's ChunkedDataSource does under ExoPlayer's buffer.
 *
 * One per decoder; [DesktopCanvasDecoder] closes it with itself.
 */
internal class ForkVideoIo(url: String, headers: Map<String, String>) {

    val reader = ForkVideoRangeReader(url, headers)

    private var avio: AVIOContext? = null
    private var read: Read_packet_Pointer_BytePointer_int? = null
    private var seek: Seek_Pointer_long_int? = null
    private var closed = false

    /** A format context reading through [reader], for avformat_open_input. */
    fun context(): AVFormatContext {
        val scratch = ByteArray(IO_BUFFER_BYTES)
        val reading = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, buffer: BytePointer?, size: Int): Int {
                if (buffer == null) return AVERROR_EOF
                // Nothing may be thrown back into FFmpeg's C frames (see DesktopAudioDecoder's read).
                val taken = try { reader.read(scratch, minOf(size, scratch.size)) } catch (_: Throwable) { return AVERROR_EIO }
                if (taken <= 0) return AVERROR_EOF
                buffer.put(scratch, 0, taken)
                return taken
            }
        }
        val seeking = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
                if (whence == AVSEEK_SIZE) return runCatching { reader.length() }.getOrDefault(-1)
                val target = when (whence) {
                    SEEK_CUR -> reader.position() + offset
                    SEEK_END -> runCatching { reader.length() }.getOrDefault(-1L).takeIf { it >= 0 }?.plus(offset) ?: return -1
                    else -> offset
                }
                reader.seek(target)
                return target
            }
        }
        val buffer = BytePointer(av_malloc(IO_BUFFER_BYTES.toLong()))
        val io = avio_alloc_context(buffer, IO_BUFFER_BYTES, 0, null, reading, null, seeking)
        val context = avformat_alloc_context()
        context.pb(io)
        read = reading
        seek = seeking
        avio = io
        return context
    }

    /** Frees the custom I/O, which avformat_close_input leaves alone. Safe to call twice. */
    fun close() {
        if (closed) return
        closed = true
        avio?.let {
            av_free(it.buffer())
            it.buffer(null as BytePointer?)
            avio_context_free(it)
        }
        avio = null
        read?.deallocate()
        seek?.deallocate()
        read = null
        seek = null
        reader.close()
    }

    private companion object {
        const val IO_BUFFER_BYTES = 64 * 1024
        const val SEEK_CUR = 1
        const val SEEK_END = 2
        const val AVERROR_EIO = -5
    }
}

/**
 * Fork: a remote file in bounded windows, the next [ahead] of them fetched before they are wanted.
 *
 * Windows are kept by start offset; a seek far away simply asks for new ones, and the ones it left
 * behind are dropped once more than [ahead] + 2 are held. A refusal (403/404/410) is recorded in
 * [refusedWith] for the music video to try the next client, as the phone does.
 */
internal class ForkVideoRangeReader(
    private val url: String,
    private val headers: Map<String, String>,
    private val windowBytes: Int = WINDOW_BYTES,
    private val ahead: Int = AHEAD,
    private val fetch: (url: String, headers: Map<String, String>, start: Long, end: Long) -> CompletableFuture<Fetched?> = ::httpFetch,
) {
    /** One window's bytes, where they start, and the file's length if the server said. */
    class Fetched(val start: Long, val bytes: ByteArray, val length: Long, val status: Int = 206)

    private val windows = LinkedHashMap<Long, CompletableFuture<Fetched?>>()
    @Volatile private var position = 0L
    @Volatile private var total = -1L
    @Volatile private var closed = false

    /** The status googlevideo refused a window with, if it did. */
    @Volatile var refusedWith: Int? = null
        private set

    fun position(): Long = position
    fun length(): Long = total
    fun seek(offset: Long) { position = offset.coerceAtLeast(0) }

    fun read(into: ByteArray, count: Int): Int {
        if (count <= 0) return 0
        if (closed) return -1
        if (total >= 0 && position >= total) return -1
        val start = position - position % windowBytes
        var window: Fetched? = null
        // Twice: a window dropped from under the read, or one failed request, is not the end.
        for (attempt in 0 until 2) {
            if (closed) return -1
            window = try {
                request(start).get(TIMEOUT_S, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
            if (window != null && window.status in 200..299) break
            synchronized(windows) { windows.remove(start) }
            if (window != null && window.status in REFUSALS) {
                refusedWith = window.status
                return -1
            }
            window = null
        }
        if (window == null) return -1
        if (window.length >= 0) total = window.length
        for (i in 1..ahead) {
            val next = start + i.toLong() * windowBytes
            if (total >= 0 && next >= total) break
            request(next)
        }
        val offset = (position - window.start).toInt()
        if (offset < 0 || offset >= window.bytes.size) return -1
        val taken = minOf(count, window.bytes.size - offset)
        System.arraycopy(window.bytes, offset, into, 0, taken)
        position += taken
        return taken
    }

    private fun request(start: Long): CompletableFuture<Fetched?> {
        synchronized(windows) {
            // Closed: nothing new goes out (a read racing close() would otherwise refill the map).
            if (closed) return CompletableFuture.completedFuture(null)
            windows[start]?.let { return it }
            val made = fetch(url, headers, start, start + windowBytes - 1)
            windows[start] = made
            // Bounded: windows already read past go first, then the furthest from where reading is.
            while (windows.size > ahead + 2) {
                val here = position - position % windowBytes
                val others = windows.keys.filter { it != start && it != here }
                val drop = others.filter { it < here }.minOrNull()
                    ?: others.maxByOrNull { kotlin.math.abs(it - here) }
                    ?: break
                windows.remove(drop)?.cancel(true)
            }
            return made
        }
    }

    /** How many windows are held or in flight; for tests. */
    internal fun held(): Int = synchronized(windows) { windows.size }

    fun close() {
        closed = true
        synchronized(windows) {
            windows.values.forEach { it.cancel(true) }
            windows.clear()
        }
    }

    companion object {
        /** The phone's chunk for the picture (MusicVideoEngine.CHUNK_BYTES). */
        const val WINDOW_BYTES = 1024 * 1024

        /** Three windows: two to six seconds of a 1080p picture, about one of a 4K one. */
        const val AHEAD = 3

        private const val TIMEOUT_S = 25L
        private val REFUSALS = setOf(403, 404, 410)

        private val client: HttpClient by lazy {
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build()
        }

        private fun httpFetch(url: String, headers: Map<String, String>, start: Long, end: Long): CompletableFuture<Fetched?> {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Range", "bytes=$start-$end")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> runCatching { request.header(name, value) } }
            return client.sendAsync(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply { response ->
                    val status = response.statusCode()
                    if (status !in 200..299) return@thenApply Fetched(start, ByteArray(0), -1, status)
                    val stated = response.headers().firstValue("content-range").orElse(null)
                    if (stated != null) {
                        Fetched(
                            start = stated.substringAfter("bytes ").substringBefore('-').trim().toLongOrNull() ?: start,
                            bytes = response.body(),
                            length = stated.substringAfterLast('/').trim().toLongOrNull() ?: -1,
                            status = status,
                        )
                    } else {
                        Fetched(0, response.body(), response.body().size.toLong(), status)
                    }
                }
                .exceptionally { null }
        }
    }
}
