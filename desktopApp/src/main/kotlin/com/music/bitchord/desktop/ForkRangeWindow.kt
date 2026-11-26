package com.music.bitchord.desktop

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fork: two guards on [DesktopRangeStream]'s window fetch.
 *
 * **A deadline on the whole window** ([send]). The request's `timeout(20 s)` covers the response
 * headers only (the JDK's HttpRequest timeout); a server that sent its headers and then stalled
 * mid-body held the decoder's read forever: no failure reached the decoder, so neither the stall
 * watchdog's reopen nor a recovery could replace the deck's source until the socket died. Now the
 * window as a whole has [deadlineMs]; past it the exchange is cancelled and the read fails as any
 * failed fetch does.
 *
 * **A window kept on disk only when its stated total is the file's** ([cacheable]). The on-disk
 * window cache ([ForkAudioRangeCache]) keys a window by googlevideo's `clen` among others and hands
 * back the total stored with it as the stream's length. A range answer whose `Content-Range` total
 * disagreed with `clen` (a proxy's, a truncated answer's, `*` read as the window's end) was kept with
 * that total for a week and cut every later read of the song at the wrong length.
 */
internal object ForkRangeWindow {

    /** The longest one window may take, headers and body. */
    @Volatile
    internal var deadlineMs: Long = 30_000L

    /** [request] sent with [client], its body read within [deadlineMs]; throws on a timeout (the exchange cancelled). */
    fun send(client: HttpClient, request: HttpRequest): HttpResponse<ByteArray> {
        val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
        return try {
            pending.get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.cancel(true)
            throw e
        }
    }

    /**
     * Whether a range answer for the window at [requestedStart] may be kept: it starts there
     * ([statedStart]), holds bytes, ends inside [statedTotal], and that total is the file's `clen`
     * when [url] names one.
     */
    fun cacheable(url: String, requestedStart: Long, statedStart: Long, statedTotal: Long, bodySize: Int): Boolean {
        if (statedStart != requestedStart || bodySize <= 0 || statedTotal < requestedStart + bodySize) return false
        val clen = ForkAudioRangeCache.clenOf(url) ?: return true
        return statedTotal == clen
    }
}
