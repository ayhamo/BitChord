package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.fork.ForkSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * The fork's switches as full settings rows (fork-only), for the "Fork
 * settings" group at the end of Settings. Each one is the same setting as the
 * row where it also lives in the normal sections (the same [ForkSettings]
 * flow), so flipping either moves both.
 */
@Composable
internal fun ForkCrossfadeOnSkipRow() = ForkSwitchRow(
    icon = Icons.Rounded.Waves,
    title = stringResource(R.string.fork_crossfade_on_skip),
    subtitle = stringResource(R.string.fork_crossfade_on_skip_subtitle),
    state = ForkSettings.crossfadeOnSkip,
    onChange = ForkSettings::setCrossfadeOnSkip,
)

@Composable
internal fun ForkBackdropClipsRow() = ForkSwitchRow(
    icon = Icons.Rounded.Wallpaper,
    title = stringResource(R.string.fork_backdrop_clips),
    subtitle = stringResource(R.string.fork_backdrop_clips_subtitle),
    state = ForkSettings.backdropClips,
    onChange = ForkSettings::setBackdropClips,
)

@Composable
internal fun ForkNavidromeScrobbleRow() = ForkSwitchRow(
    icon = Icons.Rounded.Cloud,
    title = stringResource(R.string.fork_navidrome_scrobble),
    subtitle = stringResource(R.string.fork_navidrome_scrobble_subtitle),
    state = ForkSettings.navidromeScrobble,
    onChange = ForkSettings::setNavidromeScrobble,
)

@Composable
private fun ForkSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    state: StateFlow<Boolean>,
    onChange: (Boolean) -> Unit,
) {
    val checked by state.collectAsStateWithLifecycle()
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { onChange(!checked) },
    )
}

@Composable
internal fun ForkNavidromeHomeShelvesRow() = ForkSwitchRow(
    icon = Icons.Rounded.Home,
    title = stringResource(R.string.fork_navidrome_home_shelves),
    subtitle = stringResource(R.string.fork_navidrome_home_shelves_subtitle),
    state = ForkSettings.navidromeHomeShelves,
    onChange = ForkSettings::setNavidromeHomeShelves,
)

/** The video clip cache's size; a tap moves to the next size offered. */
@Composable
internal fun ForkClipCacheSizeRow() {
    val mb by ForkSettings.clipCacheMb.collectAsStateWithLifecycle()
    val choices = ForkSettings.CLIP_CACHE_CHOICES_MB
    SettingsRow(
        icon = Icons.Rounded.VideoLibrary,
        title = stringResource(R.string.fork_clip_cache_size),
        subtitle = stringResource(R.string.fork_clip_cache_size_subtitle),
        value = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB",
        onClick = { ForkSettings.setClipCacheMb(choices[(choices.indexOf(mb) + 1) % choices.size]) },
    )
}

@Composable
internal fun ForkClearBackdropsRow() = ForkClearClipsRow(
    kind = com.music.bitchord.fork.ForkClipCache.Kind.BACKDROPS,
    title = stringResource(R.string.fork_clear_backdrops),
)

@Composable
internal fun ForkClearCoverClipsRow() = ForkClearClipsRow(
    kind = com.music.bitchord.fork.ForkClipCache.Kind.COVER_CLIPS,
    title = stringResource(R.string.fork_clear_cover_clips),
)

