package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.spotify.ForkSpotifyMadeForYou
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyPlaylist

/**
 * Fork: Spotify's mixes on the desktop, the phone's `ForkSpotifyMixes`: a "Made for you" shelf
 * closing Home and tiles in Explore's "Spotify" panel. Each opens as the desktop's own Spotify
 * playlist page, which finds every track on YouTube Music, as on the phone.
 */
internal object ForkSpotifyHome {

    private const val TITLE = "Made for you"

    /** The account's mixes; none when signed out of Spotify. */
    suspend fun mixes(): List<SpotifyPlaylist> =
        if (DesktopSpotifyToken.cookie().isBlank()) emptyList() else ForkSpotifyMadeForYou.mixes()

    /** [mixes] as Home's shelf, or null with none. */
    fun shelf(mixes: List<SpotifyPlaylist>): HomeShelf? = mixes.takeIf { it.isNotEmpty() }?.let { list ->
        HomeShelf(TITLE, list.map { it.toShelfItem().copy(subtitle = SPOTIFY_SHELF) }, SPOTIFY_SHELF)
    }

    /** [mixes] as Explore's tiles, as the phone builds them. */
    fun tiles(mixes: List<SpotifyPlaylist>): List<MoodGenre> =
        mixes.map { MoodGenre(title = it.name, browseId = SPOTIFY_PAGE_PREFIX + it.id, params = null, thumbnailUrl = it.imageUrl) }

    fun isMix(browseId: String): Boolean = browseId.startsWith(SPOTIFY_PAGE_PREFIX)
}
