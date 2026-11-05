package com.music.bitchord.data.canvas

/**
 * Fork: when the community canvas index (phone `CommunityCanvas`, PC `DesktopCommunityCanvas`) is
 * fetched again.
 *
 * Upstream re-read it after its 30-minute TTL, but only while it held entries: with none (the host
 * down, or an answer that parsed to nothing) every lookup fetched it again, so a queue skipping
 * through songs sent one request per song to a host that was not answering. An empty index now
 * waits [EMPTY_RETRY_MS] after the last try; the lookup reports that wait to [ForkCanvasLookup] as
 * a cool-down, so the songs are asked again once it ends rather than cached as "no clip".
 */
object ForkCommunityManifest {

    const val EMPTY_RETRY_MS = 5L * 60 * 1000

    /** Whether to fetch the index now, given what is held and when it was last tried (0 = never). */
    fun due(hasEntries: Boolean, fetchedAtMs: Long, nowMs: Long, ttlMs: Long): Boolean {
        if (fetchedAtMs == 0L) return true
        val age = nowMs - fetchedAtMs
        return if (hasEntries) age >= ttlMs else age >= EMPTY_RETRY_MS
    }

    /** When an empty index will be tried again. */
    fun retryAt(fetchedAtMs: Long): Long = fetchedAtMs + EMPTY_RETRY_MS
}
