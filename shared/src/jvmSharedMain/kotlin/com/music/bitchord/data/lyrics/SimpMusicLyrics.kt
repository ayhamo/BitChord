package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Lyrics from SimpMusic's community database, keyed on the YouTube video id.
 *
 * That key is what makes it worth having: every other provider matches on
 * title and artist and can hand back a different edit of the same song, which
 * drifts out of sync a verse in. This one is looking up the exact track that
 * is playing.
 *
 * Two caveats, both seen in the wild:
 *  - the host geoblocks some regions outright, answering 403 with a "Access
 *    denied from your region" body rather than a network error, so a miss here
 *    can be permanent for a given user and the chain must carry on past it;
 *  - the rich sync is served HTML-escaped — see [EnhancedLrc.decodeEntities].
 */
object SimpMusicLyrics {

    private const val BASE = "https://api-lyrics.simpmusic.org/v1/"

    /** Duration slack when the database holds several cuts of one video. */
    private const val DURATION_TOLERANCE_SECONDS = 10

    suspend fun lyrics(videoId: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            if (videoId.isBlank()) return@withContext null
            val body = forkLyricsGetAs(BASE + videoId, "SimpMusicLyrics/1.0") ?: return@withContext null // fork: its own User-Agent (lyrics.md §11.1); was lyricsGet
            ForkSimpMusic.pick(body, durationMs) // fork: the live reply's shape, {type:"success", data:[{durationSeconds, richSyncLyrics, syncedLyrics, plainLyric}]} (was a `success` flag and `duration`, which the reply never has, so every answer was dropped)
        }

    @Serializable
    data class Response(
        val success: Boolean = false,
        val data: List<Track>? = null,
    )

    @Serializable
    data class Track(
        val duration: Int? = null,
        val richSyncLyrics: String? = null,
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
    )
}
