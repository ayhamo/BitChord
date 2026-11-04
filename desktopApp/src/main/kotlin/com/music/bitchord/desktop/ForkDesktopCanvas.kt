package com.music.bitchord.desktop

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.ForkCanvasSwitch
import com.music.bitchord.ui.player.forkWithBackdrop
import kotlinx.coroutines.Dispatchers
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
 * So with "Backdrop videos" on and no Spotify answer yet, Spotify is asked too. Its Canvas becomes
 * the backdrop, and the first source's clip goes with it as [CanvasArtwork.sleeveUrl], which the
 * player plays in the cover square wherever the backdrop can't fill the screen (the PC's landscape
 * window: the backdrop behind the controls, the cover clip in the sleeve). The player's switches
 * still decide what is shown, live (`forkCanvases`).
 *
 * In two stages, so the cover clip doesn't wait for Spotify: [canvasFor] answers with the first
 * clip (with the backdrop only when Spotify's answer for the song is already known), and
 * [withBackdrop], the player's second stage ([ForkCanvasSwitch.backdropFor]), asks Spotify and adds
 * the backdrop to the clip already showing. Waiting for both made an Apple Music cover clip, found
 * in a moment, appear only once Spotify answered: about a second, several when its token had to be
 * fetched again in the hidden browser.
 *
 * Every stage logs one line, so "nothing found" can be told from "never asked".
 */
internal object ForkDesktopCanvas {

    private class Answer(val artwork: DesktopCanvasArtwork?, val atMs: Long)

    /**
     * Spotify's answer per song, the last 64; a miss is asked again after ten minutes. Locked on
     * itself, not with [spotifyFor]'s lock: [cached] reads it on the UI thread, and that lock is
     * held for the whole Spotify request.
     */
    private val spotify = object : LinkedHashMap<String, Answer>(16, 0.75f, true) {
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
        val backdrop = if (asksSpotify(first)) known(playing.videoId) else null
        return forkWithBackdrop(first.toShared(), backdrop?.toShared()).answering(playing)
    }

    /** The first stage: kept server clips, else the first source's clip, at once. */
    suspend fun canvasFor(playing: Song): CanvasArtwork? = lookUp(playing).answering(playing)

    /**
     * The second stage: [shown], the clip on screen for [playing], with a Spotify backdrop when
     * "Backdrop videos" asks for one and Spotify has one; [shown] itself otherwise. Not for a kept
     * server clip (that is the server's whole answer, as in [lookUp]) or a clip that already fills
     * the screen (Spotify's own, or one combined in the first stage).
     */
    suspend fun withBackdrop(playing: Song, shown: CanvasArtwork?): CanvasArtwork? {
        if (shown == null || shown.source.fillsPhone || !ForkCanvasSwitch.backdrops.value) return shown
        if (com.music.bitchord.data.navidrome.NavidromeClips.canvasFor(playing) != null) return shown
        val song = com.music.bitchord.data.navidrome.NavidromeCanvasQuery.searchable(playing)
        val backdrop = withContext(Dispatchers.IO) { spotifyFor(song) }
        DesktopTrackLog.log("canvas: '${song.title}': ${if (backdrop != null) "+ Spotify backdrop" else "no Spotify backdrop"}")
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
        // Nothing found means Spotify, the last source asked, had nothing either: not asked twice.
        val backdrop = if (asksSpotify(first)) known(song.videoId) else null
        log(song, first, backdrop)
        return forkWithBackdrop(first?.toShared(), backdrop?.toShared())
    }

    private fun asksSpotify(first: DesktopCanvasArtwork?): Boolean =
        first != null && first.source != DesktopCanvasSource.SPOTIFY && ForkCanvasSwitch.backdrops.value

    /** Spotify's backdrop for the song when already found, without asking (null: none, or not asked yet). */
    private fun known(videoId: String): DesktopCanvasArtwork? = synchronized(spotify) { spotify[videoId] }?.artwork

    /** One Spotify search at a time (the object's lock), each answer kept in [spotify]. */
    @Synchronized
    private fun spotifyFor(song: Song): DesktopCanvasArtwork? {
        synchronized(spotify) { spotify[song.videoId] }?.let { known ->
            if (known.artwork != null || System.currentTimeMillis() - known.atMs < 10L * 60 * 1000) return known.artwork
        }
        // Without a sign-in nothing is asked and nothing remembered, so signing in later counts.
        if (DesktopSpotifyToken.accessToken() == null) return null
        if (com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) return null
        val title = song.title.cleanedForCanvas()
        val artist = song.artist.cleanedForCanvas()
        if (title.isBlank() || artist.isBlank()) return null
        val found = runCatching {
            DesktopSpotifyCanvas.search(title, artist, song.albumName)?.takeIf { it.matches(title, artist, song.albumName) }
        }
        // Remembered for a while either way: the search itself can't tell "no Canvas" from HTTP 429.
        val artwork = found.getOrNull()
        if (found.isSuccess) synchronized(spotify) { spotify[song.videoId] = Answer(artwork, System.currentTimeMillis()) }
        return artwork
    }

    private fun log(song: Song, first: DesktopCanvasArtwork?, backdrop: DesktopCanvasArtwork?) {
        val cover = when {
            first == null -> "none"
            first.source == DesktopCanvasSource.SPOTIFY -> "Spotify"
            else -> "a cover clip"
        }
        val extra = if (backdrop != null) " + Spotify backdrop (known)" else ""
        DesktopTrackLog.log("canvas: '${song.title}': $cover$extra")
    }
}
