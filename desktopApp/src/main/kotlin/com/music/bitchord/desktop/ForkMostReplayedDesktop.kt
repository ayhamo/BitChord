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
    @Volatile private var position: () -> Long = { 0L }
    @Volatile private var play: ((Song) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * From the window: its seek, the song playing now, where it is, and its "play this song" (for
     * "Play from the best part").
     */
    fun install(seek: (Long) -> Unit, playingId: () -> String?, position: () -> Long = { 0L }, play: ((Song) -> Unit)? = null) {
        this.seek = seek
        this.playingId = playingId
        this.position = position
        this.play = play
        com.music.bitchord.data.canvas.ForkBestParts.player = bestPartPlayer
    }

    /** "Your best part"'s Now and Listen from here (canvas.md §14 rule 5), through the window's seek. */
    private val bestPartPlayer = object : com.music.bitchord.data.canvas.ForkBestParts.Player {
        override fun positionOf(songId: String): Long? = ForkMostReplayedDesktop.positionOf(songId)
        override fun seek(songId: String, ms: Long) {
            if (playingId() != songId || DesktopListenTogether.state.value.controlsLocked) return
            ForkMostReplayedDesktop.seek?.invoke(ms)
        }
    }

    /** Where [songId] is now while it plays; null otherwise. */
    fun positionOf(songId: String): Long? = if (playingId() == songId) position().coerceAtLeast(0) else null

    /**
     * "Play from the best part" (Prism's song-menu row): [song]'s best part
     * ([ForkMostReplayed.bestPart]: your own, else the most replayed, else the chorus), then the song
     * played from there (seeking once the window plays it).
     */
    fun playFromBestPart(song: Song) {
        scope.launch {
            val durationMs = com.music.bitchord.data.canvas.ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
            // canvas.md §14 rule 6: 5 s to find it; without one, the song from the start, saying so.
            val found = kotlinx.coroutines.withTimeoutOrNull(com.music.bitchord.data.canvas.ForkBestParts.FIND_MS) {
                withContext(Dispatchers.IO) { runCatching { ForkMostReplayed.bestPart(song.videoId, durationMs) }.getOrNull() }
            }
            if (found == null) ForkNotice.show(com.music.bitchord.data.canvas.ForkBestParts.FROM_START)
            if (found == null && playingId() == song.videoId) return@launch
            val ms = found ?: 0L
            if (DesktopListenTogether.state.value.controlsLocked) {
                ForkNotice.show("Only the party host can change playback")
                return@launch
            }
            if (playingId() != song.videoId) {
                val p = play ?: return@launch
                p(song)
                // The engine opens the stream first; seek once the song is the one playing (up to 8 s).
                var waited = 0
                while (waited < 40 && !(playingId() == song.videoId && position() > 0)) {
                    kotlinx.coroutines.delay(200)
                    waited++
                }
                if (playingId() != song.videoId) return@launch
            }
            ForkMostReplayedDesktop.seek?.invoke(ms)
        }
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

/**
 * The player menu's fork rows: the jump (only for a YouTube song; closes the menu as the lookup
 * starts), then the song's canvas choices and best part ([ForkCanvasMenuRowsDesktop]).
 */
@Composable
internal fun ForkMostReplayedRow(song: Song, onDismiss: () -> Unit) {
    if (ForkMostReplayed.eligible(song.videoId)) {
        DesktopMenuRow(Icons.Rounded.Whatshot, ForkMostReplayed.MENU_TITLE) {
            onDismiss()
            ForkMostReplayedDesktop.jump(song.videoId)
        }
    }
    ForkCanvasMenuRowsDesktop(song, onDismiss)
}
