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

    /**
     * "Animated covers": the animated art the online services have for a song
     * or album (Apple Music's motion covers, some of them the tall full-picture
     * kind, and Tidal's, the community's and Spotify's). Separate from
     * [backdrops], which is the Navidrome server's own clips. `ForkSettings`
     * persists it.
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
 * A clip from an online service (anything not on the server's x-clip route)
 * plays only while "Animated covers" is on. Both read live, so flipping either
 * mid-song changes the player at once.
 */
@Composable
internal fun forkCanvas(raw: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): CanvasArtwork? {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    if (raw != null && !raw.isServerClip() && !covers) return null
    return raw.forkInSleeve(backdrops && forkFillsScreen(raw, phoneWidth, windowWidth, windowHeight))
}

/** A clip from the Navidrome server (its x-clip route; kept offline copies keep that address too). */
private fun CanvasArtwork.isServerClip(): Boolean = "/rest/x-clip/" in url

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
