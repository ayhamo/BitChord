package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkCommunityManifestTest {

    private val ttl = 30L * 60 * 1000

    @Test
    fun `never fetched is due`() {
        assertTrue(ForkCommunityManifest.due(hasEntries = false, fetchedAtMs = 0L, nowMs = 5_000L, ttlMs = ttl))
    }

    @Test
    fun `an empty index waits five minutes instead of refetching per lookup`() {
        val at = 1_000_000L
        assertFalse(ForkCommunityManifest.due(false, at, at + 1_000, ttl))
        assertFalse(ForkCommunityManifest.due(false, at, at + ForkCommunityManifest.EMPTY_RETRY_MS - 1, ttl))
        assertTrue(ForkCommunityManifest.due(false, at, at + ForkCommunityManifest.EMPTY_RETRY_MS, ttl))
        assertEquals(at + ForkCommunityManifest.EMPTY_RETRY_MS, ForkCommunityManifest.retryAt(at))
    }

    @Test
    fun `a filled index keeps its TTL`() {
        val at = 1_000_000L
        assertFalse(ForkCommunityManifest.due(true, at, at + ttl - 1, ttl))
        assertTrue(ForkCommunityManifest.due(true, at, at + ttl, ttl))
    }
}
