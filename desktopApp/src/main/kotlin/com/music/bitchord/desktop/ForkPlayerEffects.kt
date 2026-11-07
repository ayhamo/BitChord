package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.ui.player.ForkBassGlow
import com.music.bitchord.ui.player.ForkCoverFade

/**
 * The PC's switches for two player effects drawn in sharedUi (fork): "Cover fade" ([ForkCoverFade],
 * the phone has the same switch, and backups carry it between the two as the phone's `cover_fade`)
 * and "Bass glow" ([ForkBassGlow], PC only). Both off by default, which is upstream's look.
 *
 * Stored in the main settings node, mirrored into sharedUi's flags by [install] at start (from
 * [ForkNavidrome.install], before the player is drawn) and by each switch.
 */
internal object ForkPlayerEffects {

    /** The PC's key; `cover_fade` in the phone's `bitchord_fork` (ForkBackup maps the two). */
    const val KEY_COVER_FADE = "fork_cover_fade"
    const val KEY_BASS_GLOW = "fork_bass_glow"

    private val persistence by lazy { DesktopPersistence() }

    fun install() {
        ForkCoverFade.enabled.value = persistence.boolean(KEY_COVER_FADE, false)
        ForkBassGlow.enabled.value = persistence.boolean(KEY_BASS_GLOW, false)
    }

    fun setCoverFade(on: Boolean) {
        persistence.saveBoolean(KEY_COVER_FADE, on)
        ForkCoverFade.enabled.value = on
    }

    fun setBassGlow(on: Boolean) {
        persistence.saveBoolean(KEY_BASS_GLOW, on)
        ForkBassGlow.enabled.value = on
    }

    /** Read by the audio thread once per block: the pulse's meter also runs for the glow. */
    val bassGlowOn: Boolean get() = ForkBassGlow.enabled.value
}

/**
 * Appearance, after "Backdrop pulse": both greyed (never hidden) while "Reduce animation" holds
 * every moving part still, which is when both draw nothing.
 */
@Composable
internal fun ForkPlayerEffectsRows() {
    val coverFade by ForkCoverFade.enabled.collectAsState()
    val bassGlow by ForkBassGlow.enabled.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    ForkDisabledWhen(reduceAnimation) {
        SettingsToggle(
            DesktopStrings["fork_cover_fade", "Cover fade"],
            DesktopStrings[
                "fork_cover_fade_subtitle",
                "When the song changes, the old cover stays until the new one has loaded, then dissolves into it",
            ],
            coverFade,
            ForkPlayerEffects::setCoverFade,
        )
    }
    ForkDisabledWhen(reduceAnimation) {
        SettingsToggle(
            DesktopStrings["fork_bass_glow", "Bass glow"],
            DesktopStrings[
                "fork_bass_glow_subtitle",
                "A soft light in the cover's colour flashes around it on bass hits. Settles while paused",
            ],
            bassGlow,
            ForkPlayerEffects::setBassGlow,
        )
    }
}
