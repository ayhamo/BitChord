package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.navigation.AppRoute

/*
 * Fork: MainActivity's side of a Home shelf's See all (the shared ForkShelfMore.kt draws the page
 * and the headings). Kept here so MainActivity carries one hook line for each: HomeScreen's
 * `onShelfOpen` and the `AppRoute.HomeShelfMore` page branch.
 */

/**
 * HomeScreen's `onShelfOpen`: a heading's See all opens the album / playlist / artist page itself
 * ([forkMoreOpensPage]), else the grid ([AppRoute.HomeShelfMore]).
 */
internal fun forkHomeShelfOpener(viewModel: MainViewModel): (HomeShelf) -> Unit = { shelf ->
    val more = shelf.moreBrowseId
    if (more != null && shelf.forkMoreOpensPage()) viewModel.openDetail(browseId = more, title = shelf.title)
    else viewModel.navigator.push(AppRoute.HomeShelfMore(shelf))
}

/** The [AppRoute.HomeShelfMore] page: [ForkShelfMorePage], its taps as on Home (a song plays its radio, else the page opens). */
@Composable
internal fun ForkHomeShelfMoreRoute(
    route: AppRoute.HomeShelfMore,
    viewModel: MainViewModel,
    shelfSong: (ShelfItem) -> Song?,
    playRadio: (Song, QueueSource) -> Unit,
    onShelfLongPress: (ShelfItem) -> Unit,
    contentPadding: PaddingValues,
) {
    ForkShelfMorePage(
        shelf = route.shelf,
        onItemClick = { item, shelfTitle ->
            val song = shelfSong(item)
            val browseId = item.browseId
            when {
                song != null -> playRadio(song, QueueSource(shelfTitle, PlaybackSourceType.HOME))
                browseId != null -> viewModel.openDetail(browseId = browseId, title = item.title, subtitle = item.subtitle, thumbnailUrl = item.thumbnailUrl)
            }
        },
        onItemLongPress = onShelfLongPress,
        contentPadding = contentPadding,
    )
}
