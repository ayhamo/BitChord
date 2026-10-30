package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import okhttp3.Response

/**
 * Fork: what the Navidrome code in this module needs from the app it runs in, so
 * the phone and the desktop share one client, library and lyrics. Each app fills
 * these in once at start; the defaults are a desktop with nothing kept offline.
 */
object NavidromePlatform {

    /** A small key-value file: the login, "no clip for this song" notes. */
    interface Store {
        fun string(key: String): String?
        fun long(key: String): Long
        fun putStrings(values: Map<String, String>)
        fun putLong(key: String, value: Long)
        fun keys(): Set<String>
        fun remove(keys: Collection<String>)
        fun clear()
    }

    /** Songs kept on the device for offline listening. */
    interface Kept {
        fun localUri(songId: String): String? = null
        fun lyricsFile(songId: String): File? = null
        fun songIdForFile(uri: String): String? = null
        fun clipUrl(songId: String, backdrop: Boolean): String? = null
        fun keptSongs(): List<Song> = emptyList()
        fun noteSize(songId: String, bytes: Long) = Unit
        fun serve(request: Request): Response? = null
        suspend fun pageState(browseId: String): UiState<List<Song>>? = null
    }

    @Volatile var kept: Kept = object : Kept {}

    /** Offline mode: nothing goes to the server. */
    @Volatile var offline: StateFlow<Boolean> = MutableStateFlow(false)

    /** Every song read from the server passes through here (the phone notes them for its YouTube history match). */
    @Volatile var onSong: (Song) -> Unit = {}

    /** Suspends until the network allows "Available offline" downloads. */
    @Volatile var awaitDownload: suspend () -> Unit = {}

    /** A page has started downloading (the phone's notification). */
    @Volatile var onKeepStarted: () -> Unit = {}

    /** The song's lyrics from the online sources as LRC, for a song the server has none for. */
    @Volatile var onlineLyrics: suspend (Song) -> String? = { null }

    /** The kept pages changed: the Library's "Available offline" row. */
    @Volatile var onKeptRow: (HomeShelf) -> Unit = {}

    /** The server's genres as tiles for Explore's panel; empty when there is no server. */
    @Volatile var onGenres: (List<MoodGenre>) -> Unit = {}

    /** Whether plays are reported to the server. */
    @Volatile var scrobble: () -> Boolean = { true }
}
