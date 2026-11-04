package com.music.bitchord.desktop

import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkVideoRangeReaderTest {
    private val file = ByteArray(10_000) { (it % 251).toByte() }

    private class Server(val file: ByteArray, val status: (Long) -> Int = { 206 }) {
        val asked = mutableListOf<Long>()
        fun fetch(url: String, headers: Map<String, String>, start: Long, end: Long): CompletableFuture<ForkVideoRangeReader.Fetched?> {
            synchronized(asked) { asked += start }
            val code = status(start)
            if (code !in 200..299) return CompletableFuture.completedFuture(ForkVideoRangeReader.Fetched(start, ByteArray(0), -1, code))
            val last = minOf(end, file.size - 1L)
            val bytes = file.copyOfRange(start.toInt(), last.toInt() + 1)
            return CompletableFuture.completedFuture(ForkVideoRangeReader.Fetched(start, bytes, file.size.toLong()))
        }
    }

    @Test
    fun `reads the whole file in windows, asking for the next ones ahead`() {
        val server = Server(file)
        val reader = ForkVideoRangeReader("u", emptyMap(), windowBytes = 1_000, ahead = 3, fetch = server::fetch)
        val out = ByteArray(file.size)
        var at = 0
        val chunk = ByteArray(700)
        while (true) {
            val n = reader.read(chunk, chunk.size)
            if (n < 0) break
            System.arraycopy(chunk, 0, out, at, n)
            at += n
        }
        assertEquals(file.size, at)
        assertContentEquals(file, out)
        assertEquals(10_000, reader.length())
        // The first read already had windows 1..3 asked for.
        assertTrue(server.asked.take(4) == listOf(0L, 1_000L, 2_000L, 3_000L))
        // Each window asked once, and only a handful held at a time.
        assertEquals(server.asked.size, server.asked.toSet().size)
        assertTrue(reader.held() <= 5)
    }

    @Test
    fun `a seek reads from the new place`() {
        val reader = ForkVideoRangeReader("u", emptyMap(), windowBytes = 1_000, ahead = 2, fetch = Server(file)::fetch)
        reader.seek(7_500)
        val chunk = ByteArray(10)
        assertEquals(10, reader.read(chunk, 10))
        assertContentEquals(file.copyOfRange(7_500, 7_510), chunk)
        assertEquals(7_510, reader.position())
    }

    @Test
    fun `a refusal is recorded for the next client to be tried`() {
        val reader = ForkVideoRangeReader("u", emptyMap(), windowBytes = 1_000, ahead = 1, fetch = Server(file) { 403 }::fetch)
        assertNull(reader.refusedWith)
        assertEquals(-1, reader.read(ByteArray(10), 10))
        assertEquals(403, reader.refusedWith)
    }

    @Test
    fun `one failed window is asked again before the read gives up`() {
        var failures = 1
        val server = Server(file) { start -> if (start == 0L && failures-- > 0) 500 else 206 }
        val reader = ForkVideoRangeReader("u", emptyMap(), windowBytes = 1_000, ahead = 1, fetch = server::fetch)
        assertEquals(10, reader.read(ByteArray(10), 10))
        assertNull(reader.refusedWith)
    }
}
