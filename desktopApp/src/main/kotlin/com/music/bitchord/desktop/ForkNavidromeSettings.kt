package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.navidrome.NavidromePlatform
import java.util.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: the desktop's copy of the phone's `ForkSettings` for "Report plays to Navidrome", "Navidrome
 * on Home" and the video clip cache's size, with the phone's defaults. Same Preferences node as the
 * other fork switches, as [ForkOffline] reads it; flushed at once so a crash can't lose a flip.
 */
internal object ForkNavidromeSettings {

    private const val KEY_SCROBBLE = "report_plays"
    private const val KEY_HOME_SHELVES = "home_shelves"
    private const val KEY_CLIP_CACHE_MB = "clip_cache_mb"
    private const val KEY_PRELOAD_SONGS = "preload_songs"

    const val DEFAULT_CLIP_CACHE_MB = 150

    /** The sizes offered for the clip cache, the phone's. */
    val CLIP_CACHE_CHOICES_MB = listOf(150, 300, 500, 1024)

    /** "Pre-load next songs": the counts offered, the phone's (0 = off). */
    val PRELOAD_CHOICES = listOf(0, 1, 2, 3, 5)

    private val prefs = Preferences.userRoot().node("com/music/bitchord/navidrome_fork")

    private val _scrobble = MutableStateFlow(prefs.getBoolean(KEY_SCROBBLE, true))
    private val _homeShelves = MutableStateFlow(prefs.getBoolean(KEY_HOME_SHELVES, true))
    private val _clipCacheMb = MutableStateFlow(prefs.getInt(KEY_CLIP_CACHE_MB, DEFAULT_CLIP_CACHE_MB))
    private val _preloadSongs = MutableStateFlow(prefs.getInt(KEY_PRELOAD_SONGS, 0))

    val scrobble: StateFlow<Boolean> = _scrobble
    val homeShelves: StateFlow<Boolean> = _homeShelves
    val clipCacheMb: StateFlow<Int> = _clipCacheMb

    /** How many Navidrome songs after the playing one are read whole to disk ([ForkPreload]); 0 = off, the phone's default. */
    val preloadSongs: StateFlow<Int> = _preloadSongs

    fun install() {
        NavidromePlatform.scrobble = { _scrobble.value }
    }

    fun setScrobble(on: Boolean) {
        _scrobble.value = on
        prefs.putBoolean(KEY_SCROBBLE, on)
        save()
    }

    fun setHomeShelves(on: Boolean) {
        _homeShelves.value = on
        prefs.putBoolean(KEY_HOME_SHELVES, on)
        save()
    }

    fun setClipCacheMb(megabytes: Int) {
        _clipCacheMb.value = megabytes
        prefs.putInt(KEY_CLIP_CACHE_MB, megabytes)
        save()
        ForkClipCache.applyLimit()
    }

    fun setPreloadSongs(count: Int) {
        _preloadSongs.value = count
        prefs.putInt(KEY_PRELOAD_SONGS, count)
        save()
    }

    private fun save() {
        runCatching { prefs.flush() }
    }
}

/** The "Navidrome" group of Settings, in the phone's wording. */
@Composable
internal fun ForkNavidromeSettingsGroup() {
    val scrobble by ForkNavidromeSettings.scrobble.collectAsState()
    val homeShelves by ForkNavidromeSettings.homeShelves.collectAsState()
    SettingsGroup("Navidrome") {
        SettingsToggle(
            "Report plays to Navidrome",
            "Your server shows what is playing, and counts songs played to the end",
            scrobble,
            ForkNavidromeSettings::setScrobble,
        )
        SettingsToggle(
            "Navidrome on Home",
            "Your server's albums and playlists above YouTube's feed",
            homeShelves,
            ForkNavidromeSettings::setHomeShelves,
        )
        // The phone's row: a click moves to the next count offered.
        val preload by ForkNavidromeSettings.preloadSongs.collectAsState()
        val choices = ForkNavidromeSettings.PRELOAD_CHOICES
        SettingsRow(
            Icons.Rounded.FastForward,
            "Pre-load next songs",
            "Loads the next Navidrome songs in the queue to disk while one plays, so they start at once and keep playing if the network drops: " +
                if (preload == 0) "Off" else "next $preload",
        ) { ForkNavidromeSettings.setPreloadSongs(choices[(choices.indexOf(preload) + 1) % choices.size]) }
    }
}
