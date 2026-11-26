package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.ForkSettingsBadge
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasSettings
import com.music.bitchord.fork.ForkSettings
import com.music.bitchord.ui.player.ForkCanvasSwitch

/**
 * Fork: the canvas rulebook's §13 settings on the phone, in BitChord's settings rows: the source
 * list (order and on/off), Apple's animated cover quality, the YouTube canvas quality and mode
 * ([forkCanvasSettingsRows], under "Animated covers" / "Backdrop videos" / "Music video canvas"), the
 * artist page's images and videos and the "Best part button" ([forkArtistAndBestPartRows], on the
 * Fork page). A row whose master switch is off is greyed, never hidden.
 *
 * Rule 4 (no canvas on mobile data) is upstream's own "Canvas over mobile data" switch
 * (`AppSettings.canvasOverCellular`), kept with upstream's default (FORK.md).
 */
@Composable
internal fun SettingsGroupScope.forkCanvasSettingsRows() {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    val enabled by ForkCanvasSettings.enabled.collectAsStateWithLifecycle()
    row(ForkCanvasSettings.SOURCES_TITLE, "fork", "canvas", "order", "sources", "apple", "spotify", "youtube") { ForkCanvasSourcesRow() }
    forkDisabledWhen(!covers || ForkCanvasRules.Source.APPLE_MUSIC !in enabled) {
        row(ForkCanvasSettings.COVER_QUALITY_TITLE, "fork", "canvas", "apple", "quality", "cover") {
            val q by ForkCanvasSettings.coverQuality.collectAsStateWithLifecycle()
            ForkCycleRow(Icons.Rounded.HighQuality, ForkCanvasSettings.COVER_QUALITY_TITLE, ForkCanvasSettings.COVER_QUALITY_SUBTITLE, q.label) {
                ForkCanvasSettings.setCoverQuality(next(ForkCanvasSettings.CoverQuality.entries, q))
            }
        }
    }
    forkDisabledWhen(!backdrops || ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO !in enabled) {
        row(ForkCanvasSettings.VIDEO_QUALITY_TITLE, "fork", "canvas", "youtube", "quality", "video") {
            val q by ForkCanvasSettings.videoQuality.collectAsStateWithLifecycle()
            ForkCycleRow(Icons.Rounded.HighQuality, ForkCanvasSettings.VIDEO_QUALITY_TITLE, ForkCanvasSettings.VIDEO_QUALITY_SUBTITLE, q.label) {
                ForkCanvasSettings.setVideoQuality(next(ForkCanvasSettings.VideoQuality.entries, q))
            }
        }
        row(ForkCanvasSettings.VIDEO_MODE_TITLE, "fork", "canvas", "youtube", "mode", "full video", "clips") {
            val m by ForkCanvasSettings.videoMode.collectAsStateWithLifecycle()
            ForkCycleRow(Icons.Rounded.Movie, ForkCanvasSettings.VIDEO_MODE_TITLE, ForkCanvasSettings.VIDEO_MODE_SUBTITLE, m.label) {
                ForkCanvasSettings.setVideoMode(next(ForkCanvasSettings.VideoMode.entries, m))
            }
        }
    }
}

/**
 * Rule 13.8 (artist images, artist videos following them until set, their quality) and the "Best
 * part button" by the scrubber. Neither depends on the canvas master switch, so they sit on the
 * Fork page rather than under it.
 */
@Composable
internal fun SettingsGroupScope.forkArtistAndBestPartRows() {
    val images by ForkCanvasSettings.artistImages.collectAsStateWithLifecycle()
    val videos by ForkCanvasSettings.artistVideos.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    row(ForkCanvasSettings.ARTIST_IMAGES_TITLE, "fork", "artist", "apple", "image", "photo", "logo") {
        ForkFlagRow(Icons.Rounded.Face, ForkCanvasSettings.ARTIST_IMAGES_TITLE, ForkCanvasSettings.ARTIST_IMAGES_SUBTITLE, images, ForkCanvasSettings::setArtistImages)
    }
    forkDisabledWhen(!images) {
        row(ForkCanvasSettings.ARTIST_VIDEOS_TITLE, "fork", "artist", "apple", "video", "motion") {
            ForkFlagRow(Icons.Rounded.Movie, ForkCanvasSettings.ARTIST_VIDEOS_TITLE, ForkCanvasSettings.ARTIST_VIDEOS_SUBTITLE, videos ?: images, ForkCanvasSettings::setArtistVideos)
        }
    }
    forkDisabledWhen(!images || !(videos ?: images) || !covers) {
        row(ForkCanvasSettings.ARTIST_VIDEO_QUALITY_TITLE, "fork", "artist", "video", "quality") {
            val q by ForkCanvasSettings.artistVideoQuality.collectAsStateWithLifecycle()
            ForkCycleRow(Icons.Rounded.HighQuality, ForkCanvasSettings.ARTIST_VIDEO_QUALITY_TITLE, ForkCanvasSettings.ARTIST_VIDEO_QUALITY_SUBTITLE, q.label) {
                ForkCanvasSettings.setArtistVideoQuality(next(ForkCanvasSettings.CoverQuality.entries, q))
            }
        }
    }
}

private fun <T> next(all: List<T>, now: T): T = all[(all.indexOf(now) + 1) % all.size]

/** The list of sources: each with its place, up and down, and its switch (the music video's is "Music video canvas"). */
@Composable
private fun ForkCanvasSourcesRow() {
    val order by ForkCanvasSettings.order.collectAsStateWithLifecycle()
    val enabled by ForkCanvasSettings.enabled.collectAsStateWithLifecycle()
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            icon = Icons.Rounded.Reorder,
            title = ForkCanvasSettings.SOURCES_TITLE,
            subtitle = ForkCanvasSettings.SOURCES_SUBTITLE,
            badge = ForkSettingsBadge.TEXT,
        )
        order.forEachIndexed { i, source ->
            val masterOn = if (source.canvas) backdrops else covers
            ForkDisabledWhen(!masterOn) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${ForkCanvasSettings.name(source)}", style = MaterialTheme.typography.bodyLarge)
                        if (!masterOn) {
                            Text(
                                ForkCanvasSettings.needs(source),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { ForkCanvasSettings.move(source, up = true) }, enabled = i > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { ForkCanvasSettings.move(source, up = false) }, enabled = i < order.size - 1) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    com.music.bitchord.ui.components.IosSwitch(
                        checked = source in enabled,
                        onCheckedChange = { on ->
                            if (source == ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO) ForkSettings.setMusicVideoCanvas(on)
                            else ForkCanvasSettings.setOn(source, on)
                        },
                    )
                }
            }
        }
    }
}

/** A switch row in BitChord's style. */
@Composable
private fun ForkFlagRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        badge = ForkSettingsBadge.TEXT,
        trailing = { com.music.bitchord.ui.components.IosSwitch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/** A row whose value moves to the next choice on a tap (as "Video clip cache"). */
@Composable
private fun ForkCycleRow(icon: ImageVector, title: String, subtitle: String, value: String, onClick: () -> Unit) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        badge = ForkSettingsBadge.TEXT,
        value = value,
        onClick = onClick,
    )
}
