package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.canvas.ForkAnonymousSpotifyCanvas
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkCanvasChoices
import com.music.bitchord.data.canvas.ForkCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasSettings
import com.music.bitchord.data.canvas.ForkClipGate
import com.music.bitchord.data.canvas.ForkCanvasStore
import com.music.bitchord.data.canvas.ForkDownloadedCanvas
import com.music.bitchord.data.canvas.ForkMusicVideoMatch
import com.music.bitchord.data.canvas.ForkVideoCanvas
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.player.ForkCanvasSwitch
import java.io.File

/**
 * Fork: the PC's canvas sources in the canvas rulebook's order (§1), the phone's
 * `ForkCanvasSources`: for [DesktopCanvasClient.lookup] (one hook line in place of its list) and
 * [ForkDesktopCanvas]'s second stage. See the phone's class for the reasoning; in short:
 *
 *  - the first stage asks the cover sources (Apple, then Tidal and the community index, the fork's
 *    extras after Apple), then the backdrop sources (the song's music video, then Spotify); the
 *    second stage asks the backdrop sources beside a cover, so the cover clip never waits for a loop
 *    to be cut, and what fills the screen is the video's loop, else Spotify's, else the cover;
 *  - every switch decides what is asked (rule 1.3), and is part of the in-memory key
 *    ([keySuffix], through `ForkSpotifyCanvasFirst.key`);
 *  - the rulebook's sources go through [ForkCanvasStore] (§2);
 *  - a downloaded song reads its clips saved by its id first, Spotify's canvas and then its art
 *    clip (rules 1.5 and 2.5, [ForkDownloadedCanvas]), and a clip found for it is kept by its id;
 *  - Spotify (rule 4.0): the listener's own sign-in first (`DesktopSpotifyCanvas`, its clip kept only
 *    on `scdn.co`), then, with no sign-in or when it finds nothing or fails, the anonymous lookup
 *    ([ForkAnonymousSpotifyCanvas]), else none.
 *
 * The client's `firstHit` moves its last source to the front when "Prioritize Spotify Canvas" is on
 * (`ForkSpotifyCanvasFirst.ordered`, for the album lists too). So [song] always ends with Spotify,
 * and puts the video first when Spotify is prioritised: Spotify, then the video, then the covers.
 */
internal object ForkDesktopCanvasSources {

    /** Where the music-video loops are cut and kept; set by [install]. */
    @Volatile
    var videoDir: File? = null

    private const val KEY_VIDEOS = "music_video_canvas"
    private val prefs = java.util.prefs.Preferences.userRoot().node("com/music/bitchord/navidrome_fork")

    /** The canvas settings (§13), kept beside the other fork settings in this node. */
    private val store = object : ForkCanvasSettings.Store {
        override fun get(key: String): String? = prefs.get(key, null)
        override fun put(key: String, value: String?) {
            if (value == null) prefs.remove(key) else prefs.put(key, value)
            runCatching { prefs.flush() } // a failed write stays queued (ForkFilePreferences retries it)
        }
    }

    /**
     * The saved lookups, the per-song choices and own best parts, the loops' folder, the "Music video
     * canvas" switch and the canvas settings; from `ForkNavidrome.install`.
     */
    fun install() {
        val dir = DesktopMediaCache.directory.resolve("fork_canvas").toFile()
        dir.mkdirs()
        ForkCanvasStore.init(dir)
        ForkCanvasChoices.init(dir)
        ForkBestParts.init(dir)
        videoDir = File(dir, "video")
        ForkCanvasSwitch.videos.value = prefs.get(KEY_VIDEOS, null) != "off"
        ForkCanvasSettings.load(store, ForkCanvasSwitch.videos.value)
        ForkCanvasSwitch.videos.value = ForkCanvasSettings.videoOn()
        if (ForkBestParts.takeOldButtonOff(store)) ForkPlayerSettings.setShowBestPart(false) // the fork's old Best part button, off: v1.10's pill off
    }

    /** "Music video canvas", the music video's switch in the sources list too. */
    fun setVideos(on: Boolean) {
        ForkCanvasSwitch.videos.value = on
        prefs.put(KEY_VIDEOS, if (on) "on" else "off")
        runCatching { prefs.flush() } // a failed write stays queued (ForkFilePreferences retries it)
        ForkCanvasSettings.setOn(ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO, on)
    }

