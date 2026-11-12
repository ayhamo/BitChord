package com.music.bitchord.data.canvas

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * Fork: the harvest's backoff. Upstream re-ran the 20 s WebView harvest on every lookup that
 * found no live token, so a sign-in that can't mint (no network, the page changed) loaded the
 * whole web player again for every song, and a song the lookup left un-cached as failed
 * (ForkCanvasLookup) did so on each replay. A failed harvest now waits [BACKOFF_MS]
 * (the signed route is still tried, on its own 10 min backoff); keyed to the cookie and cleared
 * by `SpotifyToken.invalidate`, so a new sign-in is harvested at once. A harvest cut short by the lookup
 * being cancelled (a skip) is not a failure.
 *
 * Moved out of `SpotifyToken` unchanged (was its private `forkHarvest…` fields and functions).
 */
internal class ForkSpotifyHarvestBackoff {
    @Volatile private var failedFor = 0
    @Volatile var retryAfterMs = 0L
        private set

    fun backingOff(cookie: String, now: Long): Boolean =
        failedFor == cookie.hashCode() && now < retryAfterMs

    /** When the harvest for [cookie] may run again, or 0 when it isn't backing off (for `ForkCanvasLookup.coolingDown`). */
    fun retryAt(cookie: String): Long =
        if (backingOff(cookie, System.currentTimeMillis())) retryAfterMs else 0L

    suspend fun failed(cookie: String) {
        if (currentCoroutineContext()[Job]?.isActive == false) return
        failedFor = cookie.hashCode()
        retryAfterMs = System.currentTimeMillis() + BACKOFF_MS
    }

    /** A new sign-in is harvested at once. */
    fun clear() {
        retryAfterMs = 0L
    }

    private companion object {
        const val BACKOFF_MS = 5L * 60 * 1000
    }
}
