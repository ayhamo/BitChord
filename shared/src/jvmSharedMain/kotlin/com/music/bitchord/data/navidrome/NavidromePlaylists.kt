package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState

/**
 * The Navidrome server's playlists as pages (fork-only), reached from the
 * Home shelf ([NavidromeHome]).
 *
 * A playlist's page id is `local:group:playlist:navidrome:<id>`. Under
 * `local:` like every page whose songs are not YouTube's, which keeps the
 * download button and YouTube's lookups off it; under `local:group:` so it is
 * drawn as a plain track listing rather than a folder with tabs; and with a
 * kind (`playlist`) that `LocalGroups` doesn't parse, so it is never taken for
 * an album or artist grouped out of a folder. The view model's page loader
 * asks [pageState] first.
 */
object NavidromePlaylists {

    const val PREFIX = "local:group:playlist:navidrome:"

    fun pageId(playlistId: String): String = PREFIX + playlistId

    /** The page's songs in the playlist's own order, or null when [browseId] isn't a Navidrome playlist. */
    suspend fun pageState(browseId: String, emptyText: () -> String): UiState<List<Song>>? {
        if (!browseId.startsWith(PREFIX)) return null
        val id = browseId.removePrefix(PREFIX)
        if (NavidromePlatform.offline.value) return NavidromePlatform.kept.pageState(browseId) ?: UiState.Error(emptyText())
        return runCatching {
            val client = NavidromeRepository.client()
            val rows = client.playlistSongs(id)
            NavidromeStars.seed(rows.filter { it.starred }.mapTo(HashSet()) { NavidromeSettings.ID_PREFIX + it.id })
            with(NavidromeRepository) { rows.map { it.toSong(client) } }
        }.fold(
            onSuccess = { songs -> if (songs.isEmpty()) UiState.Error(emptyText()) else UiState.Success(songs) },
            // No server (offline, unreachable): the kept copy, if the page is kept.
            onFailure = { if (it is kotlin.coroutines.cancellation.CancellationException) throw it // left the page: not an error
                NavidromePlatform.kept.pageState(browseId) ?: UiState.Error(it.message ?: emptyText()) },
        )
    }
}
