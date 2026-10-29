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
 * Fork: Explore in two panels, "YouTube" (upstream's moods and genres) and
 * "Navidrome" (the server's genres), each folding away under its heading.
 * The app fills [navidromeGenres]; with none (no server) there is no
 * Navidrome panel and no headings at all, so Explore is upstream's.
 */
object ForkExplore {
    val navidromeGenres = MutableStateFlow<List<MoodGenre>>(emptyList())
    val youtubeOpen = MutableStateFlow(true)
    val navidromeOpen = MutableStateFlow(true)
}

/** What the Explore list needs to know about the panels, read once per composition. */
internal class ForkExploreState(val genres: List<MoodGenre>, val youtubeOpen: Boolean, val navidromeOpen: Boolean) {
    val panels get() = genres.isNotEmpty()
}

@Composable
internal fun rememberForkExplore(): ForkExploreState {
    val all by ForkExplore.navidromeGenres.collectAsStateWithLifecycle()
    val offline by ForkLibraryPlaylists.offlineMode.collectAsStateWithLifecycle()
    val genres = if (offline) emptyList() else all
    val youtube by ForkExplore.youtubeOpen.collectAsStateWithLifecycle()
    val navidrome by ForkExplore.navidromeOpen.collectAsStateWithLifecycle()
    return ForkExploreState(genres, youtube, navidrome)
}

/** The "YouTube" heading, above upstream's grid; only when there is a Navidrome panel too. */
internal fun LazyListScope.forkYouTubeHeading(state: ForkExploreState) {
    if (!state.panels) return
    item(key = "fork:explore:youtube") {
        PanelHeading("YouTube", state.youtubeOpen) { ForkExplore.youtubeOpen.value = !state.youtubeOpen }
    }
}

/** The "Navidrome" heading and, open, the genre tiles in rows of [columns], drawn by [row] (upstream's tile row). */
internal fun LazyListScope.forkNavidromePanel(
    state: ForkExploreState,
    columns: Int,
    row: @Composable (List<MoodGenre>) -> Unit,
) {
    if (!state.panels) return
    item(key = "fork:explore:navidrome") {
        PanelHeading("Navidrome", state.navidromeOpen) { ForkExplore.navidromeOpen.value = !state.navidromeOpen }
    }
    if (!state.navidromeOpen) return
    items(state.genres.chunked(columns), key = { "fork:genre:" + it.first().browseId }) { tiles -> row(tiles) }
}

@Composable
private fun PanelHeading(title: String, open: Boolean, onToggle: () -> Unit) {
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
