package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.prefs.Preferences
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: "UI scale" (80–125 %), from Desktop Revived 8782585b (`DesktopUiScaleSettings` and the
 * `LocalDensity` wrap in Main.kt). The whole window's content is drawn at the chosen density; the
 * window itself (its size, the native resize border, AWT's tray menu) is not, and the monitor's
 * own scale and font scale stay underneath.
 */
internal object ForkUiScale {

    internal const val KEY_UI_SCALE_PERCENT = "desktop_ui_scale_percent" // Revived's key
    const val DEFAULT_PERCENT = 100

    /** Steps rather than a slider: the layout doesn't jump about while dragging, and 100 % is one click. */
    val options = listOf(80, 90, 100, 110, 125)

    private val _percent = MutableStateFlow(readPercent(DesktopPersistence().preferences))
    val percent: StateFlow<Int> = _percent

    fun setPercent(value: Int) {
        val normalized = normalizePercent(value)
        if (_percent.value == normalized) return
        DesktopPersistence().saveInt(KEY_UI_SCALE_PERCENT, normalized)
        _percent.value = normalized
    }

    internal fun normalizePercent(value: Int): Int {
        val bounded = value.coerceIn(options.first(), options.last())
        return options.minBy { abs(it - bounded) }
    }

    internal fun readPercent(preferences: Preferences): Int =
        normalizePercent(preferences.getInt(KEY_UI_SCALE_PERCENT, DEFAULT_PERCENT))

    /** The window's density with the chosen scale on top, for Main.kt's `LocalDensity`. */
    @Composable
    fun density(): Density {
        val percent by percent.collectAsState()
        val system = LocalDensity.current
        return remember(system, percent) { Density(system.density * (percent / 100f), system.fontScale) }
    }
}

/** Appearance: the scale's row, as chips like "Window material" beside it. */
@Composable
internal fun ForkUiScaleRow() {
    val percent by ForkUiScale.percent.collectAsState()
    val title = DesktopStrings["d_ui_scale", "UI scale"]
    val subtitle = DesktopStrings["d_ui_scale_subtitle", "Zoom text, controls, and panels throughout BitChord."]
    if (!settingsRowVisible(title, subtitle)) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForkUiScale.options.forEach { option ->
                FilterChip(
                    colors = desktopChipColors(),
                    selected = percent == option,
                    onClick = { ForkUiScale.setPercent(option) },
                    label = { Text("$option%") },
                )
            }
        }
    }
}
