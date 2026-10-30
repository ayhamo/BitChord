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
     * Next and Previous blend into the chosen track instead of cutting to it:
     * a plain fade of the crossfade length, or with Automix on and no length,
     * Automix's 6 s fallback (songs that end on their own stay Automix's); see
     * `CrossfadeController.crossfadeSkip`.
     */
    val crossfadeOnSkip: StateFlow<Boolean> = _crossfadeOnSkip.asStateFlow()

    private val _backdropClips = com.music.bitchord.ui.player.ForkCanvasSwitch.backdrops // the player reads it live
    private val _animatedCovers = com.music.bitchord.ui.player.ForkCanvasSwitch.covers // the player and album pages read it live
    private val _navidromeScrobble = MutableStateFlow(true)
    private val _navidromeHomeShelves = MutableStateFlow(true)
    private val _clipCacheMb = MutableStateFlow(DEFAULT_CLIP_CACHE_MB)
    private val _navidromeYouTubeHistory = MutableStateFlow(false)
    private val _downloadOnMobile = MutableStateFlow(false)
    private val _preloadSongs = MutableStateFlow(0)
    private val _offlineMode = MutableStateFlow(false)

    /** A Navidrome song's `.background.mp4` plays full screen; see `NavidromeClips`. */
    val backdropClips: StateFlow<Boolean> = _backdropClips.asStateFlow()

    /** Animated covers from the online services (Apple Music etc.); separate from the Navidrome backdrops. See `ForkCanvasSwitch.covers`. */
    val animatedCovers: StateFlow<Boolean> = _animatedCovers.asStateFlow()

    /** "Now playing" and finished plays are reported to Navidrome; see `NavidromeScrobble`. */
    val navidromeScrobble: StateFlow<Boolean> = _navidromeScrobble.asStateFlow()

    /** The Navidrome shelves on top of Home; see `NavidromeHome`. */
    val navidromeHomeShelves: StateFlow<Boolean> = _navidromeHomeShelves.asStateFlow()

    /** The video clip cache's size in MB (upstream: a fixed 150); see `ForkClipCache`. */
    val clipCacheMb: StateFlow<Int> = _clipCacheMb.asStateFlow()

    /** Navidrome plays also go to the YouTube Music history (off by default); see `ForkYouTubeHistory`. */
    val navidromeYouTubeHistory: StateFlow<Boolean> = _navidromeYouTubeHistory.asStateFlow()

    /** "Available offline" downloads and pre-loading may also use mobile data (any metered network); Wi-Fi always; see `ForkNetwork`. */
    val downloadOnMobile: StateFlow<Boolean> = _downloadOnMobile.asStateFlow()

    /** How many Navidrome songs after the playing one are loaded whole into the song cache (0 = off); see `ForkPreload`. */
    val preloadSongs: StateFlow<Int> = _preloadSongs.asStateFlow()

    /** Only what is on the phone is shown, as with no network; see `ForkNetwork.offline`. */
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_fork", Context.MODE_PRIVATE)
        _crossfadeOnSkip.value = prefs.getBoolean(KEY_CROSSFADE_ON_SKIP, true)
        _backdropClips.value = prefs.getBoolean(KEY_BACKDROP_CLIPS, true)
        _animatedCovers.value = prefs.getBoolean(KEY_ANIMATED_COVERS, true)
        _navidromeScrobble.value = prefs.getBoolean(KEY_NAVIDROME_SCROBBLE, true)
        _navidromeHomeShelves.value = prefs.getBoolean(KEY_NAVIDROME_HOME_SHELVES, true)
        _clipCacheMb.value = prefs.getInt(KEY_CLIP_CACHE_MB, DEFAULT_CLIP_CACHE_MB)
        _navidromeYouTubeHistory.value = prefs.getBoolean(KEY_NAVIDROME_YT_HISTORY, false)
        _downloadOnMobile.value = prefs.getBoolean(KEY_DOWNLOAD_ON_MOBILE, false)
        _preloadSongs.value = prefs.getInt(KEY_PRELOAD_SONGS, 0)
        _offlineMode.value = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        ForkYouTubeHistory.init(context)
        ForkNetwork.init(context)
        ForkQueues.init(context)
        ForkClipCache.applyLimit(_clipCacheMb.value)
        // Canvas controls stay up until tapped away (upstream hides them after 5 s). Once, so the
        // owner can turn auto-hide back on in Settings.
        if (!prefs.getBoolean(KEY_CANVAS_MANUAL_SET, false)) {
            com.music.bitchord.data.settings.AppSettings.setSpotifyCanvasAutoHide(false)
            prefs.edit().putBoolean(KEY_CANVAS_MANUAL_SET, true).apply()
        }
    }

    fun setBackdropClips(value: Boolean) {
        _backdropClips.value = value
        prefs.edit().putBoolean(KEY_BACKDROP_CLIPS, value).apply()
    }

    fun setAnimatedCovers(value: Boolean) {
        _animatedCovers.value = value
        prefs.edit().putBoolean(KEY_ANIMATED_COVERS, value).apply()
    }

    fun setNavidromeScrobble(value: Boolean) {
        _navidromeScrobble.value = value
        prefs.edit().putBoolean(KEY_NAVIDROME_SCROBBLE, value).apply()
    }

    fun setNavidromeYouTubeHistory(value: Boolean) {
        _navidromeYouTubeHistory.value = value
        prefs.edit().putBoolean(KEY_NAVIDROME_YT_HISTORY, value).apply()
    }

    fun setClipCacheMb(value: Int) {
        _clipCacheMb.value = value
        prefs.edit().putInt(KEY_CLIP_CACHE_MB, value).apply()
        ForkClipCache.applyLimit(value)
    }

    fun setNavidromeHomeShelves(value: Boolean) {
        _navidromeHomeShelves.value = value
        prefs.edit().putBoolean(KEY_NAVIDROME_HOME_SHELVES, value).apply()
    }

    fun setDownloadOnMobile(value: Boolean) {
        _downloadOnMobile.value = value
        prefs.edit().putBoolean(KEY_DOWNLOAD_ON_MOBILE, value).apply()
    }

    fun setPreloadSongs(value: Int) {
        _preloadSongs.value = value
        prefs.edit().putInt(KEY_PRELOAD_SONGS, value).apply()
    }

    fun setOfflineMode(value: Boolean) {
        _offlineMode.value = value
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()
    }

    fun setCrossfadeOnSkip(value: Boolean) {
        _crossfadeOnSkip.value = value
        prefs.edit().putBoolean(KEY_CROSSFADE_ON_SKIP, value).apply()
    }

    private const val KEY_CROSSFADE_ON_SKIP = "crossfade_on_skip"
    private const val KEY_BACKDROP_CLIPS = "backdrop_clips"
    private const val KEY_ANIMATED_COVERS = "animated_covers"
    private const val KEY_NAVIDROME_SCROBBLE = "navidrome_scrobble"
    private const val KEY_NAVIDROME_HOME_SHELVES = "navidrome_home_shelves"
    private const val KEY_CANVAS_MANUAL_SET = "canvas_manual_set"
    private const val KEY_CLIP_CACHE_MB = "clip_cache_mb"
    private const val KEY_NAVIDROME_YT_HISTORY = "navidrome_yt_history"
    private const val KEY_DOWNLOAD_ON_MOBILE = "download_on_mobile"
    private const val KEY_PRELOAD_SONGS = "preload_songs"
    private const val KEY_OFFLINE_MODE = "offline_mode"

    const val DEFAULT_CLIP_CACHE_MB = 150

    /** The sizes offered for the clip cache. */
    val CLIP_CACHE_CHOICES_MB = listOf(150, 300, 500, 1024)

    /** The pre-load counts offered (0 = off). */
    val PRELOAD_CHOICES = listOf(0, 1, 2, 3, 5)
}
