package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.music.bitchord.data.model.ForkHomeChips
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.navigation.AppRoute

/*
 * Fork: the phone's side of YouTube Music's Home drawn 1:1 (ForkHomeFeedUi.kt in sharedUi). One hook
 * line in MainActivity passes these to HomeScreen.
 */

/** Explore's "Moods & genres" button: the Explore tab is that page already. */
private const val MOODS_AND_GENRES = "FEmusic_moods_and_genres"

/**
 * HomeScreen's `forkFeed` on the phone.
 *
 * - A chip toggles ([ForkHomeChips.toggle]) and Home reloads with its params, from the top.
 * - Moods & genres opens the Explore tab; the other Explore buttons (New releases, Charts) open their
 *   page as a See all page does ([AppRoute.HomeShelfMore], which reads grids too).
 * - Play all plays the shelf's songs from the first, else opens the playlist YouTube's button names.
 * - An artist heading opens the artist.
 */
@Composable
internal fun rememberForkHomeFeedActions(
    viewModel: MainViewModel,
    shelfSong: (ShelfItem) -> Song?,
    playFrom: (List<Song>, Int, QueueSource) -> Unit,
    openExplore: () -> Unit,
    toTop: () -> Unit,
): ForkHomeFeedActions {
    val songOf by rememberUpdatedState(shelfSong)
    val play by rememberUpdatedState(playFrom)
    val explore by rememberUpdatedState(openExplore)
    val top by rememberUpdatedState(toTop)
    return remember(viewModel) {
        ForkHomeFeedActions(
            onChip = { chip ->
                ForkHomeChips.toggle(chip)
                viewModel.loadHome()
                top()
            },
            onButton = { button ->
                if (button.browseId == MOODS_AND_GENRES) {
                    explore()
                } else {
                    viewModel.navigator.push(
                        AppRoute.HomeShelfMore(HomeShelf(button.title, emptyList(), moreBrowseId = button.browseId, moreParams = button.params)),
                    )
                }
            },
            onPlayAll = { shelf ->
                val songs = shelf.items.mapNotNull(songOf)
                val playlist = shelf.fork?.playAll?.playlistId
                when {
                    songs.isNotEmpty() -> play(songs, 0, QueueSource(shelf.title, PlaybackSourceType.HOME))
                    playlist != null -> viewModel.openDetail(browseId = "VL" + playlist.removePrefix("VL"), title = shelf.title)
                }
            },
            onHeaderOpen = { browseId, title -> viewModel.openDetail(browseId = browseId, title = title) },
        )
    }
}