/** Drops one kind of Navidrome clip from the clip cache, showing what it holds now. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ForkClearClipsRow(kind: com.music.bitchord.fork.ForkClipCache.Kind, title: String) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val bytes by produceState(0L, refresh) { value = com.music.bitchord.fork.ForkClipCache.size(kind) }
    SettingsRow(
        icon = Icons.Rounded.DeleteSweep,
        title = title,
        subtitle = stringResource(R.string.fork_clear_clips_subtitle, "%.1f MB".format(bytes / 1048576.0)),
        onClick = {
            scope.launch {
                com.music.bitchord.fork.ForkClipCache.clear(kind)
                refresh++
            }
        },
    )
}

@Composable
internal fun ForkNavidromeYouTubeHistoryRow() = ForkSwitchRow(
    icon = Icons.Rounded.History,
    title = stringResource(R.string.fork_navidrome_yt_history),
    subtitle = stringResource(R.string.fork_navidrome_yt_history_subtitle),
    state = ForkSettings.navidromeYouTubeHistory,
    onChange = ForkSettings::setNavidromeYouTubeHistory,
)

@Composable
internal fun ForkDownloadOnMobileRow() = ForkSwitchRow(
    icon = Icons.Rounded.SignalCellularAlt,
    title = stringResource(R.string.fork_download_mobile),
    subtitle = stringResource(R.string.fork_download_mobile_subtitle),
    state = ForkSettings.downloadOnMobile,
    onChange = ForkSettings::setDownloadOnMobile,
)

@Composable
internal fun ForkOfflineModeRow() = ForkSwitchRow(
    icon = Icons.Rounded.CloudOff,
    title = stringResource(R.string.fork_offline_mode),
    subtitle = stringResource(R.string.fork_offline_mode_subtitle),
    state = ForkSettings.offlineMode,
    onChange = ForkSettings::setOfflineMode,
)

/** How many songs ahead are pre-loaded; a tap moves to the next count offered. */
@Composable
internal fun ForkPreloadRow() {
    val count by ForkSettings.preloadSongs.collectAsStateWithLifecycle()
    val choices = ForkSettings.PRELOAD_CHOICES
    SettingsRow(
        icon = Icons.Rounded.FastForward,
        title = stringResource(R.string.fork_preload),
        subtitle = stringResource(R.string.fork_preload_subtitle),
        value = if (count == 0) stringResource(R.string.fork_preload_off) else count.toString(),
        onClick = { ForkSettings.setPreloadSongs(choices[(choices.indexOf(count) + 1) % choices.size]) },
    )
}

/** Empties the Navidrome lyrics cache, showing what it holds now. */
@Composable
internal fun ForkClearLyricsRow() {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val bytes by produceState(0L, refresh) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.music.bitchord.data.navidrome.NavidromeLyrics.cacheBytes()
        }
    }
    SettingsRow(
        icon = Icons.Rounded.DeleteSweep,
        title = stringResource(R.string.fork_clear_lyrics),
        subtitle = stringResource(R.string.fork_clear_lyrics_subtitle, "%.1f MB".format(bytes / 1048576.0)),
        onClick = {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.music.bitchord.data.navidrome.NavidromeLyrics.clearCache()
                refresh++
            }
        },
    )
}

/**
 * What "Available offline" holds: its size, and on a tap every kept album and
 * playlist with what it takes and a Remove button (psysonic's offline page,
 * as a dialog).
 */
@Composable
internal fun ForkOfflineStorageRow() {
    val pages by com.music.bitchord.data.navidrome.NavidromeOffline.pages.collectAsStateWithLifecycle()
    val total by produceState(0L, pages) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.music.bitchord.data.navidrome.NavidromeOffline.sizeBytes()
        }
    }
    var open by remember { mutableStateOf(false) }
    SettingsRow(
        icon = Icons.Rounded.OfflinePin,
        title = stringResource(R.string.fork_offline_storage),
        subtitle = if (pages.isEmpty()) {
            stringResource(R.string.fork_offline_storage_empty)
        } else {
            androidx.compose.ui.res.pluralStringResource(R.plurals.fork_offline_storage_subtitle, pages.size, com.music.bitchord.ui.components.forkSize(total), pages.size)
        },
        onClick = { open = true },
    )
    if (open) ForkOfflineStorageDialog(pages.values.sortedBy { it.title.lowercase() }) { open = false }
}

