package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkCanvasChoices
import com.music.bitchord.data.canvas.ForkCanvasSettings
import com.music.bitchord.data.canvas.ForkClipGate
import com.music.bitchord.data.canvas.ForkMostReplayed
import com.music.bitchord.data.canvas.ForkMusicVideoMatch
import com.music.bitchord.data.canvas.ForkVideoCanvas
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.ForkCanvasSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: Prism's per-song canvas menu and best-part rows in the PC player's song menu (after "Jump to
 * the most replayed part", [ForkMostReplayedRow]), as on the phone (`ForkCanvasMenuRows`):
 *
 * - "Canvas for this song" on/off ([ForkCanvasChoices.setOff]);
 * - for a music-video loop: "Pick another part", "Pick another video" (the song's other videos,
 *   listed under the row) and "Not this video";
 * - "Set your best part here" / "Clear your best part" ([ForkBestParts.setMine]).
 */
@Composable
internal fun ForkCanvasMenuRowsDesktop(song: Song, onDismiss: () -> Unit) {
    if (!ForkClipGate.isSong(song)) return
    val generation by ForkCanvasSettings.generation.collectAsState()
    val choice = remember(song.videoId, generation) { ForkCanvasChoices.of(song.videoId) }
    val backdrops by ForkCanvasSwitch.backdrops.collectAsState()
    val videos by ForkCanvasSwitch.videos.collectAsState()
    var picking by remember(song.videoId) { mutableStateOf(false) }

    DesktopMenuRow(Icons.Rounded.Wallpaper, ForkCanvasChoices.OFF_TITLE, value = if (choice.off) "Off" else "On") {
        ForkCanvasChoices.setOff(song.videoId, !choice.off)
    }
    if (!choice.off && choice.video != null) {
        DesktopMenuRow(Icons.Rounded.ContentCut, ForkCanvasChoices.PART_TITLE) {
            ForkCanvasChoices.nextPart(song.videoId)
            onDismiss()
        }
    }
    if (!choice.off && backdrops && videos && ForkMostReplayed.eligible(song.videoId)) {
        DesktopMenuRow(Icons.Rounded.VideoLibrary, ForkCanvasChoices.VIDEO_TITLE, selected = picking) { picking = !picking }
        if (picking) {
            val found by produceState<List<ForkMusicVideoMatch.Video>?>(null, song.videoId) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        ForkVideoCanvas.choices(song.title, song.artist, ForkMusicVideoMatch.lengthOf(song.durationText), choice.rejected)
                    }.getOrDefault(emptyList())
                }
            }
            val list = found
            when {
                list == null -> DesktopMenuRow(Icons.Rounded.VideoLibrary, "Looking for videos", busy = true, enabled = false) {}
                list.isEmpty() -> DesktopMenuRow(Icons.Rounded.VideoLibrary, ForkCanvasChoices.NO_VIDEOS, enabled = false) {}
                else -> list.forEach { v ->
                    DesktopMenuRow(
                        Icons.Rounded.PlayCircle,
                        v.title,
                        value = v.lengthSec.takeIf { it > 0 }?.let { ForkBestParts.clock(it * 1000) }.orEmpty(),
                        selected = v.id == choice.video,
                    ) {
                        ForkCanvasChoices.pick(song.videoId, v.id)
                        onDismiss()
                    }
                }
            }
        }
    }
    if (!choice.off && choice.video != null) {
        DesktopMenuRow(Icons.Rounded.Block, ForkCanvasChoices.NOT_THIS_TITLE) {
            ForkCanvasChoices.reject(song.videoId)
            onDismiss()
        }
    }

    var mineVersion by remember { mutableIntStateOf(0) }
    val mine = remember(song.videoId, mineVersion) { ForkBestParts.mine(song.videoId) }
    DesktopMenuRow(Icons.Rounded.Flag, ForkBestParts.SET_MINE) {
        ForkMostReplayedDesktop.positionOf(song.videoId)?.let { at ->
            ForkBestParts.setMine(song.videoId, at)
            mineVersion++
            ForkNotice.show("${ForkBestParts.CHIP_MINE}: ${ForkBestParts.clock(at)}")
        }
        onDismiss()
    }
    if (mine != null) {
        DesktopMenuRow(Icons.Rounded.StarOutline, ForkBestParts.CLEAR_MINE, value = ForkBestParts.clock(mine)) {
            ForkBestParts.setMine(song.videoId, null)
            mineVersion++
        }
    }
}

/** "Play from the best part", in any song's menu beside "Play next" (Prism's row). */
@Composable
internal fun ForkPlayFromBestPartRowDesktop(song: Song, onDismiss: () -> Unit) {
    if (!ForkClipGate.isSong(song) || com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) return
    DesktopMenuRow(Icons.Rounded.Star, ForkBestParts.PLAY_FROM) {
        onDismiss()
        ForkMostReplayedDesktop.playFromBestPart(song)
    }
}
