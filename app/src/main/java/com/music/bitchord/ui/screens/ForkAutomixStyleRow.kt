package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.ForkSettingsBadge
import com.music.bitchord.fork.ForkSettings
import com.music.bitchord.playback.smart.AutomixStyle
import com.music.bitchord.playback.smart.automixv2.ForkV2OverrideStore
import com.music.bitchord.ui.player.ForkMixFiles
import com.music.bitchord.ui.player.ForkMixImportConfirm
import com.music.bitchord.ui.icons.SfIcons

/**
 * Fork: "Automix phrase length" and "Automix style" (Settings > Playback, after Automix), in the
 * PC's order: a fixed 8 or 16-bar blend or Auto, then BitChord's own Automix or the YouTube Music
 * extension's Automix v2 ([com.music.bitchord.playback.smart.automixv2.ForkV2Automix]). The old Web /
 * Advanced AutoDJ choices and their "Shorter blend when keys clash" switch are gone: v2 always weighs
 * the keys, and a saved old choice reads as v2.
 * Greyed, never hidden, while Automix is off ([enabled]), the owner's rule for dependent rows.
 * The subtitle under each choice says what it does.
 */
@Composable
internal fun SettingsGroupScope.forkAutomixStyleRow(enabled: Boolean) = forkDisabledWhen(!enabled) {
    val phraseTitle = stringResource(R.string.fork_automix_phrase_length)
    row(phraseTitle, "fork", "automix", "phrase", "length", "bars", "blend", "mix") { ForkAutomixPhraseRow() }
    val title = stringResource(R.string.fork_automix_style)
    row(title, "fork", "automix", "autodj", "dj", "mix", "style", "extension", "v2") { ForkAutomixStyleRow() }
    row("Mix files", "fork", "automix", "mix", "file", "import", "export", "vivimix", "ready-made") { ForkMixFilesRow() }
}

/**
 * Mix files (spec/automix/mix-file.md rule 9: the phone's own place in the Automix screen): import ready-made mixes,
 * export every saved edit. "Export this pair" is in the transition editor, where a pair is on screen. Their edits
 * play under Automix v2 ([ForkMixFiles]).
 */
@Composable
internal fun ForkMixFilesRow() {
    val saved by ForkV2OverrideStore.overrides.collectAsStateWithLifecycle()
    SettingsRow(
        icon = SfIcons.FileDownload,
        title = "Import mixes…",
        subtitle = ForkMixFiles.status ?: "Ready-made mixes from a .vivimix.json file: play the two songs one after the other and the mix plays",
        badge = ForkSettingsBadge.TEXT,
        enabled = ForkMixFiles.available,
        onClick = { ForkMixFiles.importMixes() },
    )
    SettingsRow(
        icon = SfIcons.FileUpload,
        title = "Export all mixes…",
        subtitle = if (saved.isEmpty()) "No edited pairs yet" else "${saved.size} edited pair${if (saved.size == 1) "" else "s"}, as one .vivimix.json file",
        badge = ForkSettingsBadge.TEXT,
        enabled = ForkMixFiles.available,
        onClick = { ForkMixFiles.exportMixes() },
    )
    // mix-file.md rule 7: the last imported file's songs, queued after the playing one in its order
    SettingsRow(
        icon = SfIcons.Play,
        title = "Play these mixes",
        subtitle = ForkMixFiles.lastImport?.let { "The last imported file's ${it.ids.size} songs, in its order, after the playing song" }
            ?: "Import a mix file first",
        badge = ForkSettingsBadge.TEXT,
        enabled = ForkMixFiles.canPlay,
        onClick = { ForkMixFiles.playImported() },
    )
    ForkMixImportConfirm()
}

/** Auto, 8 or 16 bars, the PC's "Automix phrase length" chips; `CrossfadeController` passes it to the planner. */
@Composable
internal fun ForkAutomixPhraseRow() {
    val bars by ForkSettings.automixPhraseBars.collectAsStateWithLifecycle()
    val choices = ForkSettings.PHRASE_BAR_CHOICES
    SettingsRow(
        icon = SfIcons.Tune,
        title = stringResource(R.string.fork_automix_phrase_length),
        subtitle = stringResource(
            when (bars) {
                8 -> R.string.fork_automix_phrase_8_detail
                16 -> R.string.fork_automix_phrase_16_detail
                else -> R.string.fork_automix_phrase_auto_detail
            },
        ),
        badge = ForkSettingsBadge.TEXT,
    )
    SegmentedControl(
        options = listOf(
            stringResource(R.string.fork_automix_phrase_auto),
            stringResource(R.string.fork_automix_phrase_8),
            stringResource(R.string.fork_automix_phrase_16),
        ),
        selectedIndex = choices.indexOf(bars).coerceAtLeast(0),
        onSelect = { ForkSettings.setAutomixPhraseBars(choices[it]) },
        modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
    )
}

@Composable
internal fun ForkAutomixStyleRow() {
    val style by ForkSettings.automixStyle.collectAsStateWithLifecycle()
    val styles = AutomixStyle.entries
    SettingsRow(
        icon = SfIcons.AutoAwesome,
        title = stringResource(R.string.fork_automix_style),
        subtitle = stringResource(
            when (style) {
                AutomixStyle.BITCHORD -> R.string.fork_automix_style_bitchord_subtitle
                AutomixStyle.V2 -> R.string.fork_automix_style_v2_subtitle
            },
        ),
        badge = ForkSettingsBadge.TEXT,
    )
    SegmentedControl(
        options = listOf(
            stringResource(R.string.fork_automix_style_bitchord),
            stringResource(R.string.fork_automix_style_v2),
        ),
        selectedIndex = styles.indexOf(style),
        onSelect = { ForkSettings.setAutomixStyle(styles[it]) },
        modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
    )
}
