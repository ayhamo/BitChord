package com.music.bitchord.fork

import com.music.bitchord.data.canvas.ForkCanvasLookup
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fork: how long the phone remembers a Spotify backdrop answer (ForkCanvas.spotifyFor). */
class ForkCanvasBackdropRetryTest {

    private val now = 1_000_000L

    @Test
    fun aFailedSearchIsAskedAgainAfterAMinute() {
        assertEquals(now + ForkCanvasLookup.RETRY_AFTER_MS, ForkCanvas.retryAt(found = false, failed = true, providerRetryAtMs = 0L, nowMs = now))
    }

    @Test
    fun aProviderInItsOwnBackoffHoldsTheSongUntilThen() {
        val later = now + 30L * 60 * 1000
        assertEquals(later, ForkCanvas.retryAt(found = false, failed = true, providerRetryAtMs = later, nowMs = now))
    }

    @Test
    fun aSettledMissIsHeldTenMinutes() {
        assertEquals(now + ForkCanvas.MISS_MS, ForkCanvas.retryAt(found = false, failed = false, providerRetryAtMs = 0L, nowMs = now))
    }

    @Test
    fun aFoundClipIsKept() {
        assertEquals(Long.MAX_VALUE, ForkCanvas.retryAt(found = true, failed = true, providerRetryAtMs = 0L, nowMs = now))
    }
}
