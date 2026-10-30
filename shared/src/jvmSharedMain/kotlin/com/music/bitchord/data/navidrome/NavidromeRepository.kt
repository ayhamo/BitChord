package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    /** As many songs as Navidrome's own Instant Mix asks for. */
    private const val INSTANT_MIX_SIZE = 100

    private val lock = Mutex()
    @Volatile private var cached: List<Song>? = null
    @Volatile private var cachedAt = 0L

    /** Bumped by [invalidate]; a listing started under an older value is not kept. */
    @Volatile private var generation = 0

    fun isConfigured(): Boolean = NavidromeSettings.isConfigured()

    /** Drops the cached listing only (a kept file changes how a song plays); the server answers the same. */
    fun invalidateListing() {
        generation++ // a listing paging right now was made before the change: not kept either
        cached = null
    }

    fun invalidate() {
        generation++
        cached = null
        NavidromeClips.reset()
        NavidromeAlbums.clear() // album ids belong to the old server or account
    }

    suspend fun getSongs(): List<Song> = lock.withLock {
        // Offline mode (the switch, or no network): the songs kept on the phone, so the library pages and search show only those.
        if (NavidromePlatform.offline.value) return@withLock NavidromePlatform.kept.keptSongs()
        cached?.takeIf { (System.nanoTime() / 1_000_000) - cachedAt < CACHE_MS }?.let { return@withLock it }
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
            cachedAt = (System.nanoTime() / 1_000_000)
        }
        songs
    }

    /** A client for the saved server, with the saved salt so its URLs stay the same across starts. */
    /**
     * [url] as it may be written to disk: a server address without its login. `t` and `s` log in as
     * the user until the password changes, so they stay out of settings, backups and manifests.
     */
    fun atRest(url: String?): String? {
        val parsed = url?.toHttpUrlOrNull() ?: return url
        if ("/rest/" !in parsed.encodedPath || parsed.queryParameter("t") == null) return url
        return parsed.newBuilder()
            .removeAllQueryParameters("u")
            .removeAllQueryParameters("t")
            .removeAllQueryParameters("s")
            .build()
            .toString()
    }

    /** [atRest] undone with the current login, for an address read back from disk. */
    fun live(url: String?): String? {
        if (url == null || "/rest/" !in url) return url
        return runCatching { client().withLogin(url) }.getOrDefault(url)
    }

    fun client() = NavidromeClient(
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
    }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }

    /**
     * "Start radio (Instant Mix)" on a Navidrome song: the server's similar songs (AudioMuse, when
     * the server has it), in the server's order, as Navidrome's own Instant Mix plays them.
     *
     * Unlike [related] an empty answer stays empty, so the player can say so instead of playing
     * random songs under that name. And not through `QueueBuilder.extend`: its two-per-artist cap
     * is for YouTube's mixes, and would cut a server's mix of 100 down to a fraction.
     */
    suspend fun instantMix(seed: Song): Result<List<Song>> = runCatching {
        val client = client()
        val found = client.similarSongs(seed.videoId.removePrefix(NavidromeSettings.ID_PREFIX), INSTANT_MIX_SIZE)
            .map { it.toSong(client) }
        withContext(Dispatchers.Default) { withoutRepeats(seed, found) }.map {
            it.copy(
                radioName = seed.radioName,
                playbackSource = seed.playbackSource,
                playbackSourceType = seed.playbackSourceType,
                playbackSourceId = seed.playbackSourceId,
            ).let { song -> with(QueueTimeline) { song.asQueueEntry(QueueTier.AUTOPLAY) } }
        }
    }

    /** Each recording once and never the seed's, in the server's order: one song can be in the library twice (a FLAC and an opus copy). */
    fun withoutRepeats(seed: Song, songs: List<Song>): List<Song> {
        val taken = mutableListOf(seed)
        return songs.filter { song ->
            taken.none { QueueBuilder.isSameRecording(it, song) } && taken.add(song)
        }
    }

    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        runCatching { NavidromeClient(url, username, password).ping() }

    /**
     * [Song.albumId] and [Song.artistId] are the library's own album and artist
     * pages (the ones its Albums and Artists tabs open), so the player's album
     * and artist links lead there, and the player doesn't ask YouTube to fill
     * them in for a song it has never heard of.
     */
    fun NavidromeClient.SongRow.toSong(client: NavidromeClient): Song {
        NavidromeAlbums.remember(album, albumId) // so its album page opens with one request
        sizeBytes?.let { NavidromePlatform.kept.noteSize(id, it) } // so "Available offline" can say how much a page takes
        val lossless = suffix?.lowercase() in LOSSLESS_SUFFIXES
        val artistName = artist ?: "Unknown Artist"
        return Song(
            videoId = NavidromeSettings.ID_PREFIX + id,
            title = title,
            artist = artistName,
            artistId = NavidromeSettings.artistPageId(artistName),
            albumId = album?.let { NavidromeSettings.albumPageId(it) },
            thumbnailUrl = coverArt?.let { client.coverUrl(it) },
            durationText = durationSec?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%d:%02d", it / 60, it % 60) },
            albumName = album,
            // A song kept offline plays from its file (NavidromeOffline).
            localUri = NavidromePlatform.kept.localUri(id) ?: client.streamUrl(id),
            trackNumber = track,
            sourceQuality = if (lossless) "LOSSLESS" else null,
            sourceHiRes = lossless && ((bitDepth ?: 0) > 16 || (samplingRate ?: 0) > 48_000),
            playbackSource = "Navidrome",
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = NavidromeSettings.BROWSE_ID,
        ).also(NavidromePlatform.onSong) // title/artist/length for the YouTube history match
    }

    private val LOSSLESS_SUFFIXES = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")
}
