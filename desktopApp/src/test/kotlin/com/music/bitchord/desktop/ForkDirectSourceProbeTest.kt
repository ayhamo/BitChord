package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: the direct-source length probe sends no cookies and judges each redirect (audit 2026-11-26).
 * Its `HttpURLConnection` took the JVM's default `CookieHandler` (the PC's Google/YouTube session).
 */
class ForkDirectSourceProbeTest {

    /** A handler that offers a session cookie for every address, as `DesktopWebCookies` would for Google's. */
    private object EveryCookie : CookieHandler() {
        override fun get(uri: URI, requestHeaders: MutableMap<String, MutableList<String>>): MutableMap<String, MutableList<String>> =
            mutableMapOf("Cookie" to mutableListOf("SID=secret"))
        override fun put(uri: URI, responseHeaders: MutableMap<String, MutableList<String>>) = Unit
    }

    @Test
    fun `no cookie reaches the probed host or its redirect`() {
        val cookies = Collections.synchronizedList(ArrayList<String?>())
        val asked = Collections.synchronizedList(ArrayList<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            asked += ex.requestURI.path
            cookies += ex.requestHeaders.getFirst("Cookie")
            when (ex.requestURI.path) {
                "/a" -> { ex.responseHeaders.add("Location", "/b"); ex.sendResponseHeaders(302, -1) }
                "/away" -> { ex.responseHeaders.add("Location", "http://example.invalid/x"); ex.sendResponseHeaders(302, -1) }
                else -> { ex.responseHeaders.add("Content-Range", "bytes 0-0/12345"); ex.sendResponseHeaders(206, 1); ex.responseBody.write(0) }
            }
            ex.close()
        }
        server.start()
        val before = CookieHandler.getDefault()
        CookieHandler.setDefault(EveryCookie)
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(12345L, ForkDirectSourceCheck.bytesAt("$base/a", mapOf("X-Test" to "1")))
            assertEquals(listOf("/a", "/b"), asked.toList())
            assertTrue(cookies.all { it == null }, "cookies sent: $cookies")
            // a cleartext hop off the local addon's own host is never asked
            assertNull(ForkDirectSourceCheck.bytesAt("$base/away", emptyMap()))
        } finally {
            CookieHandler.setDefault(before)
            server.stop(0)
        }
    }

    @Test
    fun `a redirect must be https and not this machine`() {
        val remote = "https://cdn.example.com/a.flac".toHttpUrl()
        assertTrue(ForkDirectSourceCheck.hopAllowed(remote, "https://other.example.net/b.flac".toHttpUrl()))
        assertFalse(ForkDirectSourceCheck.hopAllowed(remote, "http://other.example.net/b.flac".toHttpUrl()))
        assertFalse(ForkDirectSourceCheck.hopAllowed(remote, "https://localhost/b".toHttpUrl()))
        assertFalse(ForkDirectSourceCheck.hopAllowed(remote, "https://127.0.0.2/b".toHttpUrl()))
        assertFalse(ForkDirectSourceCheck.hopAllowed(remote, "https://[::1]/b".toHttpUrl()))
        // a local addon's link may move on its own host only
        val local = "http://127.0.0.1:8080/a".toHttpUrl()
        assertTrue(ForkDirectSourceCheck.hopAllowed(local, "http://127.0.0.1:8080/b".toHttpUrl()))
        assertFalse(ForkDirectSourceCheck.hopAllowed(local, "http://127.0.0.1:9090/b".toHttpUrl()))
    }
}
