package com.music.bitchord.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.ForkHomeChips
import com.music.bitchord.data.model.ForkNavButton
import com.music.bitchord.data.model.ForkShelfKind
import com.music.bitchord.data.model.HomeChip
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.ShelfRow
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.trackColumnWidth
import com.music.bitchord.ui.icons.BitChordIcons

/*
 * Fork: YouTube Music's Home drawn 1:1 (owner, 2026-11-11). Every shelf the fork's reader
 * (ForkHomeFeedParser) recorded a layout for is drawn here the way YouTube draws that kind: the mood
 * chips over the page, Speed dial as a paged grid, Quick picks / Trending / Long listens as track
 * lists, daily discover as large cards, podcast episodes as rows with their descriptions, a "More
 * from …" heading with its artist's picture and Play all, and Explore's buttons. HomeScreen carries
 * hook lines only: the chips item, the callbacks below, and one branch per shelf.
 */

/** What Home's YouTube shelves can ask of the app beyond a card tap. Null leaves Home as upstream drew it. */
class ForkHomeFeedActions(
    /** A chip was tapped: the app toggles it ([ForkHomeChips.toggle]) and reloads Home. */
    val onChip: (HomeChip) -> Unit,
    /** One of Explore's buttons (New releases, Charts, Moods & genres). */
    val onButton: (ForkNavButton) -> Unit,
    /** A heading's Play all: the shelf's songs from the first. */
    val onPlayAll: (HomeShelf) -> Unit,
    /** A heading's picture or title that links to a page (an artist's "More from …"). */
    val onHeaderOpen: (browseId: String, title: String) -> Unit,
)

internal val LocalForkHomeFeed = compositionLocalOf<ForkHomeFeedActions?> { null }

/** Whether a chip is picked, which hides the fork's own rows as YouTube's chip pages have none. */
@Composable
internal fun forkHomeChipPicked(): Boolean {
    val picked by ForkHomeChips.selected.collectAsState()
    return picked != null
}

