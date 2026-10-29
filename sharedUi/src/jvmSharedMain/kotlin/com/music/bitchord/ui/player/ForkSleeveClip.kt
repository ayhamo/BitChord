package com.music.bitchord.ui.player

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource

/**
 * Fork: a backdrop never plays in the cover square.
 *
 * A Navidrome backdrop (`.background.mp4`, 9:16) is made to fill a phone. Where
 * the player can't give it the screen (a wide window, a tablet, landscape),
 * upstream would shrink any clip into the sleeve, and the sleeve would show a
 * cropped backdrop instead of the cover. There the song's square cover clip
 * plays instead ([CanvasArtwork.sleeveUrl]), or, without one, the still cover.
 */
internal fun CanvasArtwork?.forkInSleeve(fillsWindow: Boolean): CanvasArtwork? = when {
    this == null || source != CanvasSource.BACKDROP || fillsWindow -> this
    else -> sleeveUrl?.let { copy(url = it, fallbackUrl = null, source = CanvasSource.OTHER, sleeveUrl = null) }
}
