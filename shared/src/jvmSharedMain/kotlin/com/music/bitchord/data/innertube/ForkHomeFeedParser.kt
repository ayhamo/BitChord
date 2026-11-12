package com.music.bitchord.data.innertube

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.model.ForkNavButton
import com.music.bitchord.data.model.ForkPlayAll
import com.music.bitchord.data.model.ForkShelfKind
import com.music.bitchord.data.model.ForkShelfStyle
import com.music.bitchord.data.model.HomeChip
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Fork: YouTube Music's Home, read 1:1 (owner, 2026-11-11).
 *
 * Upstream's [InnertubeParser.parseHome] keeps only `musicCarouselShelfRenderer` and
 * `musicShelfRenderer`, reads a carousel's items only as two-row cards or list rows, and thins any
 * shelf titled "video" down to its playable items, so Speed dial's layout, the large daily-discover
 * cards, podcast episodes, the card shelves and the Explore buttons all fell away, and the chips
 * over the page were never read. This reader takes every section kind YouTube Home is known to send,
 * in YouTube's order and with YouTube's titles, and records each one's layout in [ForkShelfStyle] so
 * the UI can draw it as YouTube does. A section of a kind it doesn't know is skipped and logged once
 * per kind, so a new one shows up in the logs instead of vanishing silently.
 *
 * Only Home uses this; Explore's mood pages, See all pages and the old supplements keep upstream's
 * reader.
 */
object ForkHomeFeedParser {

    private const val TAG = "ForkHomeFeed"
    private const val LISTEN_AGAIN = "FEmusic_listen_again"

    /** The first page of Home (or a chip's Home): its shelves, its continuation and its chips. */
    fun parse(response: JsonObject): HomeFeed {
        val list = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer")
            ?: findFirst(response, "sectionListRenderer") as? JsonObject
        val sections = list.a("contents").orEmpty()
        return HomeFeed(
            shelves = sections.mapNotNull { section(it) },
            continuation = InnertubeParser.continuationToken(response),
            chips = chips(list.o("header")),
        )
    }

    /**
     * A continuation page of Home. Its sections sit in `continuationContents.sectionListContinuation`
     * (the shape YouTube sends today) or in an `appendContinuationItemsAction` (the newer envelope
     * other feeds have moved to); either is read in order, with the same section reader as the
     * first page.
     */
    fun parseContinuation(response: JsonElement): HomeFeed {
        val sections = (findFirst(response, "sectionListContinuation") as? JsonObject)?.a("contents")
            ?: (findFirst(response, "appendContinuationItemsAction") as? JsonObject)?.a("continuationItems")
            ?: (findFirst(response, "sectionListRenderer") as? JsonObject)?.a("contents")
            ?: JsonArray(emptyList())
        return HomeFeed(
            shelves = sections.mapNotNull { section(it) },
            continuation = InnertubeParser.continuationToken(response),
        )
    }

    /** The chip cloud over Home: `sectionListRenderer.header.chipCloudRenderer.chips[]`. */
    fun chips(header: JsonElement?): List<HomeChip> {
        val cloud = header.o("chipCloudRenderer") ?: (findFirst(header, "chipCloudRenderer") as? JsonObject)
        return cloud.a("chips").orEmpty().mapNotNull { element ->
            val chip = element.o("chipCloudChipRenderer") ?: return@mapNotNull null
            val title = chip.o("text").runs().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val endpoint = chip.o("navigationEndpoint").o("browseEndpoint") ?: return@mapNotNull null
            HomeChip(
                title = title,
                params = endpoint.s("params"),
                selected = (chip["isSelected"] as? JsonPrimitive)?.booleanOrNull == true,
                deselectParams = chip.o("onDeselectedCommand").o("browseEndpoint").s("params"),
            )
        }
    }

    // ---- Sections ----------------------------------------------------------

    private val loggedUnknown = ConcurrentHashMap.newKeySet<String>()

    private fun logOnce(what: String) {
        if (loggedUnknown.add(what)) DebugLog.w(TAG, "skipped an unknown Home $what")
    }

