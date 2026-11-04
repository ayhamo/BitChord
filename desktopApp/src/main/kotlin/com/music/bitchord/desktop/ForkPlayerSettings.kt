package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.LocaleTags
import com.music.bitchord.data.lyrics.PaxSenix
import com.music.bitchord.data.lyrics.normalizePaxSenixApiKey
import com.music.bitchord.data.navidrome.NavidromeLyrics
import com.music.bitchord.data.settings.AudioQuality
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.rounded.DeleteSweep
import kotlinx.coroutines.withContext

/**
 * Fork: the phone's player settings the PC had no switch for, and the PC switches the shared
 * player never read.
 *
 * `DesktopPlayerSettings` (the shared player's settings on the PC) took its values from eleven
 * lines in the window; the rest stayed at the phone's defaults, so "Hide volume bar", "Reduce
 * dynamic blur" and "Translation language" saved a value nothing read. Those now come from the
 * PC's own stores ([DesktopAppearanceSettings], [DesktopTranslationSetting]); the rows below
 * add the phone's "Reduce animation", "Show Listen Together button", "Show queue history",
 * "Hide Song Status" and "Auto-hide player controls", with its wording and defaults.
 */
internal object ForkPlayerSettings {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun flag(key: String, default: Boolean) = MutableStateFlow(DesktopPersistence().boolean(key, default))

    private val _hideSongStatus = flag(KEY_HIDE_SONG_STATUS, false)
    private val _showQueueHistory = flag(KEY_SHOW_QUEUE_HISTORY, false)
    private val _showListenTogetherButton = flag(KEY_LISTEN_TOGETHER_BUTTON, false)
    // Upstream's default is on; the phone's fork turns it off once (ForkSettings, `canvas_manual_set`),
    // so the controls over a clip stay until tapped away. The PC had it hard-coded off for the same reason.
    private val _spotifyCanvasAutoHide = flag(KEY_SPOTIFY_CANVAS_AUTO_HIDE, false)

    val hideSongStatus: StateFlow<Boolean> = _hideSongStatus
    val showQueueHistory: StateFlow<Boolean> = _showQueueHistory
    val showListenTogetherButton: StateFlow<Boolean> = _showListenTogetherButton
    val spotifyCanvasAutoHide: StateFlow<Boolean> = _spotifyCanvasAutoHide

    fun setHideSongStatus(on: Boolean) = write(KEY_HIDE_SONG_STATUS, on, _hideSongStatus)
    fun setShowQueueHistory(on: Boolean) = write(KEY_SHOW_QUEUE_HISTORY, on, _showQueueHistory)
    fun setShowListenTogetherButton(on: Boolean) = write(KEY_LISTEN_TOGETHER_BUTTON, on, _showListenTogetherButton)
    fun setSpotifyCanvasAutoHide(on: Boolean) = write(KEY_SPOTIFY_CANVAS_AUTO_HIDE, on, _spotifyCanvasAutoHide)

    private fun write(key: String, value: Boolean, into: MutableStateFlow<Boolean>) {
        DesktopPersistence().saveBoolean(key, value)
        into.value = value
    }

    /**
     * The language the shared player translates into. The PC's store keeps blank for "follow the
     * app", but the shared player reads blank as the JVM's locale, which is the system's, not the
     * language picked in Settings: blank is resolved here against the app's, as the shared player
     * would resolve it ([translationTarget]).
     */
    val translationLanguage: StateFlow<String> =
        combine(DesktopTranslationSetting.language, DesktopStrings.language) { stored, _ ->
            translationTarget(stored, DesktopStrings.resolvedTag())
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            translationTarget(DesktopTranslationSetting.language.value, DesktopStrings.resolvedTag()),
        )

    /** The shared player's own rule for a blank setting (`rememberLyricsTranslation`), over [appTag]. */
    internal fun translationTarget(stored: String, appTag: String): String = stored.ifBlank {
        if (LocaleTags.isChineseTag(appTag)) {
            LocaleTags.translationWireTarget(appTag)
        } else {
            Locale.forLanguageTag(appTag).language.ifBlank { "en" }
        }
    }

