package com.music.bitchord.desktop

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.ForkCanvasSwitch
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
 * Every lookup logs one line, so "nothing found" can be told from "never asked".
 */
internal object ForkDesktopCanvas {

    private class Answer(val artwork: DesktopCanvasArtwork?, val atMs: Long)

    /** Spotify's answer per song, the last 64; a miss is asked again after ten minutes. */
    private val spotify = object : LinkedHashMap<String, Answer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>) = size > 64
    }

    suspend fun canvasFor(song: Song): CanvasArtwork? {
        com.music.bitchord.data.navidrome.NavidromeClips.canvasFor(song)?.let { return it } // kept clips from before
        val first = withContext(Dispatchers.IO) { DesktopCanvasClient.lookup(song) }
        // Nothing found means Spotify, the last source asked, had nothing either: not asked twice.
        if (first == null || first.source == DesktopCanvasSource.SPOTIFY || !ForkCanvasSwitch.backdrops.value) {
            log(song, first, null)
            return first?.toShared()
        }
        val backdrop = withContext(Dispatchers.IO) { spotifyFor(song) }
        log(song, first, backdrop)
        if (backdrop == null) return first?.toShared()
        return backdrop.toShared().copy(source = CanvasSource.SPOTIFY, sleeveUrl = first?.url)
    }

    @Synchronized
    private fun spotifyFor(song: Song): DesktopCanvasArtwork? {
        spotify[song.videoId]?.let { known ->
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
        if (found.isSuccess) spotify[song.videoId] = Answer(artwork, System.currentTimeMillis())
        return artwork
    }

    private fun log(song: Song, first: DesktopCanvasArtwork?, backdrop: DesktopCanvasArtwork?) {
        val cover = when {
            first == null -> "none"
            first.source == DesktopCanvasSource.SPOTIFY -> "Spotify"
            else -> "a cover clip"
        }
        val extra = if (backdrop != null) " + Spotify backdrop" else ""
        DesktopTrackLog.log("canvas: '${song.title}': $cover$extra")
    }
}
