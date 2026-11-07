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
        NavidromeArtistInfo.clear() // so do artist ids and similar artists
        NavidromeArtistPictures.stopPass() // its artist list is the old server's; the next library load starts it again
    }

    suspend fun getSongs(): List<Song> = lock.withLock {
        // Offline mode (the switch, or no network): the songs kept on the phone, so the library pages and search show only those.
        if (NavidromePlatform.offline.value) return@withLock NavidromePlatform.kept.keptSongs()
        cached?.takeIf { (System.nanoTime() / 1_000_000) - cachedAt < CACHE_MS }?.let { return@withLock it }
        if (!isConfigured()) return@withLock emptyList()
        val startedAt = generation
        val client = client()
        val starred = mutableSetOf<String>()
        val songs: List<Song> = try {
            buildList<Song> {
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
        } catch (e: java.io.IOException) {
            // The server down on a network that works (offline mode only follows the network on the
            // phone): the library pages showed an error over songs kept on the device. Not cached,
            // so the next look asks the server again.
            keptInstead(e)?.let { return@withLock it }
            throw e
        }
        // The settings changed while this was paging: it describes the old server (its stars too:
        // seeded after the account switch's forgetAll, they came back as hearts).
        if (startedAt == generation) {
            NavidromeStars.seedAll(starred) // the whole library: a star taken off elsewhere goes here too
            cached = songs
            cachedAt = (System.nanoTime() / 1_000_000)
        }
        songs
    }

    /** The songs kept on the device when the server couldn't be read, or null when none are kept (the error stands). */
    private fun keptInstead(e: java.io.IOException): List<Song>? =
        NavidromePlatform.kept.keptSongs().takeIf { it.isNotEmpty() }
            ?.also { com.music.bitchord.data.DebugLog.d("BitChordNavidrome", "library: server unreachable (${e.message}), showing ${it.size} kept songs") }

    /** A client for the saved server, with the saved salt so its URLs stay the same across starts. */
    /**
     * [url] without a login. `t` and `s` log in as the user until the password changes, so they
     * stay out of every address: for one an older version saved with its login.
     */
    fun atRest(url: String?): String? {
        if (url == null || "/rest/" !in url) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        if ("/rest/" !in parsed.encodedPath || parsed.queryParameter("t") == null) return url
        return parsed.newBuilder()
            .removeAllQueryParameters("u")
            .removeAllQueryParameters("t")
            .removeAllQueryParameters("s")
            .build()
            .toString()
    }

    /** An address read back from disk: [atRest], since an older version may have saved its login. */
    fun live(url: String?): String? = atRest(url)

    /** For what can't go through [com.music.bitchord.data.Http]: see [NavidromeClient.withLogin]. */
    fun withLogin(url: String): String = runCatching { client().withLogin(url) }.getOrDefault(url)

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
    }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it } // a cancelled radio start isn't "the server has no mix"

    /** Each recording once and never the seed's, in the server's order: one song can be in the library twice (a FLAC and an opus copy). */
    fun withoutRepeats(seed: Song, songs: List<Song>): List<Song> {
        val taken = mutableListOf(seed)
        return songs.filter { song ->
            taken.none { QueueBuilder.isSameRecording(it, song) } && taken.add(song)
        }
    }

    /** [getSongs] for the library page (phone and PC): a failure's message is a plain sentence ([NavidromeErrors]). */
    suspend fun getSongsExplained(): List<Song> = NavidromeErrors.explained(NavidromeSettings.url.value) { getSongs() }.getOrThrow()

    /** The login test (phone and PC): a failure's message is a plain sentence ([NavidromeErrors]), shown as it is. */
    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        NavidromeErrors.explained(url) { NavidromeClient(url, username, password).ping() }

    /**
     * [Song.albumId] and [Song.artistId] are the library's own album and artist
     * pages (the ones its Albums and Artists tabs open), so the player's album
     * and artist links lead there, and the player doesn't ask YouTube to fill
     * them in for a song it has never heard of.
     */
    fun NavidromeClient.SongRow.toSong(client: NavidromeClient): Song {
        NavidromeAlbums.remember(album, albumId) // so its album page opens with one request
        NavidromeArtistInfo.remember(this) // so its artists' radio and similar artists need no search for the id
        sizeBytes?.let { NavidromePlatform.kept.noteSize(id, it) } // so "Available offline" can say how much a page takes
        val lossless = suffix?.lowercase() in LOSSLESS_SUFFIXES
        val artistName = artist ?: "Unknown Artist"
        return Song(
            videoId = NavidromeSettings.ID_PREFIX + id,
            title = title,
            artist = artistName,
            artistId = NavidromeSettings.artistPageId(artistName),
            albumId = album?.let { NavidromeSettings.albumPageId(it) },
            // Its own cover (`mf-…`); a file with none gets its YouTube thumbnail before the album's (NavidromeYouTubeCover).
            thumbnailUrl = NavidromeYouTubeCover.artFor(coverArt, comment) ?: coverArt?.let { client.coverUrl(it) },
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
