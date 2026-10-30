package com.music.bitchord.data.navidrome

import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.fork.ForkSpotifyMixes

/**
 * The fork's pages as one question, so `MainViewModel` asks once (fork-only):
 * the Navidrome playlist, album, artist, genre and radio pages, and which Explore
 * tiles open as pages rather than as a mood's shelves.
 */
object NavidromePages {

    /** The songs of the Navidrome page [browseId], or null when it isn't one; [text] reads a string. */
    suspend fun pageState(browseId: String, text: (Int) -> String): UiState<List<Song>>? =
        NavidromePlaylists.pageState(browseId) { text(R.string.navidrome_playlist_empty) }
            ?: NavidromeAlbums.pageState(browseId) { text(R.string.no_tracks_here) }
            ?: NavidromeArtists.pageState(browseId) { text(R.string.no_tracks_here) }
            ?: NavidromeGenres.pageState(browseId) { text(R.string.no_tracks_here) }
            ?: NavidromeRadio.pageState(browseId) { text(R.string.fork_radio_empty) }

    /** The source to credit when the Explore tile [browseId] opens as a page (a Spotify mix, a Navidrome genre), else null. */
    fun tileSource(browseId: String?): String? = when {
        ForkSpotifyMixes.isMix(browseId) -> "Spotify"
        NavidromeGenres.isGenre(browseId) -> "Navidrome"
        else -> null
    }
}
