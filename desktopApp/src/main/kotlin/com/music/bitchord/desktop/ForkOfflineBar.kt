package com.music.bitchord.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.components.ForkOfflineStrip

/**
 * Fork: the phone's offline strip ([ForkOfflineStrip]) on the PC, under the player bar along the
 * top of a wide window, or above the mini player of a compact one. Switched on by hand it offers
 * "Go online"; with the server not answering (the PC's "no network", [ForkOffline]) it is only a
 * notice and goes by itself when the server answers again. No server set up, no strip.
 *
 * Drawn only while it shows: the compact chrome spaces its rows, and a hidden strip there would
 * still have pushed the mini player up by a gap.
 */
@Composable
internal fun ForkOfflineBar(compact: Boolean) {
    val offline by ForkOffline.offline.collectAsState()
    val byHand by ForkOffline.switched.collectAsState()
    val url by NavidromeSettings.url.collectAsState()
    val user by NavidromeSettings.username.collectAsState()
    if (!offline || url.isBlank() || user.isBlank()) return
    ForkOfflineStrip(
        visible = true,
        message = if (byHand) "Offline mode: music on this computer only" else "Server not reachable: music on this computer only",
        actionLabel = if (byHand) "Go online" else null,
        onAction = { ForkOffline.setMode(false) },
        modifier = if (compact) Modifier.widthIn(max = 440.dp) else Modifier, // the mini player's width
        outerPadding = if (compact) PaddingValues(0.dp) else PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
}
