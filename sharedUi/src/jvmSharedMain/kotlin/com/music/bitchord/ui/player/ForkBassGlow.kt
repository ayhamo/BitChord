package com.music.bitchord.ui.player

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * "Bass glow" (fork, PC only; the look of the "Auris" Reddit post): a soft halo of the cover's
 * accent colour behind the sleeve that flashes on bass hits and dies away between them.
 *
 * [enabled] is false until the PC's Appearance switch turns it on, and nothing on the phone writes
 * it, so the phone's player draws exactly as before: both modifiers here return a bare [Modifier]
 * while it is off.
 *
 * The reading comes from the meter the backdrop pulse already runs ([ForkBackdropPulse.kick], the
 * mix's low end as it is, written per output block by the PC engine and read here on the frame
 * clock; see that object for why it is a plain volatile and not snapshot state). Hits, not level: a
 * sustained bass line holds the meter high and would hold a level-driven glow lit, which reads as a
 * tint, not a beat; [ForkBassOnsets] fires only when the bass rises well above its own recent
 * average.
 *
 * Drawn in the backdrop (the pulse's modifier carries it), centred on the sleeve, which reports
 * where it is through [forkBassGlowAnchor]: the cover covers the middle of the halo, so what shows
 * is the light around it, and it falls to nothing before the controls and the lyrics, so the text
 * keeps its contrast. A paused or stopped player has a silent meter (the engine zeroes it), so the
 * glow decays and the loop goes back to idle polling.
 */
object ForkBassGlow {

    /** Set by the PC's Appearance switch only. */
    val enabled = MutableStateFlow(false)

    /** The sleeve's bounds in the root, as last laid out; null when no sleeve is up. */
    var anchor: Rect? by mutableStateOf(null)
        private set

    /** The cover's accent colour. */
    internal var accent: Color by mutableStateOf(Color.White)

    /** Which sleeve owns [anchor], so one leaving does not clear another's. */
    private var anchorOwner: Any? = null

    /**
     * [owner]'s sleeve is laid out at [bounds] (in the root). A sleeve folded away (the mini
     * player, a closed pane) is no place to glow from and is ignored; an unchanged one writes
     * nothing, so a layout pass with no move does not redraw the backdrop.
     */
    fun place(owner: Any, bounds: Rect) {
        if (bounds.width < 2f || bounds.height < 2f) return
        anchorOwner = owner
        if (anchor != bounds) anchor = bounds
    }

    /** [owner]'s sleeve has left; another sleeve's place is kept (the player swapping layouts). */
    fun release(owner: Any) {
        if (anchorOwner !== owner) return
        anchor = null
        anchorOwner = null
    }
}

/**
 * Onsets in a smoothed bass meter, and the flash they make (fork, [ForkBassGlow]).
 *
 * A hit is the meter climbing to [HIT_RATIO] times its recent average or more: a ratio, so it is
 * the same on a quiet master and a loud one, and needs no scale. The first version took a fixed
 * step on the pulse's scaled meter, which a loud master holds at its ceiling: over "Starboy" it
 * fired about once in seven seconds and nothing was seen (PC app, 2026-11-07). Readings under
 * [FLOOR] are silence. The flash rises in [ATTACK_SECONDS] and
 * decays with [DECAY_SECONDS], about 0.3 s to a tenth: the "quick attack, ~250–400 ms decay" the
 * owner asked for. A hit's size follows the ratio. Plain arithmetic, stepped once per frame, so it
 * is tested without a clock.
 */
class ForkBassOnsets {

    private var average = 0f
    private var previous = 0f
    private var target = 0f
    private var shown = 0f
    private var sinceHit = Float.MAX_VALUE

    /** The flash's strength now, 0..1. */
    val value: Float get() = shown

    /** One frame of [seconds] with the meter at [bass]; returns [value]. */
    fun step(bass: Float, seconds: Float): Float {
        val dt = seconds.coerceIn(0f, MAX_STEP_SECONDS)
        val level = bass.coerceAtLeast(0f)
        // Only while the meter climbs: it lets go of a kick slowly (its own release), and on the
        // way down it can still sit above an average that has only begun to catch up, which fired
        // a second flash on every kick's tail.
        val rising = level > previous
        previous = level
        if (rising && level > FLOOR && level > average * HIT_RATIO) {
            // From nothing (the first sound, after a pause) the ratio is endless: a full flash.
            val ratio = if (average > 0f) level / average else Float.MAX_VALUE
            val strength = ((ratio - 1f) / FULL_RATIO).coerceIn(MIN_STRENGTH, 1f)
            when {
                // A new hit.
                sinceHit >= REFRACTORY_SECONDS -> {
                    target = max(target, strength)
                    sinceHit = 0f
                }
                // The same hit still rising over its first frames: it may grow, not fire again.
                sinceHit < RISE_SECONDS -> target = max(target, strength)
            }
        }
        average += (level - average) * (1f - exp(-dt / AVERAGE_SECONDS))
        sinceHit += dt
        target *= exp(-dt / DECAY_SECONDS)
        shown = if (target > shown) {
            shown + (target - shown) * (1f - exp(-dt / ATTACK_SECONDS))
        } else {
            target
        }
        if (shown < SETTLED) shown = 0f
        if (target < SETTLED) target = 0f
        return shown
    }

    /** Silence: nothing left to show, and the next sound counts from nothing. */
    fun reset() {
        average = 0f
        previous = 0f
        target = 0f
        shown = 0f
        sinceHit = Float.MAX_VALUE
    }

    companion object {
        /** The meter's "recent": a few beats' worth at most, so the next kick stands out again. */
        const val AVERAGE_SECONDS = 0.45f
        /** A hit: the meter at this many times its recent average. */
        const val HIT_RATIO = 1.35f

        /** A rise to (1 + this) times the average flashes at full strength. */
        const val FULL_RATIO = 1f

        /** The low band's RMS under which there is nothing to hear (about -55 dBFS). */
        const val FLOOR = 0.002f
        const val MIN_STRENGTH = 0.35f
        const val REFRACTORY_SECONDS = 0.14f
        const val RISE_SECONDS = 0.06f
        const val ATTACK_SECONDS = 0.025f
        const val DECAY_SECONDS = 0.13f
        const val SETTLED = 0.004f

        /** A frame longer than this (the window hidden, a hitch) counts as this long. */
        const val MAX_STEP_SECONDS = 0.1f
    }
}

/** The halo's strength at a full flash, screened over the backdrop. */
private const val GLOW_ALPHA = 0.55f

/** The halo's reach from the cover's centre, in cover sides. */
const val FORK_GLOW_REACH = 0.95f

/** How often a silent meter is looked at while nothing shows: no frame is asked for. */
private const val GLOW_IDLE_POLL_MS = 120L

/**
 * Nothing to draw and nothing to hear: the glow's loop stops asking for frames and only looks at
 * the meter now and then, so a paused player costs no redraws.
 */
fun forkGlowIdle(kick: Float, shown: Float): Boolean = kick < ForkBassOnsets.FLOOR && shown == 0f

/**
 * The halo's centre and radius in a backdrop laid out at [origin] (in the root) with [size]: round
 * the sleeve at [cover] (also in the root), reaching [FORK_GLOW_REACH] of its longer side; with no
 * sleeve up, round the backdrop's upper middle, where the cover usually sits.
 */
fun forkGlowHalo(cover: Rect?, origin: Offset, size: Size): Pair<Offset, Float> {
    val center = cover?.center?.minus(origin) ?: Offset(size.width / 2f, size.height * 0.4f)
    val side = cover?.let { max(it.width, it.height) } ?: (min(size.width, size.height) * 0.5f)
    return center to side * FORK_GLOW_REACH
}

/**
 * Put on the player's sleeve image: tells the backdrop where the cover is and which colour it is
 * (the most colourful of the player's own palette for [request]'s cover). A bare [Modifier] while
 * the glow is off.
 */
fun Modifier.forkBassGlowAnchor(request: ImageRequest): Modifier = composed {
    val on by ForkBassGlow.enabled.collectAsState()
    if (!on) return@composed Modifier
    val owner = remember { Any() }
    val palette = rememberArtworkColors(request.data as? String)
    LaunchedEffect(palette) { ForkBassGlow.accent = forkGlowAccent(palette.colors) }
    DisposableEffect(owner) {
        onDispose { ForkBassGlow.release(owner) }
    }
    Modifier.onGloballyPositioned { ForkBassGlow.place(owner, it.boundsInRoot()) }
}

/**
 * The glow itself, over a backdrop and under everything on it (carried by [forkBackdropPulse]).
 * Off with "Reduce animation", as every other moving backdrop is.
 */
internal fun Modifier.forkBassGlow(): Modifier = composed {
    val on by ForkBassGlow.enabled.collectAsState()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsState()
    if (!on || reduceAnimation) return@composed Modifier
    val flash = remember { mutableFloatStateOf(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(Unit) {
        val onsets = ForkBassOnsets()
        var last = 0L
        try {
            while (true) {
                if (forkGlowIdle(ForkBackdropPulse.kick, onsets.value)) {
                    onsets.reset()
                    flash.floatValue = 0f
                    last = 0L
                    delay(GLOW_IDLE_POLL_MS)
                    continue
                }
                val now = withFrameNanos { it }
                val seconds = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                flash.floatValue = onsets.step(ForkBackdropPulse.kick, seconds)
            }
        } finally {
            flash.floatValue = 0f
        }
    }
    Modifier
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clipToBounds()
        .drawWithContent {
            drawContent()
            val strength = flash.floatValue
            if (strength <= 0f) return@drawWithContent
            val (center, radius) = forkGlowHalo(ForkBassGlow.anchor, origin, size)
            if (radius <= 0f) return@drawWithContent
            val color = ForkBassGlow.accent
            val alpha = strength * GLOW_ALPHA
            drawCircle(
                brush = Brush.radialGradient(
                    // Strongest at the cover's edge (about 0.53 of the reach), gone well before the reach.
                    0f to color.copy(alpha = alpha),
                    0.5f to color.copy(alpha = alpha * 0.85f),
                    0.75f to color.copy(alpha = alpha * 0.3f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
                blendMode = BlendMode.Screen,
            )
        }
}

/**
 * The palette colour with the most colour in it, brightened to glow: a halo in a dark, muddy
 * swatch would read as a shadow, not light. White for a palette with nothing to pick.
 */
fun forkGlowAccent(colors: List<Color>): Color {
    val best = colors.maxByOrNull { c ->
        val hi = max(c.red, max(c.green, c.blue))
        val lo = min(c.red, min(c.green, c.blue))
        (hi - lo) + hi * 0.25f
    } ?: return Color.White
    val hi = max(best.red, max(best.green, best.blue))
    if (hi <= 0.01f) return Color.White
    // Scaled so its brightest channel is full, keeping the hue and saturation.
    return Color(best.red / hi, best.green / hi, best.blue / hi)
}
