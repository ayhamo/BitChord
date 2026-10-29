package com.music.bitchord.fork

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Playback switches the fork adds (fork-only), in their own prefs file so
 * upstream changes to `AppSettings` never conflict with them.
 */
object ForkSettings {

    private lateinit var prefs: SharedPreferences

    private val _crossfadeOnSkip = MutableStateFlow(true)

    /**
     * Next and Previous blend into the chosen track with the classic crossfade
     * instead of cutting to it. Only in classic crossfade mode (a length set,
     * Automix off); see `CrossfadeController.crossfadeSkip`.
     */
    val crossfadeOnSkip: StateFlow<Boolean> = _crossfadeOnSkip.asStateFlow()

    private val _coverClips = MutableStateFlow(true)

    /** A Navidrome song's `.cover.mp4` plays over its sleeve; see `NavidromeClips`. */
    val coverClips: StateFlow<Boolean> = _coverClips.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_fork", Context.MODE_PRIVATE)
        _crossfadeOnSkip.value = prefs.getBoolean(KEY_CROSSFADE_ON_SKIP, true)
        _coverClips.value = prefs.getBoolean(KEY_COVER_CLIPS, true)
    }

    fun setCoverClips(value: Boolean) {
        _coverClips.value = value
        prefs.edit().putBoolean(KEY_COVER_CLIPS, value).apply()
    }

    fun setCrossfadeOnSkip(value: Boolean) {
        _crossfadeOnSkip.value = value
        prefs.edit().putBoolean(KEY_CROSSFADE_ON_SKIP, value).apply()
    }

    private const val KEY_CROSSFADE_ON_SKIP = "crossfade_on_skip"
    private const val KEY_COVER_CLIPS = "cover_clips"
}