@Composable
private fun ForkOfflineStorageDialog(
    pages: List<com.music.bitchord.data.navidrome.NavidromeOffline.Page>,
    onDismiss: () -> Unit,
) {
    val sizes by produceState(emptyMap<String, Long>(), pages) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pages.associate { it.id to com.music.bitchord.data.navidrome.NavidromeOffline.pageBytes(it.id) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fork_offline_storage)) },
        text = {
            if (pages.isEmpty()) {
                Text(stringResource(R.string.fork_offline_storage_empty))
            } else {
                LazyColumn {
                    items(pages, key = { it.id }) { page ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(page.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        (if (page.gone) stringResource(R.string.fork_offline_gone) else page.subtitle).takeIf { it.isNotBlank() },
                                        androidx.compose.ui.res.pluralStringResource(R.plurals.navidrome_playlist_songs, page.songs.size, page.songs.size),
                                        sizes[page.id]?.let { com.music.bitchord.ui.components.forkSize(it) },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { com.music.bitchord.data.navidrome.NavidromeOffline.remove(page.id) }) {
                                Text(stringResource(R.string.fork_offline_remove))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fork_offline_close)) } },
        dismissButton = if (pages.isEmpty()) {
            null
        } else {
            { TextButton(onClick = com.music.bitchord.data.navidrome.NavidromeOffline::removeAll) { Text(stringResource(R.string.fork_offline_remove_all)) } }
        },
    )
}

@Composable
internal fun ForkAnimatedCoversRow() = ForkSwitchRow(
    icon = Icons.Rounded.Animation,
    title = stringResource(R.string.fork_animated_covers),
    subtitle = stringResource(R.string.fork_animated_covers_subtitle),
    state = ForkSettings.animatedCovers,
    onChange = ForkSettings::setAnimatedCovers,
)

// ---- The fork's rows inside upstream's settings groups, so SettingsSheet carries one line each ----

/** After "Account & integrations": what the Navidrome library adds. */
@Composable
internal fun SettingsGroupScope.forkNavidromeRows() {
    row(stringResource(R.string.fork_navidrome_scrobble), "navidrome", "scrobbling", "now playing") { ForkNavidromeScrobbleRow() }
    row(stringResource(R.string.fork_navidrome_home_shelves), "navidrome", "home", "shelves") { ForkNavidromeHomeShelvesRow() }
    row(stringResource(R.string.fork_navidrome_yt_history), "navidrome", "youtube", "history") { ForkNavidromeYouTubeHistoryRow() }
    row(stringResource(R.string.fork_offline_mode), "navidrome", "offline") { ForkOfflineModeRow() }
    row(stringResource(R.string.fork_offline_storage), "navidrome", "offline", "storage", "downloads") { ForkOfflineStorageRow() }
    row(stringResource(R.string.fork_download_mobile), "navidrome", "offline", "mobile", "metered", "download") { ForkDownloadOnMobileRow() }
    row(stringResource(R.string.fork_preload), "navidrome", "preload", "cache", "next") { ForkPreloadRow() }
}

/** Next / Previous blend too, with a crossfade length or Automix's own fallback ([shown]); after the Crossfade slider, which Automix hides. */
@Composable
internal fun SettingsGroupScope.forkCrossfadeOnSkipRow(shown: Boolean) {
    if (!shown) return
    val title = stringResource(R.string.fork_crossfade_on_skip)
    row(title, "fade", "skip", "next", "previous") {
        ForkSubRow(title, stringResource(R.string.fork_crossfade_on_skip_subtitle), ForkSettings.crossfadeOnSkip, ForkSettings::setCrossfadeOnSkip)
    }
}

