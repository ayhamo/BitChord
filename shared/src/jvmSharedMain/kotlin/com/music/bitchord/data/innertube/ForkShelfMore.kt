package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.shelfLayoutFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fork: a Home shelf's "More" link, and the page behind it (Desktop Revived
 * 367bf58e, e7affc8b, fbd0a9a0).
 *
 * YouTube Music's web player opens a shelf's full list when its heading is
 * tapped. The heading's link was only read on artist pages, so a Home shelf
 * carried no [HomeShelf.moreBrowseId] and its heading led nowhere. The link
 * sits in one of several places depending on the shelf: a carousel puts it on
 * the title run, the "More" button (two renderer names for it) or an end icon;
 * a plain list shelf on the title run or its bottom button. These are the same
 * places [InnertubeParser.parseArtistPage] reads for an artist's shelves.
 */
internal object ForkShelfMore {

    /** A link's browse id and params; params are null on most. */
    data class Link(val browseId: String, val params: String?)

    /** The link of a `musicCarouselShelfBasicHeaderRenderer`, or null when the shelf has none. */
    fun carousel(header: JsonObject?): Link? = link(
        header.o("title").a("runs")?.firstNotNullOfOrNull { it.o("navigationEndpoint").o("browseEndpoint") }
            ?: header.o("moreContentButton").o("buttonRenderer").o("navigationEndpoint").o("browseEndpoint")
            ?: header.o("moreContentButton").o("musicMoreContentButtonRenderer").o("navigationEndpoint").o("browseEndpoint")
            ?: header.a("endIcons")?.firstNotNullOfOrNull {
                it.o("musicNavigationButtonRenderer").o("clickCommand").o("browseEndpoint")
                    ?: it.o("musicNavigationButtonRenderer").o("navigationEndpoint").o("browseEndpoint")
            }
            ?: header.o("navigationEndpoint").o("browseEndpoint"),
    )

    /** The link of a plain `musicShelfRenderer`, or null when it has none. */
    fun plain(shelf: JsonObject?): Link? = link(
        shelf.o("title").a("runs")?.firstNotNullOfOrNull { it.o("navigationEndpoint").o("browseEndpoint") }
            ?: shelf.o("bottomEndpoint").o("browseEndpoint")
            ?: shelf.o("bottomButton").o("buttonRenderer").o("navigationEndpoint").o("browseEndpoint"),
    )

    /**
     * A See all page as shelves. Most of these are a `gridRenderer` of
     * two-row cards (New albums & singles, New music videos, Mixed for you),
     * which [InnertubeParser.parseHome] has no reader for; a few are
     * carousels or lists, which it does. Grids are read here and kept apart
     * from parseHome so the Home feed, its supplements and the mood pages,
     * all of which go through parseHome, are untouched.
     */
    fun parsePage(response: JsonObject): List<HomeShelf> {
        val sections = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            .orEmpty()
        val grids = sections.mapNotNull { it.o("gridRenderer")?.let(::gridShelf) }
        return InnertubeParser.parseHome(response) + grids
    }

    private fun gridShelf(grid: JsonObject): HomeShelf? {
        val title = grid.o("header").o("gridHeaderRenderer").o("title").runs()
        val parsed = InnertubeParser.parseTwoRowItemsDeep(grid)
        // As carouselShelf does: a grid billed as videos keeps the videos it
        // can play and loses the compilations that open onto empty pages.
        val items = if (VIDEO_WORD.containsMatchIn(title)) parsed.filter { it.videoId != null } else parsed
        if (items.isEmpty()) return null
        return HomeShelf(title, items, layout = shelfLayoutFor(title, items, fromList = false))
    }

    private fun link(endpoint: JsonObject?): Link? =
        endpoint.s("browseId")?.takeIf { it.isNotBlank() }?.let { Link(it, endpoint.s("params")) }

    /** Same test as InnertubeParser's private VIDEO_WORD. */
    private val VIDEO_WORD = Regex("""\bvideos?\b""", RegexOption.IGNORE_CASE)

    private fun JsonElement?.o(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.a(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonElement?.s(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.runs(): String =
        a("runs")?.joinToString("") { it.s("text").orEmpty() }.orEmpty()
}
