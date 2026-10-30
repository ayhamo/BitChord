package com.music.bitchord.desktop

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork: Navidrome songs in the desktop's Search, the phone's Library source (`NavidromeSearch`)
 * in the one list this page has: the cached listing, narrowed by the same match. Offline,
 * [NavidromeRepository.getSongs] answers with the kept songs only.
 *
 * Its own job, not part of YouTube's request: the first listing of a large library takes several
 * requests, and a server that can't be reached shouldn't hold up or fail YouTube's rows.
 */
internal object ForkNavidromeSearch {

    /** "All" has YouTube's sections to get to below these; "Songs" lists every match. */
    private const val ON_ALL = 20

    private val _rows = MutableStateFlow<List<SearchResult>>(emptyList())

    /** The latest search's songs from the server, empty until they are in. */
    val rows: StateFlow<List<SearchResult>> = _rows.asStateFlow()

    private var job: Job? = null

    /** Called with each search; only "All" and "Songs" list songs, the other filters are YouTube's categories. */
    fun start(scope: CoroutineScope, query: String, filter: SearchFilter) {
        job?.cancel()
        _rows.value = emptyList()
        val term = query.trim()
        if (term.isEmpty() || !NavidromeSettings.isConfigured()) return
        if (filter != SearchFilter.ALL && filter != SearchFilter.SONGS) return
        job = scope.launch {
            val library = try {
                NavidromeRepository.getSongs()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DesktopTrackLog.log("navidrome: search: ${failure.message}")
                return@launch
            }
            val found = withContext(Dispatchers.Default) { library.filter { it.matches(term) } }
            _rows.value = (if (filter == SearchFilter.ALL) found.take(ON_ALL) else found).map { SearchResult.Track(it) }
        }
    }

    /** The phone's `Song.matchesSearch`, which lives with its device-folder code. */
    private fun Song.matches(term: String): Boolean =
        title.contains(term, ignoreCase = true) ||
            artist.contains(term, ignoreCase = true) ||
            albumName?.contains(term, ignoreCase = true) == true
}
