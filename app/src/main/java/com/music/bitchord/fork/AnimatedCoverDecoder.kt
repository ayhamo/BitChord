package com.music.bitchord.fork

import android.os.Build
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.Options
import coil3.size.pxOrElse

/**
 * Animated covers (fork-only): a song whose embedded cover is an animated WebP
 * (Apple / Tidal motion art) keeps moving, as in the psysonic fork. Navidrome
 * hands such a cover out unchanged when the size asked for is larger than the
 * file (the player asks for 1200 px, see `artworkAt` in Models.kt; the fixed
 * 600 px it used to get came back still), but Coil's default decoder keeps
 * only its first frame.
 *
 * Only for images drawn at [MIN_PX] or larger (the player, Home's cards, album
 * headers): list thumbnails stay still, so a scrolling list of them isn't
 * decoding frames for every row. Needs API 28 (`AnimatedImageDrawable`); on
 * older devices, or with [ForkSettings.animatedCovers] off, the default
 * decoder runs and the cover is still.
 */
class AnimatedCoverDecoder {

    class Factory : Decoder.Factory {
        private val animated = AnimatedImageDecoder.Factory()

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            if (!ForkSettings.animatedCovers.value) return null
            val px = maxOf(options.size.width.pxOrElse { 0 }, options.size.height.pxOrElse { 0 })
            if (px < MIN_PX) return null
            // Null for anything that isn't an animated GIF / WebP / HEIF, so
            // every still cover falls through to the decoders after this one.
            return animated.create(result, options, imageLoader)
        }
    }

    private companion object {
        /** The psysonic fork's line: its 128 px list tier stays still, 256 px and up animate. */
        const val MIN_PX = 256
    }
}
