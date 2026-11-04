package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isNavidromeRadio
import com.music.bitchord.playback.PlaybackPosition
import java.util.Locale

/**
 * Fork: the seek bar's two times for an internet radio station (`Song.isNavidromeRadio`).
 *
 * The bar draws its times as fractions of the song's length, and a live stream has none, so a
 * station read 0:00 and -0:00 however long it had played. While a station is in the player, the
 * left time is how long it has been playing and the right one says it's live.
 */
internal object ForkLiveClock {

    /** The playing station's playhead, or null when the player holds a song with a length. */
    private var live by mutableStateOf<PlaybackPosition?>(null)

    /** Keeps [live] on [position] while [song] is a station. */
    @Composable
    fun Follow(song: Song, position: PlaybackPosition) {
        val station = song.isNavidromeRadio
        DisposableEffect(station, position) {
            live = position.takeIf { station }
            onDispose { if (live === position) live = null }
        }
    }

    /** The left time under the bar for a station, or null for upstream's. */
    fun elapsed(): String? = live?.let { format(it.positionMs) }

    /** The right time under the bar for a station, or null for upstream's. */
    fun remaining(): String? = live?.let { "LIVE" }

    private fun format(ms: Long): String {
        val seconds = (ms / 1_000).coerceAtLeast(0)
        val hours = seconds / 3_600
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, seconds / 60 % 60, seconds % 60)
        } else {
            "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
        }
    }
}
