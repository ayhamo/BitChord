package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The PC's addon timeouts, as the phone's `AddonClient` sets them since 38535595: a 45 s ceiling
 * on the call and a 60 s read timeout above it, so a cold-caching addon's silence is waited out.
 */
class ForkAddonTimeoutsTest {

    @Test
    fun `the ceilings are the phone's`() {
        assertEquals(45_000L, DesktopAddonClient.CALL_TIMEOUT_MS)
        assertEquals(60_000L, DesktopAddonClient.READ_TIMEOUT_MS)
        assertEquals(20_000L, DesktopAddonClient.CONNECT_TIMEOUT_MS)
        // The read timeout must sit above the ceiling, or it fires first and the ceiling is never
        // reached.
        assertTrue(DesktopAddonClient.READ_TIMEOUT_MS > DesktopAddonClient.CALL_TIMEOUT_MS)
    }

    @Test
    fun `an addon silent for longer than OkHttp's default read timeout is still waited for`() = runBlocking {
        // 11.5 s of silence: past OkHttp's 10 s default, which applied while only the call ceiling
        // was set.
        withSilentServer(silenceMs = 11_500) { url ->
            val body = DesktopAddonClient.forkAddonHttp().get(url).bodyAsText()
            assertEquals("""{"tracks":[]}""", body)
        }
    }

    @Test
    fun `the call ceiling still ends an addon that never answers`() = runBlocking {
        withSilentServer(silenceMs = 5_000) { url ->
            val started = System.nanoTime()
            try {
                DesktopAddonClient.forkAddonHttp(callTimeoutMs = 1_000).get(url).bodyAsText()
                fail("a call past its ceiling must fail")
            } catch (expected: Exception) {
                val tookMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(tookMs < 4_000, "gave up after $tookMs ms, not at the 1 s ceiling: $expected")
            }
        }
    }

    private suspend fun withSilentServer(silenceMs: Long, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/search") { exchange ->
            try {
                Thread.sleep(silenceMs)
                val bytes = """{"tracks":[]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: Exception) {
                // The client hung up first: what the ceiling test wants.
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/search")
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }
}
