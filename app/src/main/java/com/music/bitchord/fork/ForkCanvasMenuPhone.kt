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
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkCanvasChoices
import com.music.bitchord.data.canvas.ForkCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasSettings
import com.music.bitchord.data.canvas.ForkClipGate
import com.music.bitchord.data.canvas.ForkMusicVideoMatch
import com.music.bitchord.data.canvas.ForkVideoCanvas
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.ActionRow
import com.music.bitchord.ui.player.ForkCanvasSwitch
import com.music.bitchord.ui.player.ForkVideoChoiceRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork: one song's canvas in the phone player's song menu (canvas.md §15, Prism's `PlayerOptions`
 * and `CanvasSongSheets`; drawn by `ForkPlayerMenuRows`), in the menu's own rows:
 *
 * - a switch per source for this song: "Music video canvas", "Spotify canvas", "Animated cover",
 *   each while its source is on and the song has it (or it was switched off for it); with both
 *   loops there only the one showing reads On, and turning the other on makes it the song's;
 * - "Pick another part": the part playing and up to 4 others, by their time in the video;
 * - "Pick another video": YouTube's search for the song as found (at most 10, none turned away),
 *   with their pictures; a pick is cut first, and one that can't make a loop leaves the song as it
 *   was ("That video can't make a loop");
 * - "Use": which source a song with two shows; "Not this video";
 * - "Reset this song's canvas" (§15 rule 10), while the song keeps any choice: forgets them all;
 * - "Your best part" (§14 rule 5): opens the editor the player draws (`ForkBestPartEditor`).
 *
 * The rows are worked out in [ForkCanvasChoices.rows]; each change is a new question for the player
 * at once (`ForkCanvasSettings.changed`).
 */
@Composable
internal fun ForkCanvasMenuRows(song: Song, accent: Color, onDone: () -> Unit) {
    if (!ForkClipGate.isSong(song)) return
    val context = LocalContext.current
    // Re-read on each change of the choices or settings (their generation moves with every change).
    val generation by ForkCanvasSettings.generation.collectAsStateWithLifecycle()
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    val videos by ForkCanvasSwitch.videos.collectAsStateWithLifecycle()
    val spotifyFirst by com.music.bitchord.data.settings.AppSettings.effectivePrioritizeSpotifyCanvas.collectAsStateWithLifecycle() // the player's own order (audit 2026-11-26)
    val choice = remember(song.videoId, generation) { ForkCanvasChoices.of(song.videoId) }
    val note = remember(song.videoId, generation) { ForkCanvasChoices.noteOf(song.videoId) }
    val rows = remember(choice, note, backdrops, covers, videos, spotifyFirst) {
        ForkCanvasChoices.rows(choice, note, isSong = true, canvasOn = backdrops,
            order = ForkCanvasSettings.menuOrder(backdrops, covers, videos, spotifyFirst))
    }
    var sheet by remember { mutableStateOf<String?>(null) }

    @Composable
    fun switchRow(source: ForkCanvasRules.Source, shown: Boolean, checked: Boolean) {
        if (!shown) return
        ActionRow(
            icon = if (source == ForkCanvasRules.Source.APPLE_MUSIC) Icons.Rounded.Animation else Icons.Rounded.Wallpaper,
            label = ForkCanvasChoices.switchTitle(source),
            value = if (checked) "On" else "Off",
            accent = accent,
        ) { ForkCanvasChoices.toggle(song.videoId, source, rows) }
    }
    switchRow(ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO, rows.loopSwitch, rows.loopChecked)
    switchRow(ForkCanvasRules.Source.SPOTIFY, rows.spotifySwitch, rows.spotifyChecked)
    switchRow(ForkCanvasRules.Source.APPLE_MUSIC, rows.coverSwitch, rows.coverChecked)
    if (rows.pickPart) {
        ActionRow(icon = Icons.Rounded.ContentCut, label = ForkCanvasChoices.PART_TITLE, value = null, accent = accent) { sheet = "part" }
    }
    if (rows.pickVideo) {
        ActionRow(icon = Icons.Rounded.VideoLibrary, label = ForkCanvasChoices.VIDEO_TITLE, value = null, accent = accent) { sheet = "video" }
    }
    if (rows.use) {
        ActionRow(icon = Icons.Rounded.SwapHoriz, label = ForkCanvasChoices.USE_TITLE, value = ForkCanvasChoices.useName(rows.showing), accent = accent) { sheet = "use" }
    }
    if (rows.notThisVideo) {
        ActionRow(icon = Icons.Rounded.Block, label = ForkCanvasChoices.NOT_THIS_TITLE, value = null, accent = accent) {
            ForkCanvasSources.rejectVideo(song)
            onDone()
        }
    }
    if (ForkCanvasChoices.hasChoice(choice)) {
        ActionRow(icon = Icons.Rounded.RestartAlt, label = ForkCanvasChoices.RESET_TITLE, value = null, accent = accent) {
            ForkCanvasChoices.reset(song.videoId)
            onDone()
        }
    }

    // Your best part (§14 rule 5): the editor, drawn by the player.
    val mine = remember(song.videoId, generation) { ForkBestParts.mine(song.videoId) }
    ActionRow(icon = Icons.Rounded.Whatshot, label = ForkBestParts.EDITOR_TITLE, value = mine?.let(ForkBestParts::clock), accent = accent) {
        ForkBestParts.openEditor(song.videoId)
        onDone()
    }

    when (sheet) {
        "part" -> ForkPickPartDialog(note, accent, onDismiss = { sheet = null }) { ms ->
            sheet = null
            ForkCanvasChoices.pickPart(song.videoId, ms)
            onDone()
        }
        "video" -> ForkPickVideoDialog(song, choice.rejected, note.video, accent, onDismiss = { sheet = null }) { id ->
            sheet = null
            if (id == note.video) return@ForkPickVideoDialog
            Toast.makeText(context, ForkCanvasChoices.TRYING, Toast.LENGTH_SHORT).show()
            val app = context.applicationContext
            pickScope.launch { // the menu closes at once; the cut goes on
                if (!ForkCanvasSources.tryPick(song, id)) Toast.makeText(app, ForkCanvasChoices.CANT_LOOP, Toast.LENGTH_SHORT).show()
            }
            onDone()
        }
        "use" -> ForkUseDialog(rows, onDismiss = { sheet = null }) { source ->
            sheet = null
            ForkCanvasChoices.setUse(song.videoId, source)
        }
    }
}

