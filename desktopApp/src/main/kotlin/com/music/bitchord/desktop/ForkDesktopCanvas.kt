package com.music.bitchord.desktop

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.ForkCanvasSwitch
import com.music.bitchord.ui.player.forkWithBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fork: the player's clips on the PC, both kinds at once.
 *
 * [DesktopCanvasClient.lookup] stops at the first source that has anything, and Spotify, the only
 * one whose Canvas fills the screen, is asked last. While the owner's server had backdrops that
 * didn't matter (a server backdrop came first, with its cover clip beside it). Since the clips left
 * the server (2026-11), a song with an Apple Music motion cover never got a backdrop: the owner's
 * "backdrop videos don't work at all".
 *
 * So with "Backdrop videos" on and a cover clip found first, the backdrop sources are asked too
 * ([ForkDesktopCanvasSources.backdrops]: the song's music video, then Spotify, the canvas rulebook's
 * order). The first one found becomes the backdrop, and the cover clip goes with it as
 * [CanvasArtwork.sleeveUrl], which the
 * player plays in the cover square wherever the backdrop can't fill the screen (the PC's landscape
 * window: the backdrop behind the controls, the cover clip in the sleeve). The player's switches
 * still decide what is shown, live (`forkCanvases`).
 *
 * In two stages, so the cover clip doesn't wait for the backdrop: [canvasFor] answers with the
 * first clip (with the backdrop only when its answer for the song is already known), and
 * [withBackdrop], the player's second stage ([ForkCanvasSwitch.backdropFor]), asks the backdrop
 * sources and adds the backdrop to the clip already showing (a music-video loop takes seconds to
 * cut the first time). Waiting for both made an Apple Music cover clip, found
 * in a moment, appear only once Spotify answered: about a second, several when its token had to be
 * fetched again in the hidden browser.
 *
 * Every stage logs one line, so "nothing found" can be told from "never asked".
 */
internal object ForkDesktopCanvas {

    /** [untilMs]: when a miss may be asked again (a found clip is kept regardless). */
    private class Answer(val artwork: DesktopCanvasArtwork?, val untilMs: Long)

