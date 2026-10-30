package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import kotlinx.coroutines.delay
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
 * Fork: the clip the player shows for [raw], and the clip behind the landscape
 * player ([ForkLandscapeBackdrop]), read once from both switches.
 *
 * A backdrop (a Navidrome `.background.mp4` or a Spotify Canvas, both 9:16) is
 * made to fill a phone. It plays only where it can fill the screen
 * ([forkFillsScreen]) and while the backdrop switch is on; otherwise the song's
 * square cover clip plays in the sleeve ([CanvasArtwork.sleeveUrl]), or, without
 * one, the still cover. A backdrop never plays cropped in the cover square.
 *
 * Whatever fills the screen needs "Backdrop videos", whatever plays in the
 * square needs "Animated covers" ([ForkCanvasSwitch]). Both read live, so
 * flipping either mid-song changes the player at once.
 *
 * In portrait a backdrop fills the screen; landscape had none (the sleeve
 * played the cover clip instead, and upstream played a Spotify Canvas cropped in
 * the sleeve). There the second clip plays on the right of the screen, where the
 * controls are, while the cover stays on the left.
 */
@Composable
internal fun forkCanvases(raw: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): Pair<CanvasArtwork?, CanvasArtwork?> {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    val fills = forkFillsScreen(raw, phoneWidth, windowWidth, windowHeight)
    val shown = raw.forkInSleeve(backdrops && fills)?.takeIf { if (it.source.fillsPhone && fills) backdrops else covers }
    return shown to raw?.takeIf { backdrops && it.source.fillsPhone }
}

private fun CanvasArtwork?.forkInSleeve(backdropShown: Boolean): CanvasArtwork? = when {
    this == null || !source.fillsPhone || backdropShown -> this
    else -> sleeveUrl?.let { copy(url = it, fallbackUrl = null, source = CanvasSource.OTHER, sleeveUrl = null) }
}

/**
 * Fork: whether a full-screen clip gets the screen. Upstream: only where the
 * player fills a phone-width window. A backdrop also in any portrait window
 * wider than that (a big phone, a tablet held upright, the 720 dp emulator),
 * where upstream kept it cropped in the sleeve. Never in a landscape window,
 * however narrow (split screen, a small phone): the landscape player draws
 * its own backdrop beside the cover ([ForkLandscapeBackdrop]).
 */
internal fun forkFillsScreen(canvas: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): Boolean =
    windowHeight >= windowWidth && (phoneWidth || canvas?.source?.fillsPhone == true)

/**
 * Fork: where "Fullscreen cover art" runs the still cover to the edges. Upstream:
 * only a phone-width window. Also any portrait window wider than that, the same
 * windows a backdrop fills ([forkFillsScreen]), so the switch doesn't quietly do
 * nothing there.
 */
internal fun forkBigArtFits(phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): Boolean =
    phoneWidth || windowHeight >= windowWidth

/** Longer than a track change or a short stall, which also read as not playing. */
private const val FORK_ART_SHRINK_DELAY_MS = 1_000L

/**
 * Fork: "Fullscreen cover art" while playing, the small cover while paused, as a paused
 * backdrop already brings the small cover back. Only a pause that lasts counts:
 * `isPlaying` is also false while a song buffers, and the art shouldn't jump for that.
 */
@Composable
internal fun rememberForkArtAwake(isPlaying: Boolean): Boolean {
    var awake by remember { mutableStateOf(isPlaying) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) delay(FORK_ART_SHRINK_DELAY_MS)
        awake = isPlaying
    }
    return awake
}
