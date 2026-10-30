package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.artworkAt

/**
 * Fork: the cover, back over a full-screen clip while the music is paused.
 *
 * In the Canvas presentation (Spotify's Canvas, and the fork's Navidrome
 * backdrops, which behave the same way, as Spotify does) the clip *is* the
 * player and the sleeve steps aside while it plays. Paused, the clip has
 * stopped meaning anything, so the cover comes back on top of it, in the upper
 * part of the screen above the control deck, and leaves again when playback
 * resumes. "Navidrome backdrop clips" switches between the backdrop and the
 * cover for good.
 */
@Composable
internal fun BoxScope.ForkPausedCover(visible: Boolean, artUrl: String?) {
    val alpha by animateFloatAsState(
        targetValue = if (visible && artUrl != null) 1f else 0f,
        // Slow, like the clip's own fade-in on a new track (FORK_BACKDROP_FADE_MS).
        animationSpec = tween(durationMillis = 700),
        label = "forkPausedCover",
    )
    if (alpha == 0f || artUrl == null) return
    BoxWithConstraints(
        Modifier
            .matchParentSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .graphicsLayer { this.alpha = alpha },
    ) {
        // Above the deck, which takes the lower half or so while it is open.
        val side = minOf(maxWidth * 0.72f, maxHeight * 0.40f)
        AsyncImage(
            model = artUrl.artworkAt(ART_PX), // the sleeve's own size: sharp, and already in the image cache
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight * 0.08f)
                .size(side)
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}
