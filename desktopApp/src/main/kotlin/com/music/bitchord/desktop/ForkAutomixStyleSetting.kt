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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.smart.AutomixStyle

/**
 * Fork: Settings > Playback "Automix style", under the Automix phrase length (and greyed with it
 * while Automix is off): BitChord's own Automix, or the YouTube Music extension's Original /
 * Advanced AutoDJ ([ForkDesktopAutomixStyle]). The phone's row has the same three choices.
 */
@Composable
internal fun ForkAutomixStyleSetting() {
    val title = "Automix style"
    val subtitle = "BitChord's own mixes, or the YouTube Music extension's AutoDJ"
    val style by ForkDesktopAutomixStyle.style.collectAsState()
    if (settingsRowVisible(title, subtitle)) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomixStyle.entries.forEach { option ->
                FilterChip(
                    colors = desktopChipColors(),
                    selected = style == option,
                    onClick = { ForkDesktopAutomixStyle.set(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(detail(style), color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
    }
    // Advanced AutoDJ's own toggle, as in the extension: greyed under the other styles.
    val keyAware by ForkDesktopAutomixStyle.keyAware.collectAsState()
    ForkDisabledWhen(style != AutomixStyle.ADVANCED) {
        SettingsToggle(
            "Shorter blend when keys clash",
            "Automix, Advanced AutoDJ style: songs whose keys don't mix get a 2-bar blend (4 when that would be very short), as in the extension",
            keyAware,
            ForkDesktopAutomixStyle::setKeyAware,
        )
    }
}

private fun detail(style: AutomixStyle): String = when (style) {
    AutomixStyle.BITCHORD -> "BitChord's own Automix"
    AutomixStyle.ORIGINAL ->
        "The extension's Automix: phrase-aligned blends ending with the song, a bass swap and a high-shelf cross, tempo only ever sped up"
    AutomixStyle.ADVANCED ->
        "The extension's Advanced AutoDJ: the same planner, cut to where the music ends, blends moved off two singers at once, DJ fader and filter shapes"
}
