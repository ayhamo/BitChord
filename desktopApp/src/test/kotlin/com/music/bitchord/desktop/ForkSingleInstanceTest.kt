package com.music.bitchord.desktop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForkSingleInstanceTest {

    @Test
    fun `a second start hands over to the first and the first is asked to show`() {
        val dir = Files.createTempDirectory("bitchord-instance")
        val shown = CountDownLatch(1)
        val first = ForkSingleInstance.claim(dir, onShow = { shown.countDown() })
        assertIs<ForkSingleInstance.Claim.Primary>(first)
        try {
            val second = ForkSingleInstance.claim(dir, onShow = { error("the second never listens") }, waitMs = 2_000)
            assertEquals(ForkSingleInstance.Claim.Secondary(handedOver = true), second)
            assertTrue(shown.await(2, TimeUnit.SECONDS))
            // The port file names the loopback port the first one listens on.
            assertTrue(Files.readString(dir.resolve("instance.port")).startsWith("${first.port} "))
        } finally {
            first.close()
        }
    }

    @Test
    fun `the folder is free again once the first one is gone`() {
        val dir = Files.createTempDirectory("bitchord-instance")
        val first = ForkSingleInstance.claim(dir, onShow = {})
        assertIs<ForkSingleInstance.Claim.Primary>(first)
        first.close()
        val again = ForkSingleInstance.claim(dir, onShow = {})
        assertIs<ForkSingleInstance.Claim.Primary>(again)
        again.close()
    }

    @Test
    fun `a wrong key is not answered`() {
        val dir = Files.createTempDirectory("bitchord-instance")
        val shows = AtomicInteger()
        val first = ForkSingleInstance.claim(dir, onShow = { shows.incrementAndGet() })
        assertIs<ForkSingleInstance.Claim.Primary>(first)
        try {
            // A port file from someone who doesn't know the key: the right port, the wrong key.
            val port = first.port
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("SHOW not-the-key\n".toByteArray())
                socket.getOutputStream().flush()
                socket.soTimeout = 2_000
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(0, shows.get())
        } finally {
            first.close()
        }
    }

    @Test
    fun `an owner that never answers is not handed over to`() {
        val dir = Files.createTempDirectory("bitchord-instance")
        val first = ForkSingleInstance.claim(dir, onShow = {})
        assertIs<ForkSingleInstance.Claim.Primary>(first)
        try {
            // A stale port file, as a crashed owner's or one still starting would leave.
            Files.writeString(dir.resolve("instance.port"), "1 deadbeef")
            val second = ForkSingleInstance.claim(dir, onShow = {}, waitMs = 400)
            assertIs<ForkSingleInstance.Claim.Secondary>(second)
            assertFalse(second.handedOver)
        } finally {
            first.close()
        }
    }

    @Test
    fun `separate data folders each get their own instance`() {
        val a = ForkSingleInstance.claim(Files.createTempDirectory("bitchord-a"), onShow = {})
        val b = ForkSingleInstance.claim(Files.createTempDirectory("bitchord-b"), onShow = {})
        try {
            assertIs<ForkSingleInstance.Claim.Primary>(a)
            assertIs<ForkSingleInstance.Claim.Primary>(b)
        } finally {
            (a as? AutoCloseable)?.close()
            (b as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `a line is read to its newline and refused past the cap`() {
        fun read(text: String, max: Int = 16) = ForkSingleInstance.readLine(text.byteInputStream(), max)
        assertEquals("SHOW abc", read("SHOW abc\r\nmore"))
        assertEquals("SHOW abc", read("SHOW abc"))
        assertEquals(null, read("x".repeat(17) + "\n"))
        assertEquals("x".repeat(16), read("x".repeat(16) + "\n"))
    }
}
