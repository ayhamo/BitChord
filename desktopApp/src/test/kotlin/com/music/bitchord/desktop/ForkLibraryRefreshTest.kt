package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: the PC Library's quiet focus refresh ([ForkLibraryRefresh]). */
class ForkLibraryRefreshTest {

    @Test
    fun `a quiet refresh over an error page shows loading and then the error`() {
        // It used to keep "what is shown" (the skeleton it had just put up) and never leave it.
        assertFalse(ForkLibraryRefresh.keepsRows(quiet = true, showingRows = false))
        assertTrue(ForkLibraryRefresh.keepsRows(quiet = true, showingRows = true))
        assertFalse(ForkLibraryRefresh.keepsRows(quiet = false, showingRows = true))
    }

    @Test
    fun `a quiet refresh walks Liked Music only when the last walk is hours old`() {
        val start = 10_000_000_000L
        assertTrue(ForkLibraryRefresh.likedWalkDue(quiet = false, now = start))
        assertFalse(ForkLibraryRefresh.likedWalkDue(quiet = true, now = start + 60_000))
        assertTrue(ForkLibraryRefresh.likedWalkDue(quiet = true, now = start + ForkLibraryRefresh.LIKED_WALK_GAP_MS))
    }
}
