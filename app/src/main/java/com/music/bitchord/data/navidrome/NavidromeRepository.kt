package com.music.bitchord.data.navidrome

import com.music.bitchord.data.LocalGroups
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

    /** Drops the cached listing only (a kept file changes how a song plays); the server answers the same. */
    fun invalidateListing() {
        cached = null
    }

    fun invalidate() {
        generation++
        cached = null
        NavidromeClips.reset()
    }

    suspend fun getSongs(): List<Song> = lock.withLock {
        // Offline mode (the switch, or no network): the songs kept on the phone, so the library pages and search show only those.
        if (com.music.bitchord.fork.ForkNetwork.offline.value) return@withLock NavidromeOffline.keptSongs()
        cached?.takeIf { android.os.SystemClock.elapsedRealtime() - cachedAt < CACHE_MS }?.let { return@withLock it }
        if (!isConfigured()) return@withLock emptyList()
        val startedAt = generation
        val client = client()
        val starred = mutableSetOf<String>()
        val songs = buildList {
            var offset = 0
            while (true) {
                val page = client.songs(offset, PAGE)
                page.forEach { row ->
                    add(row.toSong(client))
                    if (row.starred) starred += NavidromeSettings.ID_PREFIX + row.id
                }
                if (page.size < PAGE) break
                offset += PAGE
            }
        }
        NavidromeStars.seed(starred)
        // The settings changed while this was paging: it describes the old server.
        if (startedAt == generation) {
            cached = songs
            cachedAt = android.os.SystemClock.elapsedRealtime()
        }
        songs
    }

    /** A client for the saved server, with the saved salt so its URLs stay the same across starts. */
    internal fun client() = NavidromeClient(
        NavidromeSettings.url.value,
        NavidromeSettings.username.value,
        NavidromeSettings.password.value,
        NavidromeSettings.salt,
    )

    /**
     * What AutoPlay queues after [seed]: the server's similar songs, or random
     * ones from the library when it has no agent (Last.fm etc.) to say what is
     * similar. Always this library, never a YouTube radio.
     */
    suspend fun related(seed: Song, count: Int = 50): Result<List<Song>> = runCatching {
        val client = client()
        val id = seed.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        val similar = try {
            client.similarSongs(id, count)
        } catch (e: java.io.IOException) {
            emptyList()
        }
        similar.ifEmpty { client.randomSongs(count) }.map { it.toSong(client) }
    }

    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        runCatching { NavidromeClient(url, username, password).ping() }

    /**
     * [Song.albumId] and [Song.artistId] are the library's own album and artist
     * pages (the ones its Albums and Artists tabs open), so the player's album
     * and artist links lead there, and the player doesn't ask YouTube to fill
     * them in for a song it has never heard of.
     */
    internal fun NavidromeClient.SongRow.toSong(client: NavidromeClient): Song {
        NavidromeAlbums.remember(album, albumId) // so its album page opens with one request
        sizeBytes?.let { NavidromeOffline.noteSize(id, it) } // so "Available offline" can say how much a page takes
        val lossless = suffix?.lowercase() in LOSSLESS_SUFFIXES
        val artistName = artist ?: "Unknown Artist"
        return Song(
            videoId = NavidromeSettings.ID_PREFIX + id,
            title = title,
            artist = artistName,
            artistId = LocalGroups.artistPageId(NavidromeSettings.BROWSE_ID, artistName),
            albumId = album?.let { LocalGroups.albumPageId(NavidromeSettings.BROWSE_ID, it) },
            thumbnailUrl = coverArt?.let { client.coverUrl(it) },
            durationText = durationSec?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) },
            albumName = album,
            // A song kept offline plays from its file (NavidromeOffline).
            localUri = NavidromeOffline.localFile(id)?.let { android.net.Uri.fromFile(it).toString() } ?: client.streamUrl(id),
            trackNumber = track,
            sourceQuality = if (lossless) "LOSSLESS" else null,
            sourceHiRes = lossless && ((bitDepth ?: 0) > 16 || (samplingRate ?: 0) > 48_000),
            playbackSource = "Navidrome",
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = NavidromeSettings.BROWSE_ID,
        ).also(com.music.bitchord.fork.ForkYouTubeHistory::remember) // title/artist/length for the YouTube history match
    }

    private val LOSSLESS_SUFFIXES = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")
}