    /**
     * The backdrop answer per song (and switches), the last 64; a miss is asked again after ten
     * minutes, a failed search (see [backdropFor]) after a minute or the provider's own backoff.
     * Locked on itself, not with [backdropFor]'s lock: [cached] reads it on the UI thread, and that
     * lock is held for the whole search. Each source's answer is also kept across restarts
     * ([com.music.bitchord.data.canvas.ForkCanvasStore]).
     */
    private val backdrops = object : LinkedHashMap<String, Answer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>) = size > 64
    }

    init {
        // The player's second stage. Set when this object first loads, which the player's
        // `cachedCanvas` (this object's [cached]) does before it gets to the second stage.
        ForkCanvasSwitch.backdropFor = { song, shown -> withBackdrop(song, shown) }
    }

    /**
     * What is already known for [playing], without a request: the first source's clip, with
     * Spotify's backdrop when that is known too (the phone's `ForkCanvas.cached`). Before, the PC
     * painted the first clip alone from here and the combined answer a moment later.
     */
    fun cached(playing: Song): CanvasArtwork? {
        val first = DesktopCanvasClient.cached(playing) ?: return null
        val backdrop = if (asksBackdrop(first)) known(playing.videoId) else null
        return forkWithBackdrop(first.toShared(), backdrop?.toShared()).answering(playing)
    }

    /** The first stage: kept server clips, else the first source's clip, at once. */
    suspend fun canvasFor(playing: Song): CanvasArtwork? = lookUp(playing).answering(playing)

    /**
     * The second stage: [shown], the clip on screen for [playing], with a backdrop (the music
     * video's loop, else Spotify's Canvas) when "Backdrop videos" asks for one and one is found;
     * [shown] itself otherwise. Not for a kept
     * server clip (that is the server's whole answer, as in [lookUp]) or a clip that already fills
     * the screen (Spotify's own, or one combined in the first stage).
     */
    suspend fun withBackdrop(playing: Song, shown: CanvasArtwork?): CanvasArtwork? {
        if (shown == null || shown.source.fillsPhone || !ForkCanvasSwitch.backdrops.value) return shown
        if (ForkSpotifyCanvasFirst.prioritized.value) return shown // the backdrop sources were asked first already (see asksBackdrop)
        if (com.music.bitchord.data.navidrome.NavidromeClips.canvasFor(playing) != null) return shown
        val song = com.music.bitchord.data.navidrome.NavidromeCanvasQuery.searchable(playing)
        val scope = com.music.bitchord.data.canvas.ForkCanvasLookup.Scope() // where the providers report a failed search
        val backdrop = withContext(Dispatchers.IO + scope.context) { backdropFor(song, scope) }
        DesktopTrackLog.log("canvas: '${song.title}': ${if (backdrop != null) "+ ${backdrop.source.name.lowercase()} backdrop" else "no backdrop"}")
        return forkWithBackdrop(shown, backdrop?.toShared()).answering(playing)
    }

    /**
     * A server song's clip, named after the song itself: the player checks a clip against the
     * playing song's title, artists and album (`CanvasArtwork.matches`), and the query differs.
     */
    private fun CanvasArtwork?.answering(playing: Song): CanvasArtwork? =
        if (this == null || !com.music.bitchord.data.navidrome.NavidromeSettings.isNavidromeId(playing.videoId)) this
        else copy(title = playing.title, artist = playing.artist, album = playing.albumName)

    private suspend fun lookUp(playing: Song): CanvasArtwork? {
        com.music.bitchord.data.navidrome.NavidromeClips.canvasFor(playing)?.let { return it } // kept clips from before
        // A server song is asked about without its album or a repeated "Artist - " (see NavidromeCanvasQuery).
        val song = com.music.bitchord.data.navidrome.NavidromeCanvasQuery.searchable(playing)
        val first = withContext(Dispatchers.IO) { DesktopCanvasClient.lookup(song) }
        // Nothing found means the backdrop sources, the last ones asked, had nothing either: not asked twice.
        val backdrop = if (asksBackdrop(first)) known(song.videoId) else null
        log(song, first, backdrop)
        return forkWithBackdrop(first?.toShared(), backdrop?.toShared())
    }

    /**
     * Not with "Prioritize Spotify Canvas" on (the phone's `asksBackdrop`): the backdrop sources
     * were asked first then, so a first clip from anyone else means they had none, and asking again
     * would be the same requests twice. Not beside a clip that fills the screen itself.
     */
    private fun asksBackdrop(first: DesktopCanvasArtwork?): Boolean =
        first != null && first.source == DesktopCanvasSource.OTHER && ForkCanvasSwitch.backdrops.value &&
            !ForkSpotifyCanvasFirst.prioritized.value

    private fun memoKey(videoId: String) = videoId + ForkDesktopCanvasSources.keySuffix()

    /** The backdrop for the song when already found, without asking (null: none, or not asked yet). */
    private fun known(videoId: String): DesktopCanvasArtwork? = synchronized(backdrops) { backdrops[memoKey(videoId)] }?.artwork

    /**
     * One backdrop search at a time (the object's lock), each answer kept in [backdrops].
     *
     * A miss is kept for ten minutes only when Spotify really answered. A search that never got an
     * answer (HTTP 429, a token that would not mint, a timeout, a throw: what the provider reports to
     * [scope]) is held off for a minute, or until the token's own backoff ends, and then asked
     * again. Before, every miss was kept ten minutes "either way", so one refused request left the
     * song with no backdrop for ten minutes after Spotify was answering again. From Desktop Revived
     * 1432c5af (its Spotify-only lookup caches only hits); ours keeps the short hold so a queue
     * skipped through doesn't re-ask a refusing service per track.
     */
    private val searching = kotlinx.coroutines.sync.Mutex()

    private suspend fun backdropFor(song: Song, scope: com.music.bitchord.data.canvas.ForkCanvasLookup.Scope): DesktopCanvasArtwork? = searching.withLock {
        val key = memoKey(song.videoId)
        synchronized(backdrops) { backdrops[key] }?.let { known ->
            if (known.artwork != null || System.currentTimeMillis() < known.untilMs) return@withLock known.artwork
        }
        if (com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) return@withLock null
        val title = song.title.cleanedForCanvas()
        val artist = song.artist.cleanedForCanvas()
        if (title.isBlank() || artist.isBlank()) return@withLock null
        val found = runCatching {
            var hit: DesktopCanvasArtwork? = null
            var threw: Throwable? = null
            for ((_, ask) in ForkDesktopCanvasSources.backdropsBeside(song, title, artist, song.albumName)) {
                val r = runCatching { ask() }
                r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it; threw = it }
                hit = r.getOrNull()?.takeIf { it.matches(title, artist, song.albumName) }
                if (hit != null) break
            }
            hit ?: threw?.let { throw it }
        }
        found.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        val artwork = found.getOrNull()
        val now = System.currentTimeMillis()
        val failed = artwork == null && (found.isFailure || scope.failure != null)
        val until = if (failed) {
            DesktopTrackLog.log("canvas: backdrop search failed (${scope.failure ?: found.exceptionOrNull()?.message}); asking again later")
            maxOf(now + com.music.bitchord.data.canvas.ForkCanvasLookup.RETRY_AFTER_MS, scope.retryAtMs)
        } else {
            now + 10L * 60 * 1000
        }
        synchronized(backdrops) { backdrops[key] = Answer(artwork, until) }
        artwork
    }

    private fun log(song: Song, first: DesktopCanvasArtwork?, backdrop: DesktopCanvasArtwork?) {
        val cover = when {
            first == null -> "none"
            first.source == DesktopCanvasSource.SPOTIFY -> "Spotify"
            first.source == DesktopCanvasSource.VIDEO -> "a music-video loop"
            else -> "a cover clip"
        }
        val extra = if (backdrop != null) " + ${backdrop.source.name.lowercase()} backdrop (known)" else ""
        DesktopTrackLog.log("canvas: '${song.title}': $cover$extra")
    }
}
