package com.music.bitchord.data.navidrome

import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import java.util.concurrent.ConcurrentHashMap

/**
 * A Navidrome album page (`local:group:album:navidrome:<album name>`) read
 * straight off the server (fork-only).
 *
 * The page id is the library's own album grouping (by album tag), which the
 * upstream loader fills by listing the whole library (~2,250 songs, several
 * requests) and filtering it. Opened from the player, a menu or Home, that
 * was a wait of seconds for one album. Here the album's server id is looked up
 * (remembered from any song or Home card that carried it, else a `search3` for
 * the name) and only `getAlbum` is asked. Two albums sharing a name (different
 * artists) are both listed, as the grouping did.
 *
 * The page's cover is the album's own: callers open the page with the cover of
 * the song they came from, and with Navidrome every song has its own cover.
 * The view model's page loader asks [pageState] first and takes [header].
 */
object NavidromeAlbums {

    val PREFIX = LocalGroups.albumPageId(NavidromeSettings.BROWSE_ID, "")

    /** Album server ids by album name, from songs and cards seen this session. */
    private val ids = ConcurrentHashMap<String, MutableSet<String>>()

    /** The cover and artist line each opened page settled on, by page id. */
    data class Header(val cover: String?, val artist: String?)
    private val headers = ConcurrentHashMap<String, Header>()

    fun remember(name: String?, albumId: String?) {
        if (name.isNullOrBlank() || albumId.isNullOrBlank()) return
        ids.getOrPut(name) { ConcurrentHashMap.newKeySet() }.add(albumId)
    }

    /** The album's songs, or null when [browseId] isn't a Navidrome album page or the server has no such album (the library listing answers then). */
    suspend fun pageState(browseId: String, emptyText: () -> String): UiState<List<Song>>? {
        if (!browseId.startsWith(PREFIX) || !NavidromeSettings.isConfigured()) return null
        // Offline mode: the kept copy, or the library listing (the kept songs) groups the album.
        if (com.music.bitchord.fork.ForkNetwork.offline.value) return NavidromeOffline.pageState(browseId)
        val name = browseId.removePrefix(PREFIX)
        return runCatching {
            val client = NavidromeRepository.client()
            val albumIds = ids[name]?.toList()
                ?: client.searchAlbums(name).filter { it.name == name }.map { it.id }
                    .onEach { remember(name, it) }
            if (albumIds.isEmpty()) return null
            val albums = albumIds.map { client.album(it) }
            headers[browseId] = Header(
                cover = albums.firstNotNullOfOrNull { it.first?.coverArt }?.let { client.coverUrl(it) },
                artist = albums.mapNotNull { it.first?.artist }.distinct().joinToString(", ").ifBlank { null },
            )
            val rows = albums.flatMap { it.second }
            NavidromeStars.seed(rows.filter { it.starred }.mapTo(HashSet()) { NavidromeSettings.ID_PREFIX + it.id })
            with(NavidromeRepository) { rows.map { it.toSong(client) } }
        }.fold(
            onSuccess = { songs -> if (songs.isEmpty()) UiState.Error(emptyText()) else UiState.Success(songs) },
            // No server (offline, unreachable): the kept copy, if the page is kept.
            onFailure = { NavidromeOffline.pageState(browseId) ?: UiState.Error(it.message ?: emptyText()) },
        )
    }

    /** The album's own cover and artist for a page [pageState] loaded, or null. */
    fun header(browseId: String): Header? = headers[browseId]
}
