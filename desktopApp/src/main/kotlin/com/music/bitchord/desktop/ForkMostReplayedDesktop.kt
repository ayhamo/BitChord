package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.runtime.Composable
import com.music.bitchord.data.canvas.ForkMostReplayed
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork: the PC's "Jump to the most replayed part" ([ForkMostReplayed]), a row in the player's song
 * menu as on the phone (`ForkMostReplayedPhone`). The curve and the pick are the ones the PC's
 * music-video canvas already reads (`ForkVideoCanvas.heatmap`, `ForkBestPart.partStart`).
 *
 * The seek is the window's own `seekPlayer` ([install], from `DesktopApp`), the one the scrubber uses:
 * it clamps to the song, does nothing while a party locks the controls, and tells the party a local
 * seek happened. The lookup runs on a scope of its own, since the menu closes on the tap, and lands
 * only if the same song still plays.
 */
internal object ForkMostReplayedDesktop {

    @Volatile private var seek: ((Long) -> Unit)? = null
    @Volatile private var playingId: () -> String? = { null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** From the window: its seek and the song playing now. */
    fun install(seek: (Long) -> Unit, playingId: () -> String?) {
        this.seek = seek
        this.playingId = playingId
    }

    fun jump(songId: String) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { ForkMostReplayed.find(songId) }
            if (outcome is ForkMostReplayed.Outcome.Jump) {
                val s = seek
                if (s == null || playingId() != songId) return@launch // the song changed meanwhile
                if (DesktopListenTogether.state.value.controlsLocked) {
                    ForkNotice.show("Only the party host can change playback")
                    return@launch
                }
                s(outcome.ms)
            }
            ForkNotice.show(ForkMostReplayed.message(outcome))
        }
    }
}

/** The menu row: only for a YouTube song; closes the menu as the lookup starts. */
@Composable
internal fun ForkMostReplayedRow(song: Song, onDismiss: () -> Unit) {
    if (!ForkMostReplayed.eligible(song.videoId)) return
    DesktopMenuRow(Icons.Rounded.Whatshot, ForkMostReplayed.MENU_TITLE) {
        onDismiss()
        ForkMostReplayedDesktop.jump(song.videoId)
    }
}
