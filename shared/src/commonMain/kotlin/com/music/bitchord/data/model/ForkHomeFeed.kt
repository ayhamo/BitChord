package com.music.bitchord.data.model

/*
 * Fork: YouTube Music's Home drawn 1:1 (owner, 2026-11-11). Upstream's parser kept only plain
 * carousels and list shelves, so Speed dial, the large "daily discover" cards, podcast episodes, the
 * Explore buttons and the card shelves never reached the page. The fork's reader
 * (`ForkHomeFeedParser`) keeps every kind YouTube sends and records here how YouTube lays each one
 * out, so the shared Home can draw it the same way. Upstream's own fields on [HomeShelf] are still
 * filled as before (title, items, strapline, See all link, layout), so everything that reads a shelf
 * without knowing about this keeps working.
 */

/** How YouTube Music lays out one Home shelf. */
enum class ForkShelfKind {
    /** One scrolling row of square cards. */
    CAROUSEL,

    /** One scrolling row of 16:9 cards (music videos, long listens drawn as cards). */
    VIDEO_CAROUSEL,

    /** Track rows, [ForkShelfStyle.rows] to a column, paged sideways: Quick picks, Trending, Long listens. */
    LIST,

    /** Small cards [ForkShelfStyle.rows] to a column, paged sideways: Speed dial. */
    GRID,

    /** One scrolling row of large cards: Your daily discover ("For fans of …", "Sounds like …"). */
    LARGE_CARDS,

    /** Podcast episodes: a row with its art, title, show and description. */
    EPISODES,

    /** The Explore buttons: New releases, Charts, Moods & genres. */
    BUTTONS,

    /** A single big card with a few rows under it (`musicCardShelfRenderer`). */
    CARD,
}

/** One of YouTube's navigation buttons, e.g. Explore's "Charts". */
data class ForkNavButton(
    val title: String,
    val browseId: String,
    val params: String? = null,
    /** YouTube's stripe colour, ARGB; null when the button has none. */
    val stripeColor: Long? = null,
    /** YouTube's icon name, e.g. `TRENDING_UP`; null when the button has none. */
    val icon: String? = null,
)

/** A heading's "Play all": what YouTube's own button would start. */
data class ForkPlayAll(
    val videoId: String? = null,
    val playlistId: String? = null,
    val params: String? = null,
    /** YouTube's own label for the button ("Play all", localised); blank when it gave none. */
    val label: String = "",
)

/** Everything about a Home shelf that upstream's [HomeShelf] has no field for. */
data class ForkShelfStyle(
    val kind: ForkShelfKind,
    /** Rows per column for [ForkShelfKind.LIST] and [ForkShelfKind.GRID] (YouTube's `numItemsPerColumn`). */
    val rows: Int = 1,
    /** The round picture beside the heading, e.g. an artist's for "More from …" shelves. */
    val headerThumbnailUrl: String? = null,
    /** Where tapping that picture leads (the artist's page). */
    val headerBrowseId: String? = null,
    /** The heading's "Play all" button, when YouTube gives one. */
    val playAll: ForkPlayAll? = null,
    /** [ForkShelfKind.BUTTONS]' buttons, in YouTube's order (the shelf's items mirror them). */
    val buttons: List<ForkNavButton> = emptyList(),
    /** Per item, a longer line under it (episode descriptions, card text); empty or shorter is fine. */
    val descriptions: List<String> = emptyList(),
    /** Items that are artists, drawn round as YouTube does, by index. */
    val roundItems: Set<Int> = emptySet(),
    /** YouTube's renderer name, for logs and tests. */
    val renderer: String = "",
)

/** One of the mood chips over YouTube Music's Home: "Podcasts", "Relax", "Energize", … */
data class HomeChip(
    val title: String,
    /** The `browseEndpoint.params` that `browse("FEmusic_home", params)` takes to load this chip's Home. */
    val params: String?,
    val selected: Boolean = false,
    /** The params of YouTube's own deselect command, when it gives one. */
    val deselectParams: String? = null,
)

/**
 * The chips over Home and which one is picked, shared by Home's loader and its UI on both apps.
 * The pick survives a reload (a pull, Retry, a new page) until the chip is tapped again.
 */
object ForkHomeChips {
    val chips = kotlinx.coroutines.flow.MutableStateFlow<List<HomeChip>>(emptyList())
    val selected = kotlinx.coroutines.flow.MutableStateFlow<HomeChip?>(null)

    /** [chip] tapped: picked, or back to the plain Home when it already was. Returns the new pick. */
    fun toggle(chip: HomeChip): HomeChip? {
        val next = if (selected.value?.title == chip.title) null else chip
        selected.value = next
        return next
    }

    /** The params Home loads with: the picked chip's, or null for the plain Home. */
    val params: String? get() = selected.value?.params

    /** A page's chips arrived: kept, so the row stays as YouTube sent it (a chip's page repeats them). */
    fun publish(found: List<HomeChip>) {
        if (found.isNotEmpty()) chips.value = found
    }

    /** Another account (or none): its Home has its own chips, and the old pick means nothing there. */
    fun reset() {
        selected.value = null
        chips.value = emptyList()
    }
}

/**
 * Whether Home still adds upstream's own extras around YouTube's feed: the history "Recents" shelf
 * in place of YouTube's, the New releases / Explore browses after it, and the dropping of shelves
 * whose title or releases were already shown. Off: the owner wants YouTube Music's Home as
 * YouTube sends it, with only Top Picks, Find Your Mood and the Navidrome rows beside it
 * (2026-11-11). The upstream code stays in place behind this, so merges stay cheap.
 */
const val FORK_HOME_UPSTREAM_EXTRAS = false
