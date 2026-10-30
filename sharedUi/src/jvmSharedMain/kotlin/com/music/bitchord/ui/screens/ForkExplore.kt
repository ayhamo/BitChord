package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.ui.components.PAGE_GUTTER
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: Explore in panels, "Navidrome" (the server's genres), "YouTube"
 * (upstream's moods and genres) and "Spotify" (the account's mixes), each
 * folding away under its heading. The app fills [navidromeGenres] and
 * [spotifyMixes]; with neither there are no headings at all, so Explore is
 * upstream's.
 */
object ForkExplore {
    val navidromeGenres = MutableStateFlow<List<MoodGenre>>(emptyList())
    val spotifyMixes = MutableStateFlow<List<MoodGenre>>(emptyList())
    val youtubeOpen = MutableStateFlow(true)
    val navidromeOpen = MutableStateFlow(true)
    val spotifyOpen = MutableStateFlow(true)
}

/** What the Explore list needs to know about the panels, read once per composition. */
internal class ForkExploreState(
    val genres: List<MoodGenre>,
    val mixes: List<MoodGenre>,
    val youtubeOpen: Boolean,
    val navidromeOpen: Boolean,
    val spotifyOpen: Boolean,
) {
    val panels get() = genres.isNotEmpty() || mixes.isNotEmpty()
}

@Composable
internal fun rememberForkExplore(): ForkExploreState {
    val all by ForkExplore.navidromeGenres.collectAsStateWithLifecycle()
    val mixes by ForkExplore.spotifyMixes.collectAsStateWithLifecycle()
    val offline by ForkLibraryPlaylists.offlineMode.collectAsStateWithLifecycle()
    val youtube by ForkExplore.youtubeOpen.collectAsStateWithLifecycle()
    val navidrome by ForkExplore.navidromeOpen.collectAsStateWithLifecycle()
    val spotify by ForkExplore.spotifyOpen.collectAsStateWithLifecycle()
    return ForkExploreState(
        genres = if (offline) emptyList() else all,
        mixes = if (offline) emptyList() else mixes,
        youtubeOpen = youtube,
        navidromeOpen = navidrome,
        spotifyOpen = spotify,
    )
}

/**
 * Above upstream's grid: the "Navidrome" panel, then the "YouTube" heading
 * (only when there is another panel). Tiles in rows of [columns], upstream's tile rows.
 */
internal fun LazyListScope.forkPanelsAbove(
    state: ForkExploreState,
    columns: Int,
    onClick: (MoodGenre) -> Unit,
) {
    panel("navidrome", "Navidrome", state.genres, state.navidromeOpen, ForkExplore.navidromeOpen, columns, onClick)
    if (!state.panels) return
    item(key = "fork:explore:youtube") {
        PanelHeading("YouTube", state.youtubeOpen) { ForkExplore.youtubeOpen.value = !state.youtubeOpen }
    }
}

/** Below upstream's grid: the "Spotify" panel. */
internal fun LazyListScope.forkPanelsBelow(
    state: ForkExploreState,
    columns: Int,
    onClick: (MoodGenre) -> Unit,
) {
    panel("spotify", "Spotify", state.mixes, state.spotifyOpen, ForkExplore.spotifyOpen, columns, onClick)
}

private fun LazyListScope.panel(
    key: String,
    title: String,
    tiles: List<MoodGenre>,
    open: Boolean,
    flag: MutableStateFlow<Boolean>,
    columns: Int,
    onClick: (MoodGenre) -> Unit,
) {
    if (tiles.isEmpty()) return
    item(key = "fork:explore:$key") { PanelHeading(title, open) { flag.value = !open } }
    if (!open) return
    items(tiles.chunked(columns), key = { "fork:$key:" + it.first().browseId }) { MoodGenreRow(row = it, columns = columns, onCategoryClick = onClick) }
}

@Composable
internal fun PanelHeading(title: String, open: Boolean, onToggle: () -> Unit) { // also Home's (ForkHome)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
