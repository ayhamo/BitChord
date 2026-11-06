package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.LIBRARY_GRID_SPACING
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.libraryGrid
import com.music.bitchord.ui.icons.BitChordIcons
import org.jetbrains.compose.resources.stringResource

/*
 * Fork: Home shelf "See all" pages (Desktop Revived c9b77d4f, 00343da5,
 * cf41b8fd, dcb31d8f), drawn with this app's own grid and cards rather than
 * Revived's page.
 *
 * YouTube Music opens a shelf's full list when its heading is tapped. The
 * parser now keeps that link ([HomeShelf.moreBrowseId], see the shared
 * ForkShelfMore); a heading with one gets a chevron and opens it, a heading
 * without one stays plain text. Where the link is an album, playlist or
 * artist, the app's existing page for it opens ([forkMoreOpensPage]); any
 * other feed (New albums & singles, New music videos, Mixed for you, ...) is
 * drawn by [ForkShelfMorePage] as a grid of the cards Library's Show all uses.
 *
 * The opener reaches the shelves through [LocalForkShelfOpen], set once by
 * HomeScreen, rather than through a parameter on every shelf composable, so
 * the Home file only gains hook lines; Explore and the other callers of
 * `Shelf` provide nothing and their headings stay as they were.
 */

/** HomeScreen's `onShelfOpen`, for the shelf headings under it. */
internal val LocalForkShelfOpen = compositionLocalOf<((HomeShelf) -> Unit)?> { null }

/**
 * What a tap on [shelf]'s heading does, or null when there is nothing more to see (or no one
 * listens). A shelf with YouTube's See all link opens that; one without (every shelf of a
 * signed-out Home, Navidrome's own shelves) opens its own cards as the grid, when it is a row of
 * cards long enough to scroll ([FORK_OWN_GRID_MIN] or more). Track lists (Quick picks, Listen
 * again) already page through all their songs in place, so they keep a plain heading (owner,
 * 2026-11-05).
 */
@Composable
internal fun forkShelfOpener(shelf: HomeShelf): (() -> Unit)? {
    val open = LocalForkShelfOpen.current ?: return null
    if (!shelf.forkHasMore()) return null
    return { open(shelf) }
}

/** Fewest cards a shelf without a link needs before its heading opens them as a grid. */
const val FORK_OWN_GRID_MIN = 6

/** Whether [forkShelfOpener] offers this shelf's See all: a link, or enough cards of its own. */
fun HomeShelf.forkHasMore(): Boolean =
    moreBrowseId != null || (layout != ShelfLayout.LIST && items.size >= FORK_OWN_GRID_MIN)

/** The chevron at the end of an openable heading. */
@Composable
internal fun RowScope.ForkShelfChevron(onOpen: (() -> Unit)?) {
    if (onOpen == null) return
    Icon(
        imageVector = BitChordIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp).size(20.dp),
    )
}

/**
 * Whether [HomeShelf.moreBrowseId] is a page the app already draws (an album,
 * a playlist or an artist), which the caller then opens as it would a card,
 * rather than a feed for [ForkShelfMorePage]. A country's Trending, for one,
 * links to its chart playlist.
 */
fun HomeShelf.forkMoreOpensPage(): Boolean {
    val id = moreBrowseId ?: return false
    return id.startsWith("VL") || id.startsWith("PL") || id.startsWith("OLAK") ||
        id.startsWith("MPRE") || id.startsWith("UC")
}

/**
 * [shelf]'s See all page as a grid. Loads [HomeShelf.moreBrowseId] itself; a
 * page that answers with nothing falls back to the shelf's own cards, so the
 * page is never emptier than the row that opened it.
 *
 * @param onItemClick a card and the shelf it came from, as Home's own callback.
 * @param title drawn as the page's heading; null where the app's bar already
 *   shows one (the phone).
 */
@Composable
fun ForkShelfMorePage(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    title: String? = null,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    var reloads by remember { mutableIntStateOf(0) }
    val failed = stringResource(Res.string.fork_shelf_more_failed, localizeShelfTitle(shelf.title))
    val state by produceState<UiState<List<HomeShelf>>>(UiState.Loading, shelf.moreBrowseId, shelf.moreParams, shelf.takeIf { it.moreBrowseId == null }, reloads) {
        value = UiState.Loading
        val browseId = shelf.moreBrowseId
        value = if (browseId == null) {
            UiState.Success(listOf(shelf))
        } else {
            YtMusicRepository.browseShelves(browseId, shelf.moreParams).fold(
                onSuccess = { found -> UiState.Success(found.filter { it.items.isNotEmpty() }.ifEmpty { listOf(shelf) }) },
                onFailure = { UiState.Error(it.message ?: failed) },
            )
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val grid = libraryGrid(maxWidth - PAGE_GUTTER * 2)
        // A video is about two covers wide: the row holds half as many, and a
        // cover spans [videosPerRow] slots so both kinds share one grid.
        val videosPerRow = (grid.columns / 2).coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns * videosPerRow),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        ) {
            if (title != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = localizeShelfTitle(title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            when (val current = state) {
                is UiState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                    MessageState(current.message, actionLabel = stringResource(Res.string.retry), onAction = { reloads++ })
                }
                is UiState.Success -> {
                    val shelves = current.data
                    shelves.forEach { section ->
                        // One feed is the page; several get their own headings.
                        if (shelves.size > 1 && section.title.isNotBlank()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = localizeShelfTitle(section.title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        val video = section.layout == ShelfLayout.VIDEO
                        items(section.items, span = { GridItemSpan(if (video) grid.columns else videosPerRow) }) { item ->
                            val longPress = onItemLongPress?.let { { it(item) } }
                            if (video) {
                                VideoShelfCard(
                                    item = item,
                                    onClick = { onItemClick(item, shelf.title) },
                                    onLongPress = longPress,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                ShelfCard(
                                    item = item,
                                    onClick = { onItemClick(item, shelf.title) },
                                    onLongPress = longPress,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
