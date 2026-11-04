package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeArtistInfo
import com.music.bitchord.data.navidrome.NavidromeArtistPictures

/**
 * Fork: the PC's side of [NavidromeArtistPictures] (shared with the phone, which it follows): a
 * Navidrome artist page's square header takes the artist's picture instead of the first song's
 * album cover, and the "Similar artists" cards the artist's picture instead of the server's
 * placeholder. Asked once per artist (one lookup at a time, kept in the
 * `com/music/bitchord/navidrome_artist_pictures` preferences, not in Export data); the background
 * pass over the library starts after Home's server shelves load ([ForkNavidrome.serverShelves]).
 */
internal object ForkArtistPictures {

    /** Titles that prove an Apple artist is this one: the songs' and their albums'. */
    fun titlesOf(songs: List<Song>): List<String> = songs.map { it.title } + songs.mapNotNull { it.albumName }

    /** [shelf] with each card's picture swapped for the artist's known one. */
    fun withPictures(shelf: HomeShelf): HomeShelf =
        shelf.copy(items = shelf.items.map { item -> NavidromeArtistPictures.cached(item.title)?.let { item.copy(thumbnailUrl = it) } ?: item })
}

/**
 * The picture of the Navidrome artist whose page is [browseId], or null for any other page and
 * while none is known (the caller's own artwork shows then); asked for when nothing is known yet.
 * [songs] are the page's, Apple's proof.
 */
@Composable
internal fun rememberForkArtistPicture(browseId: String, songs: List<Song>): String? {
    val name = NavidromeArtistInfo.nameOf(browseId) ?: return null
    val updates by NavidromeArtistPictures.updates.collectAsState()
    val current by rememberUpdatedState(songs)
    // The ask is keyed by the name alone: keyed by [updates] too, every answer the background pass
    // lands (one per 5 s) cancelled this lookup mid-request and started it again. An answer ticks
    // [updates], which reads the cache again below.
    LaunchedEffect(name) {
        if (!NavidromeArtistPictures.settled(name)) {
            NavidromeArtistPictures.pictureFor(name) { ForkArtistPictures.titlesOf(current) } // none yet: the server's, read by the lookup
        }
    }
    return remember(name, updates) { NavidromeArtistPictures.cached(name) }
}
