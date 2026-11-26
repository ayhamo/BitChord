package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.ForkBestPart
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkClipGate
import com.music.bitchord.data.canvas.ForkMostReplayed
import com.music.bitchord.data.canvas.ForkMusicVideoMatch
import com.music.bitchord.data.canvas.ForkVideoCanvas
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: the playing song's best part, on the phone and the PC (both draw this player; canvas.md §14).
 *
 * [ForkBestPartFollow] (from NowPlayingScreen) keeps the playing song's synced lyrics for the canvas
 * and the menus ([ForkBestParts.noteLyrics]) and works out the song's best part: the listener's own,
 * else the most replayed part (YouTube's curve), else the chorus in the lyrics, snapped to a lyric
 * line ([ForkBestParts.current]). v1.10's own Best Part pill on the artwork shows it (both hosts'
 * `bestPartMs` read [ForkBestParts.awaitStart]); the fork's button by the scrubber gave way to it
 * (owner, 2026-11-24: upstream's version wins where it does the same). With the pill off the part is
 * still worked out: the seek bar's mark ([ForkBestPartMark]) and Your best part stay. It also draws
 * "Your best part" ([ForkBestPartEditor]) while it is open for the playing song (the song menu's row).
 */
@Composable
internal fun ForkBestPartFollow(song: Song, lyrics: List<LyricLine>?) {
    LaunchedEffect(song.videoId, lyrics) { ForkBestParts.noteLyrics(song.videoId, lyrics) }
    // Keyed on synced lyrics being in, not on any lyrics: an unsynced sheet replaced by a synced one
    // kept `lyrics != null` true and the chorus was never looked for (audit 2026-11-24). The lyrics
    // are noted here as well, as the effect above is a separate coroutine and needn't run first.
    val synced = ForkBestParts.isSynced(lyrics)
    LaunchedEffect(song.videoId, synced) {
        val id = song.videoId
        ForkBestParts.noteLyrics(id, lyrics)
        if (ForkBestParts.current.value?.songId != id) ForkBestParts.setCurrent(null)
        if (!ForkClipGate.isSong(song) || com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(id)) {
            ForkBestParts.setCurrent(ForkBestParts.Current(id, null, null, 0L, false)) // none, so the pill stops waiting
            return@LaunchedEffect
        }
        val durationMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
        val auto = withContext(Dispatchers.IO) {
            runCatching {
                val peak = if (ForkMostReplayed.eligible(id)) ForkBestPart.partStart(ForkVideoCanvas.heatmap(id), 0) else -1L
                ForkBestParts.start(null, peak, ForkBestParts.lines(id), durationMs)
            }.getOrNull()
        }
        val mine = ForkBestParts.mine(id)
        ForkBestParts.setCurrent(ForkBestParts.Current(id, mine ?: auto, auto, 0L, mine != null))
    }
    val editing by ForkBestParts.editing.collectAsStateWithLifecycle()
    if (editing == song.videoId) {
        ForkBestPartEditor(song.videoId, ForkMusicVideoMatch.lengthOf(song.durationText) * 1000) { ForkBestParts.closeEditor() }
    }
}

/**
 * §14 rule 4: a mark under the seek bar at the playing song's best part, when it lies between 1% and
 * 99% of the song ([ForkBestParts.markAt]); shown whether or not the button is on (rule 8). Drawn
 * at the top of the scrubber's label row, in the times' white.
 */
@Composable
internal fun BoxScope.ForkBestPartMark(durationMs: Long, songId: String?) {
    val current by ForkBestParts.current.collectAsStateWithLifecycle()
    val at = ForkBestParts.markFor(current, songId, durationMs) ?: return // only the playing song's part
    BoxWithConstraints(Modifier.fillMaxWidth().align(Alignment.TopStart)) {
        val dot = 4.dp
        Box(
            Modifier
                .offset(x = maxWidth * at - dot / 2)
                .size(dot)
                .clip(CircleShape)
                .background(chromeWhite(0.55f)),
        )
    }
}
