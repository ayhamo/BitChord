package com.music.bitchord.fork

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.canvas.normalizeForMatch
import com.music.bitchord.data.canvas.splitArtists
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Navidrome plays into the signed-in account's YouTube Music history
 * (fork-only), so YouTube's recommendations learn from server listening too.
 *
 * Upstream's [PlaybackTracker] registers a play with YouTube's own three
 * pings, keyed by a YouTube video id; a Navidrome song has none, so it was
 * skipped. Here the song is looked up on YouTube Music once (title, artists
 * and length must all agree, so a cover or a live take never counts as the
 * song) and the tracker is handed that id. The match, or "none", is kept per
 * song. Off by default ([ForkSettings.navidromeYouTubeHistory]); needs a
 * YouTube sign-in like the tracker itself.
 */
object ForkYouTubeHistory {

    private const val TAG = "BitChordNavidrome"
    private const val NONE = "-"
    private const val MISS_MS = 7L * 24 * 60 * 60 * 1000

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val matches = ConcurrentHashMap<String, String>()
    private val songs = ConcurrentHashMap<String, Song>()

    /** The Navidrome song the player last reported as playing. */
    @Volatile private var current: String? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_fork_yt_history", Context.MODE_PRIVATE)
    }

    /** Called as each Navidrome song is queued for playing (its title and artist are needed to search). */
    fun remember(song: Song) {
        if (song.videoId.startsWith(com.music.bitchord.data.model.NAVIDROME_ID_PREFIX)) songs[song.videoId] = song
    }

    /** The YouTube id a Navidrome [mediaId] was matched to, or null. */
    fun mapped(mediaId: String): String? = matches[mediaId]?.takeIf { it != NONE }

    /**
     * [PlaybackTracker.onPlaying]'s first step: true when [mediaId] is a
     * Navidrome song, which is then handled here (matched, and the tracker
     * called with the YouTube id once there is one).
     */
    fun intercept(mediaId: String): Boolean {
        // Every track, not only Navidrome ones: a match that comes back after the player moved on
        // to a YouTube song must not register (see the `current == mediaId` check below).
        current = mediaId
        if (!mediaId.startsWith(com.music.bitchord.data.model.NAVIDROME_ID_PREFIX)) return false
        if (!ForkSettings.navidromeYouTubeHistory.value || Innertube.cookie == null) return true
        matches[mediaId]?.let { match ->
            if (match != NONE) PlaybackTracker.onPlaying(match)
            return true
        }
        prefs.getString(mediaId, null)?.let { saved ->
            val (id, at) = saved.split('|').let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
            if (id != NONE || System.currentTimeMillis() - at < MISS_MS) {
                matches[mediaId] = id
                if (id != NONE) PlaybackTracker.onPlaying(id)
                return true
            }
        }
        // No network (a kept song played offline): nothing to ask, and a failed search isn't a miss.
        if (ForkNetwork.offline.value || !searching.add(mediaId)) return true
        scope.launch {
            try {
                // A queue restored after a restart was never built here this run: ask the server for the song.
                val song = songs[mediaId] ?: runCatching {
                    val client = com.music.bitchord.data.navidrome.NavidromeRepository.client()
                    client.song(mediaId.removePrefix(com.music.bitchord.data.model.NAVIDROME_ID_PREFIX))
                        ?.let { with(com.music.bitchord.data.navidrome.NavidromeRepository) { it.toSong(client) } }
                }.getOrNull() ?: return@launch
                // A failed search (network, YouTube error) is not remembered: only a real answer is.
                val match = find(song).getOrElse {
                    Log.d(TAG, "YouTube history: ${song.title}: search failed: ${it.message}")
                    return@launch
                }
                matches[mediaId] = match ?: NONE
                prefs.edit().putString(mediaId, "${match ?: NONE}|${System.currentTimeMillis()}").apply()
                Log.d(TAG, "YouTube history: ${song.title} -> ${match ?: "no match"}")
                if (match != null && current == mediaId) PlaybackTracker.onPlaying(match)
            } finally {
                searching.remove(mediaId)
            }
        }
        return true
    }

    /** Navidrome ids being searched for, so a pause and resume doesn't start a second search. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The one YouTube Music song that is [song] (same title, same artists, length within 5 s),
     * null when there is none, or a failure when the search itself failed.
     */
    private suspend fun find(song: Song): Result<String?> {
        val results = YtMusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS)
            .getOrElse { return Result.failure(it) }
        // A title the normaliser empties (Arabic, CJK, Cyrillic) is compared as written, lowercased:
        // "" == "" would match any such title by the same artist.
        fun key(title: String) = title.normalizeForMatch().ifEmpty { title.trim().lowercase() }
        val title = key(song.title)
        if (title.isEmpty()) return Result.success(null)
        val artists = splitArtists(song.artist).toSet()
        val length = seconds(song.durationText)
        return Result.success(results.asSequence()
            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            .firstOrNull { candidate ->
                key(candidate.title) == title &&
                    splitArtists(candidate.artist).any { it in artists } &&
                    (length == null || seconds(candidate.durationText)?.let { kotlin.math.abs(it - length) <= 5 } != false)
            }?.videoId)
    }

    private fun seconds(text: String?): Int? = text?.split(':')?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.isNotEmpty() }?.fold(0) { total, part -> total * 60 + part }
}
