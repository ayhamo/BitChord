package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: the phone's "Prioritize Spotify Canvas" (`AppSettings.prioritizeSpotifyCanvas`, its row on
 * `SpotifyCanvasAuthScreen`), on the PC.
 *
 * The PC asked the four clip providers in one fixed order (Apple Music, Tidal, the community index,
 * Spotify) and told the shared player the switch was off (`DesktopPlayerHost`'s constant `false`).
 * With it on, the phone's `CanvasRepository.canvasFor` / `canvasForAlbum` ask Spotify first and the
 * rest in the same order after it, and key their cache on the order (`cacheKey`'s
 * `|spotifyFirst=`), so a toggle never reuses the answer the other order found. [ordered] and [key]
 * are those two rules for `DesktopCanvasClient`; the shared player restarts its lookup on the flow
 * (`rememberCanvasArtwork` keys on `prioritizeSpotifyCanvas`), so a toggle mid-song asks again.
 *
 * Stored under the phone's key, so a backup carries it across ([ForkBackup]). Off by default, as
 * on the phone.
 */
internal object ForkSpotifyCanvasFirst {

    internal const val KEY = "prioritize_spotify_canvas"

    private val _prioritized = MutableStateFlow(DesktopPersistence().boolean(KEY, false))
    val prioritized: StateFlow<Boolean> = _prioritized

    fun set(on: Boolean) {
        DesktopPersistence().saveBoolean(KEY, on)
        _prioritized.value = on
    }

    /** The phone's `cacheKey`: the order is part of the question. */
    fun key(base: String, spotifyFirst: Boolean = prioritized.value): String = "$base|spotifyFirst=$spotifyFirst"

    /**
     * [sources] in the order they are asked. They are passed in the PC's default order, Spotify
     * last, so "Spotify first" moves the last one to the front and keeps the others as they were,
     * which is exactly the phone's second list.
     */
    fun <T> ordered(sources: List<T>, spotifyFirst: Boolean = prioritized.value): List<T> =
        if (!spotifyFirst || sources.size < 2) sources else listOf(sources.last()) + sources.dropLast(1)
}

/**
 * Under "Spotify Canvas setup": the phone's switch, its wording. Greyed (never hidden, the owner's
 * rule) while no `sp_dc` cookie is saved: without a sign-in the Spotify provider asks nothing
 * (`DesktopSpotifyToken.accessToken` answers null), so putting it first would change nothing.
 */
@Composable
internal fun ForkPrioritizeSpotifyCanvasRow(signedIn: Boolean) {
    val prioritized by ForkSpotifyCanvasFirst.prioritized.collectAsState()
    ForkDisabledWhen(!signedIn) {
        SettingsToggle(
            DesktopStrings["prioritize_spotify_canvas", "Prioritize Spotify Canvas"],
            DesktopStrings[
                "prioritize_spotify_canvas_subtitle",
                "Try Spotify before Apple Music and other animated cover providers",
            ],
            prioritized,
            ForkSpotifyCanvasFirst::set,
        )
    }
}
