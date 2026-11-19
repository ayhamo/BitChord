package com.music.bitchord.fork

import android.graphics.Matrix
import android.view.TextureView
import com.music.bitchord.data.canvas.ForkVideoCanvas
import java.util.WeakHashMap

/**
 * Fork: how a music-video loop is framed in the clip player's `TextureView` (the canvas rulebook's
 * rules 7.8 and 8.3, as the PC extension does it from the storyboard).
 *
 * A music video is 16:9 and often letterboxed; the player's cover fit centres it, so the bars
 * showed above and below a backdrop and the cover square kept the middle of the picture whatever
 * was there. [ForkVideoCanvas] saves the loop's framing beside it (its bars and where the crop
 * band with the most detail and movement sits); [frame] turns the cover fit into one that fills the
 * view with the picture inside the bars, slid across to that band. A bar pair that would leave less
 * than [MIN_KEEP] of the height is not a letterbox (a dark scene), and stays.
 *
 * Only for a loop that has a framing file: any other clip's transform is left exactly as upstream
 * built it. The view's playing url is read through [track], set once where the view is made.
 */
object ForkClipFraming {

    /** The picture kept between top and bottom bars must be at least this share of the frame. */
    private const val MIN_KEEP = 0.5f

    private val urls = WeakHashMap<TextureView, () -> String>()
    private val framings = object : LinkedHashMap<String, ForkVideoCanvas.Framing?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ForkVideoCanvas.Framing?>) = size > 16
    }

    /** [view] plays whatever [url] answers now. */
    fun track(view: TextureView, url: () -> String) {
        synchronized(urls) { urls[view] = url }
    }

    private fun framingOf(view: TextureView): ForkVideoCanvas.Framing? {
        val url = synchronized(urls) { urls[view] }?.invoke() ?: return null
        if (!url.startsWith("file:")) return null
        return synchronized(framings) {
            if (framings.containsKey(url)) framings[url] else ForkVideoCanvas.framing(url).also { framings[url] = it }
        }
    }

    /**
     * Replaces [matrix] (upstream's cover fit of a clip [clipAspect] wide over high, stretched to the
     * view's bounds as a TextureView draws it) with the loop's own framing, when it has one and is
     * not being fitted ([fitted]: a portrait clip shown whole).
     */
    fun frame(view: TextureView, matrix: Matrix, clipAspect: Float, fitted: Boolean) {
        if (fitted) return
        val f = framingOf(view) ?: return
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f || clipAspect <= 0f) return
        val l = f.bars[0].coerceIn(0f, 0.45f)
        val r = f.bars[2].coerceIn(0f, 0.45f)
        var t = f.bars[1].coerceIn(0f, 0.45f)
        var b = f.bars[3].coerceIn(0f, 0.45f)
        if (1f - t - b < MIN_KEEP) {
            t = 0f
            b = 0f
        }
        val innerW = clipAspect * (1f - l - r) // in units of the clip's height
        val innerH = 1f - t - b
        // the scale (view px per clip-height unit) at which the picture inside the bars covers the view
        val k = maxOf(w / innerW, h / innerH)
        val excess = k * innerW - w
        val ox = -k * clipAspect * l - f.cropX.coerceIn(0f, 1f) * excess
        val oy = h / 2f - k * (t + innerH / 2f)
        // the TextureView stretches the frame to w x h: x' = u * w, y' = v * h
        matrix.reset()
        matrix.setScale(k * clipAspect / w, k / h)
        matrix.postTranslate(ox, oy)
    }
}
