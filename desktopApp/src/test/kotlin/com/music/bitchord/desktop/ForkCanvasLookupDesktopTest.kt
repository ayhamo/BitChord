package com.music.bitchord.desktop

import com.music.bitchord.data.canvas.ForkCanvasLookup
import com.music.bitchord.data.canvas.ForkCanvasLookup.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: what `DesktopCanvasClient.resolve` caches (shared ForkCanvasLookup.kt), with the PC's keys
 * (`song|<id>`, no Spotify-priority suffix) and its blocking providers.
 */
class ForkCanvasLookupDesktopTest {

    /** A provider the way the PC's are: plain blocking code giving up with `?: return failed(...)`. */
    private fun refusingProvider(): DesktopCanvasArtwork? = ForkCanvasLookup.failed("Tidal: no answer")

    private fun answeringProvider(): DesktopCanvasArtwork? = null

    @Test
    fun `a provider failing inside the lookup is not cached as no clip`() = runBlocking {
        val scope = ForkCanvasLookup.Scope()
        val found = withContext(Dispatchers.IO + scope.context) { answeringProvider() ?: refusingProvider() }
        assertNull(found)
        assertEquals(Outcome.FAILED, ForkCanvasLookup.outcome(found = false, failure = scope.failure))
        assertFalse(ForkCanvasLookup.keeps("song|pc-failed", withAlbum = true, found = false, scope = scope))
        assertTrue(ForkCanvasLookup.skips("song|pc-failed", withAlbum = true))
    }

    @Test
    fun `every provider answering empty is a cached miss`() = runBlocking {
        val scope = ForkCanvasLookup.Scope()
        withContext(Dispatchers.IO + scope.context) { answeringProvider() }
        assertTrue(ForkCanvasLookup.keeps("song|pc-empty", withAlbum = true, found = false, scope = scope))
        assertFalse(ForkCanvasLookup.skips("song|pc-empty", withAlbum = true))
    }

    @Test
    fun `a later find wins over an earlier failure`() = runBlocking {
        val scope = ForkCanvasLookup.Scope()
        val clip = DesktopCanvasArtwork(url = "https://example.invalid/clip.mp4")
        val found = withContext(Dispatchers.IO + scope.context) { refusingProvider() ?: clip }
        assertEquals(clip, found)
        assertTrue(ForkCanvasLookup.keeps("song|pc-found", withAlbum = true, found = true, scope = scope))
    }

    @Test
    fun `the window lets a failed song be asked again after a minute`() {
        var now = 0L
        val notes = ForkCanvasLookup.FailedNotes(now = { now })
        notes.record("song|x", withAlbum = true, outcome = Outcome.FAILED)
        now = ForkCanvasLookup.RETRY_AFTER_MS - 1
        assertTrue(notes.recentlyFailed("song|x", withAlbum = true))
        now = ForkCanvasLookup.RETRY_AFTER_MS
        assertFalse(notes.recentlyFailed("song|x", withAlbum = true))
    }
}
