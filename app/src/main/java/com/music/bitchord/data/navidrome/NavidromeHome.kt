package com.music.bitchord.data.navidrome

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.ForkLibraryPlaylists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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

    /** Subsonic's largest `getAlbumList2` page. */
    private const val ALL_ALBUMS = 500

    private const val BACK_ONLINE_WAIT_MS = 2_000L

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
    suspend fun load(context: Context): Unit = coroutineScope {
        // Offline mode: what is shown stays, nothing goes out (a server just disconnected still clears its shelves).
        if (com.music.bitchord.fork.ForkNetwork.offline.value) {
            if (!NavidromeSettings.isConfigured()) lock.withLock { loadServer(context) }
            return@coroutineScope
        }
        // The Spotify mixes load with Home too, server or not (they have their own section),
        // alongside the server's shelves rather than ahead of them.
        spotifyTitle = context.getString(R.string.fork_spotify_mixes)
        launch {
            try {
                com.music.bitchord.fork.ForkSpotifyMixes.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "spotify mixes: ${e.message}")
            }
        }
        lock.withLock { loadServer(context) }
    }

    private suspend fun loadServer(context: Context) {
        if (!NavidromeSettings.isConfigured()) {
            shelves.value = emptyList()
            ForkLibraryPlaylists.shelves.value = emptyList()
            NavidromeGenres.clear()
            return
        }
        val client = NavidromeRepository.client()
        val label = context.getString(R.string.navidrome)
        try {
            shelves.value = coroutineScope {
                val albums = SHELVES.map { (type, title) ->
                    async { HomeShelf(context.getString(title), client.albums(type, PER_SHELF).map { it.toItem(client) }.distinctBy { it.browseId }, label) }
                }
                val playlists = async {
                    HomeShelf(context.getString(R.string.navidrome_home_playlists), client.playlists().map { it.toItem(client, context) }, label)
                }
                // Every album, A to Z, for the Library's albums row (not shown on Home).
                val allAlbums = async {
                    HomeShelf(
                        context.getString(R.string.fork_library_navidrome_albums),
                        client.albums("alphabeticalByName", ALL_ALBUMS).map { it.toItem(client) }.distinctBy { it.browseId },
                        label,
                    )
                }
                // Playlists second: after what is new, before what is played most.
                val loaded = albums.awaitAll()
                val playlistShelf = playlists.await()
                // The albums and the same playlists as Library rows.
                ForkLibraryPlaylists.shelves.value = listOf(
                    allAlbums.await(),
                    playlistShelf.copy(title = context.getString(R.string.fork_library_navidrome_playlists)),
                )
                listOf(loaded.first(), playlistShelf) + loaded.drop(1)
            }.filter { it.items.isNotEmpty() }
        } catch (e: IOException) {
            Log.d(TAG, "home shelves: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "home shelves: ${e.message}")
        }
        // The stars too: otherwise only a page listing a song says it is starred, and the song
        // restored at start shows an empty star until one is opened.
        try {
            NavidromeStars.seed(client.starredSongIds().mapTo(HashSet()) { NavidromeSettings.ID_PREFIX + it })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "stars: ${e.message}")
        }
        // The Explore panel's genres, after the shelves are up (a failure there leaves them alone).
        try {
            NavidromeGenres.load(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "genres: ${e.message}")
        }
        NavidromeOffline.syncPlaylists() // kept playlists follow the server
    }

    /**
     * The Navidrome page ([reload]) and Home shelves follow the server settings, once per save.
     * Back online (a launch with no network, a drop, offline mode off), the page and [backOnline]
     * run once: nothing else asks again, and Home stayed without its Navidrome section until a
     * relaunch. After a short wait, because a network that just came up can't always reach the
     * server yet, and a flapping one should only count once.
     */
    fun follow(scope: CoroutineScope, context: Context, backOnline: () -> Unit, reload: () -> Unit) {
        scope.launch {
            NavidromeSettings.saved.drop(1).collect {
                reload()
                launch { load(context) }
            }
        }
        scope.launch {
            com.music.bitchord.fork.ForkNetwork.offline.drop(1).collectLatest { offline ->
                if (!offline) {
                    delay(BACK_ONLINE_WAIT_MS)
                    reload()
                    backOnline()
                }
            }
        }
    }

    /** Home's "Made for you" shelf title, set as Home loads. */
    @Volatile private var spotifyTitle = "Made for you"

    /** [youtube]'s feed with these shelves in front of it and the Spotify mixes after it. */
    fun onTop(
        youtube: StateFlow<UiState<List<HomeShelf>>>,
        scope: CoroutineScope,
    ): StateFlow<UiState<List<HomeShelf>>> =
        combine(
            youtube,
            combine(shelves, ForkLibraryPlaylists.offline, ::Pair),
            com.music.bitchord.fork.ForkSettings.navidromeHomeShelves,
            com.music.bitchord.fork.ForkNetwork.offline,
            com.music.bitchord.fork.ForkSpotifyMixes.mixes,
        ) { feed, (server, kept), shown, offline, _ ->
            // Offline mode: what is kept on the phone, in place of the server's shelves.
            val navidrome = if (!shown) emptyList() else if (offline) listOfNotNull(kept?.takeIf { it.items.isNotEmpty() }) else server
            val spotify = if (offline) emptyList() else listOfNotNull(com.music.bitchord.fork.ForkSpotifyMixes.shelf(spotifyTitle))
            // The server's shelves lead the feed; the Spotify mixes come after its end (Navidrome, YouTube, Spotify).
            // Home's "Navidrome" section is the first this many shelves and "Spotify" the last (ForkHome); none, no sections.
            com.music.bitchord.ui.screens.ForkHome.navidromeShelves.value = navidrome.size
            com.music.bitchord.ui.screens.ForkHome.spotifyShelves.value = spotify.size
            when {
                navidrome.isEmpty() && spotify.isEmpty() -> feed
                feed is UiState.Success -> UiState.Success(navidrome + feed.data + spotify)
                // Loading or failed: the library is still worth showing meanwhile.
                else -> UiState.Success(navidrome + spotify)
            }
        }.stateIn(scope, SharingStarted.Eagerly, youtube.value)

    private fun NavidromeClient.PlaylistRow.toItem(client: NavidromeClient, context: Context) = ShelfItem(
        title = name,
        subtitle = songCount?.let { context.resources.getQuantityString(R.plurals.navidrome_playlist_songs, it, it) }.orEmpty(),
        thumbnailUrl = coverArt?.let { client.coverUrl(it) },
        videoId = null,
        browseId = NavidromePlaylists.pageId(id),
    )

    private fun NavidromeClient.AlbumRow.toItem(client: NavidromeClient): ShelfItem {
        NavidromeAlbums.remember(name, id) // so its album page opens with one request
        return ShelfItem(
            title = name,
            subtitle = artist.orEmpty(),
            thumbnailUrl = coverArt?.let { client.coverUrl(it) },
            videoId = null,
            browseId = LocalGroups.albumPageId(NavidromeSettings.BROWSE_ID, name),
        )
    }
}
