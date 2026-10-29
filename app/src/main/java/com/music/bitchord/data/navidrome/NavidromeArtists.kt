package com.music.bitchord.data.navidrome

import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState

/**
 * A Navidrome artist page (`local:group:artist:navidrome:<artist>`) read
 * straight off the server (fork-only), like [NavidromeAlbums].
 *
 * The page groups the library by artist tag, which upstream fills by listing
 * the whole library (~2,250 songs, several requests). Here one `search3` for
 * the name is asked (paged when an artist has more than [PAGE] songs) and only
 * the songs whose artist tag is exactly that name are kept, in title order as
 * the grouping had them. Nothing found (a name the search can't match): null,
 * and the library listing answers as before.
 */
object NavidromeArtists {

    val PREFIX = LocalGroups.artistPageId(NavidromeSettings.BROWSE_ID, "")

    private const val PAGE = 500

    suspend fun pageState(browseId: String, emptyText: () -> String): UiState<List<Song>>? {
        if (!browseId.startsWith(PREFIX) || !NavidromeSettings.isConfigured()) return null
        // Offline mode: the library listing (the kept songs) answers.
        if (com.music.bitchord.fork.ForkNetwork.offline.value) return null
        val name = browseId.removePrefix(PREFIX)
        return runCatching {
            val client = NavidromeRepository.client()
            val rows = buildList {
                var offset = 0
                while (true) {
                    val page = client.searchSongs(name, PAGE, offset)
                    addAll(page.filter { it.artist == name })
                    if (page.size < PAGE) break
                    offset += PAGE
                }
            }
            if (rows.isEmpty()) return null
            NavidromeStars.seed(rows.filter { it.starred }.mapTo(HashSet()) { NavidromeSettings.ID_PREFIX + it.id })
            with(NavidromeRepository) { rows.map { it.toSong(client) } }.sortedBy { it.title.lowercase() }
        }.fold(
            onSuccess = { songs -> UiState.Success(songs) },
            onFailure = { UiState.Error(it.message ?: emptyText()) },
        )
    }
}
