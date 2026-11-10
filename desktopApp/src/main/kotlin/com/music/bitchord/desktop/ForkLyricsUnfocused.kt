package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.music.bitchord.ui.LocalForkLyricsOnScreen

/**
 * "Keep lyrics moving when unfocused" (fork, the idea of Desktop Revived #116 / 43ca7bc9).
 *
 * The shared lyric clock runs only while the window's lifecycle is RESUMED, and Compose Desktop
 * takes a window out of RESUMED the moment it loses focus. So synced lyrics froze on their line
 * whenever the listener clicked into another program, with BitChord in full view beside it or on
 * a second monitor, and jumped when the window was clicked again. Read as an Android port's
 * leftover rather than a saving anyone chose: on a phone an app out of RESUMED is behind
 * something; an unfocused PC window usually is not.
 *
 * Still a switch, on by default (owner, 2026-11-10; Revived made it off): kept running, the clock asks for a frame
 * every refresh while a song plays, which is GPU and CPU spent on a window the listener may have
 * covered with another one (Windows does not tell an unfocused window it is covered). On, the
 * clock follows only "shown and not minimized" ([onScreen]); off, it provides null and the
 * lifecycle decides exactly as before.
 *
 * Provided through [LocalForkLyricsOnScreen] in `Main.kt`'s window; the row sits under Lyrics,
 * greyed with the others while Synced lyrics is off.
 */
internal object ForkLyricsUnfocused {

    const val KEY = "fork_lyrics_move_unfocused"

    private val persistence by lazy { DesktopPersistence() }

    private val _enabled by lazy { MutableStateFlow(persistence.boolean(KEY, true)) }
    val enabled: StateFlow<Boolean> get() = _enabled

    fun set(on: Boolean) {
        persistence.saveBoolean(KEY, on)
        _enabled.value = on
    }

    /**
     * What the window tells the lyrics: null (the lifecycle's own answer, today's behaviour) while
     * the switch is off; with it on, whether there is a window to look at. Hidden to the tray or
     * minimized never renders frames, switch or not.
     */
    fun onScreen(enabled: Boolean, windowVisible: Boolean, windowMinimized: Boolean): Boolean? =
        if (!enabled) null else windowVisible && !windowMinimized

    @Composable
    fun provided(windowVisible: Boolean, windowMinimized: Boolean): Boolean? {
        val on by enabled.collectAsState()
        return onScreen(on, windowVisible, windowMinimized)
    }
}

/** Settings > Lyrics, after "Blur unfocused lyrics". */
@Composable
internal fun ForkLyricsUnfocusedRow() {
    val on by ForkLyricsUnfocused.enabled.collectAsState()
    SettingsToggle(
        DesktopStrings["fork_lyrics_move_unfocused", "Keep lyrics moving when unfocused"],
        DesktopStrings[
            "fork_lyrics_move_unfocused_subtitle",
            "Synced lyrics keep up while another window has focus, e.g. on a second monitor. Uses more CPU and GPU; never while minimized or in the tray",
        ],
        on,
        ForkLyricsUnfocused::set,
    )
}
