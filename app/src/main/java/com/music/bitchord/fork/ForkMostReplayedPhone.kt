package com.music.bitchord.fork

import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import com.music.bitchord.data.canvas.ForkMostReplayed
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.ui.components.ActionRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork: the phone's "Jump to the most replayed part" ([ForkMostReplayed]), a row in the player's
 * menu ([ForkMostReplayedMenuRow], drawn by `ForkPlayerMenuRows`).
 *
 * The seek goes through the UI's MediaController ([Attach], from `MainActivity` beside
 * `MusicVideoEngine.attach`), as the scrubber's does: the session's forwarding player then applies
 * what a seek from the screen gets (ignored while a party locks the controls, sent to a cast device
 * when one plays). The service's active ExoPlayer would skip all of that.
 *
 * The lookup runs on a scope of its own rather than the menu's: the menu closes on the tap, and the
 * jump still has to land once the curve comes back. It lands only if the same song still plays.
 */
object ForkMostReplayedPhone {

    @Volatile private var controller: Player? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Keeps the screen's MediaController for [jump] while it is the one in use. */
    @Composable
    fun Attach(controller: Player?) {
        DisposableEffect(controller) {
            ForkMostReplayedPhone.controller = controller
            onDispose { if (ForkMostReplayedPhone.controller === controller) ForkMostReplayedPhone.controller = null }
        }
    }

    /** Looks [songId]'s curve up and seeks to its most replayed part, saying what happened in a toast. */
    fun jump(context: Context, songId: String) {
        val app = context.applicationContext
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { ForkMostReplayed.find(songId) }
            val c = controller
            if (outcome is ForkMostReplayed.Outcome.Jump) {
                if (c == null || c.currentMediaItem?.mediaId != songId) return@launch // the song changed meanwhile
                if (ListenTogether.state.value.controlsLocked) {
                    Toast.makeText(app, "Only the host can seek in this party", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                c.seekTo(outcome.ms)
            }
            Toast.makeText(app, ForkMostReplayed.message(outcome), Toast.LENGTH_SHORT).show()
        }
    }
}

/** The menu row: only for a YouTube song, closing the menu ([onDone]) as the lookup starts. */
@Composable
internal fun ForkMostReplayedMenuRow(song: com.music.bitchord.data.model.Song, accent: Color, onDone: () -> Unit) {
    if (!ForkMostReplayed.eligible(song.videoId)) return
    val context = LocalContext.current
    ActionRow(
        icon = Icons.Rounded.Whatshot,
        label = ForkMostReplayed.MENU_TITLE,
        value = null,
        accent = accent,
    ) {
        onDone()
        ForkMostReplayedPhone.jump(context, song.videoId)
    }
}
