package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource

/**
 * Fork: the cover, drawn over a full-screen clip.
 *
 * In the Canvas presentation (Spotify's Canvas, and the fork's Navidrome
 * backdrops) the clip *is* the player and the sleeve steps aside.
 *
 * - A Navidrome backdrop is a background, not the cover: the cover stays on
 *   top of it the whole time, in the upper part of the screen above the
 *   control deck. When the song also has a square cover clip
 *   ([CanvasArtwork.sleeveUrl]) that clip plays in the cover, over the still.
 * - Spotify's Canvas (YouTube songs) keeps upstream's look while playing; only
 *   paused, when the clip has stopped meaning anything, the cover comes back.
 */
@Composable
internal fun BoxScope.ForkPausedCover(
    presentation: Boolean,
    canvas: CanvasArtwork?,
    isPlaying: Boolean,
    panelOpen: Boolean,
    artUrl: String?,
) {
    val backdrop = canvas?.source == CanvasSource.BACKDROP
    val visible = presentation && !panelOpen && (backdrop || !isPlaying)
    val alpha by animateFloatAsState(
        targetValue = if (visible && artUrl != null) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "forkPausedCover",
    )
    if (alpha == 0f || artUrl == null) return
    val coverClip = remember(canvas) {
        canvas?.sleeveUrl?.takeIf { backdrop }?.let { CanvasArtwork(url = it, source = CanvasSource.OTHER) }
    }
    var clipShowing by remember(coverClip) { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier
            .matchParentSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .graphicsLayer { this.alpha = alpha },
    ) {
        // Above the deck, which takes the lower half or so while it is open.
        val side = minOf(maxWidth * 0.72f, maxHeight * 0.40f)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight * 0.08f)
                .size(side)
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = if (clipShowing) 0f else 1f },
            )
            coverClip?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize(),
                    onRenderedChanged = { clipShowing = it },
                )
            }
        }
    }
}
