package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoRepository
import com.music.bitchord.data.compactCount
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRadio

/**
 * Fork: the phone's Song Info dialog (`SongInfoDialog`) on the PC, opened from the song menu.
 *
 * The same rows from the same [SongInfoRepository.load], so a Navidrome song shows the server's
 * release date, plays and rating (through `ForkSongInfo`, set by `NavidromeSongInfo.init()` in
 * `ForkNavidrome`) and a YouTube one its views, likes and comments.
 *
 * Not drawn by the menu itself: the menu is a popup and closes as soon as the row is pressed, so
 * the menu only records the song in [song] and [Host] — one hook line at the window's root —
 * draws the dialog. It is a popup of its own so it lands above the open player too, which the
 * window's in-tree overlays are drawn under.
 *
 * The phone's dialog stays as it is: its body is built from the app's Android resources and
 * alert pieces (`AlertRule`, `AlertAction`), which `sharedUi` does not have.
 */
internal object ForkSongInfoDialog {
    private var song by mutableStateOf<Song?>(null)

    fun open(song: Song) {
        this.song = song
    }

    @Composable
    fun Host() {
        val shown = song ?: return
        Popup(
            popupPositionProvider = WholeWindow,
            onDismissRequest = { song = null },
            properties = PopupProperties(focusable = true),
        ) {
            Box(Modifier.fillMaxSize()) {
                ForkSongInfoPanel(shown) { song = null }
            }
        }
    }

    /** The popup covers the window from its top-left corner, so the scrim covers it all. */
    private object WholeWindow : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
        ): IntOffset = IntOffset.Zero
    }
}

/** The menu row, after Open album / Open artist as on the phone. A station has no one song to describe. */
@Composable
internal fun ForkSongInfoRow(song: Song, onDismiss: () -> Unit) {
    if (NavidromeRadio.isRadio(song.videoId)) return
    DesktopMenuRow(Icons.Rounded.Info, DesktopStrings["song_info", "Song info"]) {
        ForkSongInfoDialog.open(song)
        onDismiss()
    }
}

@Composable
private fun ForkSongInfoPanel(song: Song, onDismiss: () -> Unit) {
    var retry by remember(song.videoId) { mutableIntStateOf(0) }
    val info by produceState<SongInfo?>(null, song, retry) {
        value = null
        // Offline the server isn't asked (NavidromeSongInfo asks getSong whatever the mode): the row's own album only.
        value = if (ForkNavidrome.owns(song) && ForkOffline.offline.value) SongInfo(album = song.albumName) else SongInfoRepository.load(song)
    }
    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 420, popup = true) {
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                DesktopStrings["song_info", "Song info"],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                song.title,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artist.isNotBlank()) {
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DesktopCardRule()
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            val unavailable = DesktopStrings["song_info_unavailable", "Not available"]
            // The phone's rows, in the phone's order. For a Navidrome song "views" is your plays
            // on the server and "likes" its rating or star.
            val rows = listOf(
                DesktopStrings["song_info_album", "Album"] to (info?.album ?: song.albumName),
                DesktopStrings["song_info_release_date", "Release date"] to (info?.releaseDate ?: info?.releaseYear),
                DesktopStrings["song_info_views", "Plays / views"] to info?.views?.let(::compactCount),
                DesktopStrings["song_info_likes", "Likes"] to info?.likes,
                DesktopStrings["song_info_comments", "Comments"] to info?.comments,
            )
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) DesktopCardRule(Modifier.padding(start = 22.dp))
                InfoRow(label, value ?: unavailable.takeIf { info != null })
            }
            if (info?.failed == true) {
                Text(
                    DesktopStrings["song_info_error", "Some information could not be loaded."],
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopDestructive,
                )
            }
        }
        DesktopCardRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info?.failed == true) {
                TextButton(onClick = { retry++ }) {
                    Text(DesktopStrings["song_info_retry", "Retry"], color = DesktopSecondary)
                }
            }
            TextButton(onClick = onDismiss) { Text(DesktopStrings["done", "Done"], color = DesktopAccent) }
        }
    }
}

/** Label on the left, value on the right; a small spinner stands in for the value while it loads. */
@Composable
private fun InfoRow(label: String, value: String?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DesktopSecondary)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = DesktopSecondary,
                    strokeWidth = 1.75.dp,
                )
            } else {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
