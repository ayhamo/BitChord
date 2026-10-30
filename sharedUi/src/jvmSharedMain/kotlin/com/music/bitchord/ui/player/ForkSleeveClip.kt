package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: the player's two video switches, held here so the player reads them
 * live. `ForkSettings` persists both (the same flows), so Settings, Fork
 * settings and the player menu flip one value. Each goes by where a clip
 * plays, not where it comes from:
 */
object ForkCanvasSwitch {
    /** "Backdrop videos": a clip that fills the screen, the server's backdrops and Spotify's Canvas. */
    val backdrops = MutableStateFlow(true)

    /**
     * "Animated covers": a clip in the cover square, the server's cover clips
     * and the online services' motion covers (Apple Music, some of them the
     * tall full-picture kind, Tidal, the community's), and album headers.
     */
    val covers = MutableStateFlow(true)
}

/**
 * Fork: the clip the player shows for [raw].
 *
 * A Navidrome backdrop (`.background.mp4`, 9:16) is made to fill a phone. It
 * plays only where it can fill the screen ([forkFillsScreen]) and while the
 * backdrop switch is on; otherwise the song's square cover clip plays in the
 * sleeve ([CanvasArtwork.sleeveUrl]), or, without one, the still cover. A
 * backdrop never plays cropped in the cover square.
 *
 * Whatever fills the screen needs "Backdrop videos", whatever plays in the
 * square needs "Animated covers" ([ForkCanvasSwitch]). Both read live, so
 * flipping either mid-song changes the player at once.
 */
@Composable
internal fun forkCanvas(raw: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): CanvasArtwork? {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    val fills = forkFillsScreen(raw, phoneWidth, windowWidth, windowHeight)
    val shown = raw.forkInSleeve(backdrops && fills) ?: return null
    return shown.takeIf { if (it.source.fillsPhone && fills) backdrops else covers }
}

private fun CanvasArtwork?.forkInSleeve(backdropShown: Boolean): CanvasArtwork? = when {
    this == null || source != CanvasSource.BACKDROP || backdropShown -> this
    else -> sleeveUrl?.let { copy(url = it, fallbackUrl = null, source = CanvasSource.OTHER, sleeveUrl = null) }
}

/**
 * Fork: whether a full-screen clip gets the screen. Upstream: only where the
 * player fills a phone-width window. A Navidrome backdrop also in any portrait
 * window wider than that (a big phone, a tablet held upright, the 720 dp
 * emulator), where upstream kept it in the sleeve. Never in a landscape window,
 * however narrow (split screen, a small phone): the landscape player draws
 * its own backdrop beside the cover ([ForkLandscapeBackdrop]).
 */
internal fun forkFillsScreen(canvas: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): Boolean =
    windowHeight >= windowWidth && (phoneWidth || canvas?.source == CanvasSource.BACKDROP)
