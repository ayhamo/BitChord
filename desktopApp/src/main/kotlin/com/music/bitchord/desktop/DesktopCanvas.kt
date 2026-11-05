package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A looping video that stands in for a track's cover art — what Spotify calls a Canvas and Apple
 * calls motion artwork.
 *
 * [url] is what the player mounts; [fallbackUrl] is tried once if that errors, which is how the
 * Apple provider hands over a second rendition of the same clip. The metadata is not decoration:
 * providers search by free text and will happily return the wrong album's clip, so [matches]
 * re-checks the answer against what is playing.
 */
data class DesktopCanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: DesktopCanvasSource = DesktopCanvasSource.OTHER,
) {
    /**
     * Whether this clip really belongs to the track we asked about.
     *
     * Title and artists must match exactly once punctuation, case and accents are stripped — a near
     * miss is a different song by the same artist, which is the failure people notice. The album is
     * only held to that standard when both sides know it, since a track's album resolves after the
     * player opens and one queued from search may never get one.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() ||
            com.music.bitchord.data.canvas.titlesMatch(title, wantTitle) // fork: was normalizeForCanvasMatch() ==, "" == "" for non-Latin titles
        val wanted = splitCanvasArtists(wantArtist)
        val ours = splitCanvasArtists(artist.orEmpty())
        val artistOk = artist == null || wantArtist.isBlank() ||
            (wanted.isNotEmpty() && ours.isNotEmpty() && wanted.all { want -> ours.any { it == want } })
        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForCanvasMatch() == wantAlbum.normalizeForCanvasMatch()
        return titleOk && artistOk && albumOk
    }
}

/**
 * The four providers, asked in turn until one answers with a clip that is really this track's.
 *
 * Apple and Tidal first because their clips are square and belong to the release; the community
 * index next because it is the only one covering back catalogue; Spotify last because it is the
 * one that needs a credential.
 */
object DesktopCanvasClient {

    private const val CACHE_SIZE = 64

    /**
     * A settled answer for one track or release.
     *
     * [withAlbum] records whether the album name was known when this was worked out. It is the one
     * thing that can turn a miss into a hit later: the album is what makes the catalogue searches
     * land, and on the player it resolves a beat after the track starts.
     */
    private class Entry(val artwork: DesktopCanvasArtwork?, val withAlbum: Boolean) {
        /** A hit is a hit — the album could only have confirmed it. A miss stands too, unless it
         * was reached blind and there is now an album name to try. */
        fun reusable(nowWithAlbum: Boolean): Boolean =
            artwork != null || withAlbum || !nowWithAlbum
    }

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    // Skipping through a queue fires a lookup per track. Serialising them keeps four providers'
    // worth of requests off the wire at once.
    private val gate = Mutex()

    /**
     * The canvas for [song], or null when there is not one. Never throws.
     *
     * Only a local file with no catalogue identity is answered as a miss without a request. A
     * download carries both a videoId and a local path, and skipping on the path alone is what
     * left downloaded tracks with no canvas.
     */
    suspend fun lookup(asked: Song): DesktopCanvasArtwork? {
        // fork: a server song without its album or a repeated "Artist - " (NavidromeCanvasQuery), for
        // every caller: the window's own lookup asked with the server album, was turned away, and its
        // miss was cached for the song, so the player's lookup found "none" too.
        val song = com.music.bitchord.data.navidrome.NavidromeCanvasQuery.searchable(asked)
        if (song.videoId.isBlank() && (song.localUri != null || song.localPath != null)) return null
        if (com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) return null // fork: a station's name is no song's, a hit would be another track's video
        val title = song.title.cleanedForCanvas()
        val artist = song.artist.cleanedForCanvas()
        if (title.isBlank() || artist.isBlank()) return null
        val album = song.albumName
        // Keyed on the track alone: the album arrives after the player opens, and keying on it made
        // the late arrival look like a different question.
        return resolve(ForkSpotifyCanvasFirst.key("song|${song.videoId}"), album != null) { // fork: keyed on the order, as the phone's cacheKey
            firstHit(
                { DesktopAppleMusicCanvas.search(title, artist, album) },
                { DesktopTidalCanvas.search(title, artist, album) },
                { DesktopCommunityCanvas.search(title, artist, album) },
                { DesktopSpotifyCanvas.search(title, artist, album) },
            ) { it.matches(title, artist, album) }
        }
    }

    /** A canvas already worked out for [song], without going near the network. */
    fun cached(song: Song): DesktopCanvasArtwork? =
        synchronized(cache) { cache[ForkSpotifyCanvasFirst.key("song|${song.videoId}")]?.artwork } // fork: the order's key

    /**
     * The canvas for a release, for an album page's header.
     *
     * A separate lookup rather than the first track's: the services hang motion artwork off the
     * album, so asking directly is both fewer requests and a better match.
     */
    suspend fun lookupAlbum(album: String, artist: String): DesktopCanvasArtwork? {
        val name = album.cleanedForCanvas()
        val credit = artist.cleanedForCanvas()
        if (name.isBlank() || credit.isBlank()) return null
        return resolve(ForkSpotifyCanvasFirst.key("album|$name|$credit"), withAlbum = true) { // fork: keyed on the order
            firstHit(
                { DesktopAppleMusicCanvas.searchAlbum(name, credit) },
                { DesktopTidalCanvas.searchAlbum(name, credit) },
                { DesktopCommunityCanvas.searchAlbum(name, credit) },
                { DesktopSpotifyCanvas.searchAlbum(name, credit) },
            ) { it.matches(name, credit, name) }
        }
    }

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> DesktopCanvasArtwork?,
    ): DesktopCanvasArtwork? = gate.withLock {
        synchronized(cache) {
            cache[key]?.let { if (it.reusable(withAlbum)) return@withLock it.artwork }
        }
        if (com.music.bitchord.data.canvas.ForkCanvasLookup.skips(key, withAlbum)) return@withLock null // fork: a lookup that failed is not asked again within a minute
        val forkScope = com.music.bitchord.data.canvas.ForkCanvasLookup.Scope() // fork: where the providers report a failure
        val found = withContext(Dispatchers.IO + forkScope.context) { lookUp() } // fork: + forkScope.context
        if (com.music.bitchord.data.canvas.ForkCanvasLookup.keeps(key, withAlbum, found != null, forkScope)) synchronized(cache) { cache[key] = Entry(found, withAlbum) } // fork: a failed lookup is not cached as "no clip"
        found
    }

    /**
     * The first source answering with something that survives [accept].
     *
     * Sources are passed unevaluated so each is only reached if the ones before came up empty. One
     * that throws is treated as one that found nothing: none of these hosts are ours.
     */
    private suspend fun firstHit(
        vararg sources: suspend () -> DesktopCanvasArtwork?,
        accept: (DesktopCanvasArtwork) -> Boolean,
    ): DesktopCanvasArtwork? {
        for (source in ForkSpotifyCanvasFirst.ordered(sources.toList())) { // fork: both callers pass Spotify last; "Prioritize Spotify Canvas" asks it first
            val found = runCatching { source() }
                .onFailure { DesktopTrackLog.log("canvas: a source failed: $it"); com.music.bitchord.data.canvas.ForkCanvasLookup.fail("a source threw: $it") } // fork: was silent; + the failure report
                .getOrNull() ?: continue
            if (!accept(found)) {
                DesktopTrackLog.log("canvas: turned away '${found.title}' by ${found.artist} on '${found.album}'") // fork
                continue
            }
            return found
        }
        return null
    }
}

