package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PAGE_GUTTER

/**
 * Fork: keeps the PC's Library (YouTube playlists, liked songs, artists) current without a restart.
 * Upstream fetched it once per sign-in and on the phone-only pull-to-refresh, so a playlist made in
 * YouTube Music, or on the phone, never showed until the app was restarted (owner, 2026-11-04).
 *
 * As the big apps do it, with no timer in the background:
 *  - on start (upstream's own fetch, which [started] notes),
 *  - when the window gets focus again: back from another app, or opened from the tray or the
 *    taskbar ([ForkLibraryFocusRefresh]), at most once per [FOCUS_GAP_MS] so alt-tabbing back and
 *    forth asks nothing,
 *  - on the Library page's Refresh button ([ForkLibraryRefreshRow]), at once.
 * A background refresh is [quiet]: the page keeps showing what it has instead of a spinner.
 */
internal object ForkLibraryRefresh {

    const val FOCUS_GAP_MS = 5L * 60 * 1000

    @Volatile private var lastAt = 0L

    /** True while a refresh asked for in the background runs: the page keeps its rows. */
    @Volatile var quiet = false
        private set

    /**
     * A library fetch began (any cause): the next focus refresh waits [FOCUS_GAP_MS] from now.
     * Returns whether this fetch is a quiet one and clears the flag at once, so a fetch cancelled
     * midway (an account switch) never leaves the next one quiet.
     */
    fun started(): Boolean {
        lastAt = System.currentTimeMillis()
        return quiet.also { quiet = false }
    }

    fun due(now: Long = System.currentTimeMillis()): Boolean = now - lastAt >= FOCUS_GAP_MS

    /**
     * Whether a fetch shows a loading page or keeps the rows shown. Only a quiet fetch with rows
     * already there keeps them: a quiet one over an error page used to turn it into the skeleton
     * and, failing again, never put anything back (the Refresh button still showing above it).
     * The same answer decides whether a failure shows the error.
     */
    fun keepsRows(quiet: Boolean, showingRows: Boolean): Boolean = quiet && showingRows

    /** How long a quiet refresh leaves the walk past Liked Music's first pages alone. */
    const val LIKED_WALK_GAP_MS = 6L * 60 * 60 * 1000

    @Volatile private var lastLikedWalkAt = 0L

    /**
     * Whether this fetch follows Liked Music past its first pages (for the ids alone), and if so
     * notes it. A focus refresh re-walked every page of a long Liked Music each time the window came
     * back; quietly, it now does so only when the last walk is [LIKED_WALK_GAP_MS] old. A start,
     * sign-in or Refresh button walks always.
     */
    fun likedWalkDue(quiet: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (quiet && now - lastLikedWalkAt < LIKED_WALK_GAP_MS) return false
        lastLikedWalkAt = now
        return true
    }

    /** Asks for a refresh through [bump] (the app's own reload), quietly or with the spinner. */
    fun request(quietly: Boolean, bump: () -> Unit) {
        quiet = quietly
        bump()
    }
}

/** Refreshes quietly when the window regains focus, if the last fetch is [ForkLibraryRefresh.FOCUS_GAP_MS] old. */
@Composable
internal fun ForkLibraryFocusRefresh(signedIn: Boolean, bump: () -> Unit) {
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (focused && signedIn && ForkLibraryRefresh.due()) {
            DesktopTrackLog.log("library: window back in focus, refreshing")
            ForkLibraryRefresh.request(quietly = true, bump = bump)
        }
    }
}

/** The Library page's Refresh button, at the head of the page, right-aligned. */
@Composable
internal fun ForkLibraryRefreshRow(bump: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { ForkLibraryRefresh.request(quietly = false, bump = bump) }) {
            Icon(Icons.Rounded.Refresh, null, Modifier.padding(end = 6.dp))
            Text(DesktopStrings["refresh_library", "Refresh"])
        }
    }
}
