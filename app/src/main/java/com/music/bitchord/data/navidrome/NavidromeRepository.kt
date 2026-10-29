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
 * album's. The listing is kept in memory until the settings change or the page
 * is refreshed, because a large library is several requests.
 */
object NavidromeRepository {

    private const val PAGE = 500

    private val lock = Mutex()
    @Volatile private var cached: List<Song>? = null

    fun isConfigured(): Boolean = NavidromeSettings.isConfigured()

    fun invalidate() {
        cached = null
    }

    suspend fun getSongs(): List<Song> = lock.withLock {
        cached?.let { return@withLock it }
        if (!isConfigured()) return@withLock emptyList()
        val client = NavidromeClient(
            NavidromeSettings.url.value,
            NavidromeSettings.username.value,
            NavidromeSettings.password.value,
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
        cached = songs
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
