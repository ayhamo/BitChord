package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fork: "Crossfade on skip". Next and Previous blend into the new track for as long as the
 * Crossfade slider says, the phone's `CrossfadeController.crossfadeSkip`. The running of it is
 * [DesktopPlaybackEngine.forkSkipTo]; this is the switch and the arithmetic.
 */
internal object ForkCrossfadeOnSkip {

    const val TITLE = "Crossfade on skip"
    const val SUBTITLE = "Next and Previous blend into the new track too, also with Automix on (Automix still mixes songs that end on their own)"

    /**
     * How long a skip whose track isn't open yet may keep the old song going before it happens as
     * a plain cut, the phone's `SKIP_ARM_TIMEOUT_MS`. An addon can take 10-15s to answer, and a
     * press that does nothing for that long reads as a press that did nothing.
     */
    const val ARM_TIMEOUT_MS = 3_000L

    /**
     * What a claimed skip ticket is swapped for: still non-null, so everything that holds back
     * while a skip is in flight keeps holding until the winner clears it.
     */
    val CLAIMED = Any()

    /**
     * Takes [ticket] for one of its two finishers, the arm timeout's plain cut or the audio thread's
     * blend. Both used to check the ticket and then act, so a deck landing just as the 3 s ran out
     * (microseconds apart) did both: the blend started and the [load] restarted the song under it
     * (audit 2026-11-05). Exactly one compare-and-set succeeds; the loser does nothing.
     */
    fun claim(holder: java.util.concurrent.atomic.AtomicReference<Any?>, ticket: Any): Boolean =
        holder.compareAndSet(ticket, CLAIMED)

    private const val KEY = "crossfade_on_skip"

    private val persistence by lazy { DesktopPersistence() }

    private val _enabled = MutableStateFlow(persistence.boolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        persistence.saveBoolean(KEY, on)
    }

    /**
     * The blend a skip gets right now, 0 for the plain cut: the slider's length, or Automix's own
     * fallback with the slider off, as on the phone. Never in a party: a blend starts the next
     * track at a moment this computer decides, and the transition a party shares is the plain one.
     */
    fun seconds(crossfadeSeconds: Int, automix: Boolean): Int = when {
        !_enabled.value || DesktopListenTogether.state.value.inParty -> 0
        crossfadeSeconds > 0 -> crossfadeSeconds
        automix -> DesktopPlaybackEngine.AUTOMIX_FALLBACK_SECONDS
        else -> 0
    }

    /**
     * A plain equal-power fade from where the outgoing track is, with no time to analyse the pair.
     *
     * Kept inside what is left of either track: past the outgoing track's end the fader goes on
     * rising over silence, which is heard as the new song starting quiet.
     */
    fun plan(positionMs: Long, outgoingUs: Long?, incomingUs: Long?, seconds: Int): TransitionPlan {
        val outgoingLeft = outgoingUs?.takeIf { it > 0 }?.let { (it / 1_000 - positionMs) / 1_000.0 } ?: Double.MAX_VALUE
        val incomingLength = incomingUs?.takeIf { it > 0 }?.let { it / 1_000_000.0 } ?: Double.MAX_VALUE
        val fade = seconds.toDouble().coerceAtMost(outgoingLeft).coerceAtMost(incomingLength).coerceAtLeast(1.0)
        val now = positionMs / 1_000.0
        return TransitionPlan(
            shouldStart = true,
            transitionStart = now,
            transitionEnd = now + fade,
            fadeSeconds = fade,
            transitionStyle = TransitionStyle.EQUAL_POWER,
        )
    }
}
