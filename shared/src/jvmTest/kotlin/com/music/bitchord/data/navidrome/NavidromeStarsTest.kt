package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.LikeStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [NavidromeStars]: quick taps on one song reach the server in order, and a full listing replaces stale hearts. */
class NavidromeStarsTest {

    private val id = NavidromeSettings.ID_PREFIX + "s1"

    @Test
    fun `quick taps are sent in order, the newest wins and a superseded one is not sent`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sent = Collections.synchronizedList(mutableListOf<LikeStatus>())
        val firstRunning = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val rolledBack = Collections.synchronizedList(mutableListOf<LikeStatus>())
        val taps = NavidromeStars.Taps { _, status ->
            sent += status
            if (sent.size == 1) {
                firstRunning.complete(Unit)
                releaseFirst.await()
            }
            Result.success(Unit)
        }
        try {
            taps.tap(id, LikeStatus.LIKE, LikeStatus.INDIFFERENT, scope) { rolledBack += it }
            withTimeout(2_000) { firstRunning.await() }
            taps.tap(id, LikeStatus.INDIFFERENT, LikeStatus.LIKE, scope) { rolledBack += it } // superseded before its turn
            taps.tap(id, LikeStatus.LIKE, LikeStatus.INDIFFERENT, scope) { rolledBack += it }
            releaseFirst.complete(Unit)
            withTimeout(2_000) { while (taps.busy().isNotEmpty()) delay(10) }
            assertEquals(listOf(LikeStatus.LIKE, LikeStatus.LIKE), sent.toList())
            assertTrue(rolledBack.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an old tap's failure doesn't undo a newer tap, and the newest's rolls back to what the server has`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstRunning = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val rolledBack = Collections.synchronizedList(mutableListOf<LikeStatus>())
        val taps = NavidromeStars.Taps { _, _ ->
            calls++
            if (calls == 1) {
                firstRunning.complete(Unit)
                releaseFirst.await()
                Result.failure(java.io.IOException("refused"))
            } else {
                Result.failure(java.io.IOException("refused"))
            }
        }
        try {
            taps.tap(id, LikeStatus.LIKE, LikeStatus.INDIFFERENT, scope) { rolledBack += it }
            withTimeout(2_000) { firstRunning.await() }
            taps.tap(id, LikeStatus.INDIFFERENT, LikeStatus.LIKE, scope) { rolledBack += it }
            releaseFirst.complete(Unit)
            withTimeout(2_000) { while (taps.busy().isNotEmpty()) delay(10) }
            // One roll-back, the newest tap's, to the state before either tap (nothing reached the server).
            assertEquals(listOf(LikeStatus.INDIFFERENT), rolledBack.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a full listing drops hearts the server no longer has, except a song with a tap on its way`() {
        val other = NavidromeSettings.ID_PREFIX + "s2"
        val busy = NavidromeSettings.ID_PREFIX + "s3"
        val current = mapOf(
            "dQw4w9WgXcQ" to LikeStatus.LIKE, // a YouTube song: not the server's
            id to LikeStatus.LIKE, // unstarred in the web UI since
            busy to LikeStatus.INDIFFERENT, // unstarred here, the request still going
        )
        val merged = NavidromeStars.merged(current, starred = setOf(other, busy), busy = setOf(busy))
        assertEquals(mapOf("dQw4w9WgXcQ" to LikeStatus.LIKE, busy to LikeStatus.INDIFFERENT, other to LikeStatus.LIKE), merged)
    }
}