/** "Animated covers" and "Backdrop videos", which play through the canvas switched on ([shown]) above them. */
@Composable
internal fun SettingsGroupScope.forkCanvasRows(shown: Boolean) {
    if (!shown) return
    val covers = stringResource(R.string.fork_animated_covers)
    row(covers, "canvas", "apple", "animated", "cover", "video", divided = false) {
        ForkSubRow(covers, stringResource(R.string.fork_animated_covers_subtitle), ForkSettings.animatedCovers, ForkSettings::setAnimatedCovers)
    }
    val backdrops = stringResource(R.string.fork_backdrop_clips)
    row(backdrops, "canvas", "navidrome", "backdrop", "video", divided = false) {
        ForkSubRow(backdrops, stringResource(R.string.fork_backdrop_clips_subtitle), ForkSettings.backdropClips, ForkSettings::setBackdropClips)
    }
}

/** After "Clear image cache": the video clip cache's size and the clear buttons. */
@Composable
internal fun SettingsGroupScope.forkCacheRows() {
    row(stringResource(R.string.fork_clip_cache_size), "cache", "video", "clips", "canvas") { ForkClipCacheSizeRow() }
    row(stringResource(R.string.fork_clear_backdrops), "cache", "backdrop", "video", "free space") { ForkClearBackdropsRow() }
    row(stringResource(R.string.fork_clear_cover_clips), "cache", "cover", "video", "free space") { ForkClearCoverClipsRow() }
    row(stringResource(R.string.fork_clear_lyrics), "cache", "lyrics", "free space") { ForkClearLyricsRow() }
}

/** Every fork switch again in one group (the same settings as their rows above, so they stay in sync). */
@Composable
internal fun ForkSettingsGroup(search: SettingsSearch) {
    SearchableSettingsGroup(search, header = stringResource(R.string.fork_settings)) {
        row(stringResource(R.string.fork_crossfade_on_skip), "fork", "fade", "skip") { ForkCrossfadeOnSkipRow() }
        row(stringResource(R.string.fork_animated_covers), "fork", "apple", "animated", "cover") { ForkAnimatedCoversRow() }
        row(stringResource(R.string.fork_backdrop_clips), "fork", "navidrome", "backdrop", "video") { ForkBackdropClipsRow() }
        row(stringResource(R.string.fork_navidrome_scrobble), "fork", "navidrome", "scrobbling") { ForkNavidromeScrobbleRow() }
        row(stringResource(R.string.fork_navidrome_home_shelves), "fork", "navidrome", "home", "shelves") { ForkNavidromeHomeShelvesRow() }
        row(stringResource(R.string.fork_navidrome_yt_history), "fork", "navidrome", "youtube", "history") { ForkNavidromeYouTubeHistoryRow() }
        row(stringResource(R.string.fork_clip_cache_size), "fork", "cache", "video", "clips") { ForkClipCacheSizeRow() }
        row(stringResource(R.string.fork_clear_backdrops), "fork", "cache", "backdrop") { ForkClearBackdropsRow() }
        row(stringResource(R.string.fork_clear_cover_clips), "fork", "cache", "cover") { ForkClearCoverClipsRow() }
        row(stringResource(R.string.fork_clear_lyrics), "fork", "cache", "lyrics") { ForkClearLyricsRow() }
        row(stringResource(R.string.fork_offline_mode), "fork", "navidrome", "offline") { ForkOfflineModeRow() }
        row(stringResource(R.string.fork_offline_storage), "fork", "navidrome", "offline", "storage") { ForkOfflineStorageRow() }
        row(stringResource(R.string.fork_download_mobile), "fork", "offline", "mobile") { ForkDownloadOnMobileRow() }
        row(stringResource(R.string.fork_preload), "fork", "navidrome", "preload") { ForkPreloadRow() }
    }
}

/** A setting drawn as part of the row above it ([SettingsSubRow]). */
@Composable
private fun ForkSubRow(title: String, subtitle: String, state: StateFlow<Boolean>, onChange: (Boolean) -> Unit) {
    val checked by state.collectAsStateWithLifecycle()
    SettingsSubRow(title = title, subtitle = subtitle, checked = checked, onCheckedChange = onChange)
}
