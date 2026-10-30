package com.music.bitchord.data.navidrome

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.toEnhancedLrc
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.lyrics.withBackgroundVocals
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
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
 *
 * Asked before the server, so they show with no network:
 * - a kept song's lyrics (`NavidromeOffline`, written when the page was kept);
 * - the lyrics cache: whatever timed lyrics a Navidrome song showed last time,
 *   the server's or an online source's ([remember]), in `cache/navidrome_lyrics`
 *   ("Clear lyrics cache" in Settings empties it).
 */
object NavidromeLyrics {

    private const val TAG = "BitChordNavidrome"
    private const val REMEMBERED = 64

    /** Past this many files the oldest quarter goes; a song's lyrics are a few KB. */
    private const val CACHE_FILES = 4000

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private var cacheDir: File? = null

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

    fun init(directory: File) {
        cacheDir = directory.apply { mkdirs() }
    }

    /**
     * The song id when [uri] is a stream URL on the configured server, or a
     * kept file with kept lyrics, else null (a kept file without them is read
     * for its own tags, as any file).
     */
    fun songIdFor(uri: String): String? {
        NavidromePlatform.kept.songIdForFile(uri)?.let { id -> return id.takeIf { NavidromePlatform.kept.lyricsFile(it) != null } }
        if (!NavidromeSettings.isConfigured()) return null
        val url = uri.toHttpUrlOrNull() ?: return null
        val base = NavidromeSettings.url.value.toHttpUrlOrNull() ?: return null
        if (url.host != base.host || url.port != base.port) return null
        if (url.pathSegments.takeLast(2) != listOf("rest", "stream")) return null
        return url.queryParameter("id")
    }

    /** Timed lyrics for [songId], or null (none, only plain text, or the server couldn't be asked). */
    suspend fun forSong(songId: String): List<LyricLine>? = locks.getOrPut(songId) { Mutex() }.withLock {
        // Under a lock, so the second of two callers for one song gets the first one's answer. One per
        // song: the server call can't be cancelled, and skipping on shouldn't wait out its timeout.
        answers[songId]?.let { return@withLock it.ifEmpty { null } }
        (NavidromePlatform.kept.lyricsFile(songId) ?: cacheFile(songId))?.let { file ->
            parse(runCatching { file.readText() }.getOrNull())?.let { lines ->
                answers[songId] = lines
                return@withLock lines
            }
        }
        val rows = runCatching { NavidromeRepository.client().lyricsBySongId(songId) }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.d(TAG, "lyrics for $songId: ${it.message}")
            }
            // Not remembered: a dropped connection isn't an answer.
            .getOrElse { return@withLock null }
        val lines = linesFrom(rows)
        if (lines == null) {
            Log.d(TAG, "no timed lyrics for $songId: " + rows.joinToString { "synced=${it.synced} lines=${it.lines.size}" })
        }
        answers[songId] = lines.orEmpty()
        lines?.let { write(songId, it) }
        lines
    }

    /**
     * Keeps what the player showed for a Navidrome song (an online source's
     * answer, the server having none), so it shows again with no network.
     * Called by the view model with whatever it settled on.
     */
    fun remember(videoId: String, lines: List<LyricLine>?) {
        if (!NavidromeSettings.isNavidromeId(videoId) || lines.isNullOrEmpty() || lines.none { it.timeMs > 0 }) return
        val songId = videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        // Called on the main thread; the checks and the write are disk work.
        // Only when the server answered "none": after a server outage the online copy would sit in
        // the cache, read before the server, and hide the server's own lyrics from then on.
        if (answers[songId]?.isEmpty() != true) return
        answers[songId] = lines // shown again this session too, also with no network
        scope.launch {
            if (cacheFile(songId) != null || NavidromePlatform.kept.lyricsFile(songId) != null) return@launch
            write(songId, lines)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The server's timed lyrics for [songId] as LRC, or null. */
    suspend fun serverLrc(songId: String): String? = forSong(songId)?.asLrc()?.takeIf { it.isNotBlank() }

    /** Bytes the lyrics cache takes. */
    fun cacheBytes(): Long = cacheDir?.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir?.listFiles()?.forEach { it.delete() }
        answers.clear()
    }

    // Not an empty one: an earlier build saved line-synced lyrics as empty files, and those are no answer.
    private fun cacheFile(songId: String): File? = cacheDir?.let { File(it, "$songId.lrc") }?.takeIf { it.isFile && it.length() > 0 }

    /** Word stamps when there are any, else plain line stamps: [toEnhancedLrc] is empty for line-synced lyrics, by design. */
    private fun List<LyricLine>.asLrc(): String = toEnhancedLrc().ifBlank { toLrc() }

    private fun write(songId: String, lines: List<LyricLine>) {
        val dir = cacheDir ?: return
        runCatching {
            File(dir, "$songId.lrc").writeText(lines.asLrc())
            val files = dir.listFiles() ?: return@runCatching
            if (files.size > CACHE_FILES) files.sortedBy { it.lastModified() }.take(CACHE_FILES / 4).forEach { it.delete() }
        }.onFailure { Log.d(TAG, "lyrics cache $songId: ${it.message}") }
    }

    private fun parse(lrc: String?): List<LyricLine>? =
        lrc?.takeIf { it.isNotBlank() }
            ?.let { LrcLib.parseLrc(it) }
            ?.takeIf { parsed -> parsed.any { it.text.isNotBlank() } }
            ?.withBackgroundVocals()

    /** The first timed entry as player lines, or null when the server only had plain text (or nothing). */
    fun linesFrom(rows: List<NavidromeClient.LyricsRow>): List<LyricLine>? =
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
