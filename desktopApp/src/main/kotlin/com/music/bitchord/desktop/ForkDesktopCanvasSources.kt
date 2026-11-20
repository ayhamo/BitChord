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
 *  - Spotify: the signed-in route (`DesktopSpotifyCanvas`) with an `sp_dc` cookie, else the
 *    rulebook's anonymous one ([ForkAnonymousSpotifyCanvas]).
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
        ForkBestParts.loadSettings(store)
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

    /** The order the sources are asked in now (rules 1-2 and 13.1-13.2). */
    private fun order(spotifyFirst: Boolean = false): List<ForkCanvasRules.Source> =
        ForkCanvasSettings.effectiveOrder(ForkCanvasSwitch.backdrops.value, ForkCanvasSwitch.covers.value, spotifyFirst)

    /** Whether [song] gets a canvas at all: a song ([ForkClipGate.isSong]) not switched off for ([ForkCanvasChoices]). */
    fun wanted(song: Song): Boolean = ForkClipGate.isSong(song) && !ForkCanvasChoices.of(song.videoId).off

    /** The client's sources for [song] ([title], [artist], [album] already cleaned); see the class comment. */
    fun song(song: Song, title: String, artist: String, album: String?): Array<suspend () -> DesktopCanvasArtwork?> {
        if (!wanted(song)) return emptyArray()
        val covers = covers(song, title, artist, album)
        val spotifyFirst = ForkSpotifyCanvasFirst.prioritized.value
        val backdrops = backdrops(song, title, artist, album, order(spotifyFirst))
        // The client's firstHit moves the last source to the front when Spotify is prioritised, so
        // Spotify goes last then (the same order it asks in: Spotify, the video, the covers).
        val spotify = backdrops.filter { it.first == ForkCanvasRules.Source.SPOTIFY }.map { it.second }
        val others = backdrops.filter { it.first != ForkCanvasRules.Source.SPOTIFY }.map { it.second }
        return (savedById(song) + if (spotifyFirst) others + covers + spotify else covers + backdrops.map { it.second }).toTypedArray()
    }

    /** The backdrops worth asking beside a cover clip already shown (those ordered before the cover). */
    fun backdropsBeside(song: Song, title: String, artist: String, album: String?): List<Pair<ForkCanvasRules.Source, suspend () -> DesktopCanvasArtwork?>> {
        if (!wanted(song)) return emptyList()
        return backdrops(song, title, artist, album, ForkCanvasSettings.backdropsBesideCover(order()))
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
        if (!ForkCanvasSwitch.covers.value || ForkCanvasRules.Source.APPLE_MUSIC !in order()) return emptyList()
        val idKey = if (downloaded(song)) ForkCanvasRules.downloadedKey(song.videoId) else null
        val appleKey = ForkCanvasRules.appleKey(title, artist) + ForkCanvasSettings.appleKeySuffix()
        return listOf<suspend () -> DesktopCanvasArtwork?>(
            { byId(idKey, title, artist, album) { cached(appleKey, saveMiss = album != null) { DesktopAppleMusicCanvas.search(title, artist, album)?.let(::rendition) } } },
            { byId(idKey, title, artist, album) { DesktopTidalCanvas.search(title, artist, album) } },
            { byId(idKey, title, artist, album) { DesktopCommunityCanvas.search(title, artist, album) } },
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
        return com.music.bitchord.data.Http.client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    /**
     * The backdrop sources in [order] (the video, then Spotify, unless the list says otherwise), while
     * "Backdrop videos" is on; the video only for a song with an id and with "Music video canvas" on.
     */
    fun backdrops(
        song: Song, title: String, artist: String, album: String?,
        order: List<ForkCanvasRules.Source> = order(),
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
        val suffix = ForkCanvasSettings.videoKeySuffix() + ForkCanvasChoices.keySuffix(song.videoId)
        return cached(ForkCanvasRules.videoKey(song.videoId) + suffix) {
            val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
            val wish = ForkVideoCanvas.Wish(
                height = ForkCanvasSettings.videoQuality.value.height,
                full = ForkCanvasSettings.videoMode.value == ForkCanvasSettings.VideoMode.FULL,
                choice = ForkCanvasChoices.of(song.videoId),
                chorusMs = ForkBestParts.chorusMs(ForkBestParts.lines(song.videoId), songMs),
                tag = ForkVideoCanvas.tagOf(suffix),
            )
            when (val r = ForkVideoCanvas.clip(song.videoId, title, artist, songMs, dir, 1080, ForkDesktopVideoCanvas, wish)) {
                is ForkVideoCanvas.Result.Clip -> {
                    ForkCanvasChoices.noteVideo(song.videoId, r.videoId)
                    DesktopCanvasArtwork(url = r.file.absolutePath, title = title, artist = artist, album = album, source = DesktopCanvasSource.VIDEO)
                }
                ForkVideoCanvas.Result.None -> null
            }
        }
    }

    /** Spotify's canvas; a downloaded song's is read and kept by its id too (rule 2.5). */
    private suspend fun spotify(song: Song, title: String, artist: String, album: String?): DesktopCanvasArtwork? =
        byId(if (downloaded(song)) ForkCanvasRules.downloadedSpotifyKey(song.videoId) else null, title, artist, album) { spotifySearch(song, title, artist, album) }

    private suspend fun spotifySearch(song: Song, title: String, artist: String, album: String?): DesktopCanvasArtwork? =
        cached(ForkCanvasRules.spotifyKey(title, artist)) {
            if (DesktopSpotifyToken.cookie().isBlank()) {
                val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
                ForkAnonymousSpotifyCanvas.search(title, artist, album, songMs.takeIf { it > 0 })?.toDesktop()
            } else {
                DesktopSpotifyCanvas.search(title, artist, album)
            }
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
