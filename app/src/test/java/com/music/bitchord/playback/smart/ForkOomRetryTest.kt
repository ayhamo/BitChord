package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkOomRetryTest {

    private var now = 1_000L
    private val retry = ForkOomRetry(maxAttempts = 3, backoffMs = 30_000L) { now }

    @Test
    fun anOomWaitsThenRetriesUntilTheCap() {
        assertTrue(retry.mayRun("a"))

        assertTrue("first OOM: retried later", retry.retryLater("a"))
        assertFalse("not on the next tick", retry.mayRun("a"))
        now += 29_999
        assertFalse(retry.mayRun("a"))
        now += 1
        assertTrue("after the wait", retry.mayRun("a"))

        assertTrue("second OOM: retried later", retry.retryLater("a"))
        assertFalse(retry.mayRun("a"))
        now += 30_000

        assertFalse("third OOM: written off as before", retry.retryLater("a"))
        // A write-off is the caller's record from here; the gate no longer holds it back,
        // so a new copy that reopens the track is not delayed.
        assertTrue(retry.mayRun("a"))
    }

    @Test
    fun tracksAreCountedApartAndResetStartsLevel() {
        retry.retryLater("a")
        assertTrue(retry.mayRun("b"))
        retry.reset("a")
        assertTrue(retry.mayRun("a"))
        assertTrue(retry.retryLater("a"))
        assertTrue(retry.retryLater("a"))
        assertFalse(retry.retryLater("a"))
    }

    @Test
    fun onlyAnOutOfMemoryErrorCounts() {
        assertTrue(ForkOomRetry.isOom(OutOfMemoryError("Failed to allocate a 51822352 byte allocation")))
        assertTrue(ForkOomRetry.isOom(RuntimeException("wrapped", OutOfMemoryError())))
        assertFalse(ForkOomRetry.isOom(IllegalStateException("codec")))
        assertFalse(ForkOomRetry.isOom(StackOverflowError()))
    }
}
