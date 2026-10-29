package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Navidrome library as [Song] rows (fork-only), shaped like the WebDAV and
 * SMB libraries so the shared Songs / Artists / Albums pages draw it.
 *
 * Unlike those, everything comes from the server's tags: title, artist, album,
 * track and disc numbers, and each song's **own** cover (`mf-…`), not its
 * album's. A large library is several requests, so the listing is kept in
 * memory for [CACHE_MS] (the page, its refresh and the queue all read it) and
 * dropped at once when the settings change.
 */
object NavidromeRepository {

    private const val PAGE = 500
    private const val CACHE_MS = 2 * 60 * 1000L

    private val lock = Mutex()
    @Volatile private var cached: List<Song>? = null
    @Volatile private var cachedAt = 0L

    /** Bumped by [invalidate]; a listing started under an older value is not kept. */
    @Volatile private var generation = 0

    fun isConfigured(): Boolean = NavidromeSettings.isConfigured()

    fun invalidate() {
        generation++
        cached = null
    }

    suspend fun getSongs(): List<Song> = lock.withLock {
        cached?.takeIf { android.os.SystemClock.elapsedRealtime() - cachedAt < CACHE_MS }?.let { return@withLock it }
        if (!isConfigured()) return@withLock emptyList()
        val startedAt = generation
        val client = NavidromeClient(
            NavidromeSettings.url.value,
            NavidromeSettings.username.value,
            NavidromeSettings.password.value,
            NavidromeSettings.salt,
        )
        val songs = buildList {
            var offset = 0
            while (true) {
                val page = client.songs(offset, PAGE)
                page.forEach { add(it.toSong(client)) }
                if (page.size < PAGE) break
                offset += PAGE
            }
        }
        // The settings changed while this was paging: it describes the old server.
        if (startedAt == generation) {
            cached = songs
            cachedAt = android.os.SystemClock.elapsedRealtime()
        }
        songs
    }

    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        runCatching { NavidromeClient(url, username, password).ping() }

    private fun NavidromeClient.SongRow.toSong(client: NavidromeClient): Song {
        val lossless = suffix?.lowercase() in LOSSLESS_SUFFIXES
        return Song(
            videoId = "navidrome:$id",
            title = title,
            artist = artist ?: "Unknown Artist",
            thumbnailUrl = coverArt?.let { client.coverUrl(it) },
            durationText = durationSec?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) },
            albumName = album,
            localUri = client.streamUrl(id),
            trackNumber = track,
            sourceQuality = if (lossless) "LOSSLESS" else null,
            sourceHiRes = lossless && ((bitDepth ?: 0) > 16 || (samplingRate ?: 0) > 48_000),
            playbackSource = "Navidrome",
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = NavidromeSettings.BROWSE_ID,
        )
    }

    private val LOSSLESS_SUFFIXES = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")
}
