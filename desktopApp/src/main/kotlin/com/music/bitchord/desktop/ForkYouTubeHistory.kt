package com.music.bitchord.desktop

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.canvas.normalizeForMatch
import com.music.bitchord.data.canvas.splitArtists
import com.music.bitchord.data.model.NAVIDROME_ID_PREFIX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.data.navidrome.NavidromeRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Fork: Navidrome plays into the signed-in account's YouTube Music history, as the phone's
 * `ForkYouTubeHistory` does, so YouTube's recommendations learn from server listening too.
 *
 * [DesktopPlaybackTracker] registers a play with YouTube's own pings, keyed by a YouTube id. A
 * Navidrome song has none, so it is looked up once (title, artists and length must all agree, so a
 * cover or a live take never counts as the song) and the tracker is handed that id. The match, or
 * "none", is kept per song.
 */
internal object ForkYouTubeHistory {

    private const val TAG = "BitChordNavidrome"
    private const val NONE = "-"
    private const val MISS_MS = 7L * 24 * 60 * 60 * 1000

    /** The switch on the Navidrome page, saved by [ForkNavidrome]. */
    val enabled = MutableStateFlow(true)

    private lateinit var saved: NavidromePlatform.Store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val matches = ConcurrentHashMap<String, String>()
    private val songs = ConcurrentHashMap<String, Song>()

    /** Navidrome ids being searched for, so a pause and resume doesn't start a second search. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The song the player last reported as playing. */
    @Volatile private var current: String? = null

    fun init(store: NavidromePlatform.Store) {
        saved = store
    }

    fun remember(song: Song) {
        if (song.videoId.startsWith(NAVIDROME_ID_PREFIX)) songs[song.videoId] = song
    }

    /** The YouTube id a Navidrome [mediaId] was matched to, or null. */
    fun mapped(mediaId: String): String? = matches[mediaId]?.takeIf { it != NONE }

    /** The tracker's first step: true when [mediaId] is a Navidrome song, which is then handled here. */
    fun intercept(mediaId: String): Boolean {
        // Every track: a match that comes back after the player moved on must not register.
        current = mediaId
        if (!mediaId.startsWith(NAVIDROME_ID_PREFIX)) return false
        if (!enabled.value || !DesktopYouTubeAuth.isSignedIn) return true
        matches[mediaId]?.let { match ->
            if (match != NONE) DesktopPlaybackTracker.onPlaying(match)
            return true
        }
        saved.string(mediaId)?.let { line ->
            val id = line.substringBefore('|')
            val at = line.substringAfter('|', "").toLongOrNull() ?: 0L
            if (id != NONE || System.currentTimeMillis() - at < MISS_MS) {
                matches[mediaId] = id
                if (id != NONE) DesktopPlaybackTracker.onPlaying(id)
                return true
            }
        }
        if (NavidromePlatform.offline.value || !searching.add(mediaId)) return true
        scope.launch {
            try {
                // A queue restored after a restart was never built here this run: ask the server.
                val song = songs[mediaId] ?: runCatching {
                    val client = NavidromeRepository.client()
                    client.song(mediaId.removePrefix(NAVIDROME_ID_PREFIX))?.let { with(NavidromeRepository) { it.toSong(client) } }
                }.getOrNull() ?: return@launch
                // A failed search is not remembered: only a real answer is.
                val match = find(song).getOrElse {
                    Log.d(TAG, "YouTube history: ${song.title}: search failed: ${it.message}")
                    return@launch
                }
                matches[mediaId] = match ?: NONE
                saved.putStrings(mapOf(mediaId to "${match ?: NONE}|${System.currentTimeMillis()}"))
                Log.d(TAG, "YouTube history: ${song.title} -> ${match ?: "no match"}")
                if (match != null && current == mediaId) DesktopPlaybackTracker.onPlaying(match)
            } finally {
                searching.remove(mediaId)
            }
        }
        return true
    }

    /** The one YouTube Music song that is [song], null when there is none, a failure when the search failed. */
    private suspend fun find(song: Song): Result<String?> {
        val results = YtMusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS)
            .getOrElse { return Result.failure(it) }
        // A title the normaliser empties (Arabic, CJK, Cyrillic) is compared as written.
        fun key(title: String) = title.normalizeForMatch().ifEmpty { title.trim().lowercase() }
        val title = key(song.title)
        if (title.isEmpty()) return Result.success(null)
        val artists = splitArtists(song.artist).toSet()
        val length = seconds(song.durationText)
        return Result.success(
            results.asSequence()
                .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                .firstOrNull { candidate ->
                    key(candidate.title) == title &&
                        splitArtists(candidate.artist).any { it in artists } &&
                        (length == null || seconds(candidate.durationText)?.let { kotlin.math.abs(it - length) <= 5 } != false)
                }?.videoId,
        )
    }

    private fun seconds(text: String?): Int? = text?.split(':')?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.isNotEmpty() }?.fold(0) { total, part -> total * 60 + part }
}