    /**
     * The switches and settings that change which sources are asked, for the client's in-memory key:
     * the canvas settings' generation too (rule 13.10: a change is asked at once).
     */
    fun keySuffix(): String =
        "|fork=${ForkCanvasSwitch.backdrops.value}${ForkCanvasSwitch.videos.value}${ForkCanvasSwitch.covers.value}" +
            ForkCanvasSettings.keySuffix()

    /**
     * The order [song]'s sources are asked in now (rules 1-2 and 13.1-13.2), without the sources
     * switched off for the song and with its Use first (§15, [ForkCanvasChoices.orderFor]).
     */
    fun order(song: Song, spotifyFirst: Boolean = false): List<ForkCanvasRules.Source> =
        ForkCanvasChoices.orderFor(song.videoId, ForkCanvasSettings.effectiveOrder(ForkCanvasSwitch.backdrops.value, ForkCanvasSwitch.covers.value, spotifyFirst))

    /** Whether [song] gets a canvas at all: a song ([ForkClipGate.isSong]). */
    fun wanted(song: Song): Boolean = ForkClipGate.isSong(song)

    /** The client's sources for [song] ([title], [artist], [album] already cleaned); see the class comment. */
    fun song(song: Song, title: String, artist: String, album: String?): Array<suspend () -> DesktopCanvasArtwork?> {
        if (!wanted(song)) return emptyArray()
        val covers = covers(song, title, artist, album)
        val spotifyFirst = ForkSpotifyCanvasFirst.prioritized.value
        val backdrops = backdrops(song, title, artist, album, order(song, spotifyFirst))
        // The phone's order: the clips saved by id first (rule 1.5, they answer offline), then with
        // "Prioritise Spotify Canvas" the backdrops in their order (Spotify first) and the covers.
        // The client's firstHit moves the last source to the front when Spotify is prioritised, so
        // the list is handed over turned the other way ([ForkSpotifyCanvasFirst.forOrdered]). Moving
        // Spotify to the end for it put the community index first when Spotify was off, and a
        // network Spotify lookup before a downloaded song's saved clip (audit 2026-11-24).
        val asked = savedById(song) + if (spotifyFirst) backdrops.map { it.second } + covers else covers + backdrops.map { it.second }
        return ForkSpotifyCanvasFirst.forOrdered(asked, spotifyFirst).toTypedArray()
    }

    /** The backdrops worth asking beside a cover clip already shown (those ordered before the cover). */
    fun backdropsBeside(song: Song, title: String, artist: String, album: String?): List<Pair<ForkCanvasRules.Source, suspend () -> DesktopCanvasArtwork?>> {
        if (!wanted(song)) return emptyList()
        return backdrops(song, title, artist, album, ForkCanvasSettings.backdropsBesideCover(order(song)))
    }

    /**
     * Rule 1.5: a downloaded song's clips saved by its id, Spotify's canvas then its art clip, before
     * any source is asked (so they answer offline); each only while its switch would ask for it.
     */
    private fun savedById(song: Song): List<suspend () -> DesktopCanvasArtwork?> {
        if (!downloaded(song)) return emptyList()
        val keys = ForkDownloadedCanvas.keys(song.videoId, spotify = ForkCanvasSwitch.backdrops.value, covers = ForkCanvasSwitch.covers.value)
        return if (keys.isEmpty()) emptyList() else listOf<suspend () -> DesktopCanvasArtwork?>({ ForkDownloadedCanvas.saved(keys)?.toDesktop() })
    }

    /**
     * A song the download manager saved: a catalogue id and its file on this computer. A local file
     * (no id) has nothing to key on; a server song has its own clips first.
     */
    private fun downloaded(song: Song): Boolean =
        song.videoId.isNotBlank() && !NavidromeSettings.isNavidromeId(song.videoId) && DesktopDownloadManager.isSaved(song)

