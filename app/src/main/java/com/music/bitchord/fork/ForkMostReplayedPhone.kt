package com.music.bitchord.fork

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.Player
import com.music.bitchord.data.canvas.ForkMostReplayed
import com.music.bitchord.data.listentogether.ListenTogether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork: the phone's seeks for the best part: "Play from the best part" ([playFromBestPart]) and
 * "Your best part"'s Now and Listen from here. (Its "Jump to the most replayed part" went with the
 * switch to v1.10's Best Part pill, which shows the same spot; the name stayed.)
 *
 * The seek goes through the UI's MediaController ([Attach], from `MainActivity` beside
 * `MusicVideoEngine.attach`), as the scrubber's does: the session's forwarding player then applies
 * what a seek from the screen gets (ignored while a party locks the controls, sent to a cast device
 * when one plays). The service's active ExoPlayer would skip all of that.
 *
 * The lookup runs on a scope of its own rather than the menu's: the menu closes on the tap, and the
 * seek still has to land once the part is found.
 */
object ForkMostReplayedPhone {

    @Volatile private var controller: Player? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Keeps the screen's MediaController while it is the one in use. */
    @Composable
    fun Attach(controller: Player?) {
        DisposableEffect(controller) {
            ForkMostReplayedPhone.controller = controller
            com.music.bitchord.data.canvas.ForkBestParts.player = bestPartPlayer // "Your best part" seeks and reads the position here
            onDispose { if (ForkMostReplayedPhone.controller === controller) ForkMostReplayedPhone.controller = null }
        }
    }

    /** "Your best part"'s Now and Listen from here (canvas.md §14 rule 5), through the controller. */
    private val bestPartPlayer = object : com.music.bitchord.data.canvas.ForkBestParts.Player {
        override fun positionOf(songId: String): Long? = ForkMostReplayedPhone.positionOf(songId)
        override fun seek(songId: String, ms: Long) {
            val c = controller ?: return
            if (c.currentMediaItem?.mediaId != songId || ListenTogether.state.value.controlsLocked) return
            c.seekTo(ms)
            if (!c.isPlaying) c.play()
        }
    }

    /** Where [songId] is now, while it is the controller's current song; null otherwise. */
    fun positionOf(songId: String): Long? {
        val c = controller ?: return null
        return if (c.currentMediaItem?.mediaId == songId) c.currentPosition.coerceAtLeast(0) else null
    }

    /**
     * "Play from the best part" (Prism's song-menu row): [songId]'s best part
     * ([ForkMostReplayed.bestPart]: your own, else the most replayed, else the chorus), then the song
     * from there. Playing now, it seeks; otherwise [playNext] queues it next (the menu's "Play
     * next") and the controller moves to it at that point once it is in the queue.
     */
    fun playFromBestPart(context: Context, songId: String, durationMs: Long, playNext: () -> Unit) {
        val app = context.applicationContext
        // A second tap (another song, or the same again) replaces the first: two lookups both
        // queuing and seeking raced each other. On the main thread, so no lock is needed here.
        pending?.cancel()
        pending = scope.launch {
            // canvas.md §14 rule 6: 5 s to find it; without one, the song from the start, saying so.
            val ms = ForkMostReplayed.startForPlay(
                locked = { ListenTogether.state.value.controlsLocked },
                lockedText = "Only the host can seek in this party",
                say = { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() },
            ) { withContext(Dispatchers.IO) { runCatching { ForkMostReplayed.bestPart(songId, durationMs) }.getOrNull() } } ?: return@launch
            val c = controller ?: return@launch
            if (c.currentMediaItem?.mediaId == songId) {
                c.seekTo(ms)
                if (!c.isPlaying) c.play()
                return@launch
            }
            playNext()
            // The queue is changed by the service; wait (up to 5 s) for the song to be next.
            repeat(25) {
                kotlinx.coroutines.delay(200)
                if (c.currentMediaItem?.mediaId == songId) { c.seekTo(ms); return@launch } // an empty queue plays it at once
                val next = c.currentMediaItemIndex + 1
                if (next < c.mediaItemCount && c.getMediaItemAt(next).mediaId == songId) {
                    c.seekTo(next, ms)
                    c.play()
                    return@launch
                }
            }
        }
    }

    private var pending: kotlinx.coroutines.Job? = null
}

