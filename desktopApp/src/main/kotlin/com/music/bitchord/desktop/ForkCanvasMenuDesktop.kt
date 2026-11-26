package com.music.bitchord.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkCanvasChoices
import com.music.bitchord.data.canvas.ForkCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasSettings
import com.music.bitchord.data.canvas.ForkClipGate
import com.music.bitchord.data.canvas.ForkMusicVideoMatch
import com.music.bitchord.data.canvas.ForkVideoCanvas
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.ForkCanvasSwitch
import com.music.bitchord.ui.player.ForkVideoChoiceRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork: one song's canvas in the PC player's song menu (canvas.md §15), as on the phone
 * (`ForkCanvasMenuRows`), in the menu's own
 * rows with the lists opening under their row:
 *
 * - a switch per source for this song ("Music video canvas", "Spotify canvas", "Animated cover");
 * - "Pick another part" (the part playing and up to 4 others), "Pick another video" (the search as
 *   found, with pictures; a pick that can't make a loop leaves the song as it was), "Use",
 *   "Not this video", "Reset this song's canvas" (§15 rule 10, while the song keeps any choice);
 * - "Your best part" (§14 rule 5): opens the editor the player draws.
 */
@Composable
internal fun ForkCanvasMenuRowsDesktop(song: Song, onDismiss: () -> Unit) {
    if (!ForkClipGate.isSong(song)) return
    val generation by ForkCanvasSettings.generation.collectAsState()
    val backdrops by ForkCanvasSwitch.backdrops.collectAsState()
    val covers by ForkCanvasSwitch.covers.collectAsState()
    val videos by ForkCanvasSwitch.videos.collectAsState()
    val spotifyFirst by ForkSpotifyCanvasFirst.prioritized.collectAsState() // the player's own order (audit 2026-11-26)
    val choice = remember(song.videoId, generation) { ForkCanvasChoices.of(song.videoId) }
    val note = remember(song.videoId, generation) { ForkCanvasChoices.noteOf(song.videoId) }
    val rows = remember(choice, note, backdrops, covers, videos, spotifyFirst) {
        ForkCanvasChoices.rows(choice, note, isSong = true, canvasOn = backdrops,
            order = ForkCanvasSettings.menuOrder(backdrops, covers, videos, spotifyFirst))
    }
    var open by remember(song.videoId) { mutableStateOf<String?>(null) }
    fun flip(list: String) { open = if (open == list) null else list }

    @Composable
    fun switchRow(source: ForkCanvasRules.Source, shown: Boolean, checked: Boolean) {
        if (!shown) return
        DesktopMenuRow(
            if (source == ForkCanvasRules.Source.APPLE_MUSIC) Icons.Rounded.Animation else Icons.Rounded.Wallpaper,
            ForkCanvasChoices.switchTitle(source),
            value = if (checked) "On" else "Off",
        ) { ForkCanvasChoices.toggle(song.videoId, source, rows) }
    }
    switchRow(ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO, rows.loopSwitch, rows.loopChecked)
    switchRow(ForkCanvasRules.Source.SPOTIFY, rows.spotifySwitch, rows.spotifyChecked)
    switchRow(ForkCanvasRules.Source.APPLE_MUSIC, rows.coverSwitch, rows.coverChecked)

    if (rows.pickPart) {
        DesktopMenuRow(Icons.Rounded.ContentCut, ForkCanvasChoices.PART_TITLE, selected = open == "part") { flip("part") }
        val start = note.startMs
        if (open == "part" && start != null) {
            (listOf(start) + note.others.take(ForkCanvasChoices.MAX_OTHER_PARTS)).forEach { ms ->
                DesktopMenuRow(
                    Icons.Rounded.PlayCircle,
                    ForkBestParts.clock(ms),
                    value = if (ms == start) ForkCanvasChoices.PART_PLAYING else "",
                    selected = ms == start,
                ) {
                    ForkCanvasChoices.pickPart(song.videoId, ms)
                    onDismiss()
                }
            }
        }
    }
    if (rows.pickVideo) {
        DesktopMenuRow(Icons.Rounded.VideoLibrary, ForkCanvasChoices.VIDEO_TITLE, selected = open == "video") { flip("video") }
        if (open == "video") {
            val found by produceState<List<ForkMusicVideoMatch.Video>?>(null, song.videoId) {
                value = withContext(Dispatchers.IO) {
                    runCatching { ForkVideoCanvas.choices(song.title, song.artist, choice.rejected) }.getOrDefault(emptyList())
                }
            }
            val list = found
            when {
                list == null -> DesktopMenuRow(Icons.Rounded.VideoLibrary, "Looking for videos", busy = true, enabled = false) {}
                list.isEmpty() -> DesktopMenuRow(Icons.Rounded.VideoLibrary, ForkCanvasChoices.NO_VIDEOS, enabled = false) {}
                else -> list.forEach { v ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp)) {
                        ForkVideoChoiceRow(v, inUse = v.id == note.video, accent = MaterialTheme.colorScheme.primary) {
                            onDismiss()
                            if (v.id == note.video) return@ForkVideoChoiceRow
                            ForkNotice.show(ForkCanvasChoices.TRYING)
                            pickScope.launch {
                                if (!ForkDesktopCanvasSources.tryPick(song, v.id)) ForkNotice.show(ForkCanvasChoices.CANT_LOOP)
                            }
                        }
                    }
                }
            }
        }
    }
    if (rows.use) {
        DesktopMenuRow(Icons.Rounded.SwapHoriz, ForkCanvasChoices.USE_TITLE, value = ForkCanvasChoices.useName(rows.showing), selected = open == "use") { flip("use") }
        if (open == "use") {
            (rows.useOptions + listOf<ForkCanvasRules.Source?>(null)).forEach { source ->
                DesktopMenuRow(Icons.Rounded.PlayCircle, ForkCanvasChoices.useName(source), selected = source != null && source == rows.showing) {
                    ForkCanvasChoices.setUse(song.videoId, source)
                    open = null
                }
            }
        }
    }
    if (rows.notThisVideo) {
        DesktopMenuRow(Icons.Rounded.Block, ForkCanvasChoices.NOT_THIS_TITLE) {
            ForkDesktopCanvasSources.rejectVideo(song)
            onDismiss()
        }
    }
    if (ForkCanvasChoices.hasChoice(choice)) {
        DesktopMenuRow(Icons.Rounded.RestartAlt, ForkCanvasChoices.RESET_TITLE) {
            ForkCanvasChoices.reset(song.videoId)
            onDismiss()
        }
    }

    // Your best part (§14 rule 5): the editor, drawn by the player.
    val mine = remember(song.videoId, generation) { ForkBestParts.mine(song.videoId) }
    DesktopMenuRow(Icons.Rounded.Whatshot, ForkBestParts.EDITOR_TITLE, value = mine?.let(ForkBestParts::clock).orEmpty()) {
        ForkBestParts.openEditor(song.videoId)
        onDismiss()
    }
}

/** Where a picked video is cut: outlives the menu, which closes on the click. */
private val pickScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** "Play from the best part", in any song's menu beside "Play next" (Prism's row). */
@Composable
internal fun ForkPlayFromBestPartRowDesktop(song: Song, onDismiss: () -> Unit) {
    if (!ForkClipGate.isSong(song) || com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) return
    DesktopMenuRow(Icons.Rounded.Star, ForkBestParts.PLAY_FROM) {
        onDismiss()
        ForkMostReplayedDesktop.playFromBestPart(song)
    }
}
