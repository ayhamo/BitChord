package com.music.bitchord.ui.screens

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.HomeShelf
import kotlinx.coroutines.flow.MutableStateFlow

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
) {
    /**
     * Whether the YouTube feed may page in more: not while its section is folded, where the short
     * list always reads as "near the end" and Home would fetch the whole feed into a hidden section.
     */
    val loadsMore: Boolean get() = youtubeOpen || (navidrome <= 0 && spotify <= 0)

    /** The feed cut into its sections, empty ones left out, or null for no sections (upstream's Home). */
    fun sections(shelves: List<HomeShelf>): List<ForkHomeSection>? {
        if (navidrome <= 0 && spotify <= 0) return null
        return listOf(
            ForkHomeSection("navidrome", "Navidrome", navidromeOpen, ForkHome.navidromeOpen, shelves.take(navidrome)),
            ForkHomeSection("youtube", "YouTube", youtubeOpen, ForkHome.youtubeOpen, shelves.drop(navidrome).dropLast(spotify)),
            ForkHomeSection("spotify", "Spotify", spotifyOpen, ForkHome.spotifyOpen, shelves.takeLast(spotify)),
        ).filter { it.shelves.isNotEmpty() }
    }
}

@Composable
internal fun rememberForkHome(): ForkHomeState {
    val navidrome by ForkHome.navidromeShelves.collectAsStateWithLifecycle()
    val spotify by ForkHome.spotifyShelves.collectAsStateWithLifecycle()
    val navidromeOpen by ForkHome.navidromeOpen.collectAsStateWithLifecycle()
    val spotifyOpen by ForkHome.spotifyOpen.collectAsStateWithLifecycle()
    val youtubeOpen by ForkHome.youtubeOpen.collectAsStateWithLifecycle()
    return ForkHomeState(navidrome, spotify, navidromeOpen, spotifyOpen, youtubeOpen)
}

/** A section's heading; a tap folds or unfolds it. */
internal fun LazyListScope.forkHomeHeading(section: ForkHomeSection) {
    item(key = "fork:home:" + section.key) {
        PanelHeading(section.title, section.open, section::toggle)
    }
}
