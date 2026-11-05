package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.ui.components.PAGE_GUTTER

/**
 * Fork: a Spotify library that failed to load can be tried again with fresh tokens, or the account
 * reconnected, from the page that failed (Desktop Revived 8b757325, 32e61148).
 *
 * The library reads [DesktopSpotifyToken]'s tokens, which are kept until they expire, and a failed
 * mint holds every request off for 30 minutes. So once the library failed (a token Spotify had
 * started refusing, a mint that timed out in the hidden browser), opening it again or pressing a
 * playlist's "Try again" sent the same refused token, or none, and showed the same error until
 * that time ran out; the grid's error had no button at all. [refresh] drops the tokens and the
 * backoff first, so a retry mints anew. When that fails too the sign-in itself is the likely cause,
 * and "Reconnect Spotify" opens Account & integrations, where it is redone.
 */
internal object ForkSpotifyLibraryRetry {

    /** Whether the Spotify playlists grid's last load failed: its retry buttons show only then. */
    var failed by mutableStateOf(false)

    /** Fresh tokens on the next request. */
    fun refresh() = DesktopSpotifyToken.forkForgetTokens()

    /** Before a collection page's "Try again": fresh tokens when the page is a Spotify playlist. */
    fun beforeRetry(browseId: String?) {
        if (browseId?.startsWith(SPOTIFY_PAGE_PREFIX) == true) refresh()
    }
}

/** Under the Spotify grid's error: Retry (with fresh tokens) and Reconnect. Nothing unless it failed. */
@Composable
internal fun ForkSpotifyLibraryRetryRow(onRetry: () -> Unit, onReconnect: () -> Unit) {
    if (!ForkSpotifyLibraryRetry.failed) return
    Row(
        Modifier.padding(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            ForkSpotifyLibraryRetry.refresh()
            onRetry()
        }) {
            Icon(Icons.Rounded.Refresh, null, Modifier.padding(end = 6.dp))
            Text(DesktopStrings["retry", "Retry"])
        }
        TextButton(onClick = onReconnect) {
            Text(DesktopStrings["fork_reconnect_spotify", "Reconnect Spotify"])
        }
    }
}