/** The chips row, over everything else on Home, as on YouTube Music. */
internal fun LazyListScope.forkHomeChips(actions: ForkHomeFeedActions?) {
    if (actions == null) return
    item(key = "fork:home:chips") {
        val chips by ForkHomeChips.chips.collectAsState()
        val picked by ForkHomeChips.selected.collectAsState()
        // Not zero-sized while the chips are on their way: a list keeps its first item with a size
        // in place, so a 0 dp item here let Top Picks (arriving first) hold the top and the chips
        // came in above the view, unseen until scrolled up (PC, 2026-11-12). 1 dp keeps this item
        // the anchor, so the row shows where it belongs once it arrives.
        if (chips.isEmpty()) {
            Spacer(Modifier.height(1.dp))
            return@item
        }
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        ) {
            items(chips, key = { it.title }) { chip ->
                val selected = picked?.title == chip.title
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { actions.onChip(chip) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = chip.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * [shelf] drawn the way YouTube lays it out ([HomeShelf.fork]). HomeScreen calls it only for a shelf
 * that carries one; the rest (Navidrome's) it draws as upstream.
 */
@Composable
internal fun ForkHomeShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    val style = shelf.fork ?: return
    Column(Modifier.padding(bottom = 26.dp)) {
        ForkShelfHeading(shelf)
        val playing: (ShelfItem) -> Boolean = { item -> currentSong != null && item.videoId == currentSong.videoId }
        when (style.kind) {
            ForkShelfKind.LIST -> ListPager(shelf, style.rows, onItemClick, onItemLongPress, playing, isPlaying)
            ForkShelfKind.EPISODES -> EpisodePager(shelf, onItemClick, onItemLongPress, playing, isPlaying)
            ForkShelfKind.GRID -> GridPager(shelf, style.rows, onItemClick, onItemLongPress, playing, isPlaying)
            ForkShelfKind.BUTTONS -> ButtonRow(style.buttons.ifEmpty { shelf.items.map { ForkNavButton(it.title, it.browseId.orEmpty()) } })
            ForkShelfKind.CARD -> CardShelf(shelf, onItemClick, onItemLongPress, playing, isPlaying)
            ForkShelfKind.LARGE_CARDS -> CardRow(shelf, LARGE_CARD_WIDTH, onItemClick, onItemLongPress, playing, isPlaying, video = false)
            ForkShelfKind.VIDEO_CAROUSEL -> CardRow(shelf, VIDEO_CARD_WIDTH, onItemClick, onItemLongPress, playing, isPlaying, video = true)
            ForkShelfKind.CAROUSEL -> CardRow(shelf, SHELF_CARD_WIDTH, onItemClick, onItemLongPress, playing, isPlaying, video = false)
        }
    }
}

/** Daily discover's cards: about twice a normal card, as YouTube's large item size. */
private val LARGE_CARD_WIDTH = 260.dp

/**
 * YouTube's heading: an optional round picture (an artist's), the strapline above the title
 * ("More from", "Similar to"), and at the end Play all or the See all chevron.
 */
@Composable
private fun ForkShelfHeading(shelf: HomeShelf) {
    val style = shelf.fork ?: return
    val actions = LocalForkHomeFeed.current
    val open = forkShelfOpener(shelf)
    if (shelf.title.isBlank() && style.headerThumbnailUrl == null) return
    val headerOpen = style.headerBrowseId?.let { id -> actions?.let { { it.onHeaderOpen(id, shelf.title) } } }
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth()
            .then(
                when {
                    headerOpen != null -> Modifier.clickable(onClick = headerOpen)
                    open != null -> Modifier.clickable(onClick = open)
                    else -> Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        style.headerThumbnailUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            if (shelf.subtitle.isNotBlank()) {
                Text(
                    text = localizeShelfSubtitle(shelf.subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = localizeShelfTitle(shelf.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val playAll = style.playAll
        if (playAll != null && actions != null) {
            Text(
                text = playAll.label.ifBlank { "Play all" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { actions.onPlayAll(shelf) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        } else {
            ForkShelfChevron(open)
        }
    }
}

/** Track rows, [rows] to a column, paged sideways: YouTube's Quick picks. */
@Composable
private fun ListPager(
    shelf: HomeShelf,
    rows: Int,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    playing: (ShelfItem) -> Boolean,
    isPlaying: Boolean,
) {
    BoxWithConstraints {
        val columnWidth = trackColumnWidth(maxWidth)
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items.chunked(rows.coerceAtLeast(1))) { column ->
                Column(Modifier.width(columnWidth)) {
                    column.forEach { item ->
                        CompactTrackRow(
                            title = item.title,
                            subtitle = item.subtitle,
                            thumbnailUrl = item.thumbnailUrl,
                            onClick = { onItemClick(item) },
                            onLongPress = onItemLongPress?.let { { it(item) } },
                            isCurrent = playing(item),
                            isPlaying = playing(item) && isPlaying,
                            isVideo = item.isVideo,
                        )
                    }
                }
            }
        }
    }
}

/** Podcast episodes: art, title, show, and two lines of the episode's description, three to a column. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodePager(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    playing: (ShelfItem) -> Boolean,
    isPlaying: Boolean,
) {
    val descriptions = shelf.fork?.descriptions.orEmpty()
    BoxWithConstraints {
        val columnWidth = trackColumnWidth(maxWidth)
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items.withIndex().chunked(3)) { column ->
                Column(Modifier.width(columnWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    column.forEach { (index, item) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .longPressMenuClickable(onClick = { onItemClick(item) }, onLongClick = onItemLongPress?.let { { it(item) } })
                                .contextClick(onItemLongPress?.let { { it(item) } }),
                        ) {
                            Art(item, Modifier.size(72.dp), RoundedCornerShape(8.dp), playing(item) && isPlaying, square = true)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (item.subtitle.isNotBlank()) {
                                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                descriptions.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Small cards [rows] to a column, three columns to a screen, paged sideways: YouTube's Speed dial. */
@Composable
private fun GridPager(
    shelf: HomeShelf,
    rows: Int,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    playing: (ShelfItem) -> Boolean,
    isPlaying: Boolean,
) {
    val round = shelf.fork?.roundItems.orEmpty()
    BoxWithConstraints {
        val spacing = 10.dp
        val width = ((maxWidth - PAGE_GUTTER * 2 - spacing * 2) / 3).coerceAtMost(SHELF_CARD_WIDTH)
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(shelf.items.withIndex().chunked(rows.coerceAtLeast(1))) { column ->
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    column.forEach { (index, item) ->
                        Card(item, Modifier.width(width), index in round, onItemClick, onItemLongPress, playing(item) && isPlaying, video = false, compact = true)
                    }
                }
            }
        }
    }
}

/** One row of cards, [width] wide each: carousels, large cards, video cards. */
@Composable
private fun CardRow(
    shelf: HomeShelf,
    width: Dp,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    playing: (ShelfItem) -> Boolean,
    isPlaying: Boolean,
    video: Boolean,
) {
    val round = shelf.fork?.roundItems.orEmpty()
    ShelfRow(
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(shelf.items.withIndex().toList()) { (index, item) ->
            Card(item, Modifier.width(width), index in round, onItemClick, onItemLongPress, playing(item) && isPlaying, video = video || (item.isVideo && index !in round))
        }
    }
}

/** A card: its art (square, 16:9 for a video, round for an artist), title and subtitle under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Card(
    item: ShelfItem,
    modifier: Modifier,
    round: Boolean,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    playingNow: Boolean,
    video: Boolean,
    compact: Boolean = false,
) {
    val longPress = onItemLongPress?.let { { it(item) } }
    Column(
        modifier = modifier
            .longPressMenuClickable(onClick = { onItemClick(item) }, onLongClick = longPress)
            .contextClick(longPress),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Art(
            item = item,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (video && !round) VIDEO_ASPECT else 1f),
            shape = if (round) CircleShape else RoundedCornerShape(if (compact) 8.dp else 12.dp),
            playingNow = playingNow,
            square = !video,
        )
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Text(
            text = item.title,
            style = if (compact) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = if (compact) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotBlank() && !compact) {
            Text(
                text = localizeCardSubtitle(item.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Art(item: ShelfItem, modifier: Modifier, shape: Shape, playingNow: Boolean, square: Boolean) {
    Box(
        modifier = modifier
            .clip(shape)
            .thumbnailBorder(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            // Video stills aren't resizable art; the largest offered is about card size.
            model = if (square) item.thumbnailUrl.artworkAt(CARD_ART_PX) else item.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (playingNow) SearchPlayingBars(Modifier.align(Alignment.Center))
    }
}

/** Explore's buttons, as YouTube draws them: wide tiles with the button's colour stripe. */
@Composable
private fun ButtonRow(buttons: List<ForkNavButton>) {
    val actions = LocalForkHomeFeed.current
    BoxWithConstraints {
        val width = ((maxWidth - PAGE_GUTTER * 2 - 10.dp) / 2).coerceAtMost(220.dp)
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(buttons, key = { it.browseId + it.params.orEmpty() + it.title }) { button ->
                Row(
                    modifier = Modifier
                        .width(width)
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = actions != null) { actions?.onButton(button) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .background(button.stripeColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = button.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Icon(
                        imageVector = BitChordIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 10.dp).size(18.dp),
                    )
                }
            }
        }
    }
}

/** `musicCardShelfRenderer`: one big card with its rows under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    playing: (ShelfItem) -> Boolean,
    isPlaying: Boolean,
) {
    val lead = shelf.items.firstOrNull() ?: return
    val description = shelf.fork?.descriptions?.firstOrNull().orEmpty()
    val longPress = onItemLongPress?.let { { it(lead) } }
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .longPressMenuClickable(onClick = { onItemClick(lead) }, onLongClick = longPress)
                .contextClick(longPress)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(lead, Modifier.size(96.dp), RoundedCornerShape(8.dp), playing(lead) && isPlaying, square = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(lead.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (lead.subtitle.isNotBlank()) {
                    Text(lead.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (description.isNotBlank() && description != lead.subtitle) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        shelf.items.drop(1).forEach { item ->
            CompactTrackRow(
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                onClick = { onItemClick(item) },
                onLongPress = onItemLongPress?.let { { it(item) } },
                isCurrent = playing(item),
                isPlaying = playing(item) && isPlaying,
                isVideo = item.isVideo,
            )
        }
    }
}
