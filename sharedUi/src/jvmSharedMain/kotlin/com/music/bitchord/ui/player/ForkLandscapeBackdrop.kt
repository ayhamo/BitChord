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
import androidx.compose.runtime.key
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
import com.music.bitchord.data.canvas.CanvasArtwork

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
    val brightTenth = remember(blurred) { ForkBrightTenth.p90Of(blurred) } // a near-white cover gets more shade (ForkBrightTenth)
    val scrim by animateFloatAsState(ForkBrightTenth.alpha(if (dimmed) 0.55f else 0.18f, brightTenth), tween(300), label = "forkLandscapeScrim")
    val intro = rememberForkBackdropIntro(clip.url, true)

    Box(modifier.fillMaxSize()) {
        // One player per clip, as in portrait: changed in place, the new clip stays hidden for good.
        key(clip.url) {
            CanvasArtworkPlayer(
                canvas = clip,
                isPlaying = isPlaying,
                contentMode = CanvasContentMode.CROP,
                presentationAlpha = { shown * intro.alpha },
                onRenderedChanged = { intro.rendered(it) { r -> rendered = r } },
                pausedForTransition = pausedForTransition,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight(),
            )
        }
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
                        val fadeEnd = forkFadeEnd(size.width, FADE.toPx()) ?: return@drawWithContent
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Black,
                                0.5f to Color.Black,
                                fadeEnd to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
        // Enough shade for white text on a bright clip, more on a near-white cover; more under the lyrics and queue.
        Box(Modifier.fillMaxSize().drawWithContent { drawRect(Color.Black.copy(alpha = scrim)) })
    }
}

private val FADE = 140.dp

/**
 * Where the clip's fade over the blur ends, as a fraction of [width] (it starts at the middle), or null
 * when there is no width to draw on. Clamped to the right edge: under `2 * FADE` wide (a narrow PC
 * window) the stop went past 1, and at zero width (a minimised window, the first layout pass) the
 * stops were NaN and Infinity, handed to the gradient as they were (audit 2026-11-26 2a #2).
 */
internal fun forkFadeEnd(width: Float, fadePx: Float): Float? {
    if (!(width > 0f) || !width.isFinite()) return null
    return ((width / 2 + fadePx.coerceAtLeast(0f)) / width).coerceIn(0.5f, 1f)
}
