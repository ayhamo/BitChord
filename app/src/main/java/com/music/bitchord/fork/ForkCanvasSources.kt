package com.music.bitchord.fork

import android.content.res.Resources
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.AppleMusicCanvas
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.canvas.CommunityCanvas
import com.music.bitchord.data.canvas.ForkAnonymousSpotifyCanvas
import com.music.bitchord.data.canvas.ForkAppleCanvasRules
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkCanvasChoices
import com.music.bitchord.data.canvas.ForkCanvasLookup
import com.music.bitchord.data.canvas.ForkSpotifyCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasSettings
import com.music.bitchord.data.canvas.ForkClipGate
import com.music.bitchord.data.canvas.canvasGet
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
 * Spotify (rule 4.0): the listener's own sign-in first (`SpotifyCanvas`, upstream's, its clip kept
 * only on `scdn.co` over https, rule 4.6), then, with no sign-in or when it finds nothing or fails,
 * the anonymous lookup ([ForkAnonymousSpotifyCanvas]), else no Spotify canvas.
 */
object ForkCanvasSources {

    private const val TAG = "ForkCanvasSources"

    /** Where the music-video loops are cut and kept (the app's cache folder); set by `ForkSettings.init`. */
    @Volatile
    var videoDir: File? = null

    /** When Spotify's signed-in token last failed to mint, and for which sign-in; see [signedInSpotify]. */
    private val tokenMiss = ForkTokenMiss(ForkCanvas.MISS_MS)

    /**
     * The switches and settings that change which sources are asked, for the repository's in-memory
     * key: the master switches, and the canvas settings' generation (rule 13.10: a changed order,
     * source, quality, mode or per-song choice is a new question, asked at once).
     */
    fun keySuffix(): String =
        "|fork=${ForkCanvasSwitch.backdrops.value}${ForkCanvasSwitch.videos.value}${ForkCanvasSwitch.covers.value}" +
            ForkCanvasSettings.keySuffix()

    /**
     * The order [song]'s sources are asked in now (rules 1-2 and 13.1-13.2): the settings' list and
     * switches, without the sources switched off for the song and with its Use first (§15,
     * [ForkCanvasChoices.orderFor]).
     */
    fun order(song: Song, spotifyFirst: Boolean = false): List<ForkCanvasRules.Source> =
        ForkCanvasChoices.orderFor(song.videoId, ForkCanvasSettings.effectiveOrder(ForkCanvasSwitch.backdrops.value, ForkCanvasSwitch.covers.value, spotifyFirst))

    /** Whether [song] gets a canvas at all: a song, not a device file or a live station ([ForkClipGate.isSong]). */
    fun wanted(song: Song): Boolean = ForkClipGate.isSong(song)

    /**
     * The repository's sources for [song] ([title], [artist], [album] already cleaned), in the order
     * the class comment gives. [spotifyFirst]: "Prioritise Spotify Canvas".
     *
     * With the settings' order: the cover sources come first when the order has Apple at all (a
     * cover is found in a moment and the second stage adds a backdrop ordered before it), then the
     * backdrop sources in their order, for a song with no cover.
     */
    fun song(song: Song, title: String, artist: String, album: String?, spotifyFirst: Boolean): Array<suspend () -> CanvasArtwork?> {
        if (!wanted(song)) return emptyArray()
        val covers = covers(song, title, artist, album)
        val backdrops = backdrops(song, title, artist, album, order(song, spotifyFirst))
        val ordered = if (spotifyFirst) backdrops.map { it.second } + covers
        else covers + backdrops.map { it.second }
        return (savedById(song) + ordered).toTypedArray()
    }

