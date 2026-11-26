package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ForkRangeWindow]: a window's deadline covers its body, and only a window stating the file's `clen` is kept. */
class ForkRangeWindowTest {

    @Test
    fun aWindowWhoseBodyStallsFailsAtTheDeadline() {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/media") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-1023/4096")
            exchange.sendResponseHeaders(206, 1024)
            exchange.responseBody.write(ByteArray(100))
            exchange.responseBody.flush()
            release.await(10, TimeUnit.SECONDS) // the headers and a little body, then nothing
            runCatching { exchange.close() }
        }
        server.start()
        val before = ForkRangeWindow.deadlineMs
        ForkRangeWindow.deadlineMs = 500
        try {
            val stream = DesktopRangeStream("http://127.0.0.1:${server.address.port}/media", windowBytes = 1024)
            val started = System.nanoTime()
            assertEquals(-1, stream.read(ByteArray(16), 16))
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "failed at the deadline, not when the socket died")
        } finally {
            ForkRangeWindow.deadlineMs = before
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun aWindowIsKeptOnlyWhenItsTotalIsTheFilesClen() {
        val url = "https://rr1.googlevideo.com/videoplayback?id=x&itag=251&lmt=1&clen=4096"
        assertTrue(ForkRangeWindow.cacheable(url, 0, 0, 4096, 1024))
        assertFalse(ForkRangeWindow.cacheable(url, 0, 0, 2048, 1024), "a total that isn't clen")
        assertFalse(ForkRangeWindow.cacheable(url, 1024, 0, 4096, 1024), "a window from elsewhere")
        assertFalse(ForkRangeWindow.cacheable(url, 3584, 3584, 4096, 1024), "bytes past the stated end")
        assertFalse(ForkRangeWindow.cacheable(url, 0, 0, 4096, 0), "no bytes")
        assertTrue(ForkRangeWindow.cacheable("https://example.com/a.opus", 0, 0, 2048, 1024), "no clen to hold it to")
        assertEquals(4096L, ForkAudioRangeCache.clenOf(url))
    }
}