    /**
     * Apple, Tidal, the community index, while "Animated covers" is on and the list has Apple on; a
     * downloaded song's art clip is kept by its id. Apple's clip is the rendition "Animated cover
     * quality" asks for (rule 13.5), saved under a key with that quality.
     */
    fun covers(song: Song, title: String, artist: String, album: String?): List<suspend () -> DesktopCanvasArtwork?> {
        if (!ForkCanvasSwitch.covers.value || ForkCanvasRules.Source.APPLE_MUSIC !in order(song)) return emptyList()
        val idKey = if (downloaded(song)) ForkCanvasRules.downloadedKey(song.videoId) else null
        val appleKey = ForkCanvasRules.appleKey(title, artist) + ForkCanvasSettings.appleKeySuffix()
        // A cover found is noted for the song's menu (its Animated cover switch and Use, §15).
        val had = { a: DesktopCanvasArtwork? -> a?.also { ForkCanvasChoices.noteHad(song.videoId, ForkCanvasRules.Source.APPLE_MUSIC) } }
        return listOf<suspend () -> DesktopCanvasArtwork?>(
            { had(byId(idKey, title, artist, album) { cached(appleKey, saveMiss = album != null) { DesktopAppleMusicCanvas.search(title, artist, album)?.let(::rendition) } }) },
            { had(byId(idKey, title, artist, album) { DesktopTidalCanvas.search(title, artist, album) }) },
            { had(byId(idKey, title, artist, album) { DesktopCommunityCanvas.search(title, artist, album) }) },
        )
    }

    /** [ForkDownloadedCanvas.through] for the PC's own artwork type, kept when the client's own match takes it. */
    private suspend fun byId(key: String?, title: String, artist: String, album: String?, lookUp: suspend () -> DesktopCanvasArtwork?): DesktopCanvasArtwork? =
        ForkDownloadedCanvas.through(key, { it.toDesktop().matches(title, artist, album) }) { lookUp()?.toShared() }?.toDesktop()

    /** Apple's clip as the rendition rule 13.5 picks from its HLS master list (the master when it can't be read). */
    private fun rendition(art: DesktopCanvasArtwork): DesktopCanvasArtwork {
        val cap = ForkCanvasSettings.coverQuality.value.cap
        val read = { url: String -> runCatching { readText(url) }.getOrNull() }
        fun pick(url: String?) = url?.let { ForkCanvasSettings.variantUrl(it, cap, read) }
        return art.copy(url = pick(art.url) ?: art.url, fallbackUrl = pick(art.fallbackUrl))
    }

    private fun readText(url: String): String? {
        val request = okhttp3.Request.Builder().url(url).build()
        // capped (ForkCappedBody): an HLS master list is a few KB; anything past 256 KB is not one
        return com.music.bitchord.data.Http.client.newCall(request).execute().use {
            if (it.isSuccessful) com.music.bitchord.data.ForkCappedBody.text(it, 256 * com.music.bitchord.data.ForkCappedBody.KB) else null
        }
    }

    /**
     * The backdrop sources in [order] (the video, then Spotify, unless the list says otherwise), while
     * "Backdrop videos" is on; the video only for a song with an id and with "Music video canvas" on.
     */
    fun backdrops(
        song: Song, title: String, artist: String, album: String?,
        order: List<ForkCanvasRules.Source> = order(song),
    ): List<Pair<ForkCanvasRules.Source, suspend () -> DesktopCanvasArtwork?>> {
        val backdrops = order.filter { it.canvas }
            .filter { it != ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO || (ForkCanvasSwitch.videos.value && song.videoId.isNotBlank()) }
        return backdrops.map { source ->
            val ask: suspend () -> DesktopCanvasArtwork? = when (source) {
                ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO -> { { video(song, title, artist, album) } }
                else -> { { spotify(song, title, artist, album) } }
            }
            source to ask
        }
    }

