package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource

/**
 * Fork: the full-screen clip for the landscape player (a Navidrome backdrop,
 * or a Spotify Canvas), or null.
 *
 * In portrait a backdrop fills the screen; landscape had none (the sleeve
 * played the cover clip instead, see [forkCanvas], and upstream played a
 * Spotify Canvas cropped in the sleeve). Here it plays on the right of the
 * screen, where the controls are, while the cover stays on the left.
 */
@Composable
internal fun forkLandscapeBackdrop(raw: CanvasArtwork?): CanvasArtwork? {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    return raw?.takeIf {
        when (it.source) {
            CanvasSource.BACKDROP -> backdrops
            CanvasSource.SPOTIFY -> covers
            else -> false
        }
    }
}

/**
 * Fork: the landscape player's backdrop, drawn as its background.
 *
 * The clip fills the right half, behind the controls, and its left edge melts
 * into a blur of the still cover, which fills the rest of the screen. The
 * cover, not the clip: a blur read back from the clip a few times a second
 * flickered and lagged behind it, and the cover beside it is what the left
 * half shows anyway. It freezes with the music, as in portrait, and is dimmed
 * while the lyrics or the queue are up so they read.
 */
@Composable
internal fun ForkLandscapeBackdrop(
    clip: CanvasArtwork,
    coverUrl: String?,
    isPlaying: Boolean,
    pausedForTransition: Boolean,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    var rendered by remember(clip.url) { mutableStateOf(false) }
    val shown by animateFloatAsState(if (rendered) 1f else 0f, tween(FORK_BACKDROP_FADE_MS), label = "forkLandscapeBackdrop")
    val scrim by animateFloatAsState(if (dimmed) 0.55f else 0.18f, tween(300), label = "forkLandscapeScrim")

    BoxWithConstraints(modifier.fillMaxSize()) {
        val half = maxWidth / 2
        BlurredCover(coverUrl, Modifier.fillMaxSize())
        CanvasArtworkPlayer(
            canvas = clip,
            isPlaying = isPlaying,
            contentMode = CanvasContentMode.CROP,
            presentationAlpha = { shown },
            onRenderedChanged = { rendered = it },
            pausedForTransition = pausedForTransition,
            modifier = Modifier.align(Alignment.CenterEnd).width(half).fillMaxHeight(),
        )
        // The clip's left edge melts into the blur: the same blur again, over it, fading out.
        BlurredCover(
            coverUrl,
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edge = size.width / 2
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black,
                            edge / size.width to Color.Black,
                            (edge + FADE.toPx()) / size.width to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
        // Enough shade for white text on a bright clip; more under the lyrics and queue.
        Box(Modifier.fillMaxSize().drawWithContent { drawRect(Color.Black.copy(alpha = scrim)) })
    }
}

@Composable
private fun BlurredCover(url: String?, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.blur(BLUR),
    )
}

private val BLUR = 48.dp
private val FADE = 140.dp
