package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Automix's transition texture, reshaped (fork, from PC-Continued 8498b497).
 *
 * Three changes to how the engine's texture send (flanger, phaser, crush, vibrato, stereo) rides a
 * blend, taken without the rest of that commit's engine (its stacked textures and untrimmed decks are
 * not ours):
 *
 * - **A bell over the summed mix.** The send used to open four tenths in and rise to the end, fed the
 *   outgoing deck alone: the one moment the outgoing is almost gone, which is why the flanger was hard
 *   to hear at all. It now opens early, is widest with both decks equally present and closes as the
 *   outgoing does, and it is fed the *summed dry*, so the sweep sits on the mix rather than being an
 *   ornament on the track that happens to be leaving.
 * - **The send gives way, not the mix.** The texture's wet overlaps the dry by a few milliseconds, so
 *   its peaks add to the decks'. Past the ceiling the sends are trimmed and the decks keep the engine's
 *   own trim; trimming the decks to make room for an ornament is a loudness dip through every mix.
 * - **A fallback fade gets the flanger.** A pair the planner had no tempo evidence for gets a bare
 *   equal-power fade, and the effect is most of what separates a mix from a crossfade. Only on an
 *   automatic Automix handover: a manual crossfade, a skip-mix and Gapless stay dry.
 */
internal object ForkAutomixFx {

    /** Where the bell opens, as a fraction of the fade. */
    private const val TEXTURE_START = 0.12f

    /** The bell's peak send. */
    private const val TEXTURE_SEND = 0.5f

    /** The send's wet, for the headroom check: the flanger adds to the dry, it does not replace it. */
    private const val TEXTURE_HEADROOM = 1.2f

    /** An equal-power crossfade's peak sum: the level a blend may reach before the sends give way. */
    private const val CEILING = 1.42f

    /** Shortest fallback fade worth texturing; anything shorter is a skip, not a mix. */
    private const val FALLBACK_TEXTURE_MIN_SECONDS = 3.0

    /** The texture's send at [progress] through the fade: a half-sine from [TEXTURE_START] to the end. */
    fun textureRamp(progress: Float): Float {
        val through = ((progress - TEXTURE_START) / (1f - TEXTURE_START)).coerceIn(0f, 1f)
        return sin(through * PI).toFloat() * TEXTURE_SEND
    }

    /**
     * The trim for the sends, given the decks' level after the engine's own trim ([deckLevel]) and
     * the texture's [ramp]. 1 while the two fit under [CEILING].
     */
    fun fxTrim(deckLevel: Float, ramp: Float): Float {
        val textured = deckLevel + ramp * TEXTURE_HEADROOM
        return if (textured <= CEILING) 1f else sqrt(CEILING / textured)
    }

    /**
     * The texture for a handover the engine's own choice gave none: the flanger on an automatic
     * Automix fallback fade ([TransitionStyle.EQUAL_POWER]) of at least three seconds with no echo.
     */
    fun fallbackTexture(plan: TransitionPlan, automaticAutomix: Boolean): TransitionTexture = when {
        !automaticAutomix -> TransitionTexture.NONE
        plan.transitionStyle != TransitionStyle.EQUAL_POWER -> TransitionTexture.NONE
        plan.echoSeconds > 0.0 -> TransitionTexture.NONE
        plan.fadeSeconds < FALLBACK_TEXTURE_MIN_SECONDS -> TransitionTexture.NONE
        else -> TransitionTexture.FLANGER
    }
}
