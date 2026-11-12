package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.fork.ForkSettings
import com.music.bitchord.playback.smart.AutomixStyle
import com.music.bitchord.ui.icons.SfIcons

/**
 * Fork: "Automix phrase length" and "Automix style" (Settings > Playback, after Automix), in the
 * PC's order: a fixed 8 or 16-bar blend or Auto, then BitChord's own Automix or the YouTube Music
 * extension's Web / Advanced AutoDJ ([com.music.bitchord.playback.smart.ForkAutoDj]).
 * Greyed, never hidden, while Automix is off ([enabled]), the owner's rule for dependent rows.
 * The subtitle under each choice says what it does.
 */
@Composable
internal fun SettingsGroupScope.forkAutomixStyleRow(enabled: Boolean) = forkDisabledWhen(!enabled) {
    val phraseTitle = stringResource(R.string.fork_automix_phrase_length)
    row(phraseTitle, "fork", "automix", "phrase", "length", "bars", "blend", "mix") { ForkAutomixPhraseRow() }
    val title = stringResource(R.string.fork_automix_style)
    row(title, "fork", "automix", "autodj", "dj", "mix", "style", "extension") { ForkAutomixStyleRow() }
    val style by ForkSettings.automixStyle.collectAsStateWithLifecycle()
    val keyTitle = stringResource(R.string.fork_automix_key_aware)
    // Advanced AutoDJ's own toggle, as in the extension: greyed under the other styles.
    forkDisabledWhen(style != AutomixStyle.ADVANCED) {
        row(keyTitle, "fork", "automix", "autodj", "key", "clash", "harmonic", "camelot") {
            val on by ForkSettings.automixKeyAware.collectAsStateWithLifecycle()
            SettingsRow(
                icon = SfIcons.Tune,
                title = keyTitle,
                subtitle = stringResource(R.string.fork_automix_key_aware_subtitle),
                trailing = { IosSwitch(checked = on, onCheckedChange = ForkSettings::setAutomixKeyAware) },
                onClick = { ForkSettings.setAutomixKeyAware(!on) },
            )
        }
    }
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
                AutomixStyle.ORIGINAL -> R.string.fork_automix_style_original_subtitle
                AutomixStyle.ADVANCED -> R.string.fork_automix_style_advanced_subtitle
            },
        ),
    )
    SegmentedControl(
        options = listOf(
            stringResource(R.string.fork_automix_style_bitchord),
            stringResource(R.string.fork_automix_style_original),
            stringResource(R.string.fork_automix_style_advanced),
        ),
        selectedIndex = styles.indexOf(style),
        onSelect = { ForkSettings.setAutomixStyle(styles[it]) },
        modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
    )
}
