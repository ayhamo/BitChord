package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource

/**
 * The two landscape layouts being compared. Once one is chosen, the other's
 * branch in [ForkLandscapeBackdrop] and its enum value go, nothing else.
 */
internal enum class ForkLandscapeStyle {
    /** The clip fills the right half, behind the controls, its left edge melting into its own blur. */
    BEHIND_CONTROLS,

    /** The clip whole (9:16) and full height in the right half, its own blur around it. */
    WHOLE_RIGHT,
}

/** Which layout the landscape player uses; see [ForkLandscapeStyle]. */
internal val FORK_LANDSCAPE_STYLE = ForkLandscapeStyle.BEHIND_CONTROLS

/**
 * Fork: the full-screen clip for the landscape player (a Navidrome backdrop,
 * or a Spotify Canvas), or null.
 *
 * In portrait a backdrop fills the screen; landscape had none (the sleeve
 * played the cover clip instead, see [forkCanvas]). Here it plays on the right
 * of the screen, where the controls are, while the cover stays on the left.
 */
@Composable
internal fun forkLandscapeBackdrop(raw: CanvasArtwork?): CanvasArtwork? {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    // A Spotify Canvas is the same 9:16 kind of clip (upstream played it cropped in the sleeve here).
    return raw?.takeIf {
        when (it.source) {
            CanvasSource.BACKDROP -> backdrops
            CanvasSource.SPOTIFY -> covers
            else -> false
        }
    }
}

/**
 * Fork: the landscape player's backdrop, drawn as its background (over the
 * usual cover-coloured one, which shows until the clip's first frame).
 *
 * The whole screen becomes a blur of the clip, kept current from frames the
 * player reads back every 0.4 s, small (128 px) and cheap (a second decoder only to blur would cost
 * far more), and the clip itself plays on the right as [FORK_LANDSCAPE_STYLE]
 * lays it out. It freezes with the music, as in portrait. While the lyrics or
 * the queue are up, it is dimmed so they read.
 */
@Composable
internal fun ForkLandscapeBackdrop(
    clip: CanvasArtwork,
    isPlaying: Boolean,
    pausedForTransition: Boolean,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    var frame by remember(clip.url) { mutableStateOf<ImageBitmap?>(null) }
    var rendered by remember(clip.url) { mutableStateOf(false) }
    val shown by animateFloatAsState(if (rendered) 1f else 0f, tween(FORK_BACKDROP_FADE_MS), label = "forkLandscapeBackdrop")
    val scrim by animateFloatAsState(if (dimmed) 0.55f else 0.18f, tween(300), label = "forkLandscapeScrim")

    BoxWithConstraints(modifier.fillMaxSize()) {
        val half = maxWidth / 2
        // The clip's blur, edge to edge.
        frame?.let { BlurredFrame(it, Modifier.fillMaxSize().graphicsLayer { alpha = shown }) }

        val video = @Composable { videoModifier: Modifier ->
            CanvasArtworkPlayer(
                canvas = clip,
                isPlaying = isPlaying,
                contentMode = CanvasContentMode.CROP,
                presentationAlpha = { shown },
                onRenderedChanged = { rendered = it },
                onFrameCaptured = { frame = it },
                refreshFrameEveryMs = FRAME_REFRESH_MS,
                pausedForTransition = pausedForTransition,
                modifier = videoModifier,
            )
        }
        when (FORK_LANDSCAPE_STYLE) {
            ForkLandscapeStyle.BEHIND_CONTROLS -> {
                video(Modifier.align(Alignment.CenterEnd).width(half).fillMaxHeight())
                // The clip's left edge melts into the blur: the same blur again, over it, fading out.
                frame?.let {
                    BlurredFrame(
                        it,
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = shown; compositingStrategy = CompositingStrategy.Offscreen }
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
            }
            ForkLandscapeStyle.WHOLE_RIGHT -> Box(
                Modifier.align(Alignment.CenterEnd).width(half).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                video(Modifier.fillMaxHeight().aspectRatio(9f / 16f))
            }
        }
        // Enough shade for white text on a bright clip; more under the lyrics and queue.
        Box(Modifier.fillMaxSize().drawWithContent { drawRect(Color.Black.copy(alpha = scrim * shown)) })
    }
}

@Composable
private fun BlurredFrame(frame: ImageBitmap, modifier: Modifier) {
    Image(
        bitmap = frame,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.blur(BLUR),
    )
}

private const val FRAME_REFRESH_MS = 400L
private val BLUR = 48.dp
private val FADE = 140.dp