    /** The backdrops worth asking beside a cover clip already shown (those ordered before the cover). */
    fun backdropsBeside(song: Song, title: String, artist: String, album: String?): List<Pair<ForkCanvasRules.Source, suspend () -> CanvasArtwork?>> {
        if (!wanted(song)) return emptyList()
        return backdrops(song, title, artist, album, ForkCanvasSettings.backdropsBesideCover(order(song)))
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

    /**
     * Apple, Tidal, the community index, while "Animated covers" is on and the list has Apple on; a
     * downloaded song's art clip is kept by its id. Apple's clip is the rendition the "Animated cover
     * quality" asks for (rule 13.5), and its saved answer is keyed with that quality.
     */
    fun covers(song: Song, title: String, artist: String, album: String?): List<suspend () -> CanvasArtwork?> {
        if (!ForkCanvasSwitch.covers.value || ForkCanvasRules.Source.APPLE_MUSIC !in order(song)) return emptyList()
        val idKey = if (downloaded(song)) ForkCanvasRules.downloadedKey(song.videoId) else null
        val accept = { a: CanvasArtwork -> a.matches(title, artist, album) }
        val appleKey = ForkCanvasRules.appleKey(title, artist) + ForkCanvasSettings.appleKeySuffix()
        // A cover found is noted for the song's menu (its Animated cover switch and Use, §15).
        val had = { a: CanvasArtwork? -> a?.also { ForkCanvasChoices.noteHad(song.videoId, ForkCanvasRules.Source.APPLE_MUSIC) } }
        return listOf<suspend () -> CanvasArtwork?>(
            { had(ForkDownloadedCanvas.through(idKey, accept) { ForkCanvasStore.cached(appleKey, saveMiss = album != null) { AppleMusicCanvas.search(title, artist, album)?.let { rendition(it) } }?.let(ForkAppleCanvasRules::trustedArt) }) },
            { had(ForkDownloadedCanvas.through(idKey, accept) { TidalCanvas.search(title, artist, album) }) },
            { had(ForkDownloadedCanvas.through(idKey, accept) { CommunityCanvas.search(title, artist, album) }) },
        )
    }

    /**
     * Apple's clip as the rendition rule 13.5 picks from its HLS master list (the master itself when
     * the list can't be read; the player then picks). Each of the clip's URLs is resolved, the tall
     * one too: it is a master list of its own (`AppleMusicCanvas.tallUrl`), played full screen on the
     * phone and in a lyrics story, and left unresolved it ignored "Animated cover quality" there.
     * [read]: the list's text (the network; a test passes its own).
     */
    internal fun rendition(art: CanvasArtwork, read: (String) -> String? = { url -> canvasGet(url) }): CanvasArtwork? {
        // Audit 2026-11-26: variantUrl hands an untrusted master back unchanged, so Apple's answer
        // is held to Apple's https hosts first (no read of an untrusted list), and the picked
        // renditions again after.
        val trusted = ForkAppleCanvasRules.trustedArt(art) ?: return null
        val cap = ForkCanvasSettings.coverQuality.value.cap
        fun pick(url: String?) = url?.let { ForkCanvasSettings.variantUrl(it, cap, read) }
        return ForkAppleCanvasRules.trustedArt(
            trusted.copy(url = pick(trusted.url) ?: trusted.url, fallbackUrl = pick(trusted.fallbackUrl), portraitUrl = pick(trusted.portraitUrl)),
        )
    }

    /**
     * The backdrop sources in [order] (the video, then Spotify, unless the list says otherwise), while
     * "Backdrop videos" is on; the video only for a song with an id and with "Music video canvas" on.
     */
    fun backdrops(
        song: Song, title: String, artist: String, album: String?,
        order: List<ForkCanvasRules.Source> = order(song),
    ): List<Pair<ForkCanvasRules.Source, suspend () -> CanvasArtwork?>> {
        val backdrops = order.filter { it.canvas }
            .filter { it != ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO || (ForkCanvasSwitch.videos.value && song.videoId.isNotBlank()) }
        return backdrops.map { source ->
            val ask: suspend () -> CanvasArtwork? = when (source) {
                ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO -> { { video(song, title, artist, album) } }
                else -> { { spotify(song, title, artist, album) } }
            }
            source to ask
        }
    }

    /**
     * The music video's loop, keyed by the song's id plus what the settings and the listener's
     * choices for the song ask (rules 13.6-13.7, [ForkCanvasChoices]), so each is its own answer.
     */
    private suspend fun video(song: Song, title: String, artist: String, album: String?): CanvasArtwork? {
        val dir = videoDir ?: return null
        val choice = ForkCanvasChoices.of(song.videoId)
        val suffix = ForkCanvasSettings.videoKeySuffix() + ForkCanvasChoices.keySuffixOf(choice)
        val found = ForkCanvasStore.cached(ForkCanvasRules.videoKey(song.videoId) + suffix) {
            val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
            when (val r = ForkVideoCanvas.clip(song.videoId, title, artist, songMs, dir, artPx(), ForkVideoCanvasAndroid, wish(song, choice, suffix))) {
                is ForkVideoCanvas.Result.Clip -> CanvasArtwork(url = Uri.fromFile(r.file).toString(), title = title, artist = artist, album = album, source = CanvasSource.VIDEO)
                ForkVideoCanvas.Result.None -> null
            }
        }
        // What the loop is (its video, part and runners-up), for the song's menu (§15): read back
        // from its framing file, so a saved answer notes it too.
        val framing = found?.url?.let(ForkVideoCanvas::framing)
        if (found == null) ForkCanvasChoices.noteLoop(song.videoId, null, null, emptyList())
        else if (framing?.video != null) ForkCanvasChoices.noteLoop(song.videoId, framing.video, framing.startMs, framing.others)
        return found
    }

    private fun artPx() = Resources.getSystem().displayMetrics.widthPixels

    /** What the loop for [song] is cut as: the settings' quality and mode, [choice], the chorus the player had. */
    private fun wish(song: Song, choice: ForkCanvasChoices.Choice, suffix: String): ForkVideoCanvas.Wish {
        val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
        return ForkVideoCanvas.Wish(
            height = ForkCanvasSettings.videoQuality.value.height,
            full = ForkCanvasSettings.videoMode.value == ForkCanvasSettings.VideoMode.FULL,
            choice = choice,
            chorusMs = ForkBestParts.chorusMs(ForkBestParts.lines(song.videoId), songMs),
            tag = ForkVideoCanvas.tagOf(suffix),
        )
    }

    /**
     * "Pick another video" (§15 rule 6): [videoId] cut as the song's own, before the pick is kept.
     * True when it made a loop: the pick is then kept and the lookup it starts finds that loop. False
     * (that video can't make a loop, or a request failed): nothing changes. Off the main thread.
     */
    suspend fun tryPick(song: Song, videoId: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = videoDir ?: return@withContext false
        val next = ForkCanvasChoices.picking(ForkCanvasChoices.of(song.videoId), videoId)
        val suffix = ForkCanvasSettings.videoKeySuffix() + ForkCanvasChoices.keySuffixOf(next)
        val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
        val made = runCatching {
            ForkVideoCanvas.tryPicked(song.videoId, song.title, songMs, dir, artPx(), ForkVideoCanvasAndroid, wish(song, next, suffix)) is ForkVideoCanvas.Result.Clip
        }.onFailure {
            // A cancelled pick stops; it isn't a pick that failed (audit 2026-11-24 2a #9), as in
            // ForkCanvas.backdropFor and signedInSpotify.
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "the picked video was not tried: ${it.message}")
        }.getOrDefault(false)
        if (made) ForkCanvasChoices.pick(song.videoId, videoId)
        made
    }

