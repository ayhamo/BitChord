package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.PlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: upstream v1.9.1's "Player theme" (Default / Simple / Legacy) and "Disable blend effects"
 * on the PC, the phone's two rows (`SettingsSheet`, `AppSettings.playerTheme` /
 * `.disableBlendEffects`). They replace the PC's "Legacy mesh gradient" switch, as the phone's
 * Player theme replaced its own: a PC that had the switch on starts on Legacy, the phone's
 * migration (`AppSettings`, `KEY_LEGACY_MESH_GRADIENT`).
 */
internal object ForkPlayerTheme {
    private const val KEY_THEME = "player_theme"
    private const val KEY_BLEND = "disable_blend_effects"
    private const val KEY_LEGACY_MESH = "legacy_mesh_gradient"

    private val persistence = DesktopPersistence()

    private val _theme = MutableStateFlow(
        runCatching { PlayerTheme.valueOf(persistence.string(KEY_THEME, "")) }.getOrElse {
            if (persistence.boolean(KEY_LEGACY_MESH, false)) PlayerTheme.LEGACY else PlayerTheme.DEFAULT
        },
    )
    val theme: StateFlow<PlayerTheme> = _theme

    private val _disableBlend = MutableStateFlow(persistence.boolean(KEY_BLEND, false))
    val disableBlend: StateFlow<Boolean> = _disableBlend

    fun setTheme(value: PlayerTheme) {
        _theme.value = value
        persistence.saveString(KEY_THEME, value.name)
    }

    fun setDisableBlend(value: Boolean) {
        _disableBlend.value = value
        persistence.saveBoolean(KEY_BLEND, value)
    }

    val TITLE get() = DesktopStrings["player_theme", "Player theme"]

    /** The phone's names and lines (`player_theme_*`), in its order. */
    val choices: List<Triple<PlayerTheme, String, String>>
        get() = listOf(
            Triple(
                PlayerTheme.DEFAULT,
                DesktopStrings["player_theme_default", "Default"],
                DesktopStrings["player_theme_default_subtitle", "The cover's own colours carry on below it"],
            ),
            Triple(
                PlayerTheme.SIMPLE,
                DesktopStrings["player_theme_simple", "Simple"],
                DesktopStrings["player_theme_simple_subtitle", "A soft blur of the cover behind the whole player"],
            ),
            Triple(
                PlayerTheme.LEGACY,
                DesktopStrings["player_theme_legacy", "Legacy"],
                DesktopStrings["player_theme_legacy_subtitle", "Brings back the drifting colour blobs behind the player from v1.5"],
            ),
        )
}

/** Player theme, a radio list like [ForkWidePlayerLayoutRow]. */
@Composable
internal fun ForkPlayerThemeRow() {
    val words = ForkPlayerTheme.choices.joinToString(" ") { "${it.second} ${it.third}" }
    if (!settingsRowVisible(ForkPlayerTheme.TITLE, "background mesh gradient legacy simple blur $words")) return
    val current by ForkPlayerTheme.theme.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(ForkPlayerTheme.TITLE, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        ForkPlayerTheme.choices.forEach { (theme, name, line) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .desktopRowClickable { ForkPlayerTheme.setTheme(theme) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = current == theme,
                    onClick = { ForkPlayerTheme.setTheme(theme) },
                    colors = RadioButtonDefaults.colors(selectedColor = DesktopAccent, unselectedColor = Color.White.copy(alpha = 0.5f)),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text(line, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Disable blend effects, the phone's switch. */
@Composable
internal fun ForkDisableBlendRow() {
    val on by ForkPlayerTheme.disableBlend.collectAsState()
    SettingsToggle(
        DesktopStrings["disable_blend_effects", "Disable blend effects"],
        DesktopStrings[
            "disable_blend_effects_subtitle",
            "Shows player icons, text and dark menus in plain white instead of tinting them with the artwork",
        ],
        on,
        ForkPlayerTheme::setDisableBlend,
    )
}
