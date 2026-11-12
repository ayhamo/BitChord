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
    private val _coverFade = com.music.bitchord.ui.player.ForkCoverFade.enabled // the player reads it live

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

    /** At a song change the old cover stays until the new one loads, then dissolves into it (off by default); see `ForkCoverFade`. */
    val coverFade: StateFlow<Boolean> = _coverFade.asStateFlow()

    /** Only what is on the phone is shown, as with no network; see `ForkNetwork.offline`. */
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    private val _automixStyle = MutableStateFlow(com.music.bitchord.playback.smart.AutomixStyle.BITCHORD)

    /**
     * "Automix style": BitChord's own Automix (default) or the YouTube Music extension's Original /
     * Advanced AutoDJ; the planner reads it each tick (`CrossfadeController` passes it to
     * `planTransition`), see `ForkAutoDj`.
     */
    val automixStyle: StateFlow<com.music.bitchord.playback.smart.AutomixStyle> = _automixStyle.asStateFlow()

    private val _automixKeyAware = MutableStateFlow(false)

    /** "Shorter blend when keys clash" (Advanced AutoDJ only, off by default); mirrored into `ForkAutoDj.keyAware`. */
    val automixKeyAware: StateFlow<Boolean> = _automixKeyAware.asStateFlow()

    /** The clip cache's saved size, read on its own: the cache opens on another thread before [init] runs. */
    fun applySavedClipLimit(context: Context) {
        ForkClipCache.applyLimit(context.getSharedPreferences("bitchord_fork", Context.MODE_PRIVATE).getInt(KEY_CLIP_CACHE_MB, DEFAULT_CLIP_CACHE_MB))
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_fork", Context.MODE_PRIVATE)
        // The Apple Music token backup (ForkAppleTokenBackup) lives here; not in BACKED_UP, so never exported.
        com.music.bitchord.data.canvas.ForkAppleTokenBackup.store = object : com.music.bitchord.data.canvas.ForkAppleTokenBackup.Store {
            override fun read(): String? = prefs.getString(KEY_APPLE_TOKEN_BACKUP, null)
            override fun write(line: String?) {
                prefs.edit().apply { if (line == null) remove(KEY_APPLE_TOKEN_BACKUP) else putString(KEY_APPLE_TOKEN_BACKUP, line) }.apply()
            }
        }
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
        _coverFade.value = prefs.getBoolean(KEY_COVER_FADE, false)
        _automixStyle.value = com.music.bitchord.playback.smart.AutomixStyle.fromKey(prefs.getString(KEY_AUTOMIX_STYLE, null))
        _automixKeyAware.value = prefs.getBoolean(KEY_AUTOMIX_KEY_AWARE, false)
        com.music.bitchord.playback.smart.ForkAutoDj.keyAware = _automixKeyAware.value
        // The Automix overview's picks per pair of songs (ForkMixPicks), read by the AutoDJ styles.
        com.music.bitchord.playback.smart.ForkMixPicks.style = _automixStyle.value
        com.music.bitchord.playback.smart.ForkMixPicks.load(prefs.getString(KEY_AUTOMIX_PAIR_PICKS, null))
        com.music.bitchord.playback.smart.ForkMixPicks.save = { prefs.edit().putString(KEY_AUTOMIX_PAIR_PICKS, it).apply() }
        com.music.bitchord.ui.theme.ForkSystemBars.mode.value = prefs.getString(KEY_SYSTEM_BARS, null)
            ?.let { saved -> com.music.bitchord.ui.theme.ForkSystemBars.Mode.entries.firstOrNull { it.name == saved } }
            ?: com.music.bitchord.ui.theme.ForkSystemBars.Mode.NONE
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

    /** "Hide system bars" (Settings > Appearance); see `ForkSystemBars`. */
    fun setSystemBars(value: com.music.bitchord.ui.theme.ForkSystemBars.Mode) {
        com.music.bitchord.ui.theme.ForkSystemBars.mode.value = value
        prefs.edit().putString(KEY_SYSTEM_BARS, value.name).apply()
    }

    fun setOfflineMode(value: Boolean) {
        _offlineMode.value = value
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()
    }

    fun setCoverFade(value: Boolean) {
        _coverFade.value = value
        prefs.edit().putBoolean(KEY_COVER_FADE, value).apply()
    }

    fun setAutomixStyle(value: com.music.bitchord.playback.smart.AutomixStyle) {
        _automixStyle.value = value
        com.music.bitchord.playback.smart.ForkMixPicks.style = value
        prefs.edit().putString(KEY_AUTOMIX_STYLE, value.key).apply()
    }

    fun setAutomixKeyAware(value: Boolean) {
        _automixKeyAware.value = value
        com.music.bitchord.playback.smart.ForkAutoDj.keyAware = value
        prefs.edit().putBoolean(KEY_AUTOMIX_KEY_AWARE, value).apply()
    }

    fun setCrossfadeOnSkip(value: Boolean) {
        _crossfadeOnSkip.value = value
        prefs.edit().putBoolean(KEY_CROSSFADE_ON_SKIP, value).apply()
    }

    /**
     * What a backup carries ([ForkBackup]): the switches, not the device's state. Offline mode is
     * left out (it describes this phone's network right now), and so is the one-off canvas flag.
     */
    private val BACKED_UP = listOf(
        KEY_CROSSFADE_ON_SKIP, KEY_BACKDROP_CLIPS, KEY_ANIMATED_COVERS, KEY_NAVIDROME_SCROBBLE,
        KEY_NAVIDROME_HOME_SHELVES, KEY_CLIP_CACHE_MB, KEY_NAVIDROME_YT_HISTORY, KEY_DOWNLOAD_ON_MOBILE,
        KEY_PRELOAD_SONGS, KEY_COVER_FADE,
    )

    /**
     * Parts of an imported backup's fork section this phone doesn't use (the PC's own settings),
     * as JSON, so the next export writes them back and a PC -> phone -> PC trip keeps them (the PC
     * carries the phone's settings the same way). Not in [BACKED_UP], so never exported as a setting.
     */
    internal var carriedSections: String?
        get() = if (!this::prefs.isInitialized) null else prefs.getString(KEY_CARRIED_SECTIONS, null)
        set(value) {
            if (!this::prefs.isInitialized) return
            prefs.edit().apply { if (value == null) remove(KEY_CARRIED_SECTIONS) else putString(KEY_CARRIED_SECTIONS, value) }.apply()
        }

    private const val KEY_CARRIED_SECTIONS = "backup_carried_sections"
    private const val KEY_APPLE_TOKEN_BACKUP = "apple_token_backup"

    /** The stored values of [BACKED_UP], typed as the preference file holds them. */
    internal fun exportForBackup(): Map<String, Any?> =
        if (!this::prefs.isInitialized) emptyMap() else prefs.all.filterKeys { it in BACKED_UP }

    /** Replaces [BACKED_UP] with [values] (a key left out goes back to its default) and re-reads them. */
    internal fun importFromBackup(values: Map<String, Any?>) {
        if (!this::prefs.isInitialized) return
        prefs.edit().apply {
            BACKED_UP.forEach { key ->
                when (val value = backupValue(key, values[key])) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    else -> remove(key)
                }
            }
        }.commit()
        _crossfadeOnSkip.value = prefs.getBoolean(KEY_CROSSFADE_ON_SKIP, true)
        _backdropClips.value = prefs.getBoolean(KEY_BACKDROP_CLIPS, true)
        _animatedCovers.value = prefs.getBoolean(KEY_ANIMATED_COVERS, true)
        _navidromeScrobble.value = prefs.getBoolean(KEY_NAVIDROME_SCROBBLE, true)
        _navidromeHomeShelves.value = prefs.getBoolean(KEY_NAVIDROME_HOME_SHELVES, true)
        _clipCacheMb.value = prefs.getInt(KEY_CLIP_CACHE_MB, DEFAULT_CLIP_CACHE_MB)
        _navidromeYouTubeHistory.value = prefs.getBoolean(KEY_NAVIDROME_YT_HISTORY, false)
        _downloadOnMobile.value = prefs.getBoolean(KEY_DOWNLOAD_ON_MOBILE, false)
        _preloadSongs.value = prefs.getInt(KEY_PRELOAD_SONGS, 0)
        _coverFade.value = prefs.getBoolean(KEY_COVER_FADE, false)
        ForkClipCache.applyLimit(_clipCacheMb.value)
    }

    /**
     * [value] from a backup if it is what [key] holds here, else null (the key goes back to its
     * default). A backup is a file that is copied and edited: an Int under a switch's key, stored as
     * is, made every later `getBoolean` throw, in `init` too, so the app crashed on each start; and
     * a count outside the Settings choices (a 10 000-song pre-load, a negative cache) has no row to
     * set it back.
     */
    internal fun backupValue(key: String, value: Any?): Any? = when (key) {
        KEY_CLIP_CACHE_MB -> (value as? Int)?.takeIf { it in CLIP_CACHE_CHOICES_MB }
        KEY_PRELOAD_SONGS -> (value as? Int)?.takeIf { it in PRELOAD_CHOICES }
        in BACKED_UP -> value as? Boolean
        else -> null
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
    private const val KEY_SYSTEM_BARS = "system_bars"
    private const val KEY_COVER_FADE = "cover_fade"
    private const val KEY_AUTOMIX_STYLE = "automix_style"
    private const val KEY_AUTOMIX_KEY_AWARE = "automix_key_aware"
    private const val KEY_AUTOMIX_PAIR_PICKS = "automix_pair_picks"

    const val DEFAULT_CLIP_CACHE_MB = 150

    /** The sizes offered for the clip cache. */
    val CLIP_CACHE_CHOICES_MB = listOf(150, 300, 500, 1024)

    /** The pre-load counts offered (0 = off). */
    val PRELOAD_CHOICES = listOf(0, 1, 2, 3, 5)
}
