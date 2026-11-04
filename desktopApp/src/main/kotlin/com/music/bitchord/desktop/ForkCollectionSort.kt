package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fork: the phone's song sort on an album or playlist page (`SongSort`, `sortedForDetail` in
 * DetailScreen.kt), which the PC's collection page never had: Default, Reverse order, Title A–Z /
 * Z–A, Date added newest / oldest first. Kept per page (`browseId`), as on the phone, in the
 * `fork_collection_sorts` preference ("id=SORT,…", the id URL-encoded: a Navidrome album page's
 * id carries the album's name, commas and all). The page's Play and Shuffle take the sorted
 * list, so playing follows what is shown (the phone's `forkSort` in `playDetailSongs`).
 */
internal object ForkCollectionSort {

    /** The phone's `SongSort`, same names, so a value reads the same on both. */
    enum class Sort { DEFAULT, REVERSED, TITLE_ASC, TITLE_DESC, DATE_ADDED_ASC, DATE_ADDED_DESC }

    private const val KEY = "fork_collection_sorts"

    private val _sorts = MutableStateFlow(read())
    val sorts = _sorts.asStateFlow()

    fun sortFor(browseId: String?): Sort = browseId?.let { _sorts.value[it] } ?: Sort.DEFAULT

    fun set(browseId: String, sort: Sort) {
        val next = if (sort == Sort.DEFAULT) _sorts.value - browseId else _sorts.value + (browseId to sort)
        _sorts.value = next
        DesktopPersistence().saveString(KEY, next.entries.joinToString(",") { (id, s) -> "${java.net.URLEncoder.encode(id, Charsets.UTF_8)}=${s.name}" })
    }

    private fun read(): Map<String, Sort> =
        DesktopPersistence().string(KEY).split(',').mapNotNull { entry ->
            val id = entry.substringBeforeLast('=', "").takeIf { it.isNotBlank() }
                ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() } ?: return@mapNotNull null
            val sort = Sort.entries.firstOrNull { it.name == entry.substringAfterLast('=') } ?: return@mapNotNull null
            id to sort
        }.toMap()

    /**
     * [songs] in [sort]'s order, the phone's rules: a catalogue row has no added date, so its place
     * in the running order stands in for one (a playlist is appended to, so reversed puts the newest
     * on top); a row with a file date (downloads, local files) is dated by it, position breaking ties.
     */
    fun sorted(songs: List<Song>, sort: Sort): List<Song> = when (sort) {
        Sort.DEFAULT -> songs
        Sort.REVERSED -> songs.asReversed()
        Sort.TITLE_ASC -> songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        Sort.TITLE_DESC -> songs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
        Sort.DATE_ADDED_ASC -> songs.withIndex()
            .sortedWith(compareBy<IndexedValue<Song>> { it.value.localDateAddedSeconds ?: Long.MAX_VALUE }.thenBy { it.index })
            .map { it.value }
        Sort.DATE_ADDED_DESC -> songs.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Song>> { it.value.localDateAddedSeconds ?: Long.MIN_VALUE }.thenByDescending { it.index })
            .map { it.value }
    }

    fun label(sort: Sort): String = when (sort) {
        Sort.DEFAULT -> DesktopStrings["sort_default", "Default order"]
        Sort.REVERSED -> DesktopStrings["sort_reversed", "Reverse order"]
        Sort.TITLE_ASC -> DesktopStrings["sort_title_ascending", "Alphabetical (A to Z)"]
        Sort.TITLE_DESC -> DesktopStrings["sort_title_descending", "Alphabetical (Z to A)"]
        Sort.DATE_ADDED_DESC -> DesktopStrings["sort_date_added", "Date added (newest first)"]
        Sort.DATE_ADDED_ASC -> DesktopStrings["sort_date_added_oldest", "Date added (oldest first)"]
    }
}

/**
 * The page's songs in its chosen order, read again when the sort changes. A sort is over the whole
 * list, so with one chosen and pages still to come ([complete] false) the rest is loaded first, one
 * page after another, and the list keeps its own order until then: sorting the pages loaded so far
 * put each new page above the rows on screen (Reverse: on top), which kept the "more" row in view
 * and paged through by accident, with a half-sorted list meanwhile.
 */
@Composable
internal fun rememberForkSortedSongs(
    browseId: String?,
    songs: List<Song>,
    complete: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
): List<Song> {
    val sorts by ForkCollectionSort.sorts.collectAsState()
    val sort = browseId?.let { sorts[it] } ?: ForkCollectionSort.Sort.DEFAULT
    val sorting = sort != ForkCollectionSort.Sort.DEFAULT
    LaunchedEffect(sorting, complete, loadingMore, songs.size) {
        if (sorting && !complete && !loadingMore) onLoadMore()
    }
    return remember(songs, sort, complete) { if (complete) ForkCollectionSort.sorted(songs, sort) else songs }
}

/** Each song's place in the page's own order (1-based), so a sorted album keeps its track numbers. */
@Composable
internal fun rememberForkRowNumbers(songs: List<Song>): java.util.IdentityHashMap<Song, Int> =
    remember(songs) { java.util.IdentityHashMap<Song, Int>().apply { songs.forEachIndexed { i, s -> put(s, i + 1) } } }

/** The sort button beside the page's search, with the phone's options; the chosen one is ticked. */
@Composable
internal fun ForkCollectionSortButton(browseId: String?) {
    browseId ?: return
    val sorts by ForkCollectionSort.sorts.collectAsState()
    val current = sorts[browseId] ?: ForkCollectionSort.Sort.DEFAULT
    var open by remember(browseId) { mutableStateOf(false) }
    Box {
        DesktopCircleButton(Icons.AutoMirrored.Rounded.Sort, DesktopStrings["sort_songs", "Sort songs"], onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ForkCollectionSort.Sort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(ForkCollectionSort.label(option)) },
                    trailingIcon = if (option == current) ({ Icon(Icons.Rounded.Check, null) }) else null,
                    onClick = {
                        ForkCollectionSort.set(browseId, option)
                        open = false
                    },
                )
            }
        }
    }
}
