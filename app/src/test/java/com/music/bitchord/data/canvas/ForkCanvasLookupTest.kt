package com.music.bitchord.data.canvas

import com.music.bitchord.data.canvas.ForkCanvasLookup.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork: what `CanvasRepository.resolve` caches (ForkCanvasLookup.kt). A failed lookup must not be
 * remembered as "no clip"; a found clip and a settled miss are cached as before.
 */
class ForkCanvasLookupTest {

    @Test
    fun `a find is cached even when an earlier provider failed`() {
        assertEquals(Outcome.FOUND, ForkCanvasLookup.outcome(found = true, failure = "Apple Music: http 503"))
        assertEquals(Outcome.FOUND, ForkCanvasLookup.outcome(found = true, failure = null))
    }

    @Test
    fun `nothing found is a settled miss only when every provider answered`() {
        assertEquals(Outcome.EMPTY, ForkCanvasLookup.outcome(found = false, failure = null))
        assertEquals(Outcome.FAILED, ForkCanvasLookup.outcome(found = false, failure = "Tidal: no answer"))
    }

    @Test
    fun `a failure is not cached and is left alone for a minute`() {
        var now = 1_000_000L
        val notes = ForkCanvasLookup.FailedNotes(windowMs = 60_000, now = { now })
        assertFalse(notes.record("song|a", withAlbum = true, outcome = Outcome.FAILED))
        assertTrue(notes.recentlyFailed("song|a", withAlbum = true))
        now += 59_999
        assertTrue(notes.recentlyFailed("song|a", withAlbum = true))
        now += 1
        assertFalse(notes.recentlyFailed("song|a", withAlbum = true))
    }

    @Test
    fun `a blind failure does not hold back the lookup that has the album`() {
        val notes = ForkCanvasLookup.FailedNotes(now = { 0L })
        notes.record("song|b", withAlbum = false, outcome = Outcome.FAILED)
        assertTrue(notes.recentlyFailed("song|b", withAlbum = false))
        assertFalse(notes.recentlyFailed("song|b", withAlbum = true))
        notes.record("song|c", withAlbum = true, outcome = Outcome.FAILED)
        assertTrue(notes.recentlyFailed("song|c", withAlbum = false))
    }

    @Test
    fun `an answer clears the note and is cached`() {
        val notes = ForkCanvasLookup.FailedNotes(now = { 0L })
        notes.record("song|d", withAlbum = true, outcome = Outcome.FAILED)
        assertTrue(notes.record("song|d", withAlbum = true, outcome = Outcome.EMPTY))
        assertFalse(notes.recentlyFailed("song|d", withAlbum = true))
        assertTrue(notes.record("song|d", withAlbum = true, outcome = Outcome.FOUND))
    }

    @Test
    fun `providers report into the lookup's scope across dispatcher hops`() = runBlocking {
        val scope = ForkCanvasLookup.Scope()
        val result = withContext(Dispatchers.IO + scope.context) {
            // The phone's token step hops threads (to Main for its WebView); Default stands in here.
            withContext(Dispatchers.Default) { ForkCanvasLookup.failed("Spotify: no token") }
        }
        assertNull(result)
        assertEquals("Spotify: no token", scope.failure)
        assertFalse(ForkCanvasLookup.keeps("song|test-scope", withAlbum = true, found = false, scope = scope))
        assertTrue(ForkCanvasLookup.skips("song|test-scope", withAlbum = true))
    }

    @Test
    fun `the first reason is kept and calls outside a lookup report nothing`() = runBlocking {
        ForkCanvasLookup.fail("outside any lookup")
        val scope = ForkCanvasLookup.Scope()
        withContext(Dispatchers.IO + scope.context) {
            ForkCanvasLookup.fail("Apple Music: http 500")
            ForkCanvasLookup.fail("Tidal: no answer")
        }
        assertEquals("Apple Music: http 500", scope.failure)
        val untouched = ForkCanvasLookup.Scope()
        withContext(Dispatchers.IO + untouched.context) { }
        assertNull(untouched.failure)
        assertTrue(ForkCanvasLookup.keeps("song|test-empty", withAlbum = true, found = false, scope = untouched))
    }

    @Test
    fun `two lookups running at once keep their own reports, and the thread is clean after`() = runBlocking {
        val failing = ForkCanvasLookup.Scope()
        val answering = ForkCanvasLookup.Scope()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val one = async(Dispatchers.IO + failing.context) {
            gate.await() // suspended while the other lookup runs on the same pool
            ForkCanvasLookup.fail("Tidal: no answer")
        }
        val two = async(Dispatchers.IO + answering.context) {
            withContext(Dispatchers.Default) { kotlinx.coroutines.yield() }
            gate.complete(Unit)
        }
        one.await()
        two.await()
        assertEquals("Tidal: no answer", failing.failure)
        assertNull(answering.failure)
        // Back outside both: nothing is left on this thread to report into.
        ForkCanvasLookup.fail("after the lookups")
        assertEquals("Tidal: no answer", failing.failure)
        assertNull(answering.failure)
    }

    @Test
    fun `a second opinion asked after an answer does not count as a failure`() = runBlocking {
        val scope = ForkCanvasLookup.Scope()
        val url = withContext(Dispatchers.IO + scope.context) {
            ForkCanvasLookup.answered { ForkCanvasLookup.failed("Spotify: canvaz-cache gave no answer") }
        }
        assertNull(url)
        assertNull(scope.failure)
    }

    @Test
    fun `a provider in its cool-down holds the song back until it can answer again`() = runBlocking {
        val scope = ForkCanvasLookup.Scope()
        withContext(Dispatchers.IO + scope.context) {
            ForkCanvasLookup.failed("Tidal: no answer")
            ForkCanvasLookup.coolingDown("Apple Music: no token", untilMs = 1_800_000L)
            ForkCanvasLookup.coolingDown("Spotify: token backing off", untilMs = 600_000L)
        }
        assertEquals("Tidal: no answer", scope.failure)
        assertEquals(1_800_000L, scope.retryAtMs)

        var now = 0L
        val notes = ForkCanvasLookup.FailedNotes(windowMs = 60_000, now = { now })
        assertFalse(notes.record("song|e", withAlbum = true, outcome = Outcome.FAILED, retryAtMs = scope.retryAtMs))
        now = 1_799_999L
        assertTrue(notes.recentlyFailed("song|e", withAlbum = true))
        now = 1_800_000L
        assertFalse(notes.recentlyFailed("song|e", withAlbum = true))

        // A cool-down already over (or shorter than the window) never shortens the minute.
        now = 0L
        notes.record("song|f", withAlbum = true, outcome = Outcome.FAILED, retryAtMs = 5_000L)
        now = 59_999L
        assertTrue(notes.recentlyFailed("song|f", withAlbum = true))
    }

    @Test
    fun `a cool-down inside a second opinion is not kept`() = runBlocking {
        val scope = ForkCanvasLookup.Scope()
        withContext(Dispatchers.IO + scope.context) {
            ForkCanvasLookup.answered { ForkCanvasLookup.coolingDown("Spotify: token backing off", 600_000L) }
        }
        assertNull(scope.failure)
        assertEquals(0L, scope.retryAtMs)
    }
}
