package com.music.bitchord.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.fork.ForkSettings

/**
 * Fork: "Backdrop videos" in the player's song menu, flipping the global
 * Navidrome backdrop switch (the same [ForkSettings.backdropClips] as the rows
 * in Settings and Fork settings, so there is one setting, nothing to sync).
 * Off, the player is back to the cover (or its cover clip).
 */
@Composable
internal fun ForkBackdropMenuRow(accent: Color) {
    val on by ForkSettings.backdropClips.collectAsStateWithLifecycle()
    ActionRow(
        icon = Icons.Rounded.Wallpaper,
        label = stringResource(R.string.fork_backdrop_menu),
        value = stringResource(if (on) R.string.fork_on else R.string.fork_off),
        accent = accent,
    ) { ForkSettings.setBackdropClips(!on) }
}

/**
 * Fork: "Available offline" in a Navidrome album's or playlist's sheet: keeps
 * the whole page on the phone (original files, see
 * [com.music.bitchord.data.navidrome.NavidromeOffline]), shows the download's
 * progress, and once kept, a tap lets it go again.
 */
@Composable
internal fun ForkOfflineRow(target: BrowseTarget, accent: Color) {
    val id = target.browseId ?: return
    if (!com.music.bitchord.data.navidrome.NavidromeOffline.canKeep(id) || target.songs.isEmpty()) return
    val pages by com.music.bitchord.data.navidrome.NavidromeOffline.pages.collectAsStateWithLifecycle()
    val progress by com.music.bitchord.data.navidrome.NavidromeOffline.progress.collectAsStateWithLifecycle()
    val kept = id in pages
    val running = progress[id]
    // What it takes (kept) or will take (not yet), so a big album is no surprise.
    // null until measured, so a kept page never flashes "0 MB".
    val keptBytes by produceState<Long?>(null, id, kept) {
        value = if (kept) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.music.bitchord.data.navidrome.NavidromeOffline.pageBytes(id)
            }
        } else {
            null
        }
    }
    // Songs that failed to download: a partly kept page offers to fetch the rest (removing it is in the storage page).
    val missing = remember(id, pages, running == null) { com.music.bitchord.data.navidrome.NavidromeOffline.missing(id) }
    // Again after a keep or a remove: the files on the phone are what it is measured against.
    val estimate = remember(target.songs, pages, running == null) {
        com.music.bitchord.data.navidrome.NavidromeOffline.estimate(target.songs)
    }
    ActionRow(
        icon = when {
            running != null -> Icons.Rounded.Downloading
            kept -> Icons.Rounded.OfflinePin
            else -> Icons.Rounded.DownloadForOffline
        },
        label = stringResource(R.string.fork_offline_keep),
        value = when {
            running != null -> "${(running * 100).toInt()}%"
            kept && missing > 0 -> keptBytes?.let {
                stringResource(R.string.fork_offline_kept_partial, target.songs.size - missing, target.songs.size, forkSize(it))
            }
            kept -> keptBytes?.let { stringResource(R.string.fork_offline_kept, forkSize(it)) }
            estimate != null -> stringResource(R.string.fork_offline_size, forkSize(estimate))
            else -> null
        },
        accent = accent,
    ) {
        if ((kept && missing == 0) || running != null) {
            com.music.bitchord.data.navidrome.NavidromeOffline.remove(id)
        } else {
            com.music.bitchord.data.navidrome.NavidromeOffline.keep(
                id, target.title, target.subtitle, target.thumbnailUrl, target.songs,
            )
        }
    }
}

/** A size for the fork's offline and cache rows: "412 MB", "1.3 GB". */
internal fun forkSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
}

/**
 * Fork: "Saved queues" in the player's song menu (a try-out): put the queue as
 * it stands aside under a name, or play a saved one again from its song and
 * second; see [com.music.bitchord.fork.ForkQueues].
 */
@Composable
internal fun ForkSavedQueuesRow(accent: Color) {
    val saved by com.music.bitchord.fork.ForkQueues.saved.collectAsStateWithLifecycle()
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    ActionRow(
        icon = Icons.AutoMirrored.Rounded.QueueMusic,
        label = stringResource(R.string.fork_queues),
        value = saved.size.takeIf { it > 0 }?.toString(),
        accent = accent,
    ) { open = true }
    if (open) ForkSavedQueuesDialog(saved) { open = false }
}

@Composable
private fun ForkSavedQueuesDialog(
    saved: List<com.music.bitchord.fork.ForkQueues.Saved>,
    onDismiss: () -> Unit,
) {
    var name by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(com.music.bitchord.fork.ForkQueues.suggestedName().orEmpty())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(stringResource(R.string.fork_queues)) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { androidx.compose.material3.Text(stringResource(R.string.fork_queues_name)) },
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = {
                        if (com.music.bitchord.fork.ForkQueues.saveCurrent(name)) name = ""
                    }) { androidx.compose.material3.Text(stringResource(R.string.fork_queues_save)) }
                }
                if (saved.isEmpty()) {
                    androidx.compose.material3.Text(
                        stringResource(R.string.fork_queues_empty),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = androidx.compose.ui.Modifier.padding(top = 16.dp),
                    )
                }
                androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.padding(top = 8.dp)) {
                    items(saved, key = { it.id }) { queue ->
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                                androidx.compose.material3.Text(
                                    queue.name,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                androidx.compose.material3.Text(
                                    stringResource(
                                        R.string.fork_queues_entry,
                                        androidx.compose.ui.res.pluralStringResource(R.plurals.navidrome_playlist_songs, queue.songs.size, queue.songs.size),
                                        queue.current?.title.orEmpty(),
                                        "%d:%02d".format(queue.positionMs / 60_000, queue.positionMs / 1_000 % 60),
                                    ),
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            androidx.compose.material3.TextButton(onClick = {
                                com.music.bitchord.fork.ForkQueues.play(queue)
                                onDismiss()
                            }) { androidx.compose.material3.Text(stringResource(R.string.fork_queues_play)) }
                            androidx.compose.material3.TextButton(onClick = { com.music.bitchord.fork.ForkQueues.delete(queue.id) }) {
                                androidx.compose.material3.Text(stringResource(R.string.fork_queues_delete))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(stringResource(R.string.fork_offline_close))
            }
        },
    )
}

/**
 * Fork: "Animated covers" in the player's song menu, the online services'
 * moving cover art (Apple Music's tall full-picture ones among them), the
 * same global [ForkSettings.animatedCovers] as the Settings rows. Off, songs
 * show their still cover unless the server has a clip of its own.
 */
@Composable
internal fun ForkAnimatedCoversMenuRow(accent: Color) {
    val on by ForkSettings.animatedCovers.collectAsStateWithLifecycle()
    ActionRow(
        icon = Icons.Rounded.Animation,
        label = stringResource(R.string.fork_animated_covers),
        value = stringResource(if (on) R.string.fork_on else R.string.fork_off),
        accent = accent,
    ) { ForkSettings.setAnimatedCovers(!on) }
}

/** The fork's rows in the player's menu: the two video switches and saved queues. */
@Composable
internal fun ForkPlayerMenuRows(song: com.music.bitchord.data.model.Song, accent: Color) {
    if (com.music.bitchord.data.navidrome.NavidromeSettings.isNavidromeId(song.videoId)) ForkBackdropMenuRow(accent)
    if (!com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) ForkAnimatedCoversMenuRow(accent)
    ForkSavedQueuesRow(accent)
}
