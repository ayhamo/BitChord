package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.canvas.ForkAnonymousSpotifyCanvas
import com.music.bitchord.data.canvas.ForkCanvasRules
import com.music.bitchord.data.canvas.ForkCanvasStore
import com.music.bitchord.data.canvas.ForkMusicVideoMatch
import com.music.bitchord.data.canvas.ForkVideoCanvas
import com.music.bitchord.data.model.Song
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

    /** The saved lookups and the loops' folder, and the "Music video canvas" switch; from `ForkNavidrome.install`. */
    fun install() {
        val dir = DesktopMediaCache.directory.resolve("fork_canvas").toFile()
        dir.mkdirs()
        ForkCanvasStore.init(dir)
        videoDir = File(dir, "video")
        ForkCanvasSwitch.videos.value = prefs.get(KEY_VIDEOS, null) != "off"
    }

    fun setVideos(on: Boolean) {
        ForkCanvasSwitch.videos.value = on
        prefs.put(KEY_VIDEOS, if (on) "on" else "off")
        runCatching { prefs.flush() } // a failed write stays queued (ForkFilePreferences retries it)
    }

    /** The switches that change which sources are asked, for the client's in-memory key. */
    fun keySuffix(): String =
        "|fork=${ForkCanvasSwitch.backdrops.value}${ForkCanvasSwitch.videos.value}${ForkCanvasSwitch.covers.value}"

    /** The client's sources for [song] ([title], [artist], [album] already cleaned); see the class comment. */
    fun song(song: Song, title: String, artist: String, album: String?): Array<suspend () -> DesktopCanvasArtwork?> {
        val covers = covers(title, artist, album)
        val backdrops = backdrops(song, title, artist, album)
        val video = backdrops.filter { it.first == ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO }.map { it.second }
        val spotify = backdrops.filter { it.first == ForkCanvasRules.Source.SPOTIFY }.map { it.second }
        return (if (ForkSpotifyCanvasFirst.prioritized.value) video + covers + spotify else covers + video + spotify).toTypedArray()
    }

    /** Apple, Tidal, the community index, while "Animated covers" is on. */
    fun covers(title: String, artist: String, album: String?): List<suspend () -> DesktopCanvasArtwork?> {
        if (!ForkCanvasSwitch.covers.value) return emptyList()
        return listOf<suspend () -> DesktopCanvasArtwork?>(
            { cached(ForkCanvasRules.appleKey(title, artist), saveMiss = album != null) { DesktopAppleMusicCanvas.search(title, artist, album) } },
            { DesktopTidalCanvas.search(title, artist, album) },
            { DesktopCommunityCanvas.search(title, artist, album) },
        )
    }

    /** The backdrop sources in the rulebook's order (the video, then Spotify), while "Backdrop videos" is on. */
    fun backdrops(song: Song, title: String, artist: String, album: String?): List<Pair<ForkCanvasRules.Source, suspend () -> DesktopCanvasArtwork?>> {
        val order = ForkCanvasRules.order(
            enabled = buildSet {
                if (ForkCanvasSwitch.videos.value && song.videoId.isNotBlank()) add(ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO)
                add(ForkCanvasRules.Source.SPOTIFY)
            },
            canvasOn = ForkCanvasSwitch.backdrops.value,
        )
        return order.map { source ->
            val ask: suspend () -> DesktopCanvasArtwork? = when (source) {
                ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO -> { { video(song, title, artist, album) } }
                else -> { { spotify(song, title, artist, album) } }
            }
            source to ask
        }
    }

    private suspend fun video(song: Song, title: String, artist: String, album: String?): DesktopCanvasArtwork? {
        val dir = videoDir ?: return null
        return cached(ForkCanvasRules.videoKey(song.videoId)) {
            val songMs = ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
            when (val r = ForkVideoCanvas.clip(song.videoId, title, artist, songMs, dir, 1080, ForkDesktopVideoCanvas)) {
                is ForkVideoCanvas.Result.Clip ->
                    DesktopCanvasArtwork(url = r.file.absolutePath, title = title, artist = artist, album = album, source = DesktopCanvasSource.VIDEO)
                ForkVideoCanvas.Result.None -> null
            }
        }
    }

    private suspend fun spotify(song: Song, title: String, artist: String, album: String?): DesktopCanvasArtwork? =
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
