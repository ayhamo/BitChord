package com.music.bitchord.fork

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkCanvasChoices
import com.music.bitchord.data.canvas.ForkClipGate
import com.music.bitchord.data.canvas.ForkMostReplayed
import com.music.bitchord.data.canvas.ForkMusicVideoMatch
import com.music.bitchord.data.canvas.ForkVideoCanvas
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.ActionRow
import com.music.bitchord.ui.player.ForkCanvasSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: Prism's per-song canvas menu and best-part rows in the phone player's song menu (drawn by
 * `ForkPlayerMenuRows`):
 *
 * - "Canvas for this song" on/off ([ForkCanvasChoices.setOff]);
 * - for a music-video loop: "Pick another part" (the next of the video's clip candidates),
 *   "Pick another video" (a list of the song's other videos) and "Not this video";
 * - "Set your best part here" / "Clear your best part" ([ForkBestParts.setMine]).
 *
 * Each change is a new question for the player at once (`ForkCanvasSettings.changed`).
 */
@Composable
internal fun ForkCanvasMenuRows(song: Song, accent: Color, onDone: () -> Unit) {
    if (!ForkClipGate.isSong(song)) return
    val context = LocalContext.current
    // Re-read on each change of the choices (their generation moves with every change).
    val generation by com.music.bitchord.data.canvas.ForkCanvasSettings.generation.collectAsStateWithLifecycle()
    val choice = remember(song.videoId, generation) { ForkCanvasChoices.of(song.videoId) }
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val videos by ForkCanvasSwitch.videos.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    ActionRow(
        icon = Icons.Rounded.Wallpaper,
        label = ForkCanvasChoices.OFF_TITLE,
        value = if (choice.off) "Off" else "On",
        accent = accent,
    ) { ForkCanvasChoices.setOff(song.videoId, !choice.off) }
    if (!choice.off && choice.video != null) {
        ActionRow(icon = Icons.Rounded.ContentCut, label = ForkCanvasChoices.PART_TITLE, value = null, accent = accent) {
            ForkCanvasChoices.nextPart(song.videoId)
            onDone()
        }
    }
    if (!choice.off && backdrops && videos && ForkMostReplayed.eligible(song.videoId)) {
        ActionRow(icon = Icons.Rounded.VideoLibrary, label = ForkCanvasChoices.VIDEO_TITLE, value = null, accent = accent) { picking = true }
    }
    if (!choice.off && choice.video != null) {
        ActionRow(icon = Icons.Rounded.Block, label = ForkCanvasChoices.NOT_THIS_TITLE, value = null, accent = accent) {
            ForkCanvasChoices.reject(song.videoId)
            onDone()
        }
    }

    // Your own best part: where the song is now.
    var mineVersion by remember { mutableIntStateOf(0) }
    val mine = remember(song.videoId, mineVersion) { ForkBestParts.mine(song.videoId) }
    ActionRow(icon = Icons.Rounded.Flag, label = ForkBestParts.SET_MINE, value = null, accent = accent) {
        val at = ForkMostReplayedPhone.positionOf(song.videoId)
        if (at != null) {
            ForkBestParts.setMine(song.videoId, at)
            mineVersion++
            Toast.makeText(context, "${ForkBestParts.CHIP_MINE}: ${ForkBestParts.clock(at)}", Toast.LENGTH_SHORT).show()
        }
        onDone()
    }
    if (mine != null) {
        ActionRow(icon = Icons.Rounded.StarOutline, label = ForkBestParts.CLEAR_MINE, value = ForkBestParts.clock(mine), accent = accent) {
            ForkBestParts.setMine(song.videoId, null)
            mineVersion++
        }
    }

    if (picking) ForkPickVideoDialog(song, choice.rejected, onDismiss = { picking = false }) { id ->
        picking = false
        ForkCanvasChoices.pick(song.videoId, id)
        onDone()
    }
}

/** "Play from the best part", in any song's menu beside "Play next" (Prism's row). */
@Composable
internal fun ForkPlayFromBestPartRow(song: Song, accent: Color, onPlayNext: () -> Unit) {
    if (!ForkClipGate.isSong(song) || com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) return
    val context = LocalContext.current
    ActionRow(icon = Icons.Rounded.Star, label = ForkBestParts.PLAY_FROM, value = null, accent = accent) {
        ForkMostReplayedPhone.playFromBestPart(context, song.videoId, ForkMusicVideoMatch.lengthOf(song.durationText) * 1000, onPlayNext)
    }
}

/** The song's other videos (search matches first), one tap picks the loop's video. */
@Composable
private fun ForkPickVideoDialog(song: Song, rejected: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val found by produceState<List<ForkMusicVideoMatch.Video>?>(null, song.videoId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ForkVideoCanvas.choices(song.title, song.artist, ForkMusicVideoMatch.lengthOf(song.durationText), rejected)
            }.getOrDefault(emptyList())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ForkCanvasChoices.VIDEO_TITLE) },
        text = {
            val list = found
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text(ForkCanvasChoices.NO_VIDEOS)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.id }) { v ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(v.id) }.padding(vertical = 10.dp)) {
                            Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(v.channel.takeIf { it.isNotBlank() }, v.lengthSec.takeIf { it > 0 }?.let { ForkBestParts.clock(it * 1000) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