/** Where a picked video is cut: outlives the menu, which closes on the tap. */
private val pickScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** "Play from the best part", in any song's menu beside "Play next" (Prism's row). */
@Composable
internal fun ForkPlayFromBestPartRow(song: Song, accent: Color, onPlayNext: () -> Unit) {
    if (!ForkClipGate.isSong(song) || com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) return
    val context = LocalContext.current
    ActionRow(icon = Icons.Rounded.Star, label = ForkBestParts.PLAY_FROM, value = null, accent = accent) {
        ForkMostReplayedPhone.playFromBestPart(context, song.videoId, ForkMusicVideoMatch.lengthOf(song.durationText) * 1000, onPlayNext)
    }
}

/** The part playing (marked) and up to 4 others, by their time in the video (§15 rule 3). */
@Composable
private fun ForkPickPartDialog(note: ForkCanvasChoices.Note, accent: Color, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val start = note.startMs ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ForkCanvasChoices.PART_TITLE) },
        text = {
            Column {
                (listOf(start) + note.others.take(ForkCanvasChoices.MAX_OTHER_PARTS)).forEach { ms ->
                    val playing = ms == start
                    Text(
                        ForkBestParts.clock(ms) + if (playing) "  ·  ${ForkCanvasChoices.PART_PLAYING}" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (playing) FontWeight.SemiBold else null,
                        color = if (playing) accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(ms) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The song's videos as a search finds them (§15 rule 5), with their pictures; one tap picks the loop's video. */
@Composable
private fun ForkPickVideoDialog(song: Song, rejected: Set<String>, inUse: String?, accent: Color, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val found by produceState<List<ForkMusicVideoMatch.Video>?>(null, song.videoId) {
        value = withContext(Dispatchers.IO) {
            runCatching { ForkVideoCanvas.choices(song.title, song.artist, rejected) }.getOrDefault(emptyList())
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
                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(list, key = { it.id }) { v -> ForkVideoChoiceRow(v, inUse = v.id == inUse, accent = accent) { onPick(v.id) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Use (§15 rule 7): which of the song's sources it shows, or the settings' order. */
@Composable
private fun ForkUseDialog(rows: ForkCanvasChoices.Rows, onDismiss: () -> Unit, onPick: (ForkCanvasRules.Source?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ForkCanvasChoices.USE_TITLE) },
        text = {
            Column {
                (rows.useOptions + listOf<ForkCanvasRules.Source?>(null)).forEach { source ->
                    Text(
                        ForkCanvasChoices.useName(source),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (source != null && source == rows.showing) FontWeight.SemiBold else null,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(source) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
