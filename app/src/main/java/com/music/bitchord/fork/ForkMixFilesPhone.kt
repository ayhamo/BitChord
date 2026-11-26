package com.music.bitchord.fork

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.smart.automixv2.ForkV2MixFile
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.ui.player.ForkMixFiles

/**
 * Fork: the phone's file side of the mix files' import and export (the transition editor, Automix settings) ([ForkMixFiles]): the system
 * document picker, as the backup's Export / Import data does, so no storage permission is needed. Hosted once in
 * `MainActivity`'s content; the launchers live as long as it does.
 */
@Composable
fun ForkMixFilesPhone() {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<Any>(2) } // [0]: (onText, onFail) for the open, [1]: (text, done) for the save
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        @Suppress("UNCHECKED_CAST")
        val job = pending[0] as? Pair<(String) -> Unit, (String) -> Unit>
        pending[0] = null
        if (job == null || uri == null) return@rememberLauncherForActivityResult // cancelled
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        when {
            bytes == null -> job.second("the file could not be read")
            bytes.size > ForkV2MixFile.MAX_BYTES -> job.second("the file is over 2 MB, not a mix file")
            else -> job.first(bytes.toString(Charsets.UTF_8))
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        @Suppress("UNCHECKED_CAST")
        val job = pending[1] as? Pair<String, (Boolean?) -> Unit>
        pending[1] = null
        if (job == null) return@rememberLauncherForActivityResult
        if (uri == null) { job.second(null); return@rememberLauncherForActivityResult } // cancelled
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(job.first.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        job.second(ok)
    }
    DisposableEffect(opener, saver) {
        ForkMixFiles.open = { onText, onFail ->
            pending[0] = onText to onFail
            // *.vivimix.json may come as JSON, text or plain bytes depending on the file manager
            runCatching { opener.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                .onFailure { pending[0] = null; onFail("the file could not be read") }
        }
        ForkMixFiles.saveAs = { name, text, done ->
            pending[1] = text to done
            runCatching { saver.launch(name) }.onFailure { pending[1] = null; done(false) }
        }
        onDispose {
            ForkMixFiles.open = null
            ForkMixFiles.saveAs = null
        }
    }
}

/**
 * Fork: the phone's queue for a mix file's "Play these mixes" (mix-file.md rule 7, [ForkMixFiles.Queue]), through the
 * screen's MediaController: a fresh queue of the file's songs in order, the queue before it replaced (the playing song
 * too, unless it is the file's first, which plays on), then the first plays. With nothing playing they start through
 * `MainActivity`'s [play], as "Add to queue" on a silent player does. Hosted beside `playSongsNext`.
 */
@Composable
fun ForkMixQueuePhone(controller: Player?, play: (List<Song>, Int) -> Unit) {
    val current by rememberUpdatedState(controller)
    val start by rememberUpdatedState(play)
    DisposableEffect(Unit) {
        val q = object : ForkMixFiles.Queue {
            override fun refusal(): String? =
                if (ListenTogether.state.value.controlsLocked) "the party's host holds the queue" else if (current == null) "the player isn't ready" else null

            override fun playingId(): String? = current?.takeIf { it.mediaItemCount > 0 }?.currentMediaItem?.mediaId

            override fun fresh(songs: List<Song>, keep: Boolean) {
                val c = current ?: return
                // the file's songs as the queue's context, so autoplay continues after them as after a playlist
                val items = songs.map {
                    it.copy(playbackSourceType = it.playbackSourceType ?: PlaybackSourceType.QUEUE).asQueueEntry(QueueTier.CONTEXT).toMediaItem()
                }
                if (items.isEmpty()) return
                if (keep && c.mediaItemCount > 0) {
                    // the playing song (the file's first) plays on: everything around it is replaced
                    val at = c.currentMediaItemIndex
                    if (at + 1 < c.mediaItemCount) c.removeMediaItems(at + 1, c.mediaItemCount)
                    if (at > 0) c.removeMediaItems(0, at)
                    c.addMediaItems(items.drop(1))
                } else if (c.mediaItemCount == 0) {
                    start(songs, 0) // nothing playing: as "Add to queue" on a silent player
                    return
                } else {
                    c.setMediaItems(items, 0, 0L)
                    c.prepare()
                }
                c.play()
            }
        }
        ForkMixFiles.queue = q
        onDispose { if (ForkMixFiles.queue === q) ForkMixFiles.queue = null }
    }
}
