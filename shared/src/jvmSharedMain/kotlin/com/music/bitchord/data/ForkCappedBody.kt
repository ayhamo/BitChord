package com.music.bitchord.data

import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Fork: an HTTP answer read to at most a set size, for the fork's own lookups (the Spotify web
 * player's page and token, the canvas providers, the PC's clip playlists).
 *
 * `body.string()` and `BodyHandlers.ofString()` read whatever the server sends into memory, so a
 * wrong redirect, a captive portal or a hostile host could hand a small JSON lookup a gigabyte and
 * take the app down with it. Each caller states what a real answer can weigh (a few KB of JSON, a
 * ~1 MB web player page) with room to spare, and anything past that is an [IOException]: the
 * failure path every one of those callers already has, so an oversized answer is retried later
 * rather than cached. An empty body is an [IOException] too: none of these endpoints answers a
 * success with nothing, and treating it as "no result" would cache a network hiccup as a miss.
 */
object ForkCappedBody {

    const val KB = 1024
    const val MB = 1024 * 1024

    /** [input] whole, or [IOException] when it is longer than [max] bytes or empty. */
    fun read(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * KB))
        val buffer = ByteArray(16 * KB)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("answer over ${max / KB} KB")
            out.write(buffer, 0, n)
        }
        if (total == 0L) throw IOException("empty answer")
        return out.toByteArray()
    }

    /** [read] as text in [charset]. */
    fun text(input: InputStream, max: Int, charset: Charset = Charsets.UTF_8): String = String(read(input, max), charset)

    /** An OkHttp answer's body, capped; a declared length past [max] is refused before reading. */
    fun bytes(response: Response, max: Int): ByteArray {
        val body = response.body
        if (body.contentLength() > max) throw IOException("answer over ${max / KB} KB")
        return body.byteStream().use { read(it, max) }
    }

    /** [bytes] as text, in the answer's declared charset (UTF-8 when none). */
    fun text(response: Response, max: Int): String =
        String(bytes(response, max), response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}
