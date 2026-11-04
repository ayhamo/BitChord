package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: the Navidrome server's albums and playlists as Library rows, under the
 * on-device row, signed in to YouTube or not. The app fills [shelves] (with
 * their titles) when it reads them for Home; empty rows draw nothing.
 */
object ForkLibraryPlaylists {
    val shelves = MutableStateFlow<List<HomeShelf>>(emptyList())

    /** "Available offline": the kept albums and playlists, there even with no server. */
    val offline = MutableStateFlow<HomeShelf?>(null)

    /** Offline mode (the switch, or no network): only the kept row, and no server genres on Explore. */
    val offlineMode = MutableStateFlow(false)
}

@Composable
internal fun ForkLibraryPlaylistsRow(
    onItemClick: (ShelfItem) -> Unit,
    onShowAll: (HomeShelf) -> Unit,
) {
    val shelves by ForkLibraryPlaylists.shelves.collectAsStateWithLifecycle()
    val offline by ForkLibraryPlaylists.offline.collectAsStateWithLifecycle()
    val offlineMode by ForkLibraryPlaylists.offlineMode.collectAsStateWithLifecycle()
    Column {
        (listOfNotNull(offline) + if (offlineMode) emptyList() else shelves).filter { it.items.isNotEmpty() }.forEach { shelf ->
            LibraryGridShelf(
                shelf = shelf,
                onItemClick = onItemClick,
                // The upstream menu is YouTube's (rename, delete on YouTube); a server album or playlist has none here.
                onItemLongPress = {},
                onShowAll = { onShowAll(shelf) },
            )
        }
    }
}

/**
 * Fork: the "Show all" grid's keys (`LibraryGridPage`), one per card and never repeated.
 *
 * Upstream keyed on `browseId ?: title`: a song shelf has no browse id and two tracks can share a
 * title, so keys repeated and the grid crashed (PC-Continued aaaaee54). That port keyed every card on
 * its index as well, which made every key new whenever a pin or a sort moved the cards, and the grid
 * lost each card's identity (and its scroll anchor) on exactly the reorders the page is built for.
 * Here a card keeps its plain id, and only a repeat of one gets `#n`, its count so far.
 */
internal fun forkShelfKeys(items: List<ShelfItem>): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val base = "card:${item.videoId ?: item.browseId ?: item.title}"
        val n = seen.merge(base, 1, Int::plus)!! - 1
        if (n == 0) base else "$base#$n"
    }
}

/**
 * Fork: the name a Library shelf is shown under. YouTube's own "Playlists" shelf reads "YouTube
 * playlists" (owner, 2026-11-04): with a Navidrome server the Library also has the server's
 * playlists, and two rows both called "Playlists" couldn't be told apart. Only the shown name
 * changes; the shelf's title stays YouTube's, since the code keys on it (the playlist shelf's
 * New playlist / Import Spotify cards, pinning, the Show all page).
 */
fun forkLibraryShelfTitle(title: String): String =
    if (title == com.music.bitchord.data.YtMusicRepository.PLAYLISTS_SHELF) "YouTube playlists" else title
