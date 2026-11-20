package com.music.bitchord.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.music.bitchord.ui.components.contextClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: Prism's "Best part" button by the scrubber, on the phone and the PC (both draw this player;
 * canvas.md §14).
 *
 * [ForkBestPartFollow] (from NowPlayingScreen) keeps the playing song's synced lyrics for the canvas
 * and the menus ([ForkBestParts.noteLyrics]) and works out the song's best part: the listener's own,
 * else the most replayed part (YouTube's curve), else the chorus in the lyrics, snapped to a lyric
 * line ([ForkBestParts.current]). With "Best part button" off (rule 8) the part is still worked out:
 * the seek bar's mark ([ForkBestPartMark]) and Your best part stay. It also draws "Your best part"
 * ([ForkBestPartEditor]) while it is open for the playing song.
 *
 * [forkBestPartChip] (from `PlayerScrubber`) draws the button in the centre label's place while the
 * part is ahead, in the song's first seconds ([ForkBestParts.shows]); a tap scrubs there as a drag
 * of the bar would, holding it (the PC: a right-click) opens Your best part (rule 3).
 */
@Composable
internal fun ForkBestPartFollow(song: Song, lyrics: List<LyricLine>?) {
    LaunchedEffect(song.videoId, lyrics) { ForkBestParts.noteLyrics(song.videoId, lyrics) }
    LaunchedEffect(song.videoId, lyrics != null) {
        val id = song.videoId
        if (ForkBestParts.current.value?.songId != id) ForkBestParts.setCurrent(null)
        if (!ForkClipGate.isSong(song) || com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(id)) {
            ForkBestParts.setCurrent(null)
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
 * The button, in [PlayerScrubber]'s centre ([shown]: the handle's fraction of [durationMs]). True
 * when drawn, so the caller leaves its own centre label out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.forkBestPartChip(shown: Float, durationMs: Long, onScrub: (Float) -> Unit, onScrubFinished: () -> Unit): Boolean {
    val on by ForkBestParts.button.collectAsStateWithLifecycle()
    val current by ForkBestParts.current.collectAsStateWithLifecycle()
    val part = current
    val start = part?.startMs
    if (!on || part == null || start == null || durationMs <= 0 || start >= durationMs) return false
    val atMs = (shown * durationMs).toLong()
    // Prism's window counts from when the part became known: here, where the song was then.
    val knownAt = remember(part.songId, start) { atMs }
    if (!ForkBestParts.shows(atMs, start, knownAt)) return false
    val color = LocalContentColor.current
    val edit = { ForkBestParts.openEditor(part.songId) }
    Text(
        text = (if (part.mine) ForkBestParts.CHIP_MINE else ForkBestParts.CHIP) + "  ›",
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .align(Alignment.Center)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .contextClick(edit)
            .combinedClickable(
                onClick = {
                    onScrub(start.toFloat() / durationMs)
                    onScrubFinished()
                },
                onLongClick = edit,
            )
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
    return true
}

/**
 * §14 rule 4: a mark under the seek bar at the playing song's best part, when it lies between 1% and
 * 99% of the song ([ForkBestParts.markAt]); shown whether or not the button is on (rule 8). Drawn
 * at the top of the scrubber's label row, in the times' white.
 */
@Composable
internal fun BoxScope.ForkBestPartMark(durationMs: Long) {
    val current by ForkBestParts.current.collectAsStateWithLifecycle()
    val at = ForkBestParts.markAt(current?.startMs, durationMs) ?: return
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
