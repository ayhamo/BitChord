package com.music.bitchord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Fork: the playing song's colours from its cover video while one plays, instead of the still cover
 * (the idea from Desktop Revived bbb5f682, whose own player samples Canvas frames into
 * `DesktopArtworkPalette`; ours draws the shared player, so the PC's own cover-coloured surfaces
 * read from here instead).
 *
 * A clip is often not the cover in motion but a different shot with different colours, and the
 * "From the song" accent ([ForkAccentSource]) and the ambient window backdrop ([ForkAmbientMesh])
 * stayed the still's colours under it. The shared player's own legacy mesh already re-tints off the
 * clip (upstream's `canvasFrame`), so this covers only what the PC draws itself.
 *
 * **Cost.** Never per frame and never on the audio thread: [Sampler] copies the frame on screen
 * into a [EDGE]-pixel bitmap on the UI thread (where the decoder's loop also closes its frames, so
 * nothing can be closed under the copy) at the first frame and then every [EVERY_MS], [MAX_SAMPLES]
 * times in all, which spans a typical 6–10 s loop, and then stops. Colours are worked out from those
 * few hundred pixels. Only the player's clip is sampled: [offer] is told what the player asked for
 * (DesktopPlayerHost), and a clip anywhere else (an artist page, Home's cards) is left alone.
 *
 * With no clip, a clip not yet sampled, a different song, or "Animated canvas" off, every reader
 * gets null and falls back to the cover.
 */
internal object ForkClipColours {

    /** The longer edge of a sample: plenty for a dominant colour or a 6×6 mesh. */
    private const val EDGE = 48

    private const val EVERY_MS = 3_000L

    private const val MAX_SAMPLES = 4

    /** A mean brightness below this (0..255) is a fade from black, not the clip's colour. */
    private const val DARK_MEAN = 12

    /** One frame, shrunk: its pixels (ARGB) and the bitmap they came from. */
    class Sample(val bitmap: ImageBitmap, val pixels: IntArray)

    /** What the player was answered for [videoId] (cover clip, backdrop, sleeve, renditions). */
    private class Offer(val videoId: String, val coverUrl: String?, val urls: List<String>)

    @Volatile
    private var offer: Offer? = null

    /** Samples per clip address, for the offered song only; snapshot state so readers recompose. */
    private var samples by mutableStateOf<Map<String, List<Sample>>>(emptyMap())

    /**
     * The player's clip for [song] (the host's two lookups). A different song drops what was
     * sampled; the same song keeps it, so a player closed and opened again doesn't flash back to
     * the cover's colours. Called from any thread; returns [artwork].
     */
    fun offer(song: Song, artwork: CanvasArtwork?): CanvasArtwork? {
        val previous = offer
        // The sleeve first: on the PC that is the cover square's clip, the "cover in motion".
        val urls = artwork?.let { listOfNotNull(it.sleeveUrl, it.url, it.fallbackUrl, it.portraitUrl).distinct() }.orEmpty()
        if (artwork == null && previous?.videoId == song.videoId) return null // a lookup that came back empty keeps the clip already found
        offer = Offer(song.videoId, song.thumbnailUrl, if (previous?.videoId == song.videoId) (previous.urls + urls).distinct() else urls)
        if (previous?.videoId != song.videoId) {
            java.awt.EventQueue.invokeLater { if (offer?.videoId == song.videoId) samples = emptyMap() }
        }
        return artwork
    }

    private fun wanted(url: String): Boolean = offer?.urls?.contains(url) == true

    /**
     * The samples for the song whose cover is [coverUrl], the cover clip's before any other's; null
     * when there are none (or "Animated canvas" is off). A snapshot read: callers recompose on a new sample.
     */
    @Composable
    fun samplesFor(coverUrl: String?): List<Sample>? {
        val animated by DesktopPlayerSettings.animatedCanvas.collectAsState()
        val held = samples
        val current = offer ?: return null
        if (!animated || coverUrl == null || current.coverUrl != coverUrl) return null
        return current.urls.firstNotNullOfOrNull { url -> held[url]?.takeIf { it.isNotEmpty() } }
    }

    /**
     * Hooked into `DesktopCanvasVideo` beside its own frame reports: samples the clip at [url]
     * while it has a frame up ([rendered]), when it is the player's. [frame] reads the frame now on
     * screen.
     */
    @Composable
    fun Sampler(url: String, rendered: Boolean, frame: () -> ImageBitmap?) {
        val current by rememberUpdatedState(frame)
        LaunchedEffect(url, rendered) {
            if (!rendered || !wanted(url)) return@LaunchedEffect
            var taken = samples[url]?.size ?: 0
            var tries = 0
            while (taken < MAX_SAMPLES && tries < MAX_SAMPLES * 3) {
                tries++
                val sample = current()?.let(::shrink)
                if (sample != null && wanted(url)) {
                    samples = samples + (url to (samples[url].orEmpty() + sample))
                    taken++
                }
                delay(if (sample == null && taken == 0) 500L else EVERY_MS)
            }
        }
    }

    /**
     * [frame] drawn into an [EDGE]-pixel bitmap of its own, which nothing ever closes (the decoder
     * closes its frames two frames on). Null for a frame too dark to say anything.
     */
    private fun shrink(frame: ImageBitmap): Sample? = runCatching {
        if (frame.width < 1 || frame.height < 1) return null
        val scale = EDGE.toFloat() / maxOf(frame.width, frame.height)
        val width = (frame.width * scale).roundToInt().coerceAtLeast(1)
        val height = (frame.height * scale).roundToInt().coerceAtLeast(1)
        val small = ImageBitmap(width, height)
        androidx.compose.ui.graphics.Canvas(small).drawImageRect(
            image = frame,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(frame.width, frame.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height),
            paint = Paint().apply { filterQuality = FilterQuality.Low },
        )
        val pixels = IntArray(width * height)
        small.readPixels(pixels)
        var sum = 0L
        for (pixel in pixels) sum += ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
        if (sum / pixels.size < DARK_MEAN) null else Sample(small, pixels)
    }.getOrNull()
}

/**
 * The ambient window backdrop's mesh (`DesktopPageBackdrop`): the playing clip's first sample while
 * there is one for this cover, else the still cover's mesh as before ([DesktopMesh]). The first
 * sample only, so the whole window changes colour once (cover to clip), not every few seconds.
 */
@Composable
internal fun ForkAmbientMesh(url: String?) {
    val first = ForkClipColours.samplesFor(url)?.firstOrNull()
    val mesh = remember(first) { first?.let { DesktopArtworkMesh.of(it.bitmap, seed = url.hashCode()) } }
    if (mesh == null) {
        DesktopMesh(url)
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        drawImage(
            image = mesh,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}
