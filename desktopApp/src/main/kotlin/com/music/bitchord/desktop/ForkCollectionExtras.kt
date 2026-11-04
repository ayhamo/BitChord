package com.music.bitchord.desktop

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fork: two things a YouTube album or playlist page tells the phone that the PC's page dropped,
 * carried through [DesktopCollection.forkSuggested] and [DesktopCollection.forkDescription] from the
 * same shared `YtMusicRepository.SongPage` the phone reads (`MainViewModel.openDetail`).
 *
 * - "Suggested" (`DetailScreen`'s `suggestedSongs` section): the tracks YouTube offers to round out
 *   a playlist the account owns. Shown under the playlist's own rows and never folded into them,
 *   where they would read as the listener's own picks. A row plays the suggested list from itself;
 *   its + adds it to the playlist, which takes it out of this section and puts it at the end of the
 *   list above (the phone's `addSuggestedSong`: both halves, since either alone reads as the track
 *   having been thrown away or as YouTube still suggesting what is already there).
 * - "About the album" (`DetailScreen`'s `aboutItem`, `AboutSection`): YouTube's editorial blurb, on
 *   an album only (a playlist's response rarely carries one and the phone skips it even then),
 *   between the header and the songs, two lines with "More" when it runs longer.
 */
internal object ForkCollectionExtras {

    /**
     * A later page of a playlist, with its suggestions: they can arrive on a later page than the
     * real tracks, once the playlist's own continuation runs dry (`InnertubeParser.parsePlaylistShelf`),
     * so the window's "load more" reads them here rather than through
     * `DesktopSearchClient.moreCollectionSongs`, which keeps only the rows and the token.
     */
    suspend fun morePage(token: String): Result<Triple<List<Song>, String?, List<Song>>> =
        YtMusicRepository.moreSongs(token).map { Triple(it.songs, it.continuation, it.suggested) }

    /**
     * Adds one suggested track to the playlist it was suggested for, as the phone's
     * `addSuggestedSong`: on success it leaves the Suggested section and joins the end of the list,
     * with the per-entry id YouTube filed it under, so "Remove from playlist" works on it at once.
     * [update] is the window's way of changing the page it has open; the page may have been left
     * meanwhile, which the window's update checks by id. A guest is refused nothing here because
     * YouTube only suggests tracks to the playlist's signed-in owner; a refusal is logged and said.
     */
    fun addSuggested(
        scope: CoroutineScope,
        collection: DesktopCollection,
        song: Song,
        update: ((DesktopCollection) -> DesktopCollection) -> Unit,
    ) {
        scope.launch {
            YtMusicRepository.addToPlaylist(collection.playlistId, listOf(song.videoId)).fold(
                onSuccess = { added ->
                    update { page -> page.withSuggestionAdded(song, added[song.videoId]) }
                },
                onFailure = {
                    DesktopTrackLog.log("playlist: could not add suggested ${song.videoId}: ${it.message}")
                    ForkNotice.show(it.message ?: "Could not add to ${collection.title}")
                },
            )
        }
    }
}

/**
 * Suggestions from a later page merged in, the phone's `fillIn`: only tracks that are neither in
 * the playlist's rows nor already suggested, in the order they came.
 */
internal fun DesktopCollection.forkWithSuggested(more: List<Song>): DesktopCollection {
    if (more.isEmpty()) return this
    val inList = songs.mapTo(HashSet()) { it.videoId }
    val known = forkSuggested.mapTo(HashSet()) { it.videoId }
    val added = more.filter { it.videoId !in inList && known.add(it.videoId) }
    return if (added.isEmpty()) this else copy(forkSuggested = forkSuggested + added)
}

/**
 * The page after a suggestion was added: out of Suggested, onto the end of the rows unless a row
 * for it is already there (a double click is not two entries anybody asked for), the phone's
 * `appendToOpenPlaylist`. A row with no cover takes the page's.
 */
internal fun DesktopCollection.withSuggestionAdded(song: Song, setVideoId: String?): DesktopCollection {
    val rest = forkSuggested.filterNot { it.videoId == song.videoId }
    if (songs.any { it.videoId == song.videoId }) return copy(forkSuggested = rest)
    val row = song.copy(setVideoId = setVideoId, thumbnailUrl = song.thumbnailUrl ?: thumbnailUrl)
    return copy(songs = songs + row, forkSuggested = rest)
}

/**
 * "About the album" between the header and the songs, as the phone places it on a release page.
 * Album only, and only when YouTube wrote something.
 */
internal fun LazyListScope.forkAboutAlbum(collection: DesktopCollection) {
    if (collection.type != BrowseType.ALBUM) return
    val text = collection.forkDescription?.takeIf(String::isNotBlank) ?: return
    item(key = "fork-about-album") {
        ForkAboutText(DesktopStrings["about_album", "About the album"], text, Modifier.padding(bottom = 22.dp))
    }
}

/**
 * The phone's `AboutSection`: the heading, the text held to two lines, and "More" / "Less" under it
 * only when the two lines actually cut it short; the text itself toggles too.
 */
@Composable
internal fun ForkAboutText(title: String, text: String, modifier: Modifier = Modifier) {
    var expanded by remember(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(6.dp))
        Text(
            text,
            color = DesktopSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) clipped = result.hasVisualOverflow },
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .let { m -> if (clipped || expanded) m.clickable { expanded = !expanded } else m },
        )
        if (clipped || expanded) {
            Text(
                if (expanded) DesktopStrings["less", "Less"] else DesktopStrings["more", "More"],
                color = DesktopAccent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 4.dp).clickable { expanded = !expanded },
            )
        }
    }
}

