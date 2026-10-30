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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.ui.components.PAGE_GUTTER
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: Explore in panels, "Navidrome" (the server's genres) and "YouTube"
 * (upstream's moods and genres), each folding away under its heading. A copy
 * of `:sharedUi`'s, which the phone draws, without its Spotify panel. The app
 * fills [navidromeGenres]; with none there are no headings at all, so Explore
 * is upstream's.
 */
object ForkExplore {
    val navidromeGenres = MutableStateFlow<List<MoodGenre>>(emptyList())
    val youtubeOpen = MutableStateFlow(true)
    val navidromeOpen = MutableStateFlow(true)
}

/** What the Explore list needs to know about the panels, read once per composition. */
internal class ForkExploreState(
    val genres: List<MoodGenre>,
    val youtubeOpen: Boolean,
    val navidromeOpen: Boolean,
) {
    val panels get() = genres.isNotEmpty()
}

@Composable
internal fun rememberForkExplore(): ForkExploreState {
    val all by ForkExplore.navidromeGenres.collectAsState()
    val offline by NavidromePlatform.offline.collectAsState()
    val youtube by ForkExplore.youtubeOpen.collectAsState()
    val navidrome by ForkExplore.navidromeOpen.collectAsState()
    return ForkExploreState(
        genres = if (offline) emptyList() else all,
        youtubeOpen = youtube,
        navidromeOpen = navidrome,
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
    if (!state.panels) return
    item(key = "fork:explore:navidrome") {
        PanelHeading("Navidrome", state.navidromeOpen) { ForkExplore.navidromeOpen.value = !state.navidromeOpen }
    }
    if (state.navidromeOpen) {
        items(state.genres.chunked(columns), key = { "fork:navidrome:" + it.first().browseId }) {
            MoodGenreRow(row = it, columns = columns, onCategoryClick = onClick)
        }
    }
    item(key = "fork:explore:youtube") {
        PanelHeading("YouTube", state.youtubeOpen) { ForkExplore.youtubeOpen.value = !state.youtubeOpen }
    }
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
