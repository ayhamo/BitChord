package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the lyrics are on screen, as a platform says it, in place of [rememberIsForeground] (fork).
 *
 * The lyric clock and the translation motion in `PlayerLyrics.kt` run only while
 * [rememberIsForeground] holds, which is the lifecycle's RESUMED. On a phone that is "the app is
 * in front". On the PC, Compose drops a window to STARTED as soon as it loses focus, so lyrics
 * stopped dead on the line they were at whenever the listener clicked into another window, even
 * with the BitChord window in full view beside it or on a second monitor (Desktop Revived #116),
 * and jumped ahead when the window was clicked again.
 *
 * Null, the default, leaves the lifecycle in charge: the phone never provides this, and the PC
 * provides null unless its "Keep lyrics moving when unfocused" switch is on. Then it provides
 * true while the window is shown and not minimized, false otherwise, so a window in the tray or
 * the taskbar still renders nothing.
 */
val LocalForkLyricsOnScreen = compositionLocalOf<Boolean?> { null }

/**
 * [lifecycleForeground] is [rememberIsForeground]'s answer, read by the caller on every pass, so
 * the composable calls stay the same whichever way this goes.
 */
@Composable
fun forkLyricsOnScreen(lifecycleForeground: Boolean): Boolean =
    resolveForkLyricsOnScreen(LocalForkLyricsOnScreen.current, lifecycleForeground)

/** The rule, apart for the tests. */
fun resolveForkLyricsOnScreen(platform: Boolean?, lifecycleForeground: Boolean): Boolean =
    platform ?: lifecycleForeground
