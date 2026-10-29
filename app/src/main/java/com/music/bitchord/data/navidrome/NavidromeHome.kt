package com.music.bitchord.data.navidrome

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Album shelves from the Navidrome server at the top of Home (fork-only),
 * above YouTube's feed, which stays as it is below them.
 *
 * Kept in their own flow and laid over the feed by [onTop] rather than written
 * into it: the feed is rebuilt from YouTube alone on a pull-to-refresh, and
 * YouTube's "Recently played" is prepended whenever it arrives, either of
 * which would push these off or below. This way they stay first, and they
 * still show when YouTube is loading, failing or offline.
 *
 * Each card opens the library's own album page (the one its Albums tab opens).
 */
object NavidromeHome {

    private const val TAG = "BitChordNavidrome"
    private const val PER_SHELF = 20

    /** `getAlbumList2` types, in the order they are shown, with their headings. */
    private val SHELVES = listOf(
        "newest" to R.string.navidrome_home_newest,
        "recent" to R.string.navidrome_home_recent,
        "frequent" to R.string.navidrome_home_frequent,
        "random" to R.string.navidrome_home_random,
    )

    private val shelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    private val lock = Mutex()

    /**
     * Re-reads the shelves. A server that can't be reached keeps what was shown
     * rather than blanking it; one that is no longer configured clears them.
     */
    suspend fun load(context: Context) = lock.withLock {
        if (!NavidromeSettings.isConfigured()) {
            shelves.value = emptyList()
            return@withLock
        }
        val client = NavidromeRepository.client()
        val label = context.getString(R.string.navidrome)
        try {
            shelves.value = coroutineScope {
                val albums = SHELVES.map { (type, title) ->
                    async { HomeShelf(context.getString(title), client.albums(type, PER_SHELF).map { it.toItem(client) }, label) }
                }
                val playlists = async {
                    HomeShelf(context.getString(R.string.navidrome_home_playlists), client.playlists().map { it.toItem(client, context) }, label)
                }
                // Playlists second: after what is new, before what is played most.
                val loaded = albums.awaitAll()
                listOf(loaded.first(), playlists.await()) + loaded.drop(1)
            }.filter { it.items.isNotEmpty() }
        } catch (e: IOException) {
            Log.d(TAG, "home shelves: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "home shelves: ${e.message}")
        }
    }

    /** [youtube]'s feed with these shelves in front of it. */
    fun onTop(
        youtube: StateFlow<UiState<List<HomeShelf>>>,
        scope: CoroutineScope,
    ): StateFlow<UiState<List<HomeShelf>>> =
        combine(youtube, shelves) { feed, ours ->
            when {
                ours.isEmpty() -> feed
                feed is UiState.Success -> UiState.Success(ours + feed.data)
                // Loading or failed: the library is still worth showing meanwhile.
                else -> UiState.Success(ours)
            }
        }.stateIn(scope, SharingStarted.Eagerly, youtube.value)

    private fun NavidromeClient.PlaylistRow.toItem(client: NavidromeClient, context: Context) = ShelfItem(
        title = name,
        subtitle = songCount?.let { context.resources.getQuantityString(R.plurals.navidrome_playlist_songs, it, it) }.orEmpty(),
        thumbnailUrl = coverArt?.let { client.coverUrl(it) },
        videoId = null,
        browseId = NavidromePlaylists.pageId(id),
    )

    private fun NavidromeClient.AlbumRow.toItem(client: NavidromeClient) = ShelfItem(
        title = name,
        subtitle = artist.orEmpty(),
        thumbnailUrl = coverArt?.let { client.coverUrl(it) },
        videoId = null,
        browseId = LocalGroups.albumPageId(NavidromeSettings.BROWSE_ID, name),
    )
}
