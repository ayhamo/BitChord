package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Karaoke: the centre of the stereo field taken out of the mix (fork, from PC-Continued e16ed1f8's
 * `DesktopEqualizer.setVocalRemoval`).
 *
 * It lives under the equaliser, as there: the switch sits in the equaliser dialog, whose body is
 * already dimmed and closed to the pointer while the equaliser is off, and [engaged] only holds while
 * both are on, so switching the equaliser off is a full bypass rather than a canceller left running
 * behind a flat curve.
 */
internal object ForkKaraoke {

    private val persistence = DesktopPersistence()

    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))

    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        persistence.saveBoolean(KEY_ENABLED, value)
    }

    /** Read by the audio thread once per block; both are StateFlow reads, which are safe there. */
    val engaged: Boolean
        get() = _enabled.value && DesktopEqualizerSettings.enabled.value

    private const val KEY_ENABLED = "fork_karaoke_enabled"
}

/**
 * The centre-channel canceller, one per equaliser (so one per deck of a mix).
 *
 * A lead vocal is usually mixed dead centre, so subtracting the middle (what both channels share)
 * from both leaves the sides and the vocal drops away, along with anything else panned centre: it
 * is a cancellation, not a separator, so a centred bass or kick goes with it. The amount glides
 * (about 11 ms at 44.1 kHz, as PC-Continued's) so toggling it is not a click. It cannot push a
 * sample past full scale: |L - (L + R) / 2| = |L - R| / 2, never more than the louder input.
 * Mono and multichannel streams are left alone: there is no stereo centre to take out.
 *
 * Nor is a stereo stream whose two channels are the same signal: a mono source the decoder
 * upmixed to the stereo output, or a "dual mono" stereo file. Its whole content *is* the centre,
 * so cancelling it played as silence (audit 2026-11-05), and Skip silence then raced through the
 * song. So the canceller only engages while the source is not known to be mono ([process]'s
 * `sourceChannels`, the decoder's measured format) *and* the side signal (L - R) carries real
 * energy over a short window ([WINDOW] frames, about 90 ms at 44.1 kHz); otherwise it glides
 * back out, the same glide as the switch. A silent window keeps the last verdict, so a quiet
 * intro or a gap does not toggle it.
 */
internal class ForkVocalCanceller {

    private var amount = 0f

    /** The last window's verdict: the two channels differ enough to have a centre to take out. */
    private var stereo = false
    private var midEnergy = 0.0
    private var sideEnergy = 0.0
    private var frames = 0

    /**
     * Cancels the centre of [count] interleaved samples of [block], in place. [sourceChannels] is
     * how many channels the source itself has (the decoder's measured format), when known.
     */
    fun process(block: FloatArray, count: Int, channels: Int, sourceChannels: Int? = null) =
        process(block, count, channels, sourceChannels, ForkKaraoke.engaged)

    /** [process] with the switch passed in, so it can be tested without the settings store. */
    internal fun process(block: FloatArray, count: Int, channels: Int, sourceChannels: Int?, engaged: Boolean) {
        if (channels != 2) return
        val wanted = engaged && sourceChannels != 1
        if (!wanted && amount < SETTLED) {
            amount = 0f
            forgetMeter()
            return
        }
        var at = 0
        while (at + 1 < count) {
            val left = block[at]
            val right = block[at + 1]
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f
            midEnergy += (mid * mid).toDouble()
            sideEnergy += (side * side).toDouble()
            if (++frames >= WINDOW) judgeWindow()
            val target = if (wanted && stereo) 1f else 0f
            amount += (target - amount) * GLIDE
            val middle = mid * amount
            block[at] = left - middle
            block[at + 1] = right - middle
            at += 2
        }
    }

    private fun judgeWindow() {
        // Silence (or near it) says nothing about the stereo field: keep the last verdict.
        if (midEnergy + sideEnergy > SILENT_PER_FRAME * frames) {
            stereo = sideEnergy > midEnergy * SIDE_RATIO
        }
        midEnergy = 0.0
        sideEnergy = 0.0
        frames = 0
    }

    private fun forgetMeter() {
        stereo = false
        midEnergy = 0.0
        sideEnergy = 0.0
        frames = 0
    }

    private companion object {
        /** Per-frame glide, as PC-Continued's: about 11 ms. */
        const val GLIDE = 0.002f

        /** Below this the canceller is doing nothing anyone can hear. */
        const val SETTLED = 1e-4f

        /** Frames per stereo-field measurement: about 90 ms at 44.1 kHz. */
        const val WINDOW = 4096

        /**
         * Side energy below this share of the middle's (40 dB down) is "the same signal on both
         * channels": encoder noise on a dual-mono file sits far below it, a real mix far above.
         */
        const val SIDE_RATIO = 1e-4

        /** Mean energy per frame below which a window is treated as silence (about -80 dBFS). */
        const val SILENT_PER_FRAME = 1e-8
    }
}

/**
 * The switch under the equaliser's balance. No greying of its own: the equaliser body it sits in
 * is dimmed while the equaliser is off, so the row stays visible and greyed with it, and the
 * switch is disabled then.
 */
@Composable
internal fun ForkKaraokeRow() {
    val on by ForkKaraoke.enabled.collectAsState()
    val equalizerOn by DesktopEqualizerSettings.enabled.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = panelInset(22.dp), end = panelInset(22.dp), top = 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                DesktopStrings["fork_karaoke", "Karaoke"],
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                DesktopStrings[
                    "fork_karaoke_footer",
                    "Cancels the centre of the stereo mix, where the lead vocal usually sits. " +
                        "Bass and kick drums are often centred too, so they drop with it.",
                ],
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = on,
            onCheckedChange = ForkKaraoke::setEnabled,
            // The body's empty pointerInput dims but consumes nothing, so the switch itself is
            // what stops answering while the equaliser is off.
            enabled = equalizerOn,
            colors = desktopSwitchColors(),
        )
    }
}
