package com.music.bitchord.fork

import android.content.res.Resources
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.AppleMusicCanvas
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.canvas.CommunityCanvas
import com.music.bitchord.data.canvas.ForkAnonymousSpotifyCanvas
import com.music.bitchord.data.canvas.ForkCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasStore
import com.music.bitchord.data.canvas.ForkDownloadedCanvas
import com.music.bitchord.data.canvas.ForkMusicVideoMatch
import com.music.bitchord.data.canvas.ForkVideoCanvas
import com.music.bitchord.data.canvas.SpotifyCanvas
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.canvas.TidalCanvas
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.player.ForkCanvasSwitch
import java.io.File

/**
 * Fork: the phone's canvas sources in the canvas rulebook's order (§1), for `CanvasRepository`
 * (one hook line in place of its two hard-coded lists) and [ForkCanvas]'s second stage.
 *
 * The rulebook asks the song's music video, then Spotify, then Apple; what fills the screen is the
 * first of those with a clip. The fork's player has two stages ([ForkCanvas]), and that stays: a
 * motion cover is found in a moment, a music-video loop takes seconds to cut the first time, and the
 * cover clip must not wait for it. So the first stage asks the cover sources first (Apple, then
 * Tidal and the community index, which the rulebook doesn't have and the fork keeps after Apple),
 * and only with no cover the backdrop sources ([backdrops]: the video, then Spotify); the second
 * stage asks [backdrops] beside a cover and makes the first one found the backdrop. What fills the
 * screen is therefore the video's loop, else Spotify's Canvas, else the cover clip: the rulebook's
 * order. "Prioritise Spotify Canvas" is the rulebook's custom order (rule 1.4): Spotify first, then
 * the video, then the covers, in one stage as upstream had it.
 *
 * Each switch decides what is asked (rule 1.3), so a switch that is off costs no requests:
 * "Backdrop videos" for the video and Spotify, "Music video canvas" for the video, "Animated
 * covers" for the cover sources. [keySuffix] puts them in the repository's in-memory key, so a
 * flipped switch is a new question.
 *
 * Every rulebook source goes through [ForkCanvasStore] (§2): a found clip is kept, a miss for a day,
 * a failed lookup not at all. The music video is keyed by the song's id, Spotify and Apple by title
 * and artist; Apple's miss is kept only when the album was known (the fork searches with it).
 *
 * A downloaded song ([downloaded]) reads its clips saved by its id first, Spotify's canvas and then
 * its art clip (rules 1.5 and 2.5, [ForkDownloadedCanvas]), and a clip found for it is kept by its id.
 *
 * Spotify: with the listener's `sp_dc` cookie set, the signed-in route (`SpotifyCanvas`, upstream's)
 * as before; without one, the rulebook's anonymous route ([ForkAnonymousSpotifyCanvas]), where the
 * fork had none at all.
 */
object ForkCanvasSources {

    private const val TAG = "ForkCanvasSources"

    /** Where the music-video loops are cut and kept (the app's cache folder); set by `ForkSettings.init`. */
    @Volatile
    var videoDir: File? = null

    /** When Spotify's signed-in token last failed to mint; see [spotify]. */
    @Volatile
    private var tokenMissedAt = 0L

    /** The switches that change which sources are asked, for the repository's in-memory key. */
    fun keySuffix(): String =
        "|fork=${ForkCanvasSwitch.backdrops.value}${ForkCanvasSwitch.videos.value}${ForkCanvasSwitch.covers.value}"

    /**
     * The repository's sources for [song] ([title], [artist], [album] already cleaned), in the order
     * the class comment gives. [spotifyFirst]: "Prioritise Spotify Canvas".
     */
    fun song(song: Song, title: String, artist: String, album: String?, spotifyFirst: Boolean): Array<suspend () -> CanvasArtwork?> {
        val covers = covers(song, title, artist, album)
        val backdrops = backdrops(song, title, artist, album)
        val ordered = if (spotifyFirst) backdrops.sortedBy { if (it.first == ForkCanvasRules.Source.SPOTIFY) 0 else 1 }.map { it.second } + covers
        else covers + backdrops.map { it.second }
        return (savedById(song) + ordered).toTypedArray()
    }

    /**
     * Rule 1.5: a downloaded song's clips saved by its id, Spotify's canvas then its art clip, before
     * any source is asked (so they answer offline); each only while its switch would ask for it.
     */
    private fun savedById(song: Song): List<suspend () -> CanvasArtwork?> {
        if (!downloaded(song)) return emptyList()
        val keys = ForkDownloadedCanvas.keys(song.videoId, spotify = ForkCanvasSwitch.backdrops.value, covers = ForkCanvasSwitch.covers.value)
        return if (keys.isEmpty()) emptyList() else listOf<suspend () -> CanvasArtwork?>({ ForkDownloadedCanvas.saved(keys) })
    }

