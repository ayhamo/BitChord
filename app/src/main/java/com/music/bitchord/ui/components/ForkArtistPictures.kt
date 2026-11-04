package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.navidrome.NavidromeArtistInfo
import com.music.bitchord.data.navidrome.NavidromeArtistPictures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fork: the phone's side of [NavidromeArtistPictures]: a Navidrome artist's picture on their page's
 * header, on their row and card in the library's Artists tab, and on the "Similar artists" cards.
 *
 * A Navidrome artist page is a tag grouping with no picture of its own (`LocalGroups.artist` has
 * none), so it opened on the person placeholder; the Artists tab drew the artist with one of their
 * songs' sleeves. Both now take the artist's picture once it is known, and ask for it (one lookup
 * at a time, kept for good) when it isn't. Every other page and row is left as upstream draws it.
 */
object ForkArtistPictures {

    /** How long the page header waits for the page's songs (Apple's proof) before asking without them. */
    private const val SONGS_WAIT_MS = 20_000L

    /**
     * For `MainViewModel.openDetail` / `openLocalGroup`, right after the page is pushed: a Navidrome
     * artist page [browseId] takes the artist's picture as its [DetailPage.thumbnailUrl] (the
     * header's sleeve and the page's colours), at once when it is known, else when the lookup
     * lands. What the caller passed (a Replay photo, a "Similar artists" card's server picture)
     * stands until then. Any other page: nothing.
     */
    fun fill(scope: CoroutineScope, browseId: String, stack: MutableStateFlow<List<DetailPage>>) {
        val name = NavidromeArtistInfo.nameOf(browseId) ?: return
        fun show(url: String) {
            stack.value = stack.value.map { if (it.browseId == browseId && it.thumbnailUrl != url) it.copy(thumbnailUrl = url) else it }
        }
        NavidromeArtistPictures.cached(name)?.let { show(it); return }
        if (NavidromeArtistPictures.settled(name)) return // a recent "none": the placeholder stays
        scope.launch {
            // The page's songs are Apple's proof that a same-named artist there is this one.
            val page = withTimeoutOrNull(SONGS_WAIT_MS) {
                stack.map { pages -> pages.firstOrNull { it.browseId == browseId } }
                    .first { it == null || it.songs !is UiState.Loading }
            }
            if (page == null && stack.value.none { it.browseId == browseId }) return@launch // closed meanwhile
            val songs = when (val state = page?.songs) {
                is UiState.Success -> state.data
                else -> emptyList()
            }
            NavidromeArtistPictures.pictureFor(name) { titlesOf(songs) }?.let(::show)
        }
    }

    /** Titles that prove an Apple artist is this one: the songs' and their albums'. */
    fun titlesOf(songs: List<Song>): List<String> = songs.map { it.title } + songs.mapNotNull { it.albumName }

    /** [shelf] with each card's picture swapped for the artist's known one (a server placeholder for a real photo). */
    fun withPictures(shelf: HomeShelf): HomeShelf =
        shelf.copy(items = shelf.items.map { item -> NavidromeArtistPictures.cached(item.title)?.let { item.copy(thumbnailUrl = it) } ?: item })
}

/**
 * The picture of the Navidrome artist whose page is [browseId] (an Artists tab row or card), or null
 * for any other grouping and while nothing is known; asked for when nothing is known yet, and given
 * up when the row leaves the screen. [songs] are the artist's, Apple's proof.
 */
@Composable
fun forkArtistPicture(browseId: String?, songs: List<Song>): String? {
    val name = NavidromeArtistInfo.nameOf(browseId) ?: return null
    val updates by NavidromeArtistPictures.updates.collectAsStateWithLifecycle()
    val current by rememberUpdatedState(songs)
    // Asked once per name, not restarted by [NavidromeArtistPictures.updates]: every answer (the
    // background pass's, every 5 s) ticks it, and a restart cancelled the row's lookup in flight.
    LaunchedEffect(name) {
        if (!NavidromeArtistPictures.settled(name)) NavidromeArtistPictures.pictureFor(name) { ForkArtistPictures.titlesOf(current) }
    }
    // Read again on each tick: the answer lands through it (and a learned placeholder drops one).
    return remember(name, updates) { NavidromeArtistPictures.cached(name) }
}