    private fun section(element: JsonElement): HomeShelf? {
        val section = element as? JsonObject ?: return null
        section.o("musicCarouselShelfRenderer")?.let { return carousel(it, "musicCarouselShelfRenderer") }
        section.o("musicImmersiveCarouselShelfRenderer")?.let { return carousel(it, "musicImmersiveCarouselShelfRenderer") }
        section.o("musicShelfRenderer")?.let { return listShelf(it) }
        section.o("gridRenderer")?.let { return grid(it) }
        section.o("musicCardShelfRenderer")?.let { return cardShelf(it) }
        // Not shelves: the page's own continuation slot, spacers.
        if (section.containsKey("continuationItemRenderer") || section.containsKey("itemSectionRenderer")) return null
        section.keys.firstOrNull()?.let { logOnce("section $it") }
        return null
    }

    /** One item of a shelf, whatever its renderer, with what the UI needs beside upstream's [ShelfItem]. */
    private class Item(
        val item: ShelfItem,
        val description: String = "",
        val round: Boolean = false,
        val button: ForkNavButton? = null,
        val row: Boolean = false,
        val episode: Boolean = false,
    )

    private fun item(element: JsonElement): Item? {
        val content = element as? JsonObject ?: return null
        content.o("musicTwoRowItemRenderer")?.let { renderer ->
            val card = InnertubeParser.forkTwoRowItem(renderer) ?: return null
            val artist = renderer.o("navigationEndpoint").o("browseEndpoint")
                .o("browseEndpointContextSupportedConfigs").o("browseEndpointContextMusicConfig")
                .s("pageType")?.contains("ARTIST") == true
            return Item(card, round = artist)
        }
        content.o("musicResponsiveListItemRenderer")?.let { renderer ->
            InnertubeParser.forkListSong(renderer)?.let { song ->
                return Item(
                    ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, isVideo = song.isVideo),
                    row = true,
                )
            }
            // A row with nothing to play: an artist, as on the charts.
            InnertubeParser.forkArtistRow(renderer)?.let { return Item(it, round = true, row = true) }
            return null
        }
        content.o("musicMultiRowListItemRenderer")?.let { return multiRow(it) }
        content.o("musicNavigationButtonRenderer")?.let { renderer ->
            val button = navButton(renderer) ?: return null
            return Item(ShelfItem(button.title, "", null, null, button.browseId), button = button)
        }
        content.keys.firstOrNull()?.let { logOnce("item $it") }
        return null
    }

    /** A podcast episode row: its art, title, show line and description. */
    private fun multiRow(renderer: JsonObject): Item? {
        val title = renderer.o("title").runs().takeIf { it.isNotBlank() } ?: return null
        val titleBrowse = renderer.o("title").a("runs")?.firstNotNullOfOrNull {
            it.o("navigationEndpoint").o("browseEndpoint").s("browseId")
        }
        val videoId = renderer.o("onTap").o("watchEndpoint").s("videoId")
            ?: findFirst(renderer.o("overlay"), "watchEndpoint").s("videoId")
            ?: titleBrowse?.takeIf { it.startsWith("MPED") }?.removePrefix("MPED")
        val browseId = titleBrowse?.takeUnless { it.startsWith("MPED") }
        if (videoId == null && browseId == null) return null
        val subtitle = listOf(renderer.o("subtitle").runs(), renderer.o("secondTitle").runs())
            .filter { it.isNotBlank() }.joinToString(" • ")
        return Item(
            ShelfItem(
                title = title,
                subtitle = subtitle,
                thumbnailUrl = renderer.o("thumbnail").o("musicThumbnailRenderer")
                    .o("thumbnail").a("thumbnails").best(),
                videoId = videoId,
                browseId = if (videoId == null) browseId else null,
            ),
            description = renderer.o("description").runs(),
            episode = true,
        )
    }

    private fun navButton(renderer: JsonObject): ForkNavButton? {
        val endpoint = renderer.o("clickCommand").o("browseEndpoint")
            ?: renderer.o("navigationEndpoint").o("browseEndpoint")
            ?: return null
        val browseId = endpoint.s("browseId") ?: return null
        val title = renderer.o("buttonText").runs().takeIf { it.isNotBlank() } ?: return null
        return ForkNavButton(
            title = title,
            browseId = browseId,
            params = endpoint.s("params"),
            stripeColor = renderer.o("solid").s("leftStripeColor")?.toLongOrNull(),
            icon = renderer.o("iconStyle").o("icon").s("iconType"),
        )
    }

    private fun carousel(carousel: JsonObject, renderer: String): HomeShelf? {
        val header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
            ?: carousel.o("header").o("musicImmersiveCarouselShelfHeaderRenderer")
        val title = header.o("title").runs()
        val items = carousel.a("contents").orEmpty().mapNotNull(::item)
        if (items.isEmpty()) return null
        val perColumn = carousel.s("numItemsPerColumn")?.toIntOrNull() ?: 1
        val large = carousel.s("itemSize")?.contains("LARGE") == true
        val more = ForkShelfMore.carousel(header)
        // The web feed sends no layout for two shelves the YouTube Music app draws its own way, so
        // they're recognised by what they hold: "Listen again" (the app's Speed dial) links to
        // FEmusic_listen_again and is a 2-row paged grid there; a carousel of square song cards
        // ("Your daily discover") is drawn as large cards there.
        val speedDial = more?.browseId == LISTEN_AGAIN
        // An artist's shelf (a picture in its heading, or immersive) stays a carousel, as in the app.
        val artistShelf = renderer != "musicCarouselShelfRenderer" || header.o("thumbnail") != null
        val songCards = !artistShelf && items.size >= 2 && items.all { !it.row && it.button == null && it.item.videoId != null && !it.item.isVideo }
        val kind = when {
            items.all { it.button != null } -> ForkShelfKind.BUTTONS
            items.any { it.episode } -> ForkShelfKind.EPISODES
            items.count { it.row } * 2 > items.size -> ForkShelfKind.LIST
            perColumn >= 2 || speedDial -> ForkShelfKind.GRID
            large || songCards -> ForkShelfKind.LARGE_CARDS
            items.count { it.item.isVideo } * 2 > items.size -> ForkShelfKind.VIDEO_CAROUSEL
            else -> ForkShelfKind.CAROUSEL
        }
        val rows = when (kind) {
            ForkShelfKind.LIST -> if (perColumn >= 2) perColumn else 4
            ForkShelfKind.GRID -> if (speedDial) perColumn.coerceAtLeast(2) else perColumn
            else -> 1
        }
        val artist = header.o("title").a("runs")?.firstNotNullOfOrNull { run ->
            run.o("navigationEndpoint").o("browseEndpoint")?.takeIf {
                it.o("browseEndpointContextSupportedConfigs").o("browseEndpointContextMusicConfig")
                    .s("pageType")?.contains("ARTIST") == true
            }?.s("browseId")
        } ?: header.o("thumbnail").o("musicThumbnailRenderer").o("navigationEndpoint")
            .o("browseEndpoint").s("browseId")
        return shelf(
            title = title,
            subtitle = header.o("strapline").runs(),
            items = items,
            kind = kind,
            rows = rows,
            moreBrowseId = more?.browseId,
            moreParams = more?.params,
            headerThumbnail = header.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails").best(),
            headerBrowseId = artist,
            playAll = playAll(header.o("moreContentButton").o("buttonRenderer")),
            renderer = renderer,
        )
    }

    private fun listShelf(shelf: JsonObject): HomeShelf? {
        val items = shelf.a("contents").orEmpty().mapNotNull(::item)
        if (items.isEmpty()) return null
        val more = ForkShelfMore.plain(shelf)
        return shelf(
            title = shelf.o("title").runs(),
            subtitle = shelf.o("subtitle").runs(),
            items = items,
            kind = if (items.any { it.episode }) ForkShelfKind.EPISODES else ForkShelfKind.LIST,
            rows = 4,
            moreBrowseId = more?.browseId,
            moreParams = more?.params,
            playAll = playAll(shelf.o("bottomButton").o("buttonRenderer")),
            renderer = "musicShelfRenderer",
        )
    }

    private fun grid(grid: JsonObject): HomeShelf? {
        val header = grid.o("header").o("gridHeaderRenderer")
        val items = grid.a("items").orEmpty().mapNotNull(::item)
        if (items.isEmpty()) return null
        val buttons = items.all { it.button != null }
        return shelf(
            title = header.o("title").runs(),
            subtitle = header.o("strapline").runs(),
            items = items,
            kind = if (buttons) ForkShelfKind.BUTTONS else ForkShelfKind.GRID,
            rows = if (buttons) 1 else 2,
            renderer = "gridRenderer",
        )
    }

    /**
     * `musicCardShelfRenderer`: one big card (its art, title, subtitle and what it plays or opens)
     * with a few list rows under it. The card is the shelf's first item, the rows follow.
     */
    private fun cardShelf(card: JsonObject): HomeShelf? {
        val title = card.o("title").runs().takeIf { it.isNotBlank() } ?: return null
        val browse = card.o("title").a("runs")?.firstNotNullOfOrNull { it.o("navigationEndpoint").o("browseEndpoint") }
            ?: card.o("onTap").o("browseEndpoint")
        val watch = card.o("onTap").o("watchEndpoint")
            ?: card.o("title").a("runs")?.firstNotNullOfOrNull { it.o("navigationEndpoint").o("watchEndpoint") }
            ?: card.a("buttons")?.firstNotNullOfOrNull { findFirst(it, "watchEndpoint") as? JsonObject }
        val browseId = browse.s("browseId")?.takeUnless { it.startsWith("MPED") }
        val videoId = watch.s("videoId") ?: browse.s("browseId")?.takeIf { it.startsWith("MPED") }?.removePrefix("MPED")
        val thumbnails = card.o("thumbnail").o("musicThumbnailRenderer").o("thumbnail").a("thumbnails")
        val lead = Item(
            ShelfItem(
                title = title,
                subtitle = card.o("subtitle").runs(),
                thumbnailUrl = thumbnails.best(),
                videoId = if (browseId == null) videoId else null,
                browseId = browseId,
            ),
            description = card.o("description").runs().ifBlank { card.o("subtitle").runs() },
        )
        val rows = card.a("contents").orEmpty().mapNotNull(::item)
        val heading = card.o("header").o("musicCardShelfHeaderBasicRenderer").o("title").runs()
        return shelf(
            title = heading,
            subtitle = "",
            items = listOf(lead) + rows,
            kind = ForkShelfKind.CARD,
            rows = 1,
            renderer = "musicCardShelfRenderer",
        )
    }

    private fun shelf(
        title: String,
        subtitle: String,
        items: List<Item>,
        kind: ForkShelfKind,
        rows: Int,
        moreBrowseId: String? = null,
        moreParams: String? = null,
        headerThumbnail: String? = null,
        headerBrowseId: String? = null,
        playAll: ForkPlayAll? = null,
        renderer: String,
    ): HomeShelf {
        val descriptions = items.map { it.description }
        return HomeShelf(
            // A blank heading is kept blank: YouTube's own page shows the shelf without one.
            title = title,
            items = items.map { it.item },
            subtitle = subtitle,
            moreBrowseId = moreBrowseId,
            moreParams = moreParams,
            layout = when (kind) {
                ForkShelfKind.LIST, ForkShelfKind.EPISODES -> ShelfLayout.LIST
                ForkShelfKind.VIDEO_CAROUSEL -> ShelfLayout.VIDEO
                else -> ShelfLayout.CARDS
            },
            fork = ForkShelfStyle(
                kind = kind,
                rows = rows.coerceAtLeast(1),
                headerThumbnailUrl = headerThumbnail,
                headerBrowseId = headerBrowseId,
                playAll = playAll,
                buttons = items.mapNotNull { it.button },
                descriptions = if (descriptions.all { it.isBlank() }) emptyList() else descriptions,
                roundItems = items.indices.filterTo(HashSet()) { items[it].round },
                renderer = renderer,
            ),
        )
    }

    /**
     * A heading button that plays ("Play all"): its `watchEndpoint` or `watchPlaylistEndpoint`.
     * A button that browses instead is the See all link, which [ForkShelfMore] reads.
     */
    private fun playAll(button: JsonObject?): ForkPlayAll? {
        val endpoint = button.o("navigationEndpoint")
        val label = button.o("text").runs()
        val watch = endpoint.o("watchEndpoint")
        if (watch != null) return ForkPlayAll(watch.s("videoId"), watch.s("playlistId"), watch.s("params"), label)
        val list = endpoint.o("watchPlaylistEndpoint") ?: return null
        return ForkPlayAll(playlistId = list.s("playlistId"), params = list.s("params"), label = label)
    }

    // ---- JSON helpers (InnertubeParser's are private) -----------------------

    private fun findFirst(root: JsonElement?, key: String): JsonElement? = when (root) {
        is JsonObject -> root[key] ?: root.values.firstNotNullOfOrNull { findFirst(it, key) }
        is JsonArray -> root.firstNotNullOfOrNull { findFirst(it, key) }
        else -> null
    }

    private fun JsonElement?.o(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.a(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonElement?.s(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.runs(): String =
        a("runs")?.joinToString("") { it.s("text").orEmpty() }.orEmpty()
    private fun JsonArray?.best(): String? = this?.lastOrNull().s("url")
}
