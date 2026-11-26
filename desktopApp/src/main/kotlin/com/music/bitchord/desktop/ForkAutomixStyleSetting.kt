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
import com.music.bitchord.playback.smart.automixv2.ForkV2OverrideStore
import com.music.bitchord.ui.player.ForkMixFiles
import com.music.bitchord.ui.player.ForkMixImportConfirm

/**
 * Fork: Settings > Playback "Automix style", under the Automix phrase length (and greyed with it
 * while Automix is off): BitChord's own Automix, or Automix v2, the YouTube Music extension's engine
 * ported ([ForkDesktopAutomixStyle]). The phone's row has the same two choices. The old Web /
 * Advanced AutoDJ chips and the "Shorter blend when keys clash" toggle are gone: v2 always weighs
 * the keys, and a saved old choice reads as v2.
 */
@Composable
internal fun ForkAutomixStyleSetting() {
    val title = "Automix style"
    val subtitle = "BitChord's own mixes, or the YouTube Music extension's Automix v2"
    val style by ForkDesktopAutomixStyle.style.collectAsState()
    if (settingsRowVisible(title, subtitle)) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        ForkSettingsTitle(title, fontWeight = FontWeight.Medium, fork = true)
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
    ForkMixFilesSetting()
}

/**
 * Mix files (spec/automix/mix-file.md rule 9: BitChord's own place in the Automix screen), under the Automix style:
 * import ready-made mixes, export every saved edit. "Export this pair" is in the transition editor, where a pair is
 * on screen ([ForkMixFiles], files through [ForkDesktopMixFiles]).
 */
@Composable
private fun ForkMixFilesSetting() {
    val title = "Mix files"
    val subtitle = "Ready-made mixes from a .json file: play the two songs one after the other and the mix plays"
    val saved by ForkV2OverrideStore.overrides.collectAsState()
    if (settingsRowVisible(title, subtitle)) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        ForkSettingsTitle(title, fontWeight = FontWeight.Medium, fork = true)
        Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                colors = desktopChipColors(),
                selected = false,
                enabled = ForkMixFiles.available,
                onClick = { ForkMixFiles.importMixes() },
                label = { Text("Import…") },
            )
            FilterChip(
                colors = desktopChipColors(),
                selected = false,
                enabled = ForkMixFiles.available && saved.isNotEmpty(),
                onClick = { ForkMixFiles.exportMixes() },
                label = { Text("Export all") },
            )
            // mix-file.md rule 7: the last imported file's songs, queued after the playing one in its order
            FilterChip(
                colors = desktopChipColors(),
                selected = false,
                enabled = ForkMixFiles.canPlay,
                onClick = { ForkMixFiles.playImported() },
                label = { Text("Play these mixes") },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            ForkMixFiles.status ?: if (saved.isEmpty()) "No edited pairs yet" else "${saved.size} edited pair${if (saved.size == 1) "" else "s"}",
            color = DesktopSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    ForkMixImportConfirm()
}

private fun detail(style: AutomixStyle): String = when (style) {
    AutomixStyle.BITCHORD -> "BitChord's own Automix"
    AutomixStyle.V2 ->
        "The extension's Automix v2: beatmatched blends that glide between tempos up to 8% apart, a hand-over when two songs " +
            "can't be matched, keys, sub-bass and loudness weighed, and a loop mix only when it beats the blend"
}
