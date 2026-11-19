package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/**
 * Fork (owner, 2026-11-19): how Settings reaches the Navidrome page. [openPage] opens Settings >
 * Navidrome ([DesktopSettingsPage.NAVIDROME]); [openServer] leaves Settings for the sidebar's
 * Navidrome page, which holds the login and Disconnect. One object so `DesktopSettingsScreen` takes
 * one parameter for both.
 */
internal class ForkNavidromeSettingsLinks(
    val openPage: () -> Unit,
    val openServer: () -> Unit,
)

/**
 * Settings' "Navidrome" group, at the foot of the main page above About (owner, 2026-11-19): one row
 * into the Navidrome page, as "Account & integrations" opens its page. It sat second on the page,
 * right after Account, with every row inline; the fork's groups now come after upstream's.
 *
 * While the search field has text the rows themselves are drawn here instead, so a search for
 * "pre-load" or "scrobble" still finds them: the field only filters the main page's rows, and a
 * row on a sub-page would otherwise be out of its reach (the phone's search does the same for its
 * categories).
 */
@Composable
internal fun ForkNavidromeSettingsEntry(links: ForkNavidromeSettingsLinks) {
    if (LocalSettingsQuery.current.isNotBlank()) {
        SettingsGroup("Navidrome") {
            ForkNavidromeServerRow(links.openServer)
            ForkNavidromeOptionRows()
        }
        return
    }
    val url by com.music.bitchord.data.navidrome.NavidromeSettings.url.collectAsState()
    val user by com.music.bitchord.data.navidrome.NavidromeSettings.username.collectAsState()
    SettingsGroup("Navidrome") {
        ForkMarked {
            SettingsRow(
                Icons.Rounded.Cloud,
                "Navidrome",
                forkNavidromeServerLine(url, user) + ". Reporting plays, Home shelves, pre-load and more",
                links.openPage,
            )
        }
    }
}

/**
 * Settings > Navidrome: every setting the Navidrome library adds, in one place (owner, 2026-11-19).
 * They were a group on the main page, with "Clear lyrics cache" (the server's lyrics) under Storage
 * and the login only on the sidebar's Navidrome page. A page of Settings like Account & integrations:
 * its back is the window's (Esc, the mouse's back button), so it has no Done button of its own.
 */
@Composable
internal fun ForkNavidromeSettingsPage(onOpenServer: () -> Unit) {
    DesktopSettingsPageFrame {
        Text(
            "Navidrome",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        ) {
            item { SettingsGroup("Server") { ForkNavidromeServerRow(onOpenServer) } }
            item { SettingsGroup("Options") { ForkNavidromeOptionRows() } }
        }
    }
}

/** The server rows' line: who is signed in where, or that no server is set up (`ForkNavidromeSettingsPageTest`). */
internal fun forkNavidromeServerLine(url: String, user: String): String =
    if (com.music.bitchord.data.navidrome.NavidromeSettings.isConfigured(url, user)) "$user on $url" else "Not connected"

/**
 * The server: who is signed in where. A click opens the sidebar's Navidrome page, where the login
 * (or, signed in, Disconnect) is; never greyed, since it is how a server is set up.
 */
@Composable
private fun ForkNavidromeServerRow(onOpenServer: () -> Unit) {
    val url by com.music.bitchord.data.navidrome.NavidromeSettings.url.collectAsState()
    val user by com.music.bitchord.data.navidrome.NavidromeSettings.username.collectAsState()
    val configured = com.music.bitchord.data.navidrome.NavidromeSettings.isConfigured(url, user)
    ForkMarked {
        SettingsRow(
            Icons.Rounded.Cloud,
            "Server",
            forkNavidromeServerLine(url, user) + if (configured) ". Click to open its page" else ": click to connect",
            onOpenServer,
        )
    }
}

/** The Navidrome settings in the phone's wording and order, each with the Fork mark. */
@Composable
private fun ForkNavidromeOptionRows() {
    val scrobble by ForkNavidromeSettings.scrobble.collectAsState()
    val homeShelves by ForkNavidromeSettings.homeShelves.collectAsState()
    val url by com.music.bitchord.data.navidrome.NavidromeSettings.url.collectAsState()
    val user by com.music.bitchord.data.navidrome.NavidromeSettings.username.collectAsState()
    ForkMarked {
        // Every row but the last rides on the server: greyed (never hidden) while none is set up, as
        // on the phone (the owner's rule for dependent rows).
        ForkDisabledWhen(!com.music.bitchord.data.navidrome.NavidromeSettings.isConfigured(url, user)) {
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
            // The phone's row, after "Navidrome on Home"; the same switch as the Navidrome page's.
            val history by ForkYouTubeHistory.enabled.collectAsState()
            SettingsToggle(
                "Navidrome plays in YouTube history",
                "Songs you play from your server count on YouTube Music too (signed in), so its recommendations learn from them",
                history,
                ForkNavidrome::setYouTubeHistory,
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
            val handoff by ForkHandoffDesktop.enabled.collectAsState()
            SettingsToggle(ForkHandoffDesktop.SETTING_TITLE, ForkHandoffDesktop.SETTING_SUBTITLE, handoff, ForkHandoffDesktop::setEnabled)
        }
        // Moved here from Storage, where it followed the clip rows: it empties the server's lyrics,
        // so it belongs with the server's settings. Not greyed: lyrics kept from a server since
        // disconnected still take space, and clearing them needs no server.
        ForkClearLyricsRow()
    }
}