    /**
     * A song saved by Downloads: its id names a saved file. A server song is left out: it has its own
     * clips first (`NavidromeClips`), and "Available offline" is the server's, not a catalogue download.
     */
    private fun downloaded(song: Song): Boolean =
        song.videoId.isNotBlank() && !NavidromeSettings.isNavidromeId(song.videoId) &&
            song.videoId in com.music.bitchord.download.Downloads.saved.value

    /** Apple, Tidal, the community index, while "Animated covers" is on; a downloaded song's art clip is kept by its id. */
    fun covers(song: Song, title: String, artist: String, album: String?): List<suspend () -> CanvasArtwork?> {
        if (!ForkCanvasSwitch.covers.value) return emptyList()
        val idKey = if (downloaded(song)) ForkCanvasRules.downloadedKey(song.videoId) else null
        val accept = { a: CanvasArtwork -> a.matches(title, artist, album) }
        return listOf<suspend () -> CanvasArtwork?>(
            { ForkDownloadedCanvas.through(idKey, accept) { ForkCanvasStore.cached(ForkCanvasRules.appleKey(title, artist), saveMiss = album != null) { AppleMusicCanvas.search(title, artist, album) } } },
            { ForkDownloadedCanvas.through(idKey, accept) { TidalCanvas.search(title, artist, album) } },
            { ForkDownloadedCanvas.through(idKey, accept) { CommunityCanvas.search(title, artist, album) } },
        )
    }

    /** The backdrop sources in the rulebook's order (the video, then Spotify), while "Backdrop videos" is on. */
    fun backdrops(song: Song, title: String, artist: String, album: String?): List<Pair<ForkCanvasRules.Source, suspend () -> CanvasArtwork?>> {
        val order = ForkCanvasRules.order(
            enabled = buildSet {
                if (ForkCanvasSwitch.videos.value && song.videoId.isNotBlank()) add(ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO)
                add(ForkCanvasRules.Source.SPOTIFY)
            },
            canvasOn = ForkCanvasSwitch.backdrops.value,
        )
        return order.map { source ->
            val ask: suspend () -> CanvasArtwork? = when (source) {
                ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO -> { { video(song, title, artist, album) } }
                else -> { { spotify(song, title, artist, album) } }
            }
            source to ask
        }
    }

    private suspend fun video(song: Song, title: String, artist: String, album: String?): CanvasArtwork? {
        val dir = videoDir ?: return null
        return ForkCanvasStore.cached(ForkCanvasRules.videoKey(song.videoId)) {
            val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
            val artPx = Resources.getSystem().displayMetrics.widthPixels
            when (val r = ForkVideoCanvas.clip(song.videoId, title, artist, songMs, dir, artPx, ForkVideoCanvasAndroid)) {
                is ForkVideoCanvas.Result.Clip ->
                    CanvasArtwork(url = Uri.fromFile(r.file).toString(), title = title, artist = artist, album = album, source = CanvasSource.VIDEO)
                ForkVideoCanvas.Result.None -> null
            }
        }
    }

    /** Spotify's canvas; a downloaded song's is read and kept by its id too (rule 2.5). */
    private suspend fun spotify(song: Song, title: String, artist: String, album: String?): CanvasArtwork? =
        ForkDownloadedCanvas.through(
            if (downloaded(song)) ForkCanvasRules.downloadedSpotifyKey(song.videoId) else null,
            { it.matches(title, artist, album) },
        ) { spotifySearch(song, title, artist, album) }

    private suspend fun spotifySearch(song: Song, title: String, artist: String, album: String?): CanvasArtwork? {
        if (AppSettings.spotifySpdcToken.value.isBlank()) {
            val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
            return ForkCanvasStore.cached(ForkCanvasRules.spotifyKey(title, artist)) {
                ForkAnonymousSpotifyCanvas.search(title, artist, album, songMs.takeIf { it > 0 })
            }
        }
        // A sign-in whose token can't be minted (no network, offline mode, a harvest timing out) is
        // left alone for ForkCanvas.MISS_MS: the WebView harvest waits up to 20 s
        // (SpotifyToken.HARVEST_TIMEOUT_MS), and asked for each song in turn a dead sign-in held one
        // open for every song of a session. Not a miss for the song: nothing is saved.
        if (System.currentTimeMillis() - tokenMissedAt < ForkCanvas.MISS_MS) return null
        if (SpotifyToken.accessToken() == null) {
            tokenMissedAt = System.currentTimeMillis()
            Log.d(TAG, "Spotify sign-in has no token; not asked for a while")
            return null
        }
        return ForkCanvasStore.cached(ForkCanvasRules.spotifyKey(title, artist)) { SpotifyCanvas.search(title, artist, album) }
    }
}
