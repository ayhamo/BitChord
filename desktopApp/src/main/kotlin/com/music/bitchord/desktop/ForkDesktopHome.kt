package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.ForkExplore
import com.music.bitchord.ui.screens.ForkHome
import com.music.bitchord.ui.screens.ForkHomeShelves
import com.music.bitchord.ui.screens.ForkLibraryPlaylists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Fork: the PC's Home in the phone's sections (`NavidromeHome.onTop`): "Navidrome" (the server's
 * shelves, or what is kept offline), "YouTube" (upstream's feed, which `DesktopApp` keeps in its own
 * `homeState`) and "Spotify" (the account's mixes), each folding under its heading ([ForkHome]).
 *
 * The fork's shelves are held here rather than written into the feed: the feed is rebuilt from YouTube
 * on every reload and its Recently Played is put in front whenever it lands, which pushed the server's
 * shelves off the top; and the sections need to know how many of each there are.
 * [load] also fills the Library's server rows ([ForkLibraryPlaylists.shelves]) and Explore's Spotify
 * tiles ([ForkExplore.spotifyMixes]), as the phone's Home load does.
 */
internal object ForkDesktopHome {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MutableStateFlow<List<HomeShelf>>(emptyList())
    private val mixes = MutableStateFlow<HomeShelf?>(null)
    private var job: Job? = null

    /**
     * Re-reads the server's shelves and Spotify's mixes, as Home loads (it runs again on a new login,
     * offline changing, "Navidrome on Home" and the Spotify sign-in). A server that can't be reached
     * keeps what was shown; offline nothing goes out.
     */
    fun load() {
        job?.cancel()
        job = scope.launch {
            launch {
                try {
                    ForkNavidrome.serverShelves()?.let { server.value = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DesktopTrackLog.log("navidrome: home shelves: ${e.message}")
                }
            }
            launch {
                if (ForkOffline.offline.value) return@launch // what is there stays, hidden while offline
                val found = try {
                    ForkSpotifyHome.mixes()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DesktopTrackLog.log("spotify: mixes: ${e.message}")
                    emptyList()
                }
                mixes.value = ForkSpotifyHome.shelf(found)
                ForkExplore.spotifyMixes.value = ForkSpotifyHome.tiles(found) // Explore hides them offline itself
            }
        }
    }

    /** [feed], YouTube's Home, with the fork's sections around it, for the shared `HomeScreen`. */
    @Composable
    fun present(feed: UiState<List<HomeShelf>>): UiState<List<HomeShelf>> {
        val serverShelves by server.collectAsState()
        val kept by ForkLibraryPlaylists.offline.collectAsState()
        val shown by ForkNavidromeSettings.homeShelves.collectAsState()
        val offline by ForkOffline.offline.collectAsState()
        val spotify by mixes.collectAsState()
        val navidrome = remember(serverShelves, kept, shown, offline) { navidromeShelves(serverShelves, kept, shown, offline) }
        val tail = remember(spotify, offline) { if (offline) emptyList() else listOfNotNull(spotify) }
        // The sizes also travel in the list (ForkHomeShelves); these are for a Home with no sections.
        SideEffect {
            ForkHome.navidromeShelves.value = navidrome.size
            ForkHome.spotifyShelves.value = tail.size
        }
        return remember(feed, navidrome, tail) { ForkHomeShelves.merge(feed, navidrome, tail) }
    }

    /**
     * The "Navidrome" section's shelves: none with "Navidrome on Home" off; offline, what is kept on
     * this computer in place of the server's (the phone's rule).
     */
    internal fun navidromeShelves(server: List<HomeShelf>, kept: HomeShelf?, shown: Boolean, offline: Boolean): List<HomeShelf> = when {
        !shown -> emptyList()
        offline -> listOfNotNull(kept?.takeIf { it.items.isNotEmpty() })
        else -> server
    }
}
