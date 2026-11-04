package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

// Fork: four of the phone's library and song-menu actions the PC lacked — saving an album or
// playlist to the YouTube library, pinned playlists, "Remove from history" and "Remove from cache".

/**
 * The phone's pinned playlists (`AppSettings.pinnedPlaylists` / `togglePinnedPlaylist`): up to
 * [MAX] playlists put first on the Library page, in the order pinned; a sixth is refused rather
 * than evicting one. Kept in the PC's preferences, which the portable build writes to its `data`
 * folder. Read by the shared Library page through `DesktopAppUiHost.pinnedPlaylists`.
 */
internal object ForkPins {

    /** The phone's `MAX_PINNED_PLAYLISTS`. */
    const val MAX = 5
    const val LIMIT = "Only %1\$d playlists can be pinned"

    private const val KEY = "pinned_playlists"
    private val persistence by lazy { DesktopPersistence() }

    private val _pinned = MutableStateFlow(read())
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    /** The phone's rule: playlists only, not the device's own groups. */
    fun pinnable(browseId: String, type: BrowseType): Boolean =
        type == BrowseType.PLAYLIST && !browseId.startsWith("local:")

    /** Pins or unpins [browseId]; false when it would have been a sixth pin, which is refused. */
    fun toggle(browseId: String): Boolean {
        val current = _pinned.value
        val updated = when {
            browseId in current -> current - browseId
            current.size >= MAX -> return false
            else -> current + browseId
        }
        _pinned.value = updated
        persistence.saveString(KEY, updated.joinToString(","))
        return true
    }

    private fun read(): List<String> =
        runCatching { DesktopPersistence().string(KEY, "") }.getOrDefault("").split(",").filter { it.isNotBlank() }
}

/** "Pin" / "Unpin" in a playlist page's "More" menu, the phone's row in `BrowseActionsMenu`. */
@Composable
internal fun ForkPinMenuItem(browseId: String, type: BrowseType, onDone: () -> Unit) {
    if (!ForkPins.pinnable(browseId, type)) return
    val pinned by ForkPins.pinned.collectAsState()
    val isPinned = browseId in pinned
    DropdownMenuItem(
        text = { Text(if (isPinned) DesktopStrings["unpin", "Unpin"] else DesktopStrings["pin", "Pin"]) },
        leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
        onClick = {
            onDone()
            if (!ForkPins.toggle(browseId)) {
                ForkNotice.show(DesktopStrings.format("pinned_playlist_limit", ForkPins.MAX, fallback = ForkPins.LIMIT))
            }
        },
    )
}

/**
 * The phone's library circle beside Play on an album or playlist page (`DetailScreen`, a plus or a
 * tick, `MainViewModel.toggleLibrary`): saves the release to the YouTube library or takes it out,
 * optimistic and rolled back if YouTube refuses. Only signed in and only where YouTube offers the
 * save (read off the page, as the phone's `releaseLibraryState` does for a card); a server's page
 * has none.
 */
@Composable
internal fun ForkLibrarySaveButton(browseId: String) {
    if (!DesktopYouTubeAuth.isSignedIn || NavidromeSettings.isNavidromeId(browseId) || browseId.startsWith("local:")) return
    var state by remember(browseId) { mutableStateOf<LibraryState?>(null) }
    val loaded by produceState<LibraryState?>(null, browseId) {
        value = withContext(Dispatchers.IO) { YtMusicRepository.releaseLibraryState(browseId).getOrNull() }
    }
    val shown = state ?: loaded ?: return
    val scope = rememberCoroutineScope()
    DesktopCircleButton(
        if (shown.saved) BitChordIcons.Check else BitChordIcons.Plus,
        if (shown.saved) DesktopStrings["remove_from_library", "Remove from library"] else DesktopStrings["add_to_library", "Add to library"],
        onClick = {
            val target = !shown.saved
            state = shown.copy(saved = target)
            scope.launch {
                val ok = withContext(Dispatchers.IO) { YtMusicRepository.setSaved(shown.playlistId, target).isSuccess }
                if (!ok) {
                    state = shown
                    ForkNotice.show(if (target) "Couldn't add to library" else "Couldn't remove from library")
                }
            }
        },
    )
}

/** Set around the History page's song menus: "Remove from history" is offered there only, as on the phone. */
internal val LocalForkFromHistory = compositionLocalOf { false }

@Composable
internal fun ForkFromHistory(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalForkFromHistory provides true, content = content)

/** The account history's rows removed this session, hidden at once (the phone edits its list in place). */
internal object ForkHistoryRemoval {
    private val removed = MutableStateFlow<Set<String>>(emptySet())

    @Composable
    fun visible(history: List<Song>): List<Song> {
        val gone by removed.collectAsState()
        return if (gone.isEmpty()) history else history.filterNot { it.feedbackToken in gone }
    }

