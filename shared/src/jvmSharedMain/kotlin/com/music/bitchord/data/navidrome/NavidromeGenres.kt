package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The server's genres on Explore (fork-only): the Navidrome panel's tiles,
 * each opening a page of that genre's songs. Each app draws its own panel, so
 * the tiles go out through [NavidromePlatform.onGenres].
 *
 * A tile is a [MoodGenre] whose browse id is a page id here,
 * `local:group:genre:navidrome:<genre>` (under `local:group:` like the
 * playlists, with a kind `LocalGroups` doesn't parse), so the app opens it as
 * a page ([pageState]) instead of asking YouTube for a mood.
 */
object NavidromeGenres {

    const val PREFIX = "local:group:genre:navidrome:"
    private const val TILES = 30
    private const val PAGE = 500

    fun isGenre(browseId: String?): Boolean = browseId?.startsWith(PREFIX) == true

    /** Fills the Explore panel: the biggest genres, each with one of its albums' covers. */
    suspend fun load(client: NavidromeClient) = coroutineScope {
        val genres = client.genres().filter { it.songCount > 0 }.sortedByDescending { it.songCount }.take(TILES)
        NavidromePlatform.onGenres(
            genres.map { genre ->
                async {
                    val cover = runCatching { client.albumsByGenre(genre.name, 1).firstOrNull()?.coverArt }.getOrNull()
                    MoodGenre(title = genre.name, browseId = PREFIX + genre.name, params = null, thumbnailUrl = cover?.let { client.coverUrl(it) })
                }
            }.awaitAll(),
        )
    }

    fun clear() {
        NavidromePlatform.onGenres(emptyList())
    }

    /** A genre page's songs, or null when [browseId] isn't one. */
    suspend fun pageState(browseId: String, emptyText: () -> String): UiState<List<Song>>? {
        if (!isGenre(browseId)) return null
        val genre = browseId.removePrefix(PREFIX)
        if (NavidromePlatform.offline.value) return UiState.Error(emptyText())
        return runCatching {
            val client = NavidromeRepository.client()
            val rows = buildList {
                var offset = 0
                while (true) {
                    val page = client.songsByGenre(genre, PAGE, offset)
                    addAll(page)
                    if (page.size < PAGE) break
                    offset += PAGE
                }
            }
            NavidromeStars.seed(rows.filter { it.starred }.mapTo(HashSet()) { NavidromeSettings.ID_PREFIX + it.id })
            with(NavidromeRepository) { rows.map { it.toSong(client) } }
        }.fold(
            onSuccess = { songs -> if (songs.isEmpty()) UiState.Error(emptyText()) else UiState.Success(songs) },
            onFailure = { if (it is kotlin.coroutines.cancellation.CancellationException) throw it // left the page: not an error
                UiState.Error(it.message ?: emptyText()) },
        )
    }
}
