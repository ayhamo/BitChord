package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.fork.ForkNetwork
import com.music.bitchord.fork.ForkSettings

/**
 * Fork: a strip above the mini player while offline mode is on, so it is never
 * a mystery why the server's music is gone. Switched on by hand, it offers
 * "Go online" (the switch off again); with no network it only says so, and
 * goes by itself when the network is back. Drawn by the shared [ForkOfflineStrip],
 * which the PC uses too.
 */
@Composable
internal fun ForkOfflineBanner(modifier: Modifier = Modifier) {
    val offline by ForkNetwork.offline.collectAsStateWithLifecycle()
    val byHand by ForkSettings.offlineMode.collectAsStateWithLifecycle()
    // Offline mode is about the Navidrome library; without a server there is nothing it hides.
    val url by com.music.bitchord.data.navidrome.NavidromeSettings.url.collectAsStateWithLifecycle()
    val user by com.music.bitchord.data.navidrome.NavidromeSettings.username.collectAsStateWithLifecycle()
    ForkOfflineStrip(
        visible = offline && url.isNotBlank() && user.isNotBlank(),
        message = stringResource(if (byHand) R.string.fork_offline_banner else R.string.fork_offline_banner_no_network),
        actionLabel = if (byHand) stringResource(R.string.fork_offline_banner_go_online) else null,
        onAction = { ForkSettings.setOfflineMode(false) },
        modifier = modifier,
    )
}
