package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.components.contextClick

/**
 * Fork: the phone's "Recent Album / Single / EP" card at the top of an artist page
 * (`DetailScreen`'s `TopReleaseCard`, `topRelease()`, `recentLabel()`): the artist's newest release
 * pulled out of the shelves below as a card of its own, between the Play row and the rest of the
 * page. The shelves still list it.
 *
 * Its add button is the album page's own library circle ([ForkLibrarySaveButton]: signed in only,
 * read off the album once, as the phone's `onLoadReleaseLibrary` reads it), and a right-click opens
 * the Home card's release menu ([ForkCardMenuHost]), the phone's long-press on the card.
 */
internal object ForkArtistTopRelease {

    private val RELEASE_YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val EP = Regex("""\bep\b""")

    /**
     * The newest release on an artist's shelves: an album, or a single or EP that came out after
     * it. Playlists, videos and related artists share these shelves and are skipped, as only an
     * `MPRE…` browse id is a release. Newest by the year in the card's subtitle, which is all a
     * shelf item says about when; ties and releases with no year keep YouTube's own order, which
     * lists albums first.
     */
    fun pick(sections: List<HomeShelf>): ShelfItem? =
        sections.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.browseId?.startsWith("MPRE") == true }
            .withIndex()
            .maxWithOrNull(
                compareBy<IndexedValue<ShelfItem>> { releaseYear(it.value) ?: 0 }
                    .thenByDescending { it.index },
            )?.value

    fun releaseYear(item: ShelfItem): Int? = RELEASE_YEAR.find(item.subtitle)?.value?.toIntOrNull()

    /** "Recent Single" and "Recent EP" where the card says so, otherwise "Recent Album": the string's key. */
    fun labelKey(item: ShelfItem): String {
        val kind = item.subtitle.lowercase()
        return when {
            kind.contains("single") -> "recent_single"
            EP.containsMatchIn(kind) -> "recent_ep"
            else -> "recent_album"
        }
    }

    fun label(item: ShelfItem): String = when (labelKey(item)) {
        "recent_single" -> DesktopStrings["recent_single", "Recent Single"]
        "recent_ep" -> DesktopStrings["recent_ep", "Recent EP"]
        else -> DesktopStrings["recent_album", "Recent Album"]
    }
}

/** The card, after the artist page's Play row; nothing when the shelves hold no release. */
internal fun LazyListScope.forkTopRelease(sections: List<HomeShelf>, onOpen: (ShelfItem, String?) -> Unit) {
    val release = ForkArtistTopRelease.pick(sections) ?: return
    item(key = "fork-top-release") {
        ForkCardMenuHost { openMenu ->
            ForkTopReleaseCard(release, onClick = { onOpen(release, null) }, onMenu = { openMenu(release) })
        }
    }
}

/**
 * Proportioned off the phone's (Apple Music's): a 24 dp corner, a 96 dp sleeve 12 dp in, the text
 * 12 dp after it, on a light veil with a light hairline, since Apple's containers read slightly
 * white against the page and a dark panel reads as a hole. Held to a phone-like width: across a
 * wide window it would be a strip with a stamp at one end.
 */
@Composable
private fun ForkTopReleaseCard(item: ShelfItem, onClick: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val coverShape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(start = DesktopPageGutter, end = DesktopPageGutter, top = 10.dp, bottom = 12.dp)
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .contextClick(onMenu)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopArtwork(
            item.thumbnailUrl,
            Modifier.size(96.dp).clip(coverShape).border(1.dp, Color.White.copy(alpha = 0.10f), coverShape),
            px = 300,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val secondary = Color.White.copy(alpha = 0.55f)
            Text(item.subtitle, color = secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(ForkArtistTopRelease.label(item), color = secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        item.browseId?.let { id ->
            Spacer(Modifier.width(10.dp))
            ForkLibrarySaveButton(id)
        }
    }
}
