package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.ui.player.ForkBackdropPulse
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * "Backdrop pulse" (Appearance), off by default (fork, from PC-Continued 8498b497).
 *
 * The switch is stored here and mirrored into sharedUi's [ForkBackdropPulse.enabled], the flag the
 * player's backdrop reads; the phone never sets that flag, which is what keeps its player unchanged.
 * Mirrored when this object is first touched: by the engine's first block or by the settings row,
 * whichever comes first, and either comes before the backdrop has anything to show.
 */
internal object ForkBackdropPulseSetting {

    private const val KEY = "fork_backdrop_pulse"

    private val persistence = DesktopPersistence()

    init {
        ForkBackdropPulse.enabled.value = persistence.boolean(KEY, false)
    }

    val enabled: StateFlow<Boolean> get() = ForkBackdropPulse.enabled

    /** Read by the audio thread once per block. */
    val on: Boolean get() = ForkBackdropPulse.enabled.value

    fun set(value: Boolean) {
        persistence.saveBoolean(KEY, value)
        ForkBackdropPulse.enabled.value = value
        if (!value) ForkBackdropPulse.silence()
    }
}

/**
 * The backdrop's meters, fed from the engine's output block just before it is written (fork, from
 * PC-Continued 8498b497's `publishPulse`, same constants).
 *
 * The mix's RMS and the RMS of its low end (a one-pole low-pass at [LOW_HZ]: kicks and bass lines,
 * which are what make the field lift), each smoothed with a quick attack and a slow release so a
 * kick lifts the field and the next quiet bar lets it down gradually. Measured before the volume,
 * which the sink applies, so turning the music down does not flatten the picture. Owned by the audio
 * thread; does nothing at all while the setting is off.
 */
internal class ForkBackdropPulseMeter {

    private var low = 0f
    private var level = 0f
    private var bass = 0f

    fun meter(samples: FloatArray, count: Int, channels: Int, sampleRate: Int) {
        if (!ForkBackdropPulseSetting.on) {
            if (level != 0f || bass != 0f) silence()
            return
        }
        if (count <= 0) return
        val rate = sampleRate.coerceAtLeast(1)
        val lowCoefficient = 1f - exp(-2.0 * PI * LOW_HZ / rate).toFloat()
        var squares = 0.0
        var lowSquares = 0.0
        for (index in 0 until count) {
            val sample = samples[index]
            squares += sample.toDouble() * sample
            low += lowCoefficient * (sample - low)
            lowSquares += low.toDouble() * low
        }
        val target = (sqrt(squares / count) * LEVEL_SCALE).coerceIn(0.0, 1.0).toFloat()
        val bassTarget = (sqrt(lowSquares / count) * BASS_SCALE).coerceIn(0.0, 1.0).toFloat()
        val seconds = count.toDouble() / channels.coerceAtLeast(1) / rate
        val attack = (1.0 - exp(-seconds / ATTACK_SECONDS)).toFloat()
        val release = (1.0 - exp(-seconds / RELEASE_SECONDS)).toFloat()
        val bassAttack = (1.0 - exp(-seconds / BASS_ATTACK_SECONDS)).toFloat()
        level += (target - level) * if (target > level) attack else release
        bass += (bassTarget - bass) * if (bassTarget > bass) bassAttack else release
        ForkBackdropPulse.publish(level, bass)
    }

    /** Paused, or nothing left to play: the field settles rather than holding the last block. */
    fun silence() {
        low = 0f
        level = 0f
        bass = 0f
        ForkBackdropPulse.silence()
    }

    private companion object {
        /** The low band's corner. */
        const val LOW_HZ = 180.0

        /** A full meter at an RMS of 1/scale; loud masters sit around 0.2 to 0.3 RMS. */
        const val LEVEL_SCALE = 4.2
        const val BASS_SCALE = 5.5
        const val ATTACK_SECONDS = 0.045
        const val BASS_ATTACK_SECONDS = 0.012
        const val RELEASE_SECONDS = 0.32
    }
}

/** Appearance, after "Legacy mesh gradient": greyed while "Reduce animation" holds every backdrop still. */
@Composable
internal fun ForkBackdropPulseRow() {
    val on by ForkBackdropPulseSetting.enabled.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    ForkDisabledWhen(reduceAnimation) {
        SettingsToggle(
            DesktopStrings["fork_backdrop_pulse", "Backdrop pulse"],
            DesktopStrings[
                "fork_backdrop_pulse_subtitle",
                "The player's background breathes with the music and the kick. Still while paused",
            ],
            on,
            ForkBackdropPulseSetting::set,
        )
    }
}
