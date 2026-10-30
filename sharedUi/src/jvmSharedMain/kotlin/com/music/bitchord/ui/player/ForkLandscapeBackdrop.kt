package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasArtwork

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
    return raw?.takeIf { backdrops && it.source.fillsPhone }
}

/**
 * Fork: the landscape player's backdrop, drawn as its background over the
 * player's own blurred cover (upstream's full-artwork blur, drawn just below).
 *
 * The clip fills the right half, behind the controls, and its left edge melts
 * into that blur: the same pre-blurred bitmap ([blurred], upstream's
 * `rememberFullArtworkBlurImage`) laid over the clip again and faded out
 * across [FADE]. The cover, not the clip: a blur read back from the clip a few
 * times a second flickered and lagged behind it. A small pre-blurred bitmap,
 * not a screen-sized `Modifier.blur`: that one costs a full-screen render
 * effect, and does nothing at all below Android 12 (a sharp cover beside the
 * clip). The clip holds on the cover for a moment first, as in portrait
 * ([rememberForkBackdropIntro]), freezes with the music, and is dimmed while
 * the lyrics or the queue are up so they read.
 */
@Composable
internal fun ForkLandscapeBackdrop(
    clip: CanvasArtwork,
    blurred: ImageBitmap?,
    isPlaying: Boolean,
    pausedForTransition: Boolean,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    var rendered by remember(clip.url) { mutableStateOf(false) }
    val shown by animateFloatAsState(if (rendered) 1f else 0f, tween(FORK_BACKDROP_FADE_MS), label = "forkLandscapeBackdrop")
    val scrim by animateFloatAsState(if (dimmed) 0.55f else 0.18f, tween(300), label = "forkLandscapeScrim")
    val intro = rememberForkBackdropIntro(clip.url, true)

    Box(modifier.fillMaxSize()) {
        CanvasArtworkPlayer(
            canvas = clip,
            isPlaying = isPlaying,
            contentMode = CanvasContentMode.CROP,
            presentationAlpha = { shown * intro.alpha },
            onRenderedChanged = { intro.rendered(it) { r -> rendered = r } },
            pausedForTransition = pausedForTransition,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight(),
        )
        // The clip's left edge melts into the blur: the blur again, over it, fading out.
        if (blurred != null) {
            Image(
                bitmap = blurred,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
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
        }
        // Enough shade for white text on a bright clip; more under the lyrics and queue.
        Box(Modifier.fillMaxSize().drawWithContent { drawRect(Color.Black.copy(alpha = scrim)) })
    }
}

private val FADE = 140.dp
