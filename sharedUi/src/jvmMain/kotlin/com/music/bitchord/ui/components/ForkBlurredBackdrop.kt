package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import coil3.Bitmap
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: the phone's blurred caption backdrop under a release card (Home's Top Picks, new
 * releases), on the PC. Upstream's JVM version handed back the cover as it is, so the caption sat
 * on a sharp slice of the artwork where the phone shows the cover running on, out of focus.
 *
 * Coil 3.0.4 has no request transformations off Android, so (as Desktop Revived 5fa327f9 /
 * 6e407a1c do) a small copy is loaded through the app's Coil loader, its lower part blurred on a
 * worker thread with the phone's exact recipe (`BlurredBackdrop.android.kt`: 40 % strip, 48 px
 * wide, three passes of a radius-3 box blur), and the finished strip handed to `AsyncImage` as PNG
 * bytes. Nothing is loaded or blurred while drawing. Until the strip is ready the card shows no
 * backdrop, as the phone does while its transformed request loads.
 */
@Composable
internal fun forkRememberBlurredBackdrop(url: String?): Any? {
    val context = LocalPlatformContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    val backdrop = produceState(initialValue = url?.let(ForkBackdropCache::get), url, loader) {
        // produceState keeps its last value when the key changes: without this a card reused by the
        // lazy row showed the previous release's strip until the new one was ready (Revived 6e407a1c).
        value = url?.let(ForkBackdropCache::get)
        if (url == null || value != null) return@produceState
        try {
            val result = loader.execute(ImageRequest.Builder(context).data(url).size(160, 160).build())
            if (result is SuccessResult) {
                val strip = withContext(Dispatchers.Default) { forkBackdropPng(result.image.toBitmap()) }
                if (strip != null) {
                    ForkBackdropCache.put(url, strip)
                    value = strip
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A cover that fails to load leaves the card without a backdrop, not the shelf broken.
        }
    }
    return backdrop.value
}

/** Finished strips, so a card scrolling out and back in isn't blurred again. A few KB each. */
private object ForkBackdropCache {
    private const val MAX_ENTRIES = 96
    private val entries = object : LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > MAX_ENTRIES
    }

    @Synchronized fun get(url: String): ByteArray? = entries[url]

    @Synchronized fun put(url: String, png: ByteArray) {
        entries[url] = png
    }
}

private const val STRIP = 0.4f
private const val WIDTH = 48
private const val RADIUS = 3
private const val PASSES = 3

/** The lower [STRIP] of [input], [WIDTH] px wide and blurred, as a PNG. */
private fun forkBackdropPng(input: Bitmap): ByteArray? {
    if (input.width <= 0 || input.height <= 0) return null
    val pixels = forkBackdropPixels(input.width, input.height) { x, y -> input.getColor(x, y) }
    val height = pixels.size / WIDTH
    val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, WIDTH, height, pixels, 0, WIDTH)
    return ByteArrayOutputStream().use { out -> if (ImageIO.write(image, "png", out)) out.toByteArray() else null }
}

/**
 * The strip's pixels (ARGB, [WIDTH] per row), sampled from a [width] × [height] picture read
 * through [colorAt], then blurred. Sampling picks the pixel at each cell's centre: the blur that
 * follows leaves nothing of the difference from the phone's filtered downscale.
 */
internal fun forkBackdropPixels(width: Int, height: Int, colorAt: (x: Int, y: Int) -> Int): IntArray {
    val top = (height * (1f - STRIP)).toInt().coerceIn(0, height - 1)
    val stripHeight = height - top
    val outHeight = (stripHeight * WIDTH / width.toFloat()).toInt().coerceAtLeast(4)
    val pixels = IntArray(WIDTH * outHeight)
    for (y in 0 until outHeight) {
        val sy = (top + (y + 0.5f) * stripHeight / outHeight).toInt().coerceIn(top, height - 1)
        for (x in 0 until WIDTH) {
            val sx = ((x + 0.5f) * width / WIDTH).toInt().coerceIn(0, width - 1)
            pixels[y * WIDTH + x] = colorAt(sx, sy) or (0xFF shl 24)
        }
    }
    repeat(PASSES) {
        forkBoxBlur(pixels, WIDTH, outHeight, RADIUS, horizontal = true)
        forkBoxBlur(pixels, WIDTH, outHeight, RADIUS, horizontal = false)
    }
    return pixels
}

/** One pass of a running-sum box blur along rows or columns, edges clamped: the phone's `boxBlur`. */
internal fun forkBoxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val line = IntArray(length)
    val window = radius * 2 + 1
    for (l in 0 until lines) {
        fun at(i: Int) = if (horizontal) l * width + i else i * width + l
        for (i in 0 until length) line[i] = pixels[at(i)]
        var r = 0
        var g = 0
        var b = 0
        for (k in -radius..radius) {
            val p = line[k.coerceIn(0, length - 1)]
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until length) {
            pixels[at(i)] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val out = line[(i - radius).coerceIn(0, length - 1)]
            val inn = line[(i + radius + 1).coerceIn(0, length - 1)]
            r += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
            g += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}
