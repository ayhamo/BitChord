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
