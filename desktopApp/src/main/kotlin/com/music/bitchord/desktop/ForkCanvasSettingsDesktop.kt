package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasSettings
import com.music.bitchord.ui.player.ForkCanvasSwitch

/**
 * Fork: the canvas rulebook's §13 settings in the PC's settings popup (Appearance, under the
 * "Backdrop videos" / "Music video canvas" / "Animated covers" switches it already had, which are
 * the rulebook's two master switches and the music video's own switch): the source list with its
 * order and on/off, Apple's animated cover quality, the YouTube canvas quality and mode, and v1.10's
 * "Best Part button" (the pill on the artwork). Rows whose master switch is off are greyed, never hidden.
 *
 * Rule 4 (off on mobile data) is phone only: a PC has no metered network to tell (the rulebook's
 * PC is a browser extension, which can't either).
 */
/** The phone's words for v1.10's "Best Part button" (`show_best_part`). */
private const val BEST_PART_TITLE = "Best Part button"
private const val BEST_PART_SUBTITLE = "A button on the artwork that skips to the part of the song people replay most"

@Composable
internal fun ForkCanvasSettingsRows() {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsState()
    val covers by ForkCanvasSwitch.covers.collectAsState()
    val order by ForkCanvasSettings.order.collectAsState()
    val enabled by ForkCanvasSettings.enabled.collectAsState()
    ForkMarked {
        if (settingsRowVisible(ForkCanvasSettings.SOURCES_TITLE, ForkCanvasSettings.SOURCES_SUBTITLE + " " + order.joinToString(" ") { ForkCanvasSettings.name(it) })) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                ForkSettingsTitle(ForkCanvasSettings.SOURCES_TITLE)
                Text(ForkCanvasSettings.SOURCES_SUBTITLE, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
                order.forEachIndexed { i, source ->
                    val masterOn = if (source.canvas) backdrops else covers
                    ForkDisabledWhen(!masterOn) {
                        SourceRow(source, i, order.size, source in enabled)
                    }
                }
            }
        }
        ForkDisabledWhen(!covers || ForkCanvasRules.Source.APPLE_MUSIC !in enabled) {
            val q by ForkCanvasSettings.coverQuality.collectAsState()
            ForkCycleRow(ForkCanvasSettings.COVER_QUALITY_TITLE, ForkCanvasSettings.COVER_QUALITY_SUBTITLE, q.label) {
                ForkCanvasSettings.setCoverQuality(ForkCanvasSettings.CoverQuality.entries.let { it[(it.indexOf(q) + 1) % it.size] })
            }
        }
        ForkDisabledWhen(!backdrops || ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO !in enabled) {
            val q by ForkCanvasSettings.videoQuality.collectAsState()
            ForkCycleRow(ForkCanvasSettings.VIDEO_QUALITY_TITLE, ForkCanvasSettings.VIDEO_QUALITY_SUBTITLE, q.label) {
                ForkCanvasSettings.setVideoQuality(ForkCanvasSettings.VideoQuality.entries.let { it[(it.indexOf(q) + 1) % it.size] })
            }
            val m by ForkCanvasSettings.videoMode.collectAsState()
            ForkCycleRow(ForkCanvasSettings.VIDEO_MODE_TITLE, ForkCanvasSettings.VIDEO_MODE_SUBTITLE, m.label) {
                ForkCanvasSettings.setVideoMode(ForkCanvasSettings.VideoMode.entries.let { it[(it.indexOf(m) + 1) % it.size] })
            }
        }
        val button by ForkPlayerSettings.showBestPart.collectAsState()
        SettingsToggle(BEST_PART_TITLE, BEST_PART_SUBTITLE, button, ForkPlayerSettings::setShowBestPart) // v1.10's Best Part pill, in the phone's words
    }
}

/** One source in the list: its name, up and down, its switch (the music video's is "Music video canvas"). */
@Composable
private fun SourceRow(source: ForkCanvasRules.Source, index: Int, count: Int, on: Boolean) {
    val masterOn = if (source.canvas) ForkCanvasSwitch.backdrops.collectAsState().value else ForkCanvasSwitch.covers.collectAsState().value
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}.", color = DesktopSecondary, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(ForkCanvasSettings.name(source), style = MaterialTheme.typography.bodyLarge)
            if (!masterOn) Text(ForkCanvasSettings.needs(source), color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { ForkCanvasSettings.move(source, up = true) }, enabled = index > 0) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { ForkCanvasSettings.move(source, up = false) }, enabled = index < count - 1) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        Switch(checked = on, onCheckedChange = { setSourceOn(source, it) }, colors = desktopSwitchColors())
    }
}

/** The music video's switch is also "Music video canvas"; the others are the list's own. */
private fun setSourceOn(source: ForkCanvasRules.Source, on: Boolean) {
    if (source == ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO) ForkDesktopCanvasSources.setVideos(on)
    else ForkCanvasSettings.setOn(source, on)
}

/** A row whose value moves to the next choice on a click, the value at its end. */
@Composable
internal fun ForkCycleRow(title: String, subtitle: String, value: String, onClick: () -> Unit) {
    if (!settingsRowVisible(title, subtitle)) return
    Row(
        Modifier.fillMaxWidth().desktopRowClickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ForkSettingsTitle(title)
            Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
        Spacer(Modifier.width(18.dp))
        Text(value, color = DesktopAccent, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Rule 8 beside the PC's "Artist Art" switch (the artist images): "Artist videos" (following the
 * images until set) and their quality.
 */
@Composable
internal fun ForkArtistVideoRows() {
    val images by ForkArtistHeader.enabled.collectAsState()
    val videos by ForkCanvasSettings.artistVideos.collectAsState()
    val quality by ForkCanvasSettings.artistVideoQuality.collectAsState()
    val covers by ForkCanvasSwitch.covers.collectAsState()
    ForkMarked {
        ForkDisabledWhen(!images) {
            SettingsToggle(ForkCanvasSettings.ARTIST_VIDEOS_TITLE, ForkCanvasSettings.ARTIST_VIDEOS_SUBTITLE, videos ?: images, ForkCanvasSettings::setArtistVideos)
        }
        ForkDisabledWhen(!images || !(videos ?: images) || !covers) {
            ForkCycleRow(ForkCanvasSettings.ARTIST_VIDEO_QUALITY_TITLE, ForkCanvasSettings.ARTIST_VIDEO_QUALITY_SUBTITLE, quality.label) {
                ForkCanvasSettings.setArtistVideoQuality(ForkCanvasSettings.CoverQuality.entries.let { it[(it.indexOf(quality) + 1) % it.size] })
            }
        }
    }
}
