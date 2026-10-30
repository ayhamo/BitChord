package com.music.bitchord.fork

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.ForkSpotifyMadeForYou
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spotify's mixes for the signed-in account (fork-only), read by
 * [ForkSpotifyMadeForYou], as a "Made for you" shelf on Home and tiles on Explore.
 *
 * Each opens as upstream's Spotify playlist page, which finds every track's
 * YouTube Music counterpart ([com.music.bitchord.data.spotify.SpotifyImporter.matchTrack]),
 * so playback is the usual YouTube path, quality upgrades from the sources
 * included. Nothing here plays or reports anything to Spotify.
 */
object ForkSpotifyMixes {

    private val _mixes = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val mixes: StateFlow<List<SpotifyPlaylist>> = _mixes.asStateFlow()

    /** Re-reads the mixes; signed out (or any failure) leaves none. Called as Home loads. */
    suspend fun load() {
        if (AppSettings.spotifySpdcToken.value.isBlank()) {
            publish(emptyList())
            return
        }
        publish(ForkSpotifyMadeForYou.mixes())
    }

    /** The mixes as Home's "Made for you" shelf, or null with none. */
    fun shelf(title: String): HomeShelf? = _mixes.value.takeIf { it.isNotEmpty() }?.let { list ->
        HomeShelf(
            title,
            list.map { ShelfItem(title = it.name, subtitle = "Spotify", thumbnailUrl = it.imageUrl, videoId = null, browseId = SPOTIFY_PAGE_PREFIX + it.id) },
            "Spotify",
        )
    }

    fun isMix(browseId: String?): Boolean = browseId?.startsWith(SPOTIFY_PAGE_PREFIX) == true

    private fun publish(list: List<SpotifyPlaylist>) {
        _mixes.value = list
        com.music.bitchord.ui.screens.ForkExplore.spotifyMixes.value = list.map {
            MoodGenre(title = it.name, browseId = SPOTIFY_PAGE_PREFIX + it.id, params = null, thumbnailUrl = it.imageUrl)
        }
    }
}
