package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.ui.player.ForkCanvasSwitch

/** Fork: the wording of the player's three switches, the same in its menu and in Settings, as on the phone. */
internal object ForkPlayerMenu {
    const val FULLSCREEN_TITLE = "Fullscreen cover art"
    const val FULLSCREEN_SUBTITLE = "In a narrow or upright window, runs the cover to the edges of the player instead of a square sleeve. Paused, the small cover comes back"
    const val BACKDROPS_TITLE = "Backdrop videos"
    const val BACKDROPS_SUBTITLE = "A video behind the player: a song's backdrop from your server, or Spotify's Canvas. The cover comes back while paused"
    const val COVERS_TITLE = "Animated covers"
    const val COVERS_SUBTITLE = "Video in the cover square: your server's cover clips and the moving covers from Apple Music and other services. Backdrops have their own switch"

    /** Set by the window, which owns and saves "Fullscreen cover art". */
    var onFullBleedChange: (Boolean) -> Unit = {}
}

private fun onOff(on: Boolean) = if (on) "On" else "Off"

/**
 * Fork: the three switches in the player's song menu, the phone's `ForkPlayerMenuRows`. The menu
 * stays open, so a flip shows on the player behind it at once. A wide window shows no big art at
 * all, so "Fullscreen cover art" says so rather than appear to do nothing.
 */
@Composable
internal fun ForkPlayerMenuRows() {
    val fullBleed by DesktopPlayerSettings.fullBleedArtwork.collectAsState()
    val fits by ForkCanvasSwitch.bigArtFits.collectAsState()
    val backdrops by ForkCanvasSwitch.backdrops.collectAsState()
    val covers by ForkCanvasSwitch.covers.collectAsState()
    DesktopMenuRow(
        icon = Icons.Rounded.Fullscreen,
        label = ForkPlayerMenu.FULLSCREEN_TITLE,
        value = if (fits) onOff(fullBleed) else "${onOff(fullBleed)}, narrow window",
    ) { ForkPlayerMenu.onFullBleedChange(!fullBleed) }
    DesktopMenuRow(
        icon = Icons.Rounded.Wallpaper,
        label = ForkPlayerMenu.BACKDROPS_TITLE,
        value = onOff(backdrops),
    ) { ForkNavidrome.setBackdrops(!backdrops) }
    DesktopMenuRow(
        icon = Icons.Rounded.Animation,
        label = ForkPlayerMenu.COVERS_TITLE,
        value = onOff(covers),
    ) { ForkNavidrome.setCovers(!covers) }
}