/**
 * The "Suggested" section under a playlist's rows. [query] is the page's "search this list", which
 * filters these as it does the rows above (the phone's `suggestedSongs.matching(query)`).
 * [isCurrent] marks the one being played, matched against this list alone.
 */
internal fun LazyListScope.forkSuggestedSongs(
    collection: DesktopCollection,
    query: String,
    isCurrent: (Song) -> Boolean,
    onPlay: (List<Song>, Int) -> Unit,
    onAdd: (Song) -> Unit,
) {
    val needle = query.trim()
    // Unique by track: a row's key is its id, and YouTube's shelf has been seen to repeat one.
    val shown = collection.forkSuggested.distinctBy { it.videoId }.filter { song ->
        needle.isBlank() || song.title.contains(needle, ignoreCase = true) ||
            song.artist.contains(needle, ignoreCase = true) ||
            song.albumName.orEmpty().contains(needle, ignoreCase = true)
    }
    if (shown.isEmpty()) return
    item(key = "fork-suggested-heading") {
        Text(
            DesktopStrings["suggested", "Suggested"],
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
        )
    }
    itemsIndexed(shown, key = { _, song -> "fork-suggested-${song.videoId}" }) { index, song ->
        ForkSuggestedRow(song, isCurrent(song), onClick = { onPlay(shown, index) }, onAdd = { onAdd(song) })
    }
}

/** The phone's `SuggestedSongRow` in the PC's row style: cover, title and artist, and the + at the end. */
@Composable
private fun ForkSuggestedRow(song: Song, current: Boolean, onClick: () -> Unit, onAdd: () -> Unit) {
    val openContextMenu = LocalDesktopContextMenu.current
    Row(
        Modifier
            .fillMaxWidth()
            .contextClick { openContextMenu(song) }
            .clip(RoundedCornerShape(6.dp))
            .desktopHoverFill(corner = 6.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopArtwork(song.thumbnailUrl, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), px = 120)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = if (current) Color.White else Color.White.copy(alpha = 0.75f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(song.artist, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onAdd,
            modifier = Modifier.size(34.dp).clip(CircleShape).background(DesktopAccent.copy(alpha = 0.16f)),
        ) {
            Icon(BitChordIcons.Plus, DesktopStrings["add_to_playlist", "Add to playlist"], tint = DesktopAccent, modifier = Modifier.size(18.dp))
        }
    }
}