/**
 * YouTube Music titles carry packaging the catalogue services never see. Searching with it finds
 * nothing and matching against it rejects everything, so it comes off before either.
 */
internal fun String.cleanedForCanvas(): String = replace(CANVAS_NOISE, " ")
    .substringBefore(" | ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { this }

private val CANVAS_NOISE = Regex(
    """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
        """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
    RegexOption.IGNORE_CASE,
)

/** Whether this URL points at a playlist rather than at the media itself. */
internal fun isManifest(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

/** The clip itself, on disk. */
internal object DesktopCanvasCache {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fileFor(url: String): java.nio.file.Path? {
        DesktopMediaCache.existing(url, ForkClipCache.extension(url))?.let { return it } // fork: the name says backdrop or cover, for the clear buttons
        return runCatching {
            val target = DesktopMediaCache.pathFor(url, ForkClipCache.extension(url)) // fork
            if (ForkNavidrome.fetchClip(url, target)) { // fork: the server's clips need its login
                DesktopMediaCache.trim()
                return@runCatching target
            }
            val part = target.resolveSibling(target.fileName.toString() + ".part") // fork
            val response = client.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", DESKTOP_CANVAS_UA)
                    .header("Accept", "*/*")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofFile(part), // fork: was target; a download cut short at the clip's path was taken for the clip
            )
            check(response.statusCode() in 200..299) { "canvas HTTP ${response.statusCode()}" }
            check(java.nio.file.Files.size(part) > 0) { "canvas came back empty" }
            java.nio.file.Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) // fork
            // The only moment the cache can be over its limit is just after a write.
            DesktopMediaCache.trim()
            target
        }.onFailure {
            // fork: fixes upstream. A refused download left the error page at the clip's path, and the
            // check at the top then handed that to the decoder for good.
            runCatching { java.nio.file.Files.deleteIfExists(DesktopMediaCache.pathFor(url, ForkClipCache.extension(url))) }
            DesktopTrackLog.log("canvas: could not fetch the clip — ${it.message}")
        }.getOrNull()
    }
}

/**
 * The motion artwork, drawn as frames rather than played by a native child (an album page's header).
 *
 * Fork: played through the shared [com.music.bitchord.ui.player.CanvasArtworkPlayer], which on the PC
 * is [DesktopCanvasVideo], the player's own clip decoder. This used to run its own decode loop, which
 * (a) never paused: it decoded 25-30 frames a second with the window minimised or hidden in the tray
 * and under Now Playing / Replay, because nothing passed [isPlaying]; now it holds its frame then
 * ([forkPageClipMayPlay], the artist banner's gates). (b) made three copies of every frame (the array,
 * Skia's raster copy, the bitmap's) and closed none, several MB each at 30 fps, left to the collector;
 * DesktopCanvasVideo draws from one bitmap and closes the ones gone by. (c) leaked the FFmpeg decoder
 * and its file when the page closed while the clip was opening. The first-frame publish to
 * DesktopCanvasBackdrop went with it: nothing reads that frame ([DesktopCanvasBackdrop.frameFor] has
 * no caller). [contentScale] is kept for callers; the clip is cropped to fill, as it was by default.
 */
@Composable
fun DesktopCanvasView(
    url: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    @Suppress("UNUSED_PARAMETER") contentScale: ContentScale = ContentScale.Crop,
    /** Tried once if [url] will not decode — the Apple provider's second rendition of the clip. */
    fallbackUrl: String? = null,
) {
    val canvas = remember(url, fallbackUrl) {
        com.music.bitchord.data.canvas.CanvasArtwork(url = url, fallbackUrl = fallbackUrl)
    }
    val mayPlay = forkPageClipMayPlay()
    com.music.bitchord.ui.player.CanvasArtworkPlayer(
        canvas = canvas,
        isPlaying = isPlaying && mayPlay,
        modifier = modifier,
        frameCapturePx = 0,
    )
}

private const val DESKTOP_CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
