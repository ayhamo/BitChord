package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: the PC's head probe of an addon's file row (ForkNextRowPc.head) follows redirects by hand,
 * each hop https and not this machine (combined audit 2026-11-26).
 */
class ForkNextRowPcHeadTest {

    @Test
    fun `a redirect to http or to this machine is not followed`() = runBlocking {
        val local = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val port = server.address.port
        server.createContext("/to-http") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/local")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/to-loopback") { exchange ->
            exchange.responseHeaders.add("Location", "https://127.1:$port/local")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/local") { exchange ->
            local.incrementAndGet()
            val body = "#EXTM3U\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            assertTrue(assertNotNull(ForkNextRowPc.head(DesktopStream(url = "http://127.0.0.1:$port/to-http"))).blocked)
            assertTrue(assertNotNull(ForkNextRowPc.head(DesktopStream(url = "http://127.0.0.1:$port/to-loopback"))).blocked)
            assertEquals(0, local.get(), "no redirect target was asked")
            // The addon's own link still reads, http included.
            val head = assertNotNull(ForkNextRowPc.head(DesktopStream(url = "http://127.0.0.1:$port/local")))
            assertContentEquals("#EXTM3U\n".toByteArray(), head.bytes)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `only https hops off this machine are followed`() {
        assertTrue(ForkNextRowPc.redirectAllowed("https://cdn.example/a.flac"))
        assertFalse(ForkNextRowPc.redirectAllowed("http://cdn.example/a.flac"))
        assertFalse(ForkNextRowPc.redirectAllowed("https://localhost/a.flac"))
        assertFalse(ForkNextRowPc.redirectAllowed("https://2130706433/a.flac"))
        assertFalse(ForkNextRowPc.redirectAllowed("https://[::ffff:127.0.0.1]/a.flac"))
    }
}
