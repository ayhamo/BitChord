package com.music.bitchord.fork

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasRepository
import com.music.bitchord.data.canvas.ForkCanvasLookup
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.player.ForkCanvasSwitch
import com.music.bitchord.ui.player.forkWithBackdrop
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
 * So with "Backdrop videos" on and a cover clip found first, the backdrop sources are asked too
 * ([ForkCanvasSources.backdrops]: the song's music video, then Spotify, the canvas rulebook's order):
 * the first one found becomes the backdrop, the cover clip its [CanvasArtwork.sleeveUrl] (played in
 * the cover square wherever the backdrop can't fill the screen). They are not asked again when the
 * repository already did: when nothing was found (they are its last sources), or with "Prioritise
 * Spotify Canvas" on (they were its first). The player's switches still decide what shows.
 *
 * In two stages, so the cover clip doesn't wait for the backdrop: [canvasFor] answers with the first
 * clip (and the backdrop only when its answer for the song is already known), and [withBackdrop],
 * the player's second stage ([ForkCanvasSwitch.backdropFor]), asks the backdrop sources and adds the
 * backdrop to the clip already showing. A music-video loop takes seconds to cut the first time. Waiting for both made an Apple Music cover clip, found
 * in a moment, appear only once Spotify answered: about a second, several when its token had to be
 * fetched again.
 *
 * A server song is searched without a repeated "Artist - " in front of its title: files tagged that
 * way ("TheFatRat - Monody (…)", artist "TheFatRat") were matched against no catalogue title at all.
 */
object ForkCanvas {

    /** [untilMs]: when a miss may be asked again (a found clip is kept regardless). */
    private class Answer(val artwork: CanvasArtwork?, val untilMs: Long)

    /**
     * The backdrop answer per song (and switches), the last [MAX_SONGS]; a miss is asked again after
     * [MISS_MS], a failed search (a 429, no answer) after [ForkCanvasLookup.RETRY_AFTER_MS] or the
     * provider's own backoff, as the PC's `ForkDesktopCanvas` does. Each source's own answer is also
     * kept across restarts ([com.music.bitchord.data.canvas.ForkCanvasStore]).
     */
    private val backdrops = object : LinkedHashMap<String, Answer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>) = size > MAX_SONGS
    }

    /**
     * One backdrop search at a time, as the repository serialises its own. A restarted lookup (the
     * album landing) waits for the cancelled one's request to finish, but cannot use its answer:
     * `withContext` throws on a cancelled caller and the request's result is dropped with it, so the
     * restart asks again (the same as the repository's own `resolve`).
     */
    private val lock = Mutex()

    init {
        // The player's second stage. Set when this object first loads, which the player's
        // `cachedCanvas` (this object's [cached]) does before it gets to the second stage.
        ForkCanvasSwitch.backdropFor = { song, shown -> withBackdrop(song, shown) }
    }

    fun cached(song: Song): CanvasArtwork? {
        val query = searchable(song)
        val first = CanvasRepository.cached(query)
        if (!asksBackdrop(first)) return first.answering(song, query)
        return forkWithBackdrop(first, known(song)).answering(song, query)
    }

    /**
     * The first stage: the repository's clip, at once. The backdrop comes with it only when
     * the backdrop's answer for the song is already here (a song coming round again, a reopened player),
     * so the clip isn't shown alone first and then again with it.
     */
    suspend fun canvasFor(song: Song): CanvasArtwork? {
        val query = searchable(song)
        val first = CanvasRepository.canvasFor(query)
        if (!asksBackdrop(first)) return first.answering(song, query)
        return forkWithBackdrop(first, known(song)).answering(song, query)
    }

    /**
     * The second stage: [shown], the clip on screen for [song], with a backdrop (the music video's
     * loop, else Spotify's Canvas) when one is worth asking for and found; [shown] itself otherwise. Runs in the player's own
     * lookup, so a song change or the album landing cancels the search as it always did.
     */
    suspend fun withBackdrop(song: Song, shown: CanvasArtwork?): CanvasArtwork? {
        if (!asksBackdrop(shown)) return shown
        val query = searchable(song)
        val backdrop = backdropFor(song, query) ?: return shown
        return forkWithBackdrop(shown, backdrop).answering(song, query)
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

    /** Whether a backdrop is worth asking for beside [first]: see the class comment. */
    private fun asksBackdrop(first: CanvasArtwork?): Boolean =
        ForkCanvasSwitch.backdrops.value && first != null && !first.source.fillsPhone &&
            !AppSettings.prioritizeSpotifyCanvas.value

    /** The memo key: the song, and the switches that decide which backdrop sources are asked. */
    private fun memoKey(song: Song) = song.videoId + ForkCanvasSources.keySuffix()

    /** The backdrop for [song] when already found, without asking (null: none, or not asked yet). */
    private fun known(song: Song): CanvasArtwork? = synchronized(backdrops) { backdrops[memoKey(song)] }?.artwork

    private suspend fun backdropFor(song: Song, query: Song): CanvasArtwork? = lock.withLock {
        val key = memoKey(song)
        synchronized(backdrops) { backdrops[key] }?.let { known ->
            if (known.artwork != null || System.currentTimeMillis() < known.untilMs) return@withLock known.artwork
        }
        if (query.title.isBlank() || query.artist.isBlank()) return@withLock null
        // (A Spotify sign-in whose token can't be minted is left alone for MISS_MS: ForkCanvasSources.spotify.)
        val album = query.albumName.takeUnless { NavidromeSettings.isNavidromeId(song.videoId) }
        // Off the main thread, as the repository's own lookups are: the player asks from there, and a
        // request on it is refused before it leaves (NetworkOnMainThreadException, read as "http -1").
        // The search runs in a lookup scope of its own, as the PC's does: a source reports a 429
        // or a request that got no answer there (ForkCanvasLookup), and that is remembered for a
        // minute, not as a ten-minute miss. Outside a scope those reports went nowhere, so one 429
        // kept the song's backdrop away for ten minutes. A source that throws is a failure too.
        val scope = ForkCanvasLookup.Scope()
        val result = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + scope.context) {
                var found: CanvasArtwork? = null
                var threw: Throwable? = null
                for ((_, ask) in ForkCanvasSources.backdropsBeside(song, query.title, query.artist, album)) {
                    val r = runCatching { ask() }
                    r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it; threw = it }
                    found = r.getOrNull()?.takeIf { it.matches(query.title, query.artist, album) }
                    if (found != null) break
                }
                found ?: threw?.let { throw it }
            }
        }
        result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        // A lookup cancelled mid-request (the player restarts it when the album lands) reads as
        // "http -1" and a null, which is not an answer: nothing is remembered, the restart asks again.
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
        val found = result.getOrNull()
        synchronized(backdrops) { backdrops[key] = Answer(found, retryAt(found, result.isFailure, scope, System.currentTimeMillis())) }
        found
    }

    /**
     * When a backdrop answer for a song may be asked for again: a found clip is kept anyway; a failed
     * search after [ForkCanvasLookup.RETRY_AFTER_MS] (or the provider's later own retry time); a
     * settled miss after [MISS_MS].
     */
    internal fun retryAt(found: CanvasArtwork?, threw: Boolean, scope: ForkCanvasLookup.Scope, nowMs: Long): Long =
        retryAt(found != null, threw || scope.failure != null, scope.retryAtMs, nowMs)

    internal fun retryAt(found: Boolean, failed: Boolean, providerRetryAtMs: Long, nowMs: Long): Long = when {
        found -> Long.MAX_VALUE
        failed -> maxOf(nowMs + ForkCanvasLookup.RETRY_AFTER_MS, providerRetryAtMs)
        else -> nowMs + MISS_MS
    }

    /** [song] with "Artist - " taken off the front of a server song's title. */
    internal fun searchable(song: Song): Song {
        if (!NavidromeSettings.isNavidromeId(song.videoId)) return song
        val title = withoutArtistPrefix(song.title, song.artist)
        return if (title == song.title) song else song.copy(title = title)
    }

    internal fun withoutArtistPrefix(title: String, artist: String): String =
        com.music.bitchord.data.navidrome.NavidromeCanvasQuery.withoutArtistPrefix(title, artist) // shared with the PC

    private const val MAX_SONGS = 64
    internal const val MISS_MS = 10L * 60 * 1000
}
