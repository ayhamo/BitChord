package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.spotify.ForkSpotifyMadeForYou

/**
 * Fork: Spotify's mixes on the desktop's Home, the phone's "Made for you" shelf. Each opens as the
 * desktop's own Spotify playlist page, which finds every track on YouTube Music, as on the phone.
 */
internal object ForkSpotifyHome {

    private const val TITLE = "Made for you"

    /** The shelf, or null when signed out of Spotify or Spotify has none. */
    suspend fun shelf(): HomeShelf? {
        if (DesktopSpotifyToken.cookie().isBlank()) return null
        val mixes = ForkSpotifyMadeForYou.mixes().takeIf { it.isNotEmpty() } ?: return null
        return HomeShelf(TITLE, mixes.map { it.toShelfItem().copy(subtitle = SPOTIFY_SHELF) }, SPOTIFY_SHELF)
    }

    /** The feed with the mixes after everything else, however late YouTube's pages land: Navidrome, YouTube, Spotify. */
    fun keepLast(shelves: List<HomeShelf>): List<HomeShelf> = shelves.sortedBy { it.title == TITLE }
}
