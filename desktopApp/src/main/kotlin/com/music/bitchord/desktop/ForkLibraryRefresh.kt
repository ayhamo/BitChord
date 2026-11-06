package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 *  - on the Library page's Refresh button ([ForkLibraryRefreshRow]), at once,
 *  - on the sidebar's ⟳ beside the playlists ([ForkSidebarPlaylistsRefresh]) and F5 / Ctrl+R
 *    (Cmd+R on a Mac) anywhere in the window ([isRefreshKey]), quietly: the sidebar's playlists
 *    stay where they are while the new list comes in.
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

    /** The app's own reload and whether YouTube is signed in, set by [ForkLibraryFocusRefresh]. */
    @Volatile private var appBump: (() -> Unit)? = null
    private val _signedIn = kotlinx.coroutines.flow.MutableStateFlow(false)
    val signedIn: kotlinx.coroutines.flow.StateFlow<Boolean> = _signedIn

    internal fun register(signedIn: Boolean, bump: () -> Unit) {
        _signedIn.value = signedIn
        appBump = bump
    }

    /** The sidebar's button and the keys: a quiet refresh, when there is a library to refresh. */
    fun requestNow() {
        val bump = appBump ?: return
        if (!_signedIn.value) return
        DesktopTrackLog.log("library: refresh asked for (sidebar or key)")
        request(quietly = true, bump = bump)
    }

    /** F5, or Ctrl+R (Cmd+R on a Mac), on key-up as the window's other shortcuts. */
    fun isRefreshKey(key: androidx.compose.ui.input.key.Key, ctrl: Boolean, meta: Boolean, alt: Boolean = false): Boolean =
        key == androidx.compose.ui.input.key.Key.F5 ||
            // Not with Alt: Windows reports AltGr as Ctrl+Alt, and AltGr+R types a letter on some layouts.
            (key == androidx.compose.ui.input.key.Key.R && !alt && (if (DesktopPlatform.isMac) meta else ctrl))

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
    ForkLibraryRefresh.register(signedIn, bump) // the sidebar's ⟳ and F5 / Ctrl+R use the same reload
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

/**
 * The sidebar's head of the playlists: a small "Playlists" label with ⟳ at its end, so a playlist
 * made on the phone or in YouTube Music shows without opening the Library page (owner, offered
 * 2026-11-05). Greyed, not hidden, while YouTube isn't signed in.
 */
@Composable
internal fun ForkSidebarPlaylistsRefresh() {
    val signedIn by ForkLibraryRefresh.signedIn.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(start = 9.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            DesktopStrings["playlists", "Playlists"],
            Modifier.weight(1f),
            color = DesktopSecondary.copy(alpha = 0.68f),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
        androidx.compose.material3.IconButton(
            onClick = ForkLibraryRefresh::requestNow,
            enabled = signedIn,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Rounded.Refresh,
                DesktopStrings["refresh_library", "Refresh"] + " (F5)",
                tint = DesktopSecondary.copy(alpha = if (signedIn) 1f else 0.38f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