    /** The music video's loop, keyed by the song's id plus the settings and choices asking for it (rules 13.6-13.7). */
    private suspend fun video(song: Song, title: String, artist: String, album: String?): DesktopCanvasArtwork? {
        val dir = videoDir ?: return null
        val choice = ForkCanvasChoices.of(song.videoId)
        val suffix = ForkCanvasSettings.videoKeySuffix() + ForkCanvasChoices.keySuffixOf(choice)
        val found = cached(ForkCanvasRules.videoKey(song.videoId) + suffix) {
            val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
            when (val r = ForkVideoCanvas.clip(song.videoId, title, artist, songMs, dir, 1080, ForkDesktopVideoCanvas, wish(song, choice, suffix))) {
                is ForkVideoCanvas.Result.Clip -> DesktopCanvasArtwork(url = r.file.absolutePath, title = title, artist = artist, album = album, source = DesktopCanvasSource.VIDEO)
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
     * (that video can't make a loop, or a request failed): nothing changes.
     */
    suspend fun tryPick(song: Song, videoId: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = videoDir ?: return@withContext false
        val next = ForkCanvasChoices.picking(ForkCanvasChoices.of(song.videoId), videoId)
        val suffix = ForkCanvasSettings.videoKeySuffix() + ForkCanvasChoices.keySuffixOf(next)
        val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
        val made = runCatching {
            ForkVideoCanvas.tryPicked(song.videoId, song.title, songMs, dir, 1080, ForkDesktopVideoCanvas, wish(song, next, suffix)) is ForkVideoCanvas.Result.Clip
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
    private suspend fun spotify(song: Song, title: String, artist: String, album: String?): DesktopCanvasArtwork? =
        byId(if (downloaded(song)) ForkCanvasRules.downloadedSpotifyKey(song.videoId) else null, title, artist, album) { spotifySearch(song, title, artist, album) }
            ?.also { ForkCanvasChoices.noteHad(song.videoId, ForkCanvasRules.Source.SPOTIFY) } // for the song's menu (§15)

    private suspend fun spotifySearch(song: Song, title: String, artist: String, album: String?): DesktopCanvasArtwork? =
        cached(ForkCanvasRules.spotifyKey(title, artist)) {
            signedInSpotify(title, artist, album) ?: run {
                val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
                ForkAnonymousSpotifyCanvas.search(title, artist, album, songMs.takeIf { it > 0 })?.toDesktop()
            }
        }

    /**
     * Rule 4.0's first step: the listener's own sign-in, or null to go on to the anonymous lookup
     * (no sign-in, nothing found, a clip not on `scdn.co` over https, or a failure; the token's
     * backoff and a thrown search are reported, so the song isn't saved as a miss then).
     */
    private fun signedInSpotify(title: String, artist: String, album: String?): DesktopCanvasArtwork? {
        if (DesktopSpotifyToken.cookie().isBlank()) return null
        val found = try {
            DesktopSpotifyCanvas.search(title, artist, album)
        } catch (e: Exception) {
            return com.music.bitchord.data.canvas.ForkCanvasLookup.failed("Spotify (signed in): ${e.message}")
        }
        if (found != null && !com.music.bitchord.data.canvas.ForkSpotifyCanvasRules.trusted(found.url)) {
            DesktopTrackLog.log("canvas: Spotify (signed in): the clip is not on scdn.co; not taken")
            return null
        }
        return found
    }

    /** [ForkCanvasStore.cached] for the PC's own artwork type. */
    private suspend fun cached(key: String, saveMiss: Boolean = true, lookUp: suspend () -> DesktopCanvasArtwork?): DesktopCanvasArtwork? =
        ForkCanvasStore.cached(key, saveMiss) { lookUp()?.toShared() }?.toDesktop()

    private fun CanvasArtwork.toDesktop() = DesktopCanvasArtwork(
        url = url,
        fallbackUrl = fallbackUrl,
        title = title,
        artist = artist,
        album = album,
        source = when (source) {
            CanvasSource.SPOTIFY -> DesktopCanvasSource.SPOTIFY
            CanvasSource.VIDEO -> DesktopCanvasSource.VIDEO
            else -> DesktopCanvasSource.OTHER
        },
    )
}

/**
 * Fork: "Music video canvas" under "Backdrop videos" in Settings, greyed (never hidden) while that
 * is off: the loop is a backdrop. The phone's row of the same name.
 */
@Composable
internal fun ForkMusicVideoCanvasRow() {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsState()
    val videos by ForkCanvasSwitch.videos.collectAsState()
    ForkDisabledWhen(!backdrops) {
        SettingsToggle(
            DesktopStrings["fork_music_video_canvas", "Music video canvas"],
            DesktopStrings[
                "fork_music_video_canvas_subtitle",
                "A backdrop cut from the song's own music video on YouTube, its most replayed part, tried before Spotify's Canvas",
            ],
            videos,
            ForkDesktopCanvasSources::setVideos,
        )
    }
}
