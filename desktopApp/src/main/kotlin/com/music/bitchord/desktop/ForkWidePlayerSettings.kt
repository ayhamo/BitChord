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
import com.music.bitchord.ui.player.ForkWideLayout
import com.music.bitchord.ui.player.ForkWidePlayer
import java.util.prefs.Preferences

/**
 * Fork: "Wide player layout", how the player places the cover and the controls in a landscape
 * window ([ForkWideLayout], drawn by sharedUi's `ForkWidePlayerColumns`). Saved in the fork
 * switches' Preferences node; Layout 2 ("Halves") until changed.
 */
internal object ForkWidePlayerSettings {

    private const val KEY_LAYOUT = "wide_player_layout"

    const val TITLE = "Wide player layout"
    const val SUBTITLE = "How the player places the cover and the controls in a wide window"

    private val prefs = Preferences.userRoot().node("com/music/bitchord/navidrome_fork")

    /** Each choice's name and its one line, in the order shown. */
    val choices = listOf(
        Triple(ForkWideLayout.Classic, "Classic", "Cover and controls side by side in the middle"),
        Triple(ForkWideLayout.Halves, "Halves", "Cover centred in the left half, controls in the right half"),
        Triple(ForkWideLayout.HalvesSmallCover, "Halves, smaller cover", "As Halves, with a smaller cover"),
    )

    /** Called at start, before the player is drawn. */
    fun install() {
        val saved = prefs.get(KEY_LAYOUT, null)
        ForkWidePlayer.layout.value = ForkWideLayout.entries.firstOrNull { it.name == saved } ?: ForkWideLayout.Halves
    }

    fun set(layout: ForkWideLayout) {
        ForkWidePlayer.layout.value = layout
        prefs.put(KEY_LAYOUT, layout.name)
        runCatching { prefs.flush() }
    }
}

/** Appearance: the three layouts, each with its line under it. */
@Composable
internal fun ForkWidePlayerLayoutRow() {
    val words = ForkWidePlayerSettings.choices.joinToString(" ") { "${it.second} ${it.third}" }
    if (!settingsRowVisible(ForkWidePlayerSettings.TITLE, "${ForkWidePlayerSettings.SUBTITLE} $words")) return
    val current by ForkWidePlayer.layout.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(ForkWidePlayerSettings.TITLE, style = MaterialTheme.typography.bodyLarge)
        Text(ForkWidePlayerSettings.SUBTITLE, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        ForkWidePlayerSettings.choices.forEach { (layout, name, line) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .desktopRowClickable { ForkWidePlayerSettings.set(layout) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = current == layout,
                    onClick = { ForkWidePlayerSettings.set(layout) },
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
