package com.music.bitchord.fork

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasRepository
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.canvas.SpotifyCanvas
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.player.ForkCanvasSwitch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fork: the player's clips on the phone, both kinds at once (the PC's `ForkDesktopCanvas`).
 *
 * [CanvasRepository.canvasFor] stops at the first source with anything, and Spotify, the one whose
 * Canvas fills the phone, is asked last. While the owner's server had backdrops, a server song got
 * its backdrop with its cover clip beside it. Since the clips left the server (2026-11) a song with
 * an Apple Music motion cover never got a backdrop.
 *
 * So with "Backdrop videos" on and another source's clip found first, Spotify is asked too: its
 * Canvas becomes the backdrop, the first clip its [CanvasArtwork.sleeveUrl] (played in the cover
 * square wherever the backdrop can't fill the screen). Spotify is not asked again when the
 * repository already did: when nothing was found (it is the last source), or with "Prioritise
 * Spotify Canvas" on (it was the first). The player's switches still decide what shows.
 *
 * A server song is searched without a repeated "Artist - " in front of its title: files tagged that
 * way ("TheFatRat - Monody (…)", artist "TheFatRat") were matched against no catalogue title at all.
 */
object ForkCanvas {

    private class Answer(val artwork: CanvasArtwork?, val atMs: Long)

    /** Spotify's answer per song, the last [MAX_SONGS]; a miss is asked again after [MISS_MS]. */
    private val spotify = object : LinkedHashMap<String, Answer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>) = size > MAX_SONGS
    }

    /** One Spotify search at a time: a restarted lookup (the album landing) waits for the first one's answer. */
    private val lock = Mutex()

    fun cached(song: Song): CanvasArtwork? {
        val query = searchable(song)
        val first = CanvasRepository.cached(query)
        if (!asksSpotify(first)) return first.answering(song, query)
        return combine(first, synchronized(spotify) { spotify[song.videoId] }?.artwork).answering(song, query)
    }

    suspend fun canvasFor(song: Song): CanvasArtwork? {
        val query = searchable(song)
        val first = CanvasRepository.canvasFor(query)
        if (!asksSpotify(first)) return first.answering(song, query)
        return combine(first, spotifyFor(song, query)).answering(song, query)
    }

    /**
     * A server song's clip, named after the song itself: the player checks a clip against the playing
     * song's own title, artists and album before showing it (`CanvasArtwork.matches` in
     * `rememberCanvasArtwork`). "Monody (…)" is not "TheFatRat - Monody (…)", and a server album
     * ("Youtube Topic", a collection) is not the catalogue's, which is why the lookup leaves it out.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun CanvasArtwork?.answering(song: Song, query: Song): CanvasArtwork? =
        if (this == null || !NavidromeSettings.isNavidromeId(song.videoId)) this
        else copy(title = song.title, artist = song.artist, album = song.albumName)

    /** Whether a Spotify backdrop is worth asking for beside [first]: see the class comment. */
    private fun asksSpotify(first: CanvasArtwork?): Boolean =
        ForkCanvasSwitch.backdrops.value && first != null && !first.source.fillsPhone &&
            !AppSettings.prioritizeSpotifyCanvas.value

    private fun combine(first: CanvasArtwork?, backdrop: CanvasArtwork?): CanvasArtwork? = when {
        backdrop == null || first == null -> first
        else -> backdrop.copy(source = CanvasSource.SPOTIFY, sleeveUrl = first.url)
    }

    private suspend fun spotifyFor(song: Song, query: Song): CanvasArtwork? = lock.withLock {
        synchronized(spotify) { spotify[song.videoId] }?.let { known ->
            if (known.artwork != null || System.currentTimeMillis() - known.atMs < MISS_MS) return@withLock known.artwork
        }
        if (query.title.isBlank() || query.artist.isBlank()) return@withLock null
        // Without a sign-in nothing is asked and nothing remembered, so a cookie added later counts.
        if (SpotifyToken.accessToken() == null) return@withLock null
        val album = query.albumName.takeUnless { NavidromeSettings.isNavidromeId(song.videoId) }
        // Off the main thread, as the repository's own lookups are: the player asks from there, and a
        // request on it is refused before it leaves (NetworkOnMainThreadException, read as "http -1").
        val found = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SpotifyCanvas.search(query.title, query.artist, album)?.takeIf { it.matches(query.title, query.artist, album) }
            }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; return@withLock null }
        // A lookup cancelled mid-request (the player restarts it when the album lands) reads as
        // "http -1" and a null, which is not an answer: nothing is remembered, the restart asks again.
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
        synchronized(spotify) { spotify[song.videoId] = Answer(found, System.currentTimeMillis()) }
        found
    }

    /** [song] with "Artist - " taken off the front of a server song's title. */
    internal fun searchable(song: Song): Song {
        if (!NavidromeSettings.isNavidromeId(song.videoId)) return song
        val title = withoutArtistPrefix(song.title, song.artist)
        return if (title == song.title) song else song.copy(title = title)
    }

    internal fun withoutArtistPrefix(title: String, artist: String): String {
        val dash = Regex("""^\s*(.+?)\s+[-–—]\s+(.+)$""").matchEntire(title) ?: return title
        val (front, rest) = dash.destructured
        val artists = artist.split(Regex("""\s*(?:,|&|\bx\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val frontNames = front.split(Regex("""\s*(?:,|&)\s*""")).map { it.trim().lowercase() }
        return if (frontNames.isNotEmpty() && frontNames.all { it in artists } || front.trim().lowercase() == artist.trim().lowercase()) rest else title
    }

    private const val MAX_SONGS = 64
    private const val MISS_MS = 10L * 60 * 1000
}
