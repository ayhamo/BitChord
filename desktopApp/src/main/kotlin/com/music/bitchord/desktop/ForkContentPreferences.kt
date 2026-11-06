package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.withoutVideos
import com.music.bitchord.ui.components.VideoThumbnails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: upstream v1.9.1's "Content Preferences" on the PC (upstream b93c5144: `AppSettings.hideRecents`,
 * `.hideHomeVideos`, `.squareVideoThumbnails`, `SettingsSheet`'s CONTENT_PREFERENCES group, `MainActivity`'s
 * `visibleHomeState`). Same keys as the phone, so the switches mean the same thing on both.
 *
 * Ported: Hide Recents, Hide videos from Home, Square video thumbnails. Not ported: the phone's
 * "Hide Top Picks for You" and "Hide Find Your Mood", because the PC's Home never draws either shelf
 * (`HomeScreen` only draws them for a caller that passes `onTopPickClick` / `onMoodClick`, and the
 * PC passes neither), so the switches would do nothing. "Prefer music-only version" heads the group,
 * as on the phone; it lives in `ForkMusicOnly`.
 */
internal object ForkContentPreferences {
    private const val KEY_HIDE_RECENTS = "hide_recents"
    private const val KEY_HIDE_HOME_VIDEOS = "hide_home_videos"
    private const val KEY_SQUARE_VIDEO_THUMBNAILS = "square_video_thumbnails"

    private val persistence = DesktopPersistence()

    private val _hideRecents = MutableStateFlow(persistence.boolean(KEY_HIDE_RECENTS, false))
    val hideRecents: StateFlow<Boolean> = _hideRecents

    private val _hideHomeVideos = MutableStateFlow(persistence.boolean(KEY_HIDE_HOME_VIDEOS, false))
    val hideHomeVideos: StateFlow<Boolean> = _hideHomeVideos

    private val _squareVideoThumbnails = MutableStateFlow(persistence.boolean(KEY_SQUARE_VIDEO_THUMBNAILS, false))
    val squareVideoThumbnails: StateFlow<Boolean> = _squareVideoThumbnails

    /**
     * Hands the saved "Square video thumbnails" to the shared UI before anything is drawn, as the
     * phone's `AppSettings.load` does: `VideoThumbnails.square` is plain Compose state that every
     * card, row and the player read directly (`trackArtSize`, `VIDEO_ASPECT`, `VIDEO_CARD_WIDTH`),
     * and the PC never set it before, so it stayed 16:9 whatever the setting said.
     */
    fun install() {
        VideoThumbnails.square = _squareVideoThumbnails.value
    }

    fun setHideRecents(value: Boolean) {
        _hideRecents.value = value
        persistence.saveBoolean(KEY_HIDE_RECENTS, value)
    }

    fun setHideHomeVideos(value: Boolean) {
        _hideHomeVideos.value = value
        persistence.saveBoolean(KEY_HIDE_HOME_VIDEOS, value)
    }

    fun setSquareVideoThumbnails(value: Boolean) {
        _squareVideoThumbnails.value = value
        VideoThumbnails.square = value
        persistence.saveBoolean(KEY_SQUARE_VIDEO_THUMBNAILS, value)
    }

    /**
     * The YouTube feed as Home should draw it: the phone's `visibleHomeState`, Recents (by
     * `YtMusicRepository.RECENT_TITLE`) and music videos taken out (`withoutVideos`, which also drops
     * a shelf left empty). Applied to the YouTube feed *before* `ForkDesktopHome.present` merges the
     * Navidrome and Spotify sections in: filtering the merged list would lose the section sizes it
     * carries (`ForkHomeShelves`), and would also hide the PC's Navidrome "Recents" shelf, which the
     * phone names "Recently played" and so never hides.
     */
    @Composable
    fun visible(feed: UiState<List<HomeShelf>>): UiState<List<HomeShelf>> {
        val noRecents by hideRecents.collectAsState()
        val hideVideos by hideHomeVideos.collectAsState()
        return remember(feed, noRecents, hideVideos) {
            val loaded = feed as? UiState.Success
            if (loaded != null && (noRecents || hideVideos)) {
                var shelves = loaded.data
                if (noRecents) shelves = shelves.filterNot { it.title == YtMusicRepository.RECENT_TITLE }
                if (hideVideos) shelves = shelves.withoutVideos()
                UiState.Success(shelves)
            } else {
                feed
            }
        }
    }

    /** The Recents skeleton, as the phone's `homeRecentlyPlayedLoading && !hideRecents`. */
    @Composable
    fun recentsLoading(loading: Boolean): Boolean {
        val noRecents by hideRecents.collectAsState()
        return loading && !noRecents
    }
}

/**
 * Settings → Content Preferences, the phone's group (`content_preferences`), placed as the phone
 * places it: after Playback, before Appearance.
 */
@Composable
internal fun ForkContentPreferencesGroup() {
    val hideRecents by ForkContentPreferences.hideRecents.collectAsState()
    val hideHomeVideos by ForkContentPreferences.hideHomeVideos.collectAsState()
    val square by ForkContentPreferences.squareVideoThumbnails.collectAsState()
    SettingsGroup(DesktopStrings["content_preferences", "Content Preferences"]) {
        ForkPreferMusicOnlyRow() // the phone's first row here (ForkMusicOnly)
        ForkPlainToggle(
            DesktopStrings["hide_recents", "Hide Recents"],
            "recents recent history feed home",
            hideRecents,
            ForkContentPreferences::setHideRecents,
        )
        SettingsToggle(
            DesktopStrings["hide_home_videos", "Hide videos from Home"],
            DesktopStrings[
                "hide_home_videos_subtitle",
                "Shelves of only videos go too. Search, artists and playlists still show them",
            ],
            hideHomeVideos,
            ForkContentPreferences::setHideHomeVideos,
        )
        SettingsToggle(
            DesktopStrings["square_video_thumbnails", "Square video thumbnails"],
            DesktopStrings[
                "square_video_thumbnails_subtitle",
                "Show music videos as squares everywhere, including the player",
            ],
            square,
            ForkContentPreferences::setSquareVideoThumbnails,
        )
    }
}

/**
 * [SettingsToggle] for a row the phone gives no subtitle: an empty subtitle there would still take a
 * line and leave the title sitting high. [keywords] are what the settings search also matches on,
 * the phone's `row(…)` words.
 */
@Composable
private fun ForkPlainToggle(title: String, keywords: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (!settingsRowVisible(title, keywords)) return
    Row(
        Modifier
            .fillMaxWidth()
            .desktopRowClickable { onCheckedChange(!checked) }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(18.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = desktopSwitchColors())
    }
}
