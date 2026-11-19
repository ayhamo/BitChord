package com.music.bitchord.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.ForkCanvasStore
import com.music.bitchord.data.canvas.ForkVideoCanvas
import kotlin.math.roundToInt

/**
 * Fork: a music-video loop drawn by its own framing (the canvas rulebook's rules 7.8 and 8.3), the
 * phone's `ForkClipFraming` for [DesktopCanvasVideo]: the picture inside the loop's bars fills the
 * view, slid across to the crop band [ForkVideoCanvas] chose; a bar pair leaving under half the
 * height is a dark scene, not a letterbox, and stays. Any other clip is drawn exactly as upstream
 * drew it ([dstOffset] / [dstSize] of the whole frame).
 */
internal object ForkDesktopClipFraming {

    private const val MIN_KEEP = 0.5f

    private val framings = object : LinkedHashMap<String, ForkVideoCanvas.Framing?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ForkVideoCanvas.Framing?>) = size > 16
    }

    fun framingOf(url: String): ForkVideoCanvas.Framing? {
        if (!ForkCanvasStore.isLocal(url)) return null
        return synchronized(framings) {
            if (framings.containsKey(url)) framings[url] else ForkVideoCanvas.framing(url).also { framings[url] = it }
        }
    }

    /** The source rectangle of a [width] x [height] frame that fills a [viewW] x [viewH] view by [f]. */
    fun source(f: ForkVideoCanvas.Framing, width: Int, height: Int, viewW: Float, viewH: Float): Pair<IntOffset, IntSize> {
        val l = f.bars[0].coerceIn(0f, 0.45f) * width
        val r = f.bars[2].coerceIn(0f, 0.45f) * width
        var t = f.bars[1].coerceIn(0f, 0.45f)
        var b = f.bars[3].coerceIn(0f, 0.45f)
        if (1f - t - b < MIN_KEEP) {
            t = 0f
            b = 0f
        }
        val innerW = width - l - r
        val innerH = height * (1f - t - b)
        val viewAspect = viewW / viewH
        return if (innerW / innerH > viewAspect) {
            val w = innerH * viewAspect
            IntOffset((l + f.cropX.coerceIn(0f, 1f) * (innerW - w)).roundToInt(), (t * height).roundToInt()) to
                IntSize(w.roundToInt().coerceAtLeast(1), innerH.roundToInt().coerceAtLeast(1))
        } else {
            val h = innerW / viewAspect
            IntOffset(l.roundToInt(), (t * height + (innerH - h) / 2f).roundToInt()) to
                IntSize(innerW.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
        }
    }
}

/**
 * Fork: [DesktopCanvasVideo]'s one draw call: [image] at [dstOffset] / [dstSize] as upstream drew it,
 * or, for a music-video loop shown cover-fit ([fitted] false), by its framing over the whole view.
 */
internal fun DrawScope.forkDrawClip(image: ImageBitmap, url: String, fitted: Boolean, dstOffset: IntOffset, dstSize: IntSize) {
    val framing = if (fitted || size.width <= 0f || size.height <= 0f) null else ForkDesktopClipFraming.framingOf(url)
    if (framing == null) {
        drawImage(image = image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height), dstOffset = dstOffset, dstSize = dstSize)
        return
    }
    val (srcOffset, srcSize) = ForkDesktopClipFraming.source(framing, image.width, image.height, size.width, size.height)
    drawImage(
        image = image,
        srcOffset = srcOffset,
        srcSize = srcSize,
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}
