package com.music.bitchord.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeArtistInfo
import kotlinx.coroutines.launch

/**
 * Fork: a Navidrome artist's page on the desktop, as on the phone. "Start radio" beside Shuffle and
 * Play plays the server's similar songs to the artist ([NavidromeArtistInfo.radio]), and under the
 * songs a "Similar artists" row opens each one's Navidrome page. Neither shows on any other page.
 */
@Composable
internal fun ForkArtistRadioButton(browseId: String, onPlay: (List<Song>) -> Unit) {
    val name = NavidromeArtistInfo.nameOf(browseId) ?: return
    val offline by ForkOffline.offline.collectAsState()
    if (offline) return // the server's songs wouldn't play
    val scope = rememberCoroutineScope()
    DesktopCircleButton(Icons.Rounded.Radio, DesktopStrings["start_radio", "Start radio"], onClick = {
        scope.launch {
            NavidromeArtistInfo.radio(name)
                .onSuccess { songs ->
                    if (songs.isEmpty()) ForkNotice.show("No similar songs found") else onPlay(songs)
                    DesktopTrackLog.log("radio: ${songs.size} similar songs for the artist '$name'")
                }
                .onFailure { ForkNotice.show("Couldn't load tracks") }
        }
    })
}

/** The page's "Similar artists" row, once the server has answered; null for any other page. */
@Composable
internal fun rememberForkSimilarArtists(browseId: String): HomeShelf? {
    if (NavidromeArtistInfo.nameOf(browseId) == null) return null
    return produceState<HomeShelf?>(null, browseId) {
        value = null // not the previous artist's row while this one's is asked for
        value = NavidromeArtistInfo.similarShelf(browseId)
    }.value
}

internal fun LazyListScope.forkSimilarArtists(shelf: HomeShelf?, onOpen: (String, String) -> Unit) {
    shelf ?: return
    item(key = "fork-similar-artists") {
        Column(Modifier.padding(top = 32.dp)) {
            Text(shelf.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(shelf.items, key = { it.browseId ?: it.title }) { item ->
                    Column(
                        Modifier
                            .width(150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { item.browseId?.let { onOpen(it, item.title) } },
                    ) {
                        DesktopArtwork(item.thumbnailUrl, Modifier.size(150.dp).clip(RoundedCornerShape(75.dp)), px = 300)
                        Spacer(Modifier.height(8.dp))
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
