package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatLineSpacing
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.ForkSettingsBadge
import com.music.bitchord.data.lyrics.ForkLyricsRules
import com.music.bitchord.fork.ForkLyricsSettings
import com.music.bitchord.ui.player.ForkLyricsPrefs
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fork: the lyrics rulebook's settings (lyrics.md §2.6, §4.1, §5.2, §8, §9) in Settings' lyrics
 * group, after "Translation language"; greyed with the rest of the group while "Synced lyrics" is
 * off. Stored by [ForkLyricsSettings], read by the panel through `ForkLyricsPrefs`.
 */
@Composable
@Suppress("ComposableNaming")
internal fun SettingsGroupScope.forkLyricsViewRows() {
    row(stringResource(R.string.fork_lyrics_real_timing), "fork", "lyrics", "timing", "early", "highlight") {
        ForkLyricsSwitch(Icons.Rounded.Timer, R.string.fork_lyrics_real_timing, R.string.fork_lyrics_real_timing_subtitle, ForkLyricsPrefs.realTiming, ForkLyricsSettings::setRealTiming)
    }
    row(stringResource(R.string.fork_lyrics_auto_scroll), "fork", "lyrics", "scroll", "follow") {
        ForkLyricsSwitch(Icons.Rounded.VerticalAlignCenter, R.string.fork_lyrics_auto_scroll, R.string.fork_lyrics_auto_scroll_subtitle, ForkLyricsPrefs.autoScroll, ForkLyricsSettings::setAutoScroll)
    }
    row(stringResource(R.string.fork_lyrics_duet_sides), "fork", "lyrics", "duet", "singer", "sides") {
        ForkLyricsSwitch(Icons.Rounded.Groups, R.string.fork_lyrics_duet_sides, R.string.fork_lyrics_duet_sides_subtitle, ForkLyricsPrefs.duetSides, ForkLyricsSettings::setDuetSides)
    }
    row(stringResource(R.string.fork_lyrics_text_size), "fork", "lyrics", "text", "size", "font") { ForkLyricsTextSizeRow() }
    row(stringResource(R.string.fork_lyrics_line_spacing), "fork", "lyrics", "line", "spacing") { ForkLyricsLineSpacingRow() }
    row(stringResource(R.string.fork_lyrics_romanized_main), "fork", "lyrics", "romanization", "romanized") {
        ForkLyricsSwitch(Icons.Rounded.SwapVert, R.string.fork_lyrics_romanized_main, R.string.fork_lyrics_romanized_main_subtitle, ForkLyricsPrefs.romanizedAsMain, ForkLyricsSettings::setRomanizedAsMain)
    }
    val asMain = ForkLyricsPrefs.romanizedAsMain.collectAsStateWithLifecycle().value
    forkDisabledWhen(!asMain) {
        row(stringResource(R.string.fork_lyrics_keep_original), "fork", "lyrics", "romanization", "original") {
            ForkLyricsSwitch(Icons.Rounded.Translate, R.string.fork_lyrics_keep_original, R.string.fork_lyrics_keep_original_subtitle, ForkLyricsPrefs.keepOriginalUnder, ForkLyricsSettings::setKeepOriginalUnder)
        }
    }
    row(stringResource(R.string.fork_lyrics_roman_languages), "fork", "lyrics", "romanization", "language") { ForkRomanLanguagesRow() }
}

@Composable
private fun ForkLyricsSwitch(icon: ImageVector, title: Int, subtitle: Int, state: StateFlow<Boolean>, onChange: (Boolean) -> Unit) {
    val checked by state.collectAsStateWithLifecycle()
    SettingsRow(
        icon = icon,
        title = stringResource(title),
        subtitle = stringResource(subtitle),
        badge = ForkSettingsBadge.TEXT,
        trailing = { com.music.bitchord.ui.components.IosSwitch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/** §8.1: 10 to 40, steps of 1. */
@Composable
private fun ForkLyricsTextSizeRow() {
    val size by ForkLyricsPrefs.textSize.collectAsStateWithLifecycle()
    SliderRow(
        icon = Icons.Rounded.FormatSize,
        title = stringResource(R.string.fork_lyrics_text_size),
        subtitle = stringResource(R.string.fork_lyrics_text_size_subtitle),
        value = "$size",
        sliderValue = size.toFloat(),
        onSliderValue = { ForkLyricsSettings.setTextSize(it.roundToInt()) },
        valueRange = ForkLyricsPrefs.TEXT_SIZE_MIN.toFloat()..ForkLyricsPrefs.TEXT_SIZE_MAX.toFloat(),
        steps = ForkLyricsPrefs.TEXT_SIZE_MAX - ForkLyricsPrefs.TEXT_SIZE_MIN - 1,
    )
}

/** §8.2: 1.0 to 2.5 times the text size, steps of 0.1. */
@Composable
private fun ForkLyricsLineSpacingRow() {
    val spacing by ForkLyricsPrefs.lineSpacing.collectAsStateWithLifecycle()
    SliderRow(
        icon = Icons.Rounded.FormatLineSpacing,
        title = stringResource(R.string.fork_lyrics_line_spacing),
        subtitle = stringResource(R.string.fork_lyrics_line_spacing_subtitle),
        value = String.format(Locale.getDefault(), "%.1f×", spacing),
        sliderValue = spacing,
        onSliderValue = ForkLyricsSettings::setLineSpacing,
        valueRange = ForkLyricsPrefs.LINE_SPACING_MIN..ForkLyricsPrefs.LINE_SPACING_MAX,
        steps = 14,
    )
}

/** §9.1: which languages are romanized; a dialog of switches. */
@Composable
private fun ForkRomanLanguagesRow() {
    val on by ForkLyricsPrefs.romanLanguages.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val all = ForkLyricsRules.RomanLanguage.entries
    SettingsRow(
        icon = Icons.Rounded.Language,
        title = stringResource(R.string.fork_lyrics_roman_languages),
        subtitle = when (on.size) {
            all.size -> stringResource(R.string.fork_lyrics_roman_languages_all)
            0 -> stringResource(R.string.fork_lyrics_roman_languages_none)
            else -> all.filter { it in on }.joinToString(", ") { it.label }
        },
        badge = ForkSettingsBadge.TEXT,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.fork_lyrics_roman_languages)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    all.forEach { language ->
                        val checked = language in on
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { ForkLyricsSettings.setRomanLanguage(language, !checked) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { ForkLyricsSettings.setRomanLanguage(language, it) })
                            Text(language.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
