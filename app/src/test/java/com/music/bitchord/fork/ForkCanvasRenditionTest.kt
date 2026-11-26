package com.music.bitchord.fork

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.canvas.ForkCanvasSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fork: Apple's clip played at the "Animated cover quality" in every shape, the tall one too. */
class ForkCanvasRenditionTest {

    private fun master(dir: String) = "https://mvod.itunes.apple.com/itunes-assets/$dir/master.m3u8"

    private val lists = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=480x480
        p480.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=720x720
        p720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1080x1080
        p1080.m3u8
    """.trimIndent()

    @Test
    fun theTallClipIsResolvedLikeTheSquareOne() {
        val cap = ForkCanvasSettings.coverQuality.value.cap
        val art = CanvasArtwork(url = master("square"), source = CanvasSource.APPLE, portraitUrl = master("tall"))
        val got = ForkCanvasSources.rendition(art) { lists }
        assertEquals(ForkCanvasSettings.variantUrl(master("square"), cap) { lists }, got.url)
        assertEquals(ForkCanvasSettings.variantUrl(master("tall"), cap) { lists }, got.portraitUrl)
        assertEquals("https://mvod.itunes.apple.com/itunes-assets/tall/p720.m3u8", got.portraitUrl) // the default, 720p
    }
}
