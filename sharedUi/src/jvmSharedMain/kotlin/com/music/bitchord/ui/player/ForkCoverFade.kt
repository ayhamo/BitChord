package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * "Cover fade" (fork; the owner's spec of 2026-11-04, from a screen recording of another player):
 * at a song change the old cover stays where it is until the new one has loaded, then dissolves
 * into it in place, ease-in-out-sine, [FORK_COVER_FADE_MS].
 *
 * Upstream lets the sleeve go blank the moment the song changes: the new request starts, the
 * painter has nothing, and the placeholder tile (and on a Navidrome cover, resized by the server on
 * first request, the black palette with it) shows until the bitmap arrives, then the new cover pops
 * in (the phone's loader fades it over 200 ms; the PC's loader not at all).
 *
 * [enabled] is off until a Settings switch turns it on (the phone's `ForkSettings`, the PC's
 * Appearance), so a player nobody has opted in keeps upstream's look exactly: the modifier is a
 * bare [Modifier] while it is off, and while "Reduce animation" is on.
 *
 * How: the cover last seen loaded is remembered ([ForkCoverFadeState.settled], the exact [ImageRequest]
 * object the sleeve drew, so Coil finds the bitmap in its memory cache and the overlay's painter
 * compares equal to it); when the sleeve's request changes, that cover is drawn on top of the
 * sleeve at full strength, and once the new one reports loaded its strength runs down to nothing.
 * Drawn after the sleeve's own content, inside the sleeve's clip and inside whatever decides
 * whether the still cover is drawn at all: put after the sleeve image's own draw rule, so wherever
 * a cover clip has taken the sleeve (the rule skips the still) the overlay is skipped with it and
 * the clip is never covered or delayed. A crossfade or Automix change hands the session over once,
 * mid-blend, which is one cover change like any other.
 *
 * The optional front Y-flip mentioned with the spec is not done: in place it needs a second layer
 * with its own camera distance and back-face handling, which is not free.
 */
object ForkCoverFade {

    /** Set by the phone's and the PC's "Cover fade" switches only. */
    val enabled = MutableStateFlow(false)
}

/** The dissolve's length: inside the owner's 0.6–0.9 s. */
internal const val FORK_COVER_FADE_MS = 750

/**
 * How long a new cover that never arrives (no network, a dead link, a song with no artwork) keeps
 * the old one up before it is let go, so the sleeve never shows the previous song's cover for good.
 * Past the first of [rememberPlayerArtwork]'s retries, so a cover that only needed one still fades.
 */
const val FORK_COVER_HOLD_MAX_MS = 4_000L

/** Ease-in-out-sine: slow off the old cover, slow into the new one, no visible edge at either end. */
internal val ForkEaseInOutSine = Easing { t -> forkEaseInOutSine(t) }

fun forkEaseInOutSine(t: Float): Float = (-(cos(PI * t.coerceIn(0f, 1f)) - 1) / 2).toFloat()

/**
 * What one sleeve's fade remembers between song changes (fork, [forkCoverFade]): the cover last
 * seen loaded, which the next change dissolves from. Plain logic, so it is tested without Compose
 * (`ForkPlayerEffectsTest`); [T] is the sleeve's [ImageRequest] in the app.
 */
class ForkCoverFadeState<T : Any> {

    /** The cover last seen loaded in this sleeve; null before the first, or after one never came. */
    var settled: T? by mutableStateOf(null)
        private set

    /**
     * The cover to dissolve from when the sleeve changes to [request]: the settled one, unless it
     * is the very same request (a recomposition, not a change). Compared by identity: the sleeve
     * builds a new request for each song, and the overlay's painter must match the sleeve's own.
     */
    fun outgoingFor(request: T): T? = settled?.takeIf { it !== request }

    /**
     * [request] is on screen. Settled at once, not when the fade ends: a skip in the middle of a
     * fade then dissolves from the cover that is mostly showing, not back to the older one.
     */
    fun loaded(request: T) {
        settled = request
    }

    /** The new cover never came ([FORK_COVER_HOLD_MAX_MS]): the placeholder shows, nothing to fade from. */
    fun gaveUp() {
        settled = null
    }
}

/**
 * How long the old cover stays at full strength before it dissolves: null when there is nothing
 * to fade from, none once the new cover is on screen, and at most [FORK_COVER_HOLD_MAX_MS] while it
 * is still loading (the hold restarts as "loaded" when it arrives, which ends it early).
 */
fun forkCoverFadeHoldMs(hasOutgoing: Boolean, loaded: Boolean): Long? = when {
    !hasOutgoing -> null
    loaded -> 0L
    else -> FORK_COVER_HOLD_MAX_MS
}

/**
 * Where a picture of [intrinsic] size is drawn to fill [box] as `ContentScale.Crop` centres it, as
 * the sleeve draws its own: its top-left and size. Null for a picture with no size to scale.
 */
fun forkCropPlacement(box: Size, intrinsic: Size): Pair<Offset, Size>? {
    if (intrinsic == Size.Unspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) return null
    val scale = max(box.width / intrinsic.width, box.height / intrinsic.height)
    val drawn = Size(intrinsic.width * scale, intrinsic.height * scale)
    return Offset((box.width - drawn.width) / 2f, (box.height - drawn.height) / 2f) to drawn
}

/**
 * The fade over a sleeve image drawn from [request]; [loaded] is that request's own "on screen"
 * flag ([PlayerArtwork.loaded]). Goes last in the image's modifier chain (see [ForkCoverFade]).
 */
fun Modifier.forkCoverFade(request: ImageRequest, loaded: Boolean): Modifier = composed {
    val on by ForkCoverFade.enabled.collectAsState()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsState()
    if (!on || reduceAnimation) return@composed Modifier
    val state = remember { ForkCoverFadeState<ImageRequest>() }
    // Taken in the composition where the request changes, so the first frame of the new song
    // already has the old cover over it: from an effect it would be one frame late, and that
    // frame is the blank sleeve this exists to hide.
    val outgoing = remember(request) { state.outgoingFor(request) }
    val strength = remember(request) { Animatable(if (outgoing != null) 1f else 0f) }
    // Not the strength itself: read here, every frame of the fade would recompose the sleeve.
    var fading by remember(request) { mutableStateOf(outgoing != null) }
    // One painter, on the old cover while it fades and on the current one otherwise, so at the
    // next change it already holds the bitmap and the hand-over draws in its first frame.
    val painter = rememberAsyncImagePainter(model = if (fading) outgoing else state.settled)
    LaunchedEffect(request, loaded) {
        if (loaded) state.loaded(request)
        val hold = forkCoverFadeHoldMs(hasOutgoing = outgoing != null, loaded = loaded) ?: return@LaunchedEffect
        if (hold > 0L) {
            delay(hold)
            state.gaveUp()
        }
        strength.animateTo(0f, tween(FORK_COVER_FADE_MS, easing = ForkEaseInOutSine))
        fading = false
    }
    Modifier.drawWithContent {
        drawContent()
        val alpha = strength.value
        if (alpha <= 0f || painter.state.value !is AsyncImagePainter.State.Success) return@drawWithContent
        val (at, drawn) = forkCropPlacement(size, painter.intrinsicSize) ?: return@drawWithContent
        clipRect {
            translate(at.x, at.y) {
                with(painter) { draw(drawn, alpha = alpha) }
            }
        }
    }
}
