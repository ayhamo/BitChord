package com.music.bitchord.desktop

import com.music.bitchord.data.navidrome.NavidromeGenres
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.screens.ForkExplore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Fork: Explore's "Navidrome" tiles. The phone reads them along with its Home shelves; here they
 * follow the login and offline on their own, because Home's load stops short when YouTube Music
 * is disabled, and it runs for other reasons too (a Spotify sign-in), each of which would be one
 * more cover request per genre.
 */
internal object ForkNavidromeGenres {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install() {
        NavidromePlatform.onGenres = { ForkExplore.navidromeGenres.value = it }
        scope.launch {
            combine(NavidromeSettings.saved, ForkOffline.offline) { _, offline -> offline }.collectLatest { offline ->
                when {
                    !NavidromeSettings.isConfigured() -> NavidromeGenres.clear()
                    // What is there stays, hidden by the panel; nothing goes out. A failure below leaves them alone too.
                    offline -> Unit
                    else -> try {
                        NavidromeGenres.load(NavidromeRepository.client())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        DesktopTrackLog.log("navidrome: genres: ${failure.message}")
                    }
                }
            }
        }
    }
}