    /** "Not this video" (§15 rule 8): the loop's video turned away for good, and its loops deleted. */
    fun rejectVideo(song: Song) {
        val video = ForkCanvasChoices.reject(song.videoId) ?: return
        videoDir?.let { ForkVideoCanvas.forget(it, song.videoId, video) }
    }

    /** Spotify's canvas; a downloaded song's is read and kept by its id too (rule 2.5). */
    private suspend fun spotify(song: Song, title: String, artist: String, album: String?): CanvasArtwork? =
        ForkDownloadedCanvas.through(
            if (downloaded(song)) ForkCanvasRules.downloadedSpotifyKey(song.videoId) else null,
            { it.matches(title, artist, album) },
        ) { spotifySearch(song, title, artist, album) }
            ?.also { ForkCanvasChoices.noteHad(song.videoId, ForkCanvasRules.Source.SPOTIFY) } // for the song's menu (§15)

    private suspend fun spotifySearch(song: Song, title: String, artist: String, album: String?): CanvasArtwork? =
        ForkCanvasStore.cached(ForkCanvasRules.spotifyKey(title, artist)) {
            signedInSpotify(title, artist, album) ?: run {
                val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
                ForkAnonymousSpotifyCanvas.search(title, artist, album, songMs.takeIf { it > 0 })
            }
        }

