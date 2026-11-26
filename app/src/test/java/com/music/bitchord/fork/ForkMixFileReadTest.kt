package com.music.bitchord.fork

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Audit 2026-11-26 3b #1: a picked document is read only up to the mix-file cap, never whole. */
class ForkMixFileReadTest {

    @Test
    fun aMixFileIsReadWholeAndALargerFileStopsOneBytePastTheCap() {
        val small = "{\"mixes\":[]}".toByteArray()
        val read = ForkMixFileRead.bounded(ByteArrayInputStream(small), 16)
        assertTrue(read is ForkMixFileRead.Bytes)
        assertArrayEquals(small, (read as ForkMixFileRead.Bytes).bytes)
        // exactly the cap still reads
        assertTrue(ForkMixFileRead.bounded(ByteArrayInputStream(ByteArray(16)), 16) is ForkMixFileRead.Bytes)

        // an endless stream: refused after max + 1 bytes, the rest never pulled
        var pulled = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { pulled++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int { pulled += len; return len }
        }
        assertSame(ForkMixFileRead.Over, ForkMixFileRead.bounded(endless, 2 * 1024 * 1024))
        assertTrue("pulled $pulled", pulled <= 2 * 1024 * 1024 + 1)
    }
}
