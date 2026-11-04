package com.music.bitchord.ui.screens

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.MessageState
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * Fork: Home in sections, "Navidrome" (the server's shelves), "YouTube"
 * (upstream's feed) and "Spotify" (the account's mixes), each folding away under its
 * heading, as Explore's panels do ([ForkExplore]).
 *
 * The app lays the server's shelves over the top of the feed and Spotify's
 * after its end, and sets how many of each there are (`NavidromeHome.onTop`); with none of either
 * there are no headings and Home is upstream's.
 */
object ForkHome {
    val navidromeShelves = MutableStateFlow(0)
    val spotifyShelves = MutableStateFlow(0)
    val navidromeOpen = MutableStateFlow(true)
    val spotifyOpen = MutableStateFlow(true)
    val youtubeOpen = MutableStateFlow(true)

    /**
     * Why YouTube's feed failed while Home shows shelves of the fork's own, for an app that doesn't
     * carry it in the list ([ForkHomeShelves]): the PC sets it as its feed fails and clears it as
     * Home loads again. Without it the server's shelves stood in for the failed feed with no
     * YouTube rows, no reason and no Retry.
     */
    val youtubeError = MutableStateFlow<String?>(null)
}

/**
 * Fork: Home's shelves with the section sizes they were cut with, and why YouTube's feed failed when
 * it did. The phone used to publish the sizes on flows of their own beside the list, so for the frame
 * after either changed the sections were cut with the other's sizes (four YouTube shelves under
 * "Navidrome" as the server's arrived); carried in the list they always match it.
 */
class ForkHomeShelves(
    private val shelves: List<HomeShelf>,
    val navidrome: Int,
    val spotify: Int,
    val youtubeError: String? = null,
) : List<HomeShelf> by shelves {
    override fun equals(other: Any?): Boolean =
        other is ForkHomeShelves && shelves == other.shelves && navidrome == other.navidrome &&
            spotify == other.spotify && youtubeError == other.youtubeError

    override fun hashCode(): Int = ((shelves.hashCode() * 31 + navidrome) * 31 + spotify) * 31 + youtubeError.hashCode()

    override fun toString(): String = "ForkHomeShelves($navidrome, $spotify, $youtubeError, $shelves)"

    companion object {
        /**
         * The phone's Home: the server's shelves ([navidrome]) in front of YouTube's [feed] and
         * Spotify's mixes ([spotify]) after it. A feed still loading or failed leaves the fork's
         * shelves showing meanwhile, and a failed one says so in its section, with Retry.
         */
        fun merge(
            feed: UiState<List<HomeShelf>>,
            navidrome: List<HomeShelf>,
            spotify: List<HomeShelf>,
        ): UiState<List<HomeShelf>> = when {
            navidrome.isEmpty() && spotify.isEmpty() -> feed
            feed is UiState.Success -> UiState.Success(ForkHomeShelves(navidrome + feed.data + spotify, navidrome.size, spotify.size))
            else -> UiState.Success(
                ForkHomeShelves(navidrome + spotify, navidrome.size, spotify.size, (feed as? UiState.Error)?.message),
            )
        }
    }
}

/** One section of Home: its heading, whether it is open, and its shelves. */
internal class ForkHomeSection(
    val key: String,
    val title: String,
    val open: Boolean,
    private val flag: MutableStateFlow<Boolean>,
    val shelves: List<HomeShelf>,
) {
    fun toggle() {
        flag.value = !open
    }
}

/** What the Home list needs to know about the sections, read once per composition. */
internal class ForkHomeState(
    private val navidrome: Int,
    private val spotify: Int,
    private val navidromeOpen: Boolean,
    private val spotifyOpen: Boolean,
    private val youtubeOpen: Boolean,
    private val youtubeError: String? = null,
) {
    /**
     * Whether the YouTube feed may page in more: not while its section is folded, where the short
     * list always reads as "near the end" and Home would fetch the whole feed into a hidden section.
     */
    val loadsMore: Boolean get() = youtubeOpen || (navidrome <= 0 && spotify <= 0)

    /** List items below the YouTube feed: Spotify's. Reaching those isn't reaching the feed's end, or Home would page in all of it. */
    val tailItems: Int get() = if (spotify <= 0) 0 else 1 + if (spotifyOpen) spotify else 0

    /** Why YouTube's feed failed under [shelves], if it did: carried in the list, else the app's [ForkHome.youtubeError]. */
    fun errorOf(shelves: List<HomeShelf>): String? = (shelves as? ForkHomeShelves)?.youtubeError ?: youtubeError

    /**
     * The feed cut into its sections, empty ones left out (YouTube's kept while it failed, to say so),
     * or null for no sections (upstream's Home). The sizes the list carries win over the flows'.
     */
    fun sections(shelves: List<HomeShelf>): List<ForkHomeSection>? {
        val carried = shelves as? ForkHomeShelves
        val navidrome = carried?.navidrome ?: navidrome
        val spotify = carried?.spotify ?: spotify
        if (navidrome <= 0 && spotify <= 0) return null
        val failed = errorOf(shelves) != null
        return listOf(
            ForkHomeSection("navidrome", "Navidrome", navidromeOpen, ForkHome.navidromeOpen, shelves.take(navidrome)),
            ForkHomeSection("youtube", "YouTube", youtubeOpen, ForkHome.youtubeOpen, shelves.drop(navidrome).dropLast(spotify)),
            ForkHomeSection("spotify", "Spotify", spotifyOpen, ForkHome.spotifyOpen, shelves.takeLast(spotify)),
        ).filter { it.shelves.isNotEmpty() || (failed && it.key == "youtube") }
    }
}

@Composable
internal fun rememberForkHome(): ForkHomeState {
    val navidrome by ForkHome.navidromeShelves.collectAsStateWithLifecycle()
    val spotify by ForkHome.spotifyShelves.collectAsStateWithLifecycle()
    val navidromeOpen by ForkHome.navidromeOpen.collectAsStateWithLifecycle()
    val spotifyOpen by ForkHome.spotifyOpen.collectAsStateWithLifecycle()
    val youtubeOpen by ForkHome.youtubeOpen.collectAsStateWithLifecycle()
    val youtubeError by ForkHome.youtubeError.collectAsStateWithLifecycle()
    return ForkHomeState(navidrome, spotify, navidromeOpen, spotifyOpen, youtubeOpen, youtubeError)
}

/** A section's heading; a tap folds or unfolds it. */
internal fun LazyListScope.forkHomeHeading(section: ForkHomeSection) {
    item(key = "fork:home:" + section.key) {
        PanelHeading(section.title, section.open, section::toggle)
    }
}

/**
 * Upstream's feed, drawn by [draw], section by section under the headings, or
 * whole with no sections. Only the first open section leads with the big cards
 * ([leadHero]); the rest keep ordinary ones. [draw] gets the section's shelves,
 * whether they lead, and a prefix for their keys (the sections share one list).
 * A failed YouTube feed says so where its shelves would be, with [onRetry].
 */
internal fun LazyListScope.forkHomeFeed(
    state: ForkHomeState,
    shelves: List<HomeShelf>,
    leadHero: Boolean,
    recentsLoading: Boolean,
    onRetry: () -> Unit,
    draw: LazyListScope.(List<HomeShelf>, Boolean, String) -> Unit,
) {
    val error = state.errorOf(shelves)
    val sections = state.sections(shelves)
    if (sections == null) {
        draw(shelves, leadHero && !recentsLoading, "")
        if (error != null) forkHomeError(error, onRetry)
        return
    }
    val firstOpen = sections.indexOfFirst { it.open }
    sections.forEachIndexed { index, section ->
        forkHomeHeading(section)
        // "Recently played" lands in the YouTube section, so only that section waits for it to be the hero.
        if (section.open) draw(section.shelves, index == firstOpen && leadHero && (section.key != "youtube" || !recentsLoading), section.key + ":")
        if (section.open && section.key == "youtube" && error != null) forkHomeError(error, onRetry)
    }
}

/** YouTube's error with Retry, as Home shows it when there is nothing else to show ([UiState.Error]). */
private fun LazyListScope.forkHomeError(message: String, onRetry: () -> Unit) {
    item(key = "fork:home:youtube-error") {
        MessageState(message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
    }
}
