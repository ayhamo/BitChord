package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The download queue's bookkeeping.
 *
 * The transfer itself is not exercised — that reaches the network and writes to the listener's own
 * Music folder — so these pin the part that decides what is queued, in what order, and what a
 * cancel does to it.
 */
class DesktopDownloadQueueTest {

    private fun song(id: String) = Song(videoId = id, title = "Track $id", artist = "Artist", thumbnailUrl = "")

    private val realTransfer = DesktopDownloadQueue.transfer

    @AfterTest
    fun tearDown() {
        DesktopDownloadQueue.cancelAll()
        DesktopDownloadQueue.transfer = realTransfer
    }

    @Test
    fun `cancelling running tracks leaves the rest of the queue to the workers`() = runBlocking {
        // Every worker busy with a track that never ends; cancelling them used to cancel the
        // workers themselves, and the tracks behind them stayed queued for good.
        DesktopDownloadQueue.transfer = { song, _ ->
            if (song.videoId.startsWith("stuck")) awaitCancellation() else Result.success(song)
        }
        val stuck = (0 until DesktopDownloadQueue.WORKERS).map { "stuck$it" }
        stuck.forEach { DesktopDownloadQueue.enqueue(song(it)) }
        listOf("after1", "after2").forEach { DesktopDownloadQueue.enqueue(song(it)) }
        withTimeout(5_000) {
            DesktopDownloadQueue.active.first { rows -> stuck.all { rows[it]?.state is DesktopDownloadState.Running } }
        }
        stuck.forEach(DesktopDownloadQueue::cancel)
        withTimeout(5_000) { DesktopDownloadQueue.active.first { it.isEmpty() } }
        Unit
    }

    @Test
    fun `a track is queued once however many times it is asked for`() {
        DesktopDownloadQueue.enqueue(song("a"))
        DesktopDownloadQueue.enqueue(song("a"))
        assertEquals(1, DesktopDownloadQueue.active.value.count { it.key == "a" })
    }

    @Test
    fun `the queue keeps the order it was asked in`() {
        listOf("a", "b", "c").forEach { DesktopDownloadQueue.enqueue(song(it)) }
        val order = DesktopDownloadQueue.active.value.values
            .sortedBy(DesktopDownloadItem::sequence)
            .map { it.song.videoId }
        assertEquals(listOf("a", "b", "c"), order.take(3))
    }

    @Test
    fun `a cancelled track leaves the manager rather than sitting in it`() {
        DesktopDownloadQueue.enqueue(song("gone"))
        assertTrue("gone" in DesktopDownloadQueue.active.value)
        DesktopDownloadQueue.cancel("gone")
        assertFalse("gone" in DesktopDownloadQueue.active.value)
    }

    @Test
    fun `cancel all empties the queue`() {
        listOf("a", "b", "c").forEach { DesktopDownloadQueue.enqueue(song(it)) }
        DesktopDownloadQueue.cancelAll()
        assertTrue(DesktopDownloadQueue.active.value.isEmpty())
    }

    @Test
    fun `a blank id is not something that can be downloaded`() {
        DesktopDownloadQueue.enqueue(song(""))
        assertFalse("" in DesktopDownloadQueue.active.value)
    }

    @Test
    fun `the summary line says what the queue is doing`() {
        assertEquals("Nothing downloading", summary(total = 0, failed = 0, busy = false))
        assertEquals("1 track", summary(total = 1, failed = 0, busy = true))
        assertEquals("4 tracks", summary(total = 4, failed = 0, busy = true))
        assertEquals("1 download failed", summary(total = 1, failed = 1, busy = false))
        assertEquals("3 downloads failed", summary(total = 3, failed = 3, busy = false))
        // Still going, so the count is what matters rather than the failures behind it.
        assertEquals("5 tracks", summary(total = 5, failed = 1, busy = true))
    }

    /**
     * Desktop Revived 8bb8152d's race, as it reached this queue: a cancelled transfer still
     * reporting progress (a read finishing as the cancel lands) overwrote the row of the same track
     * asked for again, and its cancellation then took that new row out of the manager while the new
     * transfer ran.
     */
    @Test
    fun `a cancelled transfer cannot take over the row of the same track asked for again`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        DesktopDownloadQueue.transfer = { song, progress ->
            if (calls.incrementAndGet() == 1) {
                // The old transfer: it doesn't see its cancel until this read is reported.
                withContext(NonCancellable) {
                    gate.await()
                    progress(1, 2)
                }
                Result.success(song)
            } else {
                awaitCancellation()
            }
        }
        try {
            DesktopDownloadQueue.enqueue(song("again"))
            withTimeout(5_000) { DesktopDownloadQueue.active.first { it["again"]?.state is DesktopDownloadState.Running } }
            DesktopDownloadQueue.cancel("again")
            DesktopDownloadQueue.enqueue(song("again"))
            // Queued: the old transfer still holds its worker until it unwinds.
            val renewed = DesktopDownloadQueue.active.value.getValue("again").sequence
            // Every state the row passes through while the old transfer reports and ends, and the
            // worker it frees takes the new request.
            val seen = java.util.Collections.synchronizedList(mutableListOf<DesktopDownloadItem?>())
            val watch = launch(Dispatchers.Default) { DesktopDownloadQueue.active.collect { seen += it["again"] } }
            delay(100)
            gate.complete(Unit)
            withTimeout(5_000) { while (calls.get() < 2) delay(10) }
            delay(300)
            watch.cancel()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it?.sequence == renewed }, "the new request's row was replaced or removed: $seen")
            val row = DesktopDownloadQueue.active.value["again"]
            assertEquals(renewed, row?.sequence)
            assertEquals(DesktopDownloadState.Running(0f), row?.state)
        } finally {
            // Never leave the old transfer holding a worker for the tests after this one.
            gate.complete(Unit)
        }
    }

    /**
     * A track enqueued just as the queue drained: the last workers had found it empty but not yet
     * given their places back, so it started nobody and stayed Queued. Timing-dependent, so tried
     * many times; each round is a fresh drain.
     */
    @Test
    fun `a track enqueued as the queue drains is still downloaded`() = runBlocking {
        DesktopDownloadQueue.transfer = { song, _ -> Result.success(song) }
        repeat(300) { round ->
            val id = "drain$round"
            DesktopDownloadQueue.enqueue(song(id))
            withTimeout(5_000) { DesktopDownloadQueue.active.first { id !in it } }
        }
    }

    @Test
    fun `cancel all also stops what is running and leaves nothing behind`() = runBlocking {
        DesktopDownloadQueue.transfer = { _, _ -> awaitCancellation() }
        val ids = (0 until DesktopDownloadQueue.WORKERS + 2).map { "all$it" }
        ids.forEach { DesktopDownloadQueue.enqueue(song(it)) }
        withTimeout(5_000) {
            DesktopDownloadQueue.active.first { rows -> rows.values.count { it.state is DesktopDownloadState.Running } == DesktopDownloadQueue.WORKERS }
        }
        DesktopDownloadQueue.cancelAll()
        assertTrue(DesktopDownloadQueue.active.value.isEmpty())
        // The workers come back free: a new track runs at once.
        DesktopDownloadQueue.transfer = { song, _ -> Result.success(song) }
        DesktopDownloadQueue.enqueue(song("after"))
        withTimeout(5_000) { DesktopDownloadQueue.active.first { "after" !in it } }
        Unit
    }

    @Test
    fun `four transfers run at once, matching Android`() {
        assertEquals(4, DesktopDownloadQueue.WORKERS)
    }
}