    suspend fun remove(token: String): Boolean {
        val ok = withContext(Dispatchers.IO) { YtMusicRepository.removeFromHistory(token).isSuccess }
        if (ok) {
            YtMusicRepository.invalidateHistoryCache()
            removed.update { it + token }
        }
        return ok
    }
}

/**
 * What the PC's song cache holds of a song, for "Remove from cache": the YouTube windows kept in
 * [ForkAudioRangeCache] (by the stream they came from, so the song's id is noted when the engine
 * opens it) and a server song read ahead by [ForkPreload]. The PC's cache has no per-song index, so
 * this keeps one beside the windows (`songs.index`, thrown away with them by "Clear song cache").
 * Only what playback read is known; Automix's own analysis reads are not.
 */
internal object ForkSongCache {

    /**
     * Stream address -> song id, newest last. Memory only (the addresses are signed), and bounded: one was
     * added for every song opened, for as long as the app ran.
     */
    private val urls = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_STREAMS
    })
    private const val MAX_STREAMS = 64
    private val keys = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var loaded = false

    private val directory get() = DesktopMediaCache.directory.resolve("audio_ranges")
    private val index get() = directory.resolve("songs.index")

    /** From the engine as it opens [song] on [url]; the song itself for the Cached songs page ([ForkCachedSongs]). */
    fun noteStream(song: Song, url: String) {
        if (url.contains(".googlevideo.com")) urls[url] = song.videoId
        ForkCachedSongs.noteStream(song, url)
    }

    /** From the range reader as it keeps a window of [url] under [key]. */
    fun noteSpan(url: String, key: String) {
        ForkCachedSongs.noteKept(url)
        val id = urls[url] ?: return
        load()
        if (keys.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(key)) save()
    }

    fun holds(song: Song): Boolean {
        if (song.localUri != null) return false
        if (NavidromeSettings.isNavidromeId(song.videoId)) return ForkPreload.holds(song.videoId.removePrefix(NavidromeSettings.ID_PREFIX))
        load()
        val held = keys[song.videoId] ?: return ForkCachedSongs.holds(song.videoId) // the Cached songs index knows streams this one may not (an older songs.index)
        return spans(held).isNotEmpty() || ForkCachedSongs.holds(song.videoId)
    }

    /** Throws away what is kept of [song]; the bytes freed. */
    fun remove(song: Song): Long {
        if (NavidromeSettings.isNavidromeId(song.videoId)) return ForkPreload.remove(song.videoId.removePrefix(NavidromeSettings.ID_PREFIX))
        load()
        val held = keys.remove(song.videoId)
        val freed = held?.let { set -> spans(set).sumOf { file -> runCatching { Files.size(file).also { Files.deleteIfExists(file) } }.getOrDefault(0L) } } ?: 0L
        if (held != null) save()
        return freed + ForkCachedSongs.remove(song.videoId) // and its row on the Cached songs page
    }

    private fun spans(held: Set<String>) = runCatching {
        Files.list(directory).use { files ->
            files.filter { file -> held.any { file.fileName.toString().startsWith("$it-") } }.toList()
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            Files.readAllLines(index).forEach { line ->
                val id = line.substringBefore('\t')
                val key = line.substringAfter('\t', "")
                if (id.isNotBlank() && key.isNotBlank()) keys.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(key)
            }
        }
    }

    @Synchronized
    private fun save() {
        runCatching {
            Files.createDirectories(directory)
            Files.write(index, keys.flatMap { (id, set) -> set.map { "$id\t$it" } })
        }
    }
}

/**
 * The phone's two rows after Download: "Remove from cache" (only while the cache holds some of the
 * song) and "Remove from history" (only on the History page, for a row of the account's history).
 */
/** Outlives the menu, which closes as soon as a row is picked. */
private val forkMenuScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

@Composable
internal fun ForkRemoveRows(song: Song, onDismiss: () -> Unit) {
    val scope = forkMenuScope
    val inCache by produceState(false, song.videoId) { value = withContext(Dispatchers.IO) { ForkSongCache.holds(song) } }
    if (inCache) {
        DesktopMenuRow(Icons.Rounded.Delete, DesktopStrings["remove_from_cache", "Remove from cache"]) {
            onDismiss()
            scope.launch(Dispatchers.IO) {
                if (ForkSongCache.remove(song) > 0) ForkNotice.show(DesktopStrings["removed_from_cache", "Removed from the song cache"])
            }
        }
    }
    val token = song.feedbackToken?.takeIf { LocalForkFromHistory.current }
    if (token != null) {
        DesktopMenuRow(Icons.Rounded.History, DesktopStrings["remove_from_history", "Remove from history"]) {
            onDismiss()
            scope.launch {
                if (!ForkHistoryRemoval.remove(token)) {
                    ForkNotice.show(DesktopStrings["remove_from_history_failed", "Couldn't remove from history"])
                }
            }
        }
    }
}
