package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.lyrics.ForkLyricsRules
import com.music.bitchord.ui.player.ForkLyricsOffsets
import com.music.bitchord.ui.player.ForkLyricsPrefs
import com.music.bitchord.ui.player.MAX_LYRICS_OFFSET_MS
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The PC's store and Settings rows for the lyrics rulebook's settings (lyrics.md §2.6, §4.1, §5.2,
 * §8, §9; the flows are sharedUi's [ForkLyricsPrefs], read by the shared panel), the per-song
 * lyrics offsets ([ForkLyricsOffsets]) and the song menu's "Lyrics offset" body with both offsets
 * (§3). Keys in the main settings node, each song's offset under its own key. Installed from
 * [ForkNavidrome.install], before the player is drawn.
 */
internal object ForkLyricsSettingsDesktop {

    private const val KEY_REAL_TIMING = "fork_lyrics_real_timing"
    private const val KEY_AUTO_SCROLL = "fork_lyrics_auto_scroll"
    private const val KEY_DUET_SIDES = "fork_lyrics_duet_sides"
    private const val KEY_TEXT_SIZE = "fork_lyrics_text_size"
    private const val KEY_LINE_SPACING = "fork_lyrics_line_spacing"
    private const val KEY_ROMANIZED_MAIN = "fork_lyrics_romanized_main"
    private const val KEY_KEEP_ORIGINAL = "fork_lyrics_keep_original"
    private const val KEY_ROMAN_LANGUAGES = "fork_lyrics_roman_languages"
    private const val NONE = Int.MIN_VALUE

    private val persistence by lazy { DesktopPersistence() }

    /** A song's key under the preferences' 80-character limit (a long Navidrome id is shortened by its hash). */
    internal fun offsetKey(songKey: String): String =
        "fork_lyr_off_" + if (songKey.length <= 48) songKey else songKey.take(24) + "_" + Integer.toHexString(songKey.hashCode())

    fun install() {
        ForkLyricsOffsets.store = object : ForkLyricsOffsets.Store {
            override fun read(songKey: String): Int? = persistence.int(offsetKey(songKey), NONE).takeIf { it != NONE }
            override fun write(songKey: String, ms: Int?) = persistence.saveInt(offsetKey(songKey), ms ?: NONE)
        }
        ForkLyricsPrefs.realTiming.value = persistence.boolean(KEY_REAL_TIMING, false)
        ForkLyricsPrefs.autoScroll.value = persistence.boolean(KEY_AUTO_SCROLL, true)
        ForkLyricsPrefs.duetSides.value = persistence.boolean(KEY_DUET_SIDES, true)
        ForkLyricsPrefs.textSize.value = ForkLyricsPrefs.clampTextSize(persistence.int(KEY_TEXT_SIZE, ForkLyricsPrefs.TEXT_SIZE_DEFAULT))
        ForkLyricsPrefs.lineSpacing.value = ForkLyricsPrefs.clampLineSpacing(persistence.float(KEY_LINE_SPACING, ForkLyricsPrefs.LINE_SPACING_DEFAULT))
        ForkLyricsPrefs.romanizedAsMain.value = persistence.boolean(KEY_ROMANIZED_MAIN, false)
        ForkLyricsPrefs.keepOriginalUnder.value = persistence.boolean(KEY_KEEP_ORIGINAL, true)
        ForkLyricsPrefs.romanLanguages.value = ForkLyricsPrefs.parseLanguages(persistence.string(KEY_ROMAN_LANGUAGES, "").ifEmpty { null })
    }

    fun setRealTiming(on: Boolean) { ForkLyricsPrefs.realTiming.value = on; persistence.saveBoolean(KEY_REAL_TIMING, on) }
    fun setAutoScroll(on: Boolean) { ForkLyricsPrefs.autoScroll.value = on; persistence.saveBoolean(KEY_AUTO_SCROLL, on) }
    fun setDuetSides(on: Boolean) { ForkLyricsPrefs.duetSides.value = on; persistence.saveBoolean(KEY_DUET_SIDES, on) }
    fun setRomanizedAsMain(on: Boolean) { ForkLyricsPrefs.romanizedAsMain.value = on; persistence.saveBoolean(KEY_ROMANIZED_MAIN, on) }
    fun setKeepOriginalUnder(on: Boolean) { ForkLyricsPrefs.keepOriginalUnder.value = on; persistence.saveBoolean(KEY_KEEP_ORIGINAL, on) }

    fun setTextSize(value: Int) {
        val clamped = ForkLyricsPrefs.clampTextSize(value)
        ForkLyricsPrefs.textSize.value = clamped
        persistence.saveInt(KEY_TEXT_SIZE, clamped)
    }

    fun setLineSpacing(value: Float) {
        val clamped = ForkLyricsPrefs.clampLineSpacing(value)
        ForkLyricsPrefs.lineSpacing.value = clamped
        persistence.saveFloat(KEY_LINE_SPACING, clamped)
    }

    fun setRomanLanguage(language: ForkLyricsRules.RomanLanguage, on: Boolean) {
        val next = if (on) ForkLyricsPrefs.romanLanguages.value + language else ForkLyricsPrefs.romanLanguages.value - language
        ForkLyricsPrefs.romanLanguages.value = next
        // An empty string reads back as "every language", so none is stored as a name no language has.
        persistence.saveString(KEY_ROMAN_LANGUAGES, ForkLyricsPrefs.formatLanguages(next).ifEmpty { "NONE" })
    }
}

/** Settings > Lyrics, after "Keep lyrics moving when unfocused" (greyed with the rest while Synced lyrics is off). */
@Composable
internal fun ForkLyricsViewRowsDesktop() = ForkMarked {
    val realTiming by ForkLyricsPrefs.realTiming.collectAsState()
    val autoScroll by ForkLyricsPrefs.autoScroll.collectAsState()
    val sides by ForkLyricsPrefs.duetSides.collectAsState()
    val size by ForkLyricsPrefs.textSize.collectAsState()
    val spacing by ForkLyricsPrefs.lineSpacing.collectAsState()
    val asMain by ForkLyricsPrefs.romanizedAsMain.collectAsState()
    val keep by ForkLyricsPrefs.keepOriginalUnder.collectAsState()
    val languages by ForkLyricsPrefs.romanLanguages.collectAsState()
    SettingsToggle(
        DesktopStrings["fork_lyrics_real_timing", "Lyrics by real timing"],
        DesktopStrings["fork_lyrics_real_timing_subtitle", "Each line lights up on its own start instead of 0.66 s ahead, and the panel scrolls with no lead"],
        realTiming,
        ForkLyricsSettingsDesktop::setRealTiming,
    )
    SettingsToggle(
        DesktopStrings["fork_lyrics_auto_scroll", "Auto-scroll lyrics"],
        DesktopStrings["fork_lyrics_auto_scroll_subtitle", "The lyrics follow the song. Scrolling or dragging stops following until you click Follow lyrics or a line"],
        autoScroll,
        ForkLyricsSettingsDesktop::setAutoScroll,
    )
    SettingsToggle(
        DesktopStrings["fork_lyrics_duet_sides", "Duet sides"],
        DesktopStrings["fork_lyrics_duet_sides_subtitle", "Each singer's lines on their own side, lines sung together centred"],
        sides,
        ForkLyricsSettingsDesktop::setDuetSides,
    )
    ForkLyricsSliderRow(
        DesktopStrings["fork_lyrics_text_size", "Lyrics text size"],
        "$size",
        size.toFloat(),
        ForkLyricsPrefs.TEXT_SIZE_MIN.toFloat()..ForkLyricsPrefs.TEXT_SIZE_MAX.toFloat(),
        ForkLyricsPrefs.TEXT_SIZE_MAX - ForkLyricsPrefs.TEXT_SIZE_MIN - 1,
    ) { ForkLyricsSettingsDesktop.setTextSize(it.roundToInt()) }
    ForkLyricsSliderRow(
        DesktopStrings["fork_lyrics_line_spacing", "Lyrics line spacing"],
        String.format(Locale.getDefault(), "%.1f×", spacing),
        spacing,
        ForkLyricsPrefs.LINE_SPACING_MIN..ForkLyricsPrefs.LINE_SPACING_MAX,
        14,
        ForkLyricsSettingsDesktop::setLineSpacing,
    )
    SettingsToggle(
        DesktopStrings["fork_lyrics_romanized_main", "Romanized as main"],
        DesktopStrings["fork_lyrics_romanized_main_subtitle", "With romanization on, the romanized line is the big one"],
        asMain,
        ForkLyricsSettingsDesktop::setRomanizedAsMain,
    )
    ForkDisabledWhen(!asMain) {
        SettingsToggle(
            DesktopStrings["fork_lyrics_keep_original", "Keep the original under it"],
            DesktopStrings["fork_lyrics_keep_original_subtitle", "The original script stays under the romanized line"],
            keep,
            ForkLyricsSettingsDesktop::setKeepOriginalUnder,
        )
    }
    val title = DesktopStrings["fork_lyrics_roman_languages", "Romanized languages"]
    if (settingsRowVisible(title)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            ForkSettingsTitle(title)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ForkLyricsRules.RomanLanguage.entries.chunked(7).forEach { column ->
                    Column(Modifier.weight(1f)) {
                        column.forEach { language ->
                            val on = language in languages
                            Row(
                                Modifier.fillMaxWidth().clickable { ForkLyricsSettingsDesktop.setRomanLanguage(language, !on) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = { ForkLyricsSettingsDesktop.setRomanLanguage(language, it) })
                                Text(language.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForkLyricsSliderRow(
    title: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    if (!settingsRowVisible(title)) return
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ForkSettingsTitle(title) }
            Text(value, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        DesktopBareSlider(value = current, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The song menu's "Lyrics offset" (lyrics.md §3): this song's offset, saved by its id, and the
 * global one, each in steps of 50 ms with a ±3 s slider, a field and a reset; positive shows the
 * lyrics earlier. Replaces upstream's single stepper (the global, ±5 s by 100 ms, reset at every
 * song).
 */
@Composable
internal fun ForkDesktopLyricsOffsetControl(songKey: String) {
    val storedGlobal by DesktopPlayerSettings.lyricsOffsetMs.collectAsState()
    val songs by ForkLyricsOffsets.songs.collectAsState()
    remember(songKey) { ForkLyricsOffsets.select(songKey) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            DesktopStrings["fork_lyrics_offset_sign", "Positive shows the lyrics earlier"],
            color = DesktopSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        ForkDesktopOffsetRow(DesktopStrings["fork_lyrics_offset_song", "This song"], songs[songKey] ?: 0, ForkLyricsOffsets.FIELD_MS) {
            ForkLyricsOffsets.setSongOffset(songKey, it)
        }
        ForkDesktopOffsetRow(DesktopStrings["fork_lyrics_offset_global", "All songs"], ForkLyricsRules.globalFromStored(storedGlobal), MAX_LYRICS_OFFSET_MS) {
            DesktopPlayerSettings.setLyricsOffsetMs(ForkLyricsRules.storedFromRule(it))
        }
    }
}

/** The menu row's value: both offsets added, in the rule's sign; blank at zero. */
@Composable
internal fun forkDesktopLyricsOffsetLabel(songKey: String): String {
    val storedGlobal by DesktopPlayerSettings.lyricsOffsetMs.collectAsState()
    val songs by ForkLyricsOffsets.songs.collectAsState()
    val total = ForkLyricsRules.combinedOffset(songs[songKey] ?: 0, ForkLyricsRules.globalFromStored(storedGlobal))
    return if (total == 0) "" else String.format(Locale.getDefault(), "%+.2f s", total / 1_000f)
}

@Composable
private fun ForkDesktopOffsetRow(title: String, valueMs: Int, limitMs: Int, onChange: (Int) -> Unit) {
    val set: (Int) -> Unit = { onChange(it.coerceIn(-limitMs, limitMs)) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ForkStepper("−", valueMs > -limitMs) { set(valueMs - ForkLyricsOffsets.STEP_MS) }
            ForkOffsetField(valueMs, set)
            ForkStepper("+", valueMs < limitMs) { set(valueMs + ForkLyricsOffsets.STEP_MS) }
            TextButton(onClick = { set(0) }, enabled = valueMs != 0) { Text(DesktopStrings["d_reset", "Reset"]) }
        }
        val span = ForkLyricsOffsets.SLIDER_MS
        DesktopBareSlider(
            value = valueMs.coerceIn(-span, span).toFloat(),
            onValueChange = { set((it / ForkLyricsOffsets.STEP_MS).roundToInt() * ForkLyricsOffsets.STEP_MS) },
            valueRange = -span.toFloat()..span.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ForkOffsetField(valueMs: Int, onChange: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(valueMs, editing) {
        mutableStateOf(if (editing) valueMs.toString() else String.format(Locale.getDefault(), "%+.2f s", valueMs / 1_000f))
    }
    val commit = {
        text.trim().toIntOrNull()?.let(onChange)
        editing = false
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '-' || ch == '+' }.take(7) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleSmall.copy(color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.width(72.dp).onFocusChanged { focus ->
            if (focus.isFocused && !editing) editing = true else if (!focus.isFocused && editing) commit()
        },
    )
}

@Composable
private fun ForkStepper(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.03f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) Color.White else DesktopSecondary)
    }
}
