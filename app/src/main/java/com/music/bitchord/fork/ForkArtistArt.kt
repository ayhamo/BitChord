package com.music.bitchord.fork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.ForkCanvasSettings
import com.music.bitchord.data.canvas.canvasGet
import com.music.bitchord.ui.player.ForkCanvasSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: the canvas rulebook's rule 13.8 on the phone's artist page (DetailScreen). Upstream always
 * looked up Apple's artist photograph, logo and looping video ([com.music.bitchord.data.canvas.AppleArtistArtRepository]);
 * the fork asks the settings first:
 *
 * - "Artist images" (on by default, as upstream showed them) gates the whole Apple lookup;
 * - "Artist videos" follows the images until set, and needs "Animated covers" (the motion art
 *   switch) as well as upstream's own animated canvas switch, which DetailScreen still checks;
 * - "Artist video quality" picks the rendition from Apple's HLS master list
 *   ([ForkCanvasSettings.variantUrl]); the master itself when the list can't be read.
 */
internal object ForkArtistArt {

    /** "Artist images": whether the artist page asks Apple at all. */
    @Composable
    fun imagesOn(): Boolean = ForkCanvasSettings.artistImages.collectAsStateWithLifecycle().value

    /**
     * The artist's looping video [url] (Apple's HLS master) as the rendition the settings ask for, or
     * null while it is resolved or when "Artist videos" or "Animated covers" is off.
     */
    @Composable
    fun video(url: String?): String? {
        val images by ForkCanvasSettings.artistImages.collectAsStateWithLifecycle()
        val videos by ForkCanvasSettings.artistVideos.collectAsStateWithLifecycle()
        val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
        val quality by ForkCanvasSettings.artistVideoQuality.collectAsStateWithLifecycle()
        val on = (videos ?: images) && covers
        val resolved by produceState<String?>(null, url, on, quality) {
            value = if (url == null || !on) null else withContext(Dispatchers.IO) {
                runCatching { ForkCanvasSettings.variantUrl(url, quality.cap) { canvasGet(it) } }.getOrNull() ?: url
            }
        }
        return resolved
    }
}