    /**
     * The window's values the shared player reads, called from the window's `SideEffect`: the
     * stream ceiling (the "Loading lossless" badge only when lossless is asked for, as the phone's
     * `PlayerControls` decides from its Wi-Fi / cellular ceilings; a PC is never metered, so both
     * get the one ceiling) and the quality-upgrade mark, stepped on every playback state.
     */
    fun sync(quality: DesktopAudioQuality, playback: DesktopPlaybackState) {
        val shared = audioQualityOf(quality)
        DesktopPlayerSettings.audioQualityWifi.value = shared
        DesktopPlayerSettings.audioQualityCellular.value = shared
        ForkQualityUpgrade.upgraded(playback)
        ForkCopyLog.noteTrack(playback.song?.videoId) // where the playing song's Copy log starts (ForkCopyLog.kt)
    }

    internal fun audioQualityOf(quality: DesktopAudioQuality): AudioQuality =
        runCatching { AudioQuality.valueOf(quality.name) }.getOrDefault(AudioQuality.LOSSLESS)

    internal const val KEY_HIDE_SONG_STATUS = "hide_song_status"
    internal const val KEY_SHOW_QUEUE_HISTORY = "show_queue_history"
    internal const val KEY_LISTEN_TOGETHER_BUTTON = "show_listen_together_button"
    internal const val KEY_SPOTIFY_CANVAS_AUTO_HIDE = "spotify_canvas_auto_hide"
}

/**
 * Fork: the PaxSenix API key, the phone's `AppSettings.paxSenixApiKey`. PaxSenix's Spotify and
 * Musixmatch routes need it; without it they answer nothing. Kept like the PC's addon sources:
 * sealed with the Windows user's DPAPI on Windows, plain elsewhere (as the Last.fm secret is).
 */
internal object ForkPaxSenixKey {

    private const val KEY_PLAIN = "paxsenix_api_key"
    private const val KEY_PROTECTED = "paxsenix_api_key_protected"

    private val _key = MutableStateFlow("")
    val key: StateFlow<String> = _key

    /** At start, before any lyrics are asked for. */
    fun install() {
        val stored = read()
        _key.value = stored
        PaxSenix.setApiKey(stored)
    }

    fun set(value: String) {
        val normalized = normalizePaxSenixApiKey(value)
        write(normalized)
        _key.value = normalized
        PaxSenix.setApiKey(normalized)
    }

    private fun read(): String {
        val persistence = DesktopPersistence()
        if (DesktopPlatform.isWindows) {
            val sealed = persistence.string(KEY_PROTECTED)
            if (sealed.isBlank()) return ""
            return runCatching {
                DesktopWindowsCrypto.unprotect(Base64.getDecoder().decode(sealed))?.toString(StandardCharsets.UTF_8)
            }.getOrNull().orEmpty()
        }
        return persistence.string(KEY_PLAIN)
    }

    private fun write(value: String) {
        val persistence = DesktopPersistence()
        if (DesktopPlatform.isWindows) {
            val sealed = value.takeIf { it.isNotEmpty() }
                ?.let { DesktopWindowsCrypto.protect(it.toByteArray(StandardCharsets.UTF_8)) }
            persistence.saveString(KEY_PROTECTED, sealed?.let { Base64.getEncoder().encodeToString(it) }.orEmpty())
        } else {
            persistence.saveString(KEY_PLAIN, value)
        }
    }
}

/** Appearance: the phone's "Reduce animation", above "Reduce dynamic blur" as there. */
@Composable
internal fun ForkReduceAnimationRow() {
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    SettingsToggle(
        DesktopStrings["reduce_animation", "Reduce animation"],
        DesktopStrings["reduce_animation_subtitle", "Freezes the main player's gradient instead of drifting"],
        reduceAnimation,
        DesktopAppearanceSettings::setReduceAnimation,
    )
}

