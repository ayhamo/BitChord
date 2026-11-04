package com.music.bitchord.desktop

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork: the decode-ahead's lifecycle behind the mid-song recovery (from Desktop Revived 700e0d16 / 418d328d). */
class ForkDecodeRecoveryTest {

    private class Source(private val blocks: Int, private val failAt: Int? = null) : DesktopSampleSource {
        @Volatile var next = 0
        @Volatile var stall: CountDownLatch? = null
        private val buffer = FloatArray(4)
        override var sampleCount = 0
            private set

        override fun readSamples(): FloatArray? {
            stall?.await()
            if (failAt != null && next >= failAt) throw java.io.IOException("connection reset")
            if (next >= blocks) return null
            buffer.fill(next.toFloat())
            next++
            sampleCount = buffer.size
            return buffer
        }

        override fun seek(micros: Long): Boolean = true
        override fun close() {}
    }

    private fun ahead(source: DesktopSampleSource) = DesktopDecodeAhead(
        source = source,
        outputFormat = DesktopPcmFormat(44_100, 2, 4, isFloat = true),
        durationUs = null,
        measuredFormat = null,
        aheadSamples = 16,
    )

    @Test
    fun aDecoderThatThrowsIsFailedNotEnded() {
        val reader = ahead(Source(blocks = 100, failAt = 3))
        repeat(3) { reader.readSamples() }
        assertNull(reader.readSamples())
        assertEquals(ForkDecodeState.FAILED, reader.forkState)
        assertEquals("connection reset", reader.forkFailure?.message)
        assertFalse(reader.seek(0L))
        reader.close()
        assertEquals(ForkDecodeState.CLOSED, reader.forkState)
    }

    @Test
    fun theRealEndIsEof() {
        val reader = ahead(Source(blocks = 2))
        reader.readSamples()
        reader.readSamples()
        assertNull(reader.readSamples())
        assertEquals(ForkDecodeState.EOF, reader.forkState)
        reader.close()
    }

    @Test
    fun aTimedReadGivesControlBackAndCountsTheStall() {
        val source = Source(blocks = 100).apply { stall = CountDownLatch(1) }
        val reader = ahead(source)
        val from = System.nanoTime()
        assertNull(reader.readSamples(50))
        assertTrue((System.nanoTime() - from) / 1_000_000 < 2_000)
        assertEquals(ForkDecodeState.RUNNING, reader.forkState)
        Thread.sleep(30)
        assertNull(reader.readSamples(10))
        assertTrue(reader.forkUnderrunMs >= 30)
        source.stall?.countDown()
        val block = reader.readSamples(2_000)
        assertEquals(0f, block?.get(0))
        assertEquals(0L, reader.forkUnderrunMs)
        reader.close()
    }

    @Test
    fun aDeckNeverReopenedIsNotForgivenSoTheTriesRunOut() {
        // nanoTime is far past a minute after 0: the old rule handed a never-reopened deck its tries
        // back on every failure, an endless resolve loop with the network down.
        val now = 10_000_000_000_000L
        assertTrue(forkRecoveryAllowed(attempt = 1, recoveredAtNs = 0L, nowNs = now, limit = 2, forgiveNs = 60_000_000_000L))
        assertFalse(forkRecoveryAllowed(attempt = 2, recoveredAtNs = 0L, nowNs = now, limit = 2, forgiveNs = 60_000_000_000L))
    }

    @Test
    fun aMinuteOfPlayAfterAReopenEarnsTheTriesBack() {
        val minute = 60_000_000_000L
        assertFalse(forkRecoveryAllowed(attempt = 2, recoveredAtNs = 1_000L, nowNs = 1_000L + minute / 2, limit = 2, forgiveNs = minute))
        assertTrue(forkRecoveryAllowed(attempt = 2, recoveredAtNs = 1_000L, nowNs = 1_000L + minute + 1, limit = 2, forgiveNs = minute))
    }
}
