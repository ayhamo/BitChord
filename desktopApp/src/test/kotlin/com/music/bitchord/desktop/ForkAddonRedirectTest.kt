package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Fork: an addon's search and /stream redirects are judged hop by hop (protocol.md §1 Redirects, 2026-11-27). */
class ForkAddonRedirectTest {

    @Test
    fun `a hop off https or to another place is refused, a local addon's own origin is followed`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/off") { ex -> ex.responseHeaders.add("Location", "http://example.invalid/x"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/same") { ex -> ex.responseHeaders.add("Location", "/ok"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/ok") { ex -> val b = "fine".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val client = OkHttpClient.Builder().addNetworkInterceptor(DesktopAddonClient.forkRedirectGuard).build()
            fun get(path: String) = client.newCall(Request.Builder().url("$base$path").build()).execute().use { it.body.string() }
            assertEquals("fine", get("/same"))
            val e = assertFailsWith<IOException> { get("/off") }
            assertTrue("redirect" in e.message.orEmpty(), e.message)
        } finally {
            server.stop(0)
        }
    }
}