/** Miscellaneous, after "Hide volume bar": the phone's player rows, in its order. */
@Composable
internal fun ForkPlayerMiscRows() {
    val listenTogether by ForkPlayerSettings.showListenTogetherButton.collectAsState()
    SettingsToggle(
        DesktopStrings["show_listen_together_button", "Show Listen Together button"],
        DesktopStrings[
            "show_listen_together_button_subtitle",
            "Off, the player shows only the audio output button between lyrics and the queue",
        ],
        listenTogether,
        ForkPlayerSettings::setShowListenTogetherButton,
    )
    val queueHistory by ForkPlayerSettings.showQueueHistory.collectAsState()
    SettingsToggle(
        DesktopStrings["show_queue_history", "Show queue history"],
        DesktopStrings["show_queue_history_subtitle", "Show up to 25 previously played songs above Now playing in the queue"],
        queueHistory,
        ForkPlayerSettings::setShowQueueHistory,
    )
    val songStatus by ForkPlayerSettings.hideSongStatus.collectAsState()
    SettingsToggle(
        DesktopStrings["hide_song_status", "Hide Song Status"],
        DesktopStrings["hide_song_status_subtitle", "Hides the “Playing from” or “Played by” text in the main player"],
        songStatus,
        ForkPlayerSettings::setHideSongStatus,
    )
}

/** Under "Animated canvas": the phone's "Auto-hide player controls" (its Spotify Canvas page). */
@Composable
internal fun ForkCanvasAutoHideRow() {
    val autoHide by ForkPlayerSettings.spotifyCanvasAutoHide.collectAsState()
    SettingsToggle(
        DesktopStrings["spotify_canvas_auto_hide", "Auto-hide player controls"],
        DesktopStrings[
            "spotify_canvas_auto_hide_subtitle",
            "Collapse the half-player after 5 seconds while Spotify Canvas is playing",
        ],
        autoHide,
        ForkPlayerSettings::setSpotifyCanvasAutoHide,
    )
}

/** Storage: the phone's "Clear lyrics cache", after its clip rows. */
@Composable
internal fun ForkClearLyricsRow() {
    val scope = rememberCoroutineScope()
    val cleared by ForkClipCache.cleared.collectAsState()
    val bytes by produceState(0L, cleared) {
        value = withContext(Dispatchers.IO) { runCatching { NavidromeLyrics.cacheBytes() }.getOrDefault(0L) }
    }
    SettingsRow(
        Icons.Rounded.DeleteSweep,
        "Clear lyrics cache",
        "Navidrome lyrics kept from earlier plays, so they show with no network (${forkLyricsSize(bytes)}). Lyrics of kept albums stay",
    ) {
        scope.launch(Dispatchers.IO) {
            NavidromeLyrics.clearCache()
            ForkClipCache.cleared.value++
        }
    }
}

/** The phone's `forkCacheSize`: lyrics are a few KB each. */
private fun forkLyricsSize(bytes: Long): String =
    if (bytes in 1 until 1048576L) "${(bytes + 1023) / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

/**
 * The lyrics sources card's PaxSenix row, the phone's: says whether a key is set, and opens an
 * editor in place (a card over the card would stack two scrims on the PC).
 */
@Composable
internal fun ForkPaxSenixKeyRow() {
    val key by ForkPaxSenixKey.key.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var input by remember(key, editing) { mutableStateOf(key) }
    Column(Modifier.fillMaxWidth().padding(horizontal = panelInset(18.dp), vertical = 9.dp)) {
        Row(
            Modifier.fillMaxWidth().desktopRowClickable { editing = !editing },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Key, null, tint = DesktopSecondary, modifier = Modifier.padding(end = 14.dp))
            Text(
                if (key.isBlank()) {
                    DesktopStrings["paxsenix_api_key_missing", "PaxSenix API key — optional, not configured"]
                } else {
                    DesktopStrings["paxsenix_api_key_configured", "PaxSenix API key — configured"]
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }
        if (editing) {
            OutlinedTextField(
                input,
                { input = it },
                Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(DesktopStrings["paxsenix_api_key", "PaxSenix API key"]) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { editing = false }) {
                    Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    ForkPaxSenixKey.set(input)
                    editing = false
                }) { Text(DesktopStrings["save", "Save"], color = DesktopAccent) }
            }
        }
    }
}