    /**
     * Rule 4.0's first step: the listener's own sign-in, or null to go on to the anonymous lookup
     * (no sign-in, nothing found, an untrusted clip, or a failure; a failure is reported, so the
     * song isn't saved as a miss while the sign-in could not be asked).
     */
    private suspend fun signedInSpotify(title: String, artist: String, album: String?): CanvasArtwork? {
        val cookie = AppSettings.spotifySpdcToken.value
        if (cookie.isBlank()) return null
        // A sign-in whose token can't be minted (no network, offline mode, a harvest timing out) is
        // left alone for ForkCanvas.MISS_MS: the WebView harvest waits up to 20 s
        // (SpotifyToken.HARVEST_TIMEOUT_MS), and asked for each song in turn a dead sign-in held one
        // open for every song of a session. The wait belongs to that sign-in and those settings
        // ([ForkTokenMiss]): a new sign-in, or a changed canvas setting (canvas.md §13 rule 10), is
        // asked at once.
        val sameAsk = ForkTokenMiss.keyOf(cookie, ForkCanvasSettings.generation.value)
        tokenMiss.retryAt(sameAsk, System.currentTimeMillis())?.let { return ForkCanvasLookup.coolingDown("Spotify: the sign-in has no token", it) }
        if (SpotifyToken.accessToken() == null) {
            // Signed out while it minted: nothing to hold back, the next sign-in is a new one.
            if (AppSettings.spotifySpdcToken.value != cookie) return null
            val at = tokenMiss.missed(sameAsk, System.currentTimeMillis())
            Log.d(TAG, "Spotify sign-in has no token; not asked for a while")
            return ForkCanvasLookup.coolingDown("Spotify: the sign-in has no token", at)
        }
        val found = try {
            SpotifyCanvas.search(title, artist, album)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ForkCanvasLookup.failed("Spotify (signed in): ${e.message}")
        }
        if (found != null && !ForkSpotifyCanvasRules.trusted(found.url)) {
            Log.d(TAG, "Spotify (signed in): the clip is not on scdn.co; not taken")
            return null
        }
        return found
    }
}

/**
 * The 10-minute wait after Spotify's signed-in token failed to mint, kept per sign-in and canvas
 * settings ([keyOf]): a wait taken under one is no wait under another.
 *
 * Why (audit 2026-11-26 2a #1): it was one timestamp nothing cleared. `SpotifyToken.invalidate` (a new
 * sign-in) clears its own harvest backoffs and the lookup notes so a new sign-in is asked at once, but
 * this wait short-circuited before `SpotifyToken.accessToken` was called, so signed-in Spotify Canvas
 * stayed off for up to 10 minutes after signing in again, or after changing a canvas setting
 * (canvas.md §13 rule 10: a changed setting is a new question, asked at once).
 */
internal class ForkTokenMiss(private val waitMs: Long) {
    private class Miss(val key: String, val at: Long)

    @Volatile
    private var last: Miss? = null

    /** When [key] may be asked again, or null when it may be asked now. */
    fun retryAt(key: String, now: Long): Long? =
        last?.takeIf { it.key == key }?.let { it.at + waitMs }?.takeIf { now < it }

    /** [key]'s token failed to mint at [now]; returns when it may be asked again. */
    fun missed(key: String, now: Long): Long {
        last = Miss(key, now)
        return now + waitMs
    }

    companion object {
        /** A sign-in (by a hash of its cookie, never the cookie) and the canvas settings' generation. */
        fun keyOf(cookie: String, generation: Int): String = "${cookie.hashCode()}:$generation"
    }
}
