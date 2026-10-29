package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: the "Navidrome backdrop clips" switch, held here so the player reads it
 * live. The app's `ForkSettings.backdropClips` is this same flow (persisted
 * there), so Settings, Fork settings and the player menu flip one value.
 */
object ForkCanvasSwitch {
    val backdrops = MutableStateFlow(true)
}

/**
 * Fork: the clip the player shows for [raw].
 *
 * A Navidrome backdrop (`.background.mp4`, 9:16) is made to fill a phone. It
 * plays only where it can fill the screen ([forkFillsScreen]) and while the
 * backdrop switch is on; otherwise the song's square cover clip plays in the
 * sleeve ([CanvasArtwork.sleeveUrl]), or, without one, the still cover. A
 * backdrop never plays cropped in the cover square. Read live, so flipping the
 * switch mid-song changes the player at once.
 */
@Composable
internal fun forkCanvas(raw: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): CanvasArtwork? {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    return raw.forkInSleeve(backdrops && forkFillsScreen(raw, phoneWidth, windowWidth, windowHeight))
}

private fun CanvasArtwork?.forkInSleeve(backdropShown: Boolean): CanvasArtwork? = when {
    this == null || source != CanvasSource.BACKDROP || backdropShown -> this
    else -> sleeveUrl?.let { copy(url = it, fallbackUrl = null, source = CanvasSource.OTHER, sleeveUrl = null) }
}

/**
 * Fork: whether a full-screen clip gets the screen. Upstream: only where the
 * player fills a phone-width window. A Navidrome backdrop also in any portrait
 * window wider than that (a big phone, a tablet held upright, the 720 dp
 * emulator), where upstream kept it in the sleeve.
 */
internal fun forkFillsScreen(canvas: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): Boolean =
    phoneWidth || (canvas?.source == CanvasSource.BACKDROP && windowHeight > windowWidth)
