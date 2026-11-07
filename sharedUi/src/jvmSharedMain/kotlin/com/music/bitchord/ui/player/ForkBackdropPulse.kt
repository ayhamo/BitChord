package com.music.bitchord.ui.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The player backdrop breathing with the music (fork, from PC-Continued 8498b497's
 * `PlayerAudioPulse`; that commit meters the engine but nothing of its own reads the meter yet, so
 * the reading side here is ours).
 *
 * PC only. [enabled] is false until the PC's "Backdrop pulse" setting turns it on, and nothing on
 * the phone writes it or the meters, so the phone's player draws exactly as before: the modifier
 * returns a bare [Modifier] while it is off.
 *
 * The engine writes two already-smoothed numbers per output block (the mix's RMS and its low end,
 * measured before the volume so turning it down does not also flatten the picture) and the
 * backdrop reads them on the frame clock. Plain volatiles rather than snapshot state, as in
 * PC-Continued: a snapshot write on the audio thread would schedule UI work once per block for a
 * number the backdrop only needs once per frame. The engine drops both to zero when it pauses or the
 * queue runs out, so the field is still while nothing plays.
 */
object ForkBackdropPulse {

    /** Set by the PC's Appearance switch only. */
    val enabled = MutableStateFlow(false)

    /** The mix's loudness, smoothed, 0..1: the field's breathing. */
    @Volatile
    var level: Float = 0f
        private set

    /** The mix's low end, smoothed and quicker to rise, 0..1: the kick. */
    @Volatile
    var bass: Float = 0f
        private set

    /**
     * The low band's RMS as it is, unscaled and unclipped, with a quick release: what the "Bass
     * glow" finds kicks in ([ForkBassOnsets]). Not [bass]: that one is scaled for the pulse and
     * sits pinned at 1 through a loud master (0.98 on average over "Starboy", 2026-11-07), where
     * no kick can rise above it, and its slow release hides the dip between two kicks.
     */
    @Volatile
    var kick: Float = 0f
        private set

    fun publish(level: Float, bass: Float, kick: Float = 0f) {
        this.level = level.coerceIn(0f, 1f)
        this.bass = bass.coerceIn(0f, 1f)
        this.kick = kick.coerceAtLeast(0f)
    }

    fun silence() = publish(0f, 0f, 0f)
}

/** How far the loudness zooms the backdrop at full scale. */
private const val LEVEL_ZOOM = 0.035f

/** How far a kick adds to that. */
private const val BASS_ZOOM = 0.025f

/** How much a kick lifts the backdrop's light, screened over it. */
private const val BASS_LIFT = 0.07f

/** Below this a meter counts as silent. */
private const val QUIET = 0.002f

/** How often a silent meter is looked at: no frame is asked for while nothing plays. */
private const val IDLE_POLL_MS = 120L

/**
 * Applies the pulse to a backdrop: a small zoom with the loudness and a kick, and a little light on
 * the kick. Read in the layer and draw lambdas, so a beat redraws the backdrop's layer and never
 * recomposes the player. Clipped to its bounds first so the zoom never spills past the surface.
 * Off with "Reduce animation", as every other moving backdrop is.
 */
fun Modifier.forkBackdropPulse(): Modifier = forkBassGlow().forkPulse()

/**
 * The pulse alone. [forkBackdropPulse] puts the "Bass glow" ([forkBassGlow]) outside it, so the
 * halo is drawn over the zoomed backdrop without being zoomed itself, and both backdrops that carry
 * the pulse carry the glow with no hook of their own.
 */
private fun Modifier.forkPulse(): Modifier = composed {
    val on by ForkBackdropPulse.enabled.collectAsState()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsState()
    if (!on || reduceAnimation) return@composed Modifier
    val level = remember { mutableFloatStateOf(0f) }
    val bass = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        try {
            while (true) {
                val l = ForkBackdropPulse.level.let { if (it < QUIET) 0f else it }
                val b = ForkBackdropPulse.bass.let { if (it < QUIET) 0f else it }
                if (l == 0f && b == 0f && level.floatValue == 0f && bass.floatValue == 0f) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                withFrameNanos { }
                level.floatValue = l
                bass.floatValue = b
            }
        } finally {
            level.floatValue = 0f
            bass.floatValue = 0f
        }
    }
    Modifier
        .clipToBounds()
        .graphicsLayer {
            val zoom = 1f + level.floatValue * LEVEL_ZOOM + bass.floatValue * BASS_ZOOM
            scaleX = zoom
            scaleY = zoom
        }
        .drawWithContent {
            drawContent()
            val lift = bass.floatValue * BASS_LIFT
            if (lift > QUIET) drawRect(Color.White.copy(alpha = lift), blendMode = BlendMode.Screen)
        }
}
