package com.music.bitchord.fork

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.lyrics.ForkLyricsRules
import com.music.bitchord.ui.player.ForkLyricsOffsets
import com.music.bitchord.ui.player.ForkLyricsPrefs

/**
 * The phone's store for the lyrics rulebook's settings (lyrics.md; the flows are sharedUi's
 * [ForkLyricsPrefs], which the panel reads) and the per-song lyrics offsets ([ForkLyricsOffsets],
 * by video id). Their own prefs files, so upstream's `AppSettings` and the fork's `bitchord_fork`
 * are untouched. Loaded from [ForkSettings.init].
 */
object ForkLyricsSettings {

    private lateinit var prefs: SharedPreferences

    private const val KEY_REAL_TIMING = "real_timing"
    private const val KEY_AUTO_SCROLL = "auto_scroll"
    private const val KEY_DUET_SIDES = "duet_sides"
    private const val KEY_TEXT_SIZE = "text_size"
    private const val KEY_LINE_SPACING = "line_spacing"
    private const val KEY_ROMANIZED_MAIN = "romanized_as_main"
    private const val KEY_KEEP_ORIGINAL = "keep_original_under"
    private const val KEY_ROMAN_LANGUAGES = "roman_languages"

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_fork_lyrics", Context.MODE_PRIVATE)
        val offsets = context.getSharedPreferences("bitchord_fork_lyrics_offsets", Context.MODE_PRIVATE)
        ForkLyricsOffsets.store = object : ForkLyricsOffsets.Store {
            override fun read(songKey: String): Int? = if (offsets.contains(songKey)) offsets.getInt(songKey, 0) else null
            override fun write(songKey: String, ms: Int?) {
                offsets.edit().apply { if (ms == null) remove(songKey) else putInt(songKey, ms) }.apply()
            }
        }
        ForkLyricsPrefs.realTiming.value = prefs.getBoolean(KEY_REAL_TIMING, false)
        ForkLyricsPrefs.autoScroll.value = prefs.getBoolean(KEY_AUTO_SCROLL, true)
        ForkLyricsPrefs.duetSides.value = prefs.getBoolean(KEY_DUET_SIDES, true)
        ForkLyricsPrefs.textSize.value = ForkLyricsPrefs.clampTextSize(prefs.getInt(KEY_TEXT_SIZE, ForkLyricsPrefs.TEXT_SIZE_DEFAULT))
        ForkLyricsPrefs.lineSpacing.value = ForkLyricsPrefs.clampLineSpacing(prefs.getFloat(KEY_LINE_SPACING, ForkLyricsPrefs.LINE_SPACING_DEFAULT))
        ForkLyricsPrefs.romanizedAsMain.value = prefs.getBoolean(KEY_ROMANIZED_MAIN, false)
        ForkLyricsPrefs.keepOriginalUnder.value = prefs.getBoolean(KEY_KEEP_ORIGINAL, true)
        ForkLyricsPrefs.romanLanguages.value = ForkLyricsPrefs.parseLanguages(prefs.getString(KEY_ROMAN_LANGUAGES, null))
    }

    private fun save(block: SharedPreferences.Editor.() -> Unit) {
        if (this::prefs.isInitialized) prefs.edit().apply(block).apply()
    }

    fun setRealTiming(on: Boolean) {
        ForkLyricsPrefs.realTiming.value = on
        save { putBoolean(KEY_REAL_TIMING, on) }
    }

    fun setAutoScroll(on: Boolean) {
        ForkLyricsPrefs.autoScroll.value = on
        save { putBoolean(KEY_AUTO_SCROLL, on) }
    }

    fun setDuetSides(on: Boolean) {
        ForkLyricsPrefs.duetSides.value = on
        save { putBoolean(KEY_DUET_SIDES, on) }
    }

    fun setTextSize(value: Int) {
        val clamped = ForkLyricsPrefs.clampTextSize(value)
        ForkLyricsPrefs.textSize.value = clamped
        save { putInt(KEY_TEXT_SIZE, clamped) }
    }

    fun setLineSpacing(value: Float) {
        val clamped = ForkLyricsPrefs.clampLineSpacing(value)
        ForkLyricsPrefs.lineSpacing.value = clamped
        save { putFloat(KEY_LINE_SPACING, clamped) }
    }

    fun setRomanizedAsMain(on: Boolean) {
        ForkLyricsPrefs.romanizedAsMain.value = on
        save { putBoolean(KEY_ROMANIZED_MAIN, on) }
    }

    fun setKeepOriginalUnder(on: Boolean) {
        ForkLyricsPrefs.keepOriginalUnder.value = on
        save { putBoolean(KEY_KEEP_ORIGINAL, on) }
    }

    fun setRomanLanguage(language: ForkLyricsRules.RomanLanguage, on: Boolean) {
        val next = if (on) ForkLyricsPrefs.romanLanguages.value + language else ForkLyricsPrefs.romanLanguages.value - language
        ForkLyricsPrefs.romanLanguages.value = next
        save { putString(KEY_ROMAN_LANGUAGES, ForkLyricsPrefs.formatLanguages(next)) }
    }
}
