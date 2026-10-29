package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.fork.ForkNetwork
import com.music.bitchord.fork.ForkSettings

/**
 * Fork: a strip above the mini player while offline mode is on, so it is never
 * a mystery why the server's music is gone. Switched on by hand, it offers
 * "Go online" (the switch off again); with no network it only says so, and
 * goes by itself when the network is back.
 */
@Composable
internal fun ForkOfflineBanner(modifier: Modifier = Modifier) {
    val offline by ForkNetwork.offline.collectAsStateWithLifecycle()
    val byHand by ForkSettings.offlineMode.collectAsStateWithLifecycle()
    // Offline mode is about the Navidrome library; without a server there is nothing it hides.
    val url by com.music.bitchord.data.navidrome.NavidromeSettings.url.collectAsStateWithLifecycle()
    val user by com.music.bitchord.data.navidrome.NavidromeSettings.username.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = offline && url.isNotBlank() && user.isNotBlank(), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(if (byHand) R.string.fork_offline_banner else R.string.fork_offline_banner_no_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = if (byHand) 0.dp else 12.dp),
            )
            if (byHand) {
                TextButton(onClick = { ForkSettings.setOfflineMode(false) }) {
                    Text(stringResource(R.string.fork_offline_banner_go_online))
                }
            }
        }
    }
}
