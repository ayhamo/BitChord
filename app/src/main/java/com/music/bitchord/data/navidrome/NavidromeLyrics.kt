package com.music.bitchord.data.navidrome

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.withBackgroundVocals
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections
import java.util.Locale

/**
 * Lyrics for a Navidrome song, from the server (fork-only).
 *
 * Navidrome reads them out of the file itself (its tags, or a `.lrc` beside
 * it), so for these songs they are the embedded lyrics, just one request away,
 * and they belong to this exact recording rather than to a name matched
 * against somebody's database. That is why this is reached through
 * `EmbeddedLyrics.forUri` with the song's stream URL: both the player screen
 * and the service's lyric subtitle already ask that first and fall back to the
 * online sources on a null.
 *
 * Only **timed** lyrics are returned. Plain text from the server is a null, so
 * the online sources still get the chance to find a synced copy.
 */
object NavidromeLyrics {

    private const val TAG = "BitChordNavidrome"
    private const val REMEMBERED = 64

    private val lock = Mutex()

    /**
     * Answers already fetched, by song id; an empty list is a remembered miss.
     * The player screen and the service ask for the same song within a second
     * of each other, and a song played twice shouldn't ask twice.
     */
    private val answers: MutableMap<String, List<LyricLine>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<LyricLine>>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, List<LyricLine>>) = size > REMEMBERED
        },
    )

    /** The song id when [uri] is a stream URL on the configured server, else null. */
    fun songIdFor(uri: String): String? {
        if (!NavidromeSettings.isConfigured()) return null
        val url = uri.toHttpUrlOrNull() ?: return null
        val base = NavidromeSettings.url.value.toHttpUrlOrNull() ?: return null
        if (url.host != base.host || url.port != base.port) return null
        if (url.pathSegments.takeLast(2) != listOf("rest", "stream")) return null
        return url.queryParameter("id")
    }

    /** Timed lyrics for [songId], or null (none, only plain text, or the server couldn't be asked). */
    suspend fun forSong(songId: String): List<LyricLine>? = lock.withLock {
        // Under the lock, so the second of two callers for one song gets the first one's answer.
        answers[songId]?.let { return@withLock it.ifEmpty { null } }
        val rows = runCatching { NavidromeRepository.client().lyricsBySongId(songId) }
            .onFailure { Log.d(TAG, "lyrics for $songId: ${it.message}") }
            // Not remembered: a dropped connection isn't an answer.
            .getOrElse { return@withLock null }
        val lines = linesFrom(rows)
        if (lines == null) {
            Log.d(TAG, "no timed lyrics for $songId: " + rows.joinToString { "synced=${it.synced} lines=${it.lines.size}" })
        }
        answers[songId] = lines.orEmpty()
        lines
    }

    /** The first timed entry as player lines, or null when the server only had plain text (or nothing). */
    internal fun linesFrom(rows: List<NavidromeClient.LyricsRow>): List<LyricLine>? =
        rows.firstOrNull { it.synced && it.lines.isNotEmpty() }
            ?.let { LrcLib.parseLrc(it.toLrc()) }
            ?.takeIf { parsed -> parsed.any { it.text.isNotBlank() } }
            ?.withBackgroundVocals()

    /**
     * Back to LRC so the same parser as a tagged file reads it, word stamps
     * (`<mm:ss.xx>`) included where the file had them. OpenSubsonic's offset
     * is "positive = sooner", so it is taken off every start.
     */
    private fun NavidromeClient.LyricsRow.toLrc(): String = lines.joinToString("\n") { (start, text) ->
        val ms = ((start ?: 0L) - offsetMs).coerceAtLeast(0L)
        // ROOT: an Arabic or Persian locale would write the stamp in digits the parser doesn't read.
        "[%02d:%02d.%03d]%s".format(Locale.ROOT, ms / 60_000, ms / 1_000 % 60, ms % 1_000, text)
    }
}
