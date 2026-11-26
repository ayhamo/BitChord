package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import com.music.bitchord.ui.graphics.argbPixels

/**
 * Fork: extra shade for a near-white cover, so white text over a fork-drawn backdrop reads (the
 * phone's 0624b1b, Taylor Swift's "Fortnight": white title, times and controls on a white sleeve).
 * Used by the landscape backdrop and, as a hook, upstream's default-theme mesh (`ArtworkMeshBackdrop`).
 * A fixed scrim leaves a white sleeve light grey; this one also brings the cover's bright tenth
 * (its 90th-percentile luma, 0..255) down to [TARGET_LUMA] once the base scrim is applied.
 */
internal object ForkBrightTenth {
    const val TARGET_LUMA = 128.0

    /** The 90th-percentile Rec. 601 luma (0..255) of [argb]; 0 for none. */
    fun p90Luma(argb: IntArray): Double {
        if (argb.isEmpty()) return 0.0
        val counts = IntArray(256)
        for (c in argb) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            counts[((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)]++
        }
        val rank = (argb.size * 0.9).toInt().coerceAtMost(argb.size - 1)
        var seen = 0
        for (l in 0..255) {
            seen += counts[l]
            if (seen > rank) return l.toDouble()
        }
        return 255.0
    }

    /** The scrim's alpha: [base], or more when the cover's bright tenth [p90] would stay above [TARGET_LUMA] under it. */
    fun alpha(base: Float, p90: Double): Float {
        val under = p90 * (1.0 - base)
        if (under <= TARGET_LUMA) return base
        return (1.0 - (1.0 - base) * (TARGET_LUMA / under)).toFloat().coerceIn(base, 1f)
    }

    /**
     * The shade to draw over a scrim already at [base] (upstream's mesh scrim: 6% at its top), so
     * the two together come to [alpha]: 0 for a cover that already reads.
     */
    fun extra(base: Float, p90: Double): Float =
        (1f - (1f - alpha(base, p90)) / (1f - base)).coerceIn(0f, 1f)

    /** [p90Luma] of [image], or 0 when its pixels can't be read. */
    fun p90Of(image: ImageBitmap?): Double =
        image?.let { runCatching { p90Luma(it.argbPixels()) }.getOrNull() } ?: 0.0
}
