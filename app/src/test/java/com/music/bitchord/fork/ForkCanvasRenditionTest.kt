package com.music.bitchord.fork

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.canvas.ForkCanvasSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val got = ForkCanvasSources.rendition(art) { lists }!!
        assertEquals(ForkCanvasSettings.variantUrl(master("square"), cap) { lists }, got.url)
        assertEquals(ForkCanvasSettings.variantUrl(master("tall"), cap) { lists }, got.portraitUrl)
        assertEquals("https://mvod.itunes.apple.com/itunes-assets/tall/p720.m3u8", got.portraitUrl) // the default, 720p
    }

    /** Audit 2026-11-26: Apple's clip is played only from https on Apple's own hosts. */
    @Test
    fun anUntrustedClipIsNoClipAndAnUntrustedFallbackIsDropped() {
        val bad = listOf(
            "http://mvod.itunes.apple.com/itunes-assets/x/master.m3u8",
            "https://apple.com.evil.example/master.m3u8",
            "https://user@mvod.itunes.apple.com/itunes-assets/x/master.m3u8",
            "https://mvod.itunes.apple.com:8443/itunes-assets/x/master.m3u8",
        )
        for (url in bad) {
            var read = false
            assertNull(url, ForkCanvasSources.rendition(CanvasArtwork(url = url, source = CanvasSource.APPLE)) { read = true; lists })
            assertEquals("no list read from $url", false, read)
            val got = ForkCanvasSources.rendition(
                CanvasArtwork(url = master("square"), fallbackUrl = url, portraitUrl = url, source = CanvasSource.APPLE),
            ) { lists }!!
            assertNull(got.fallbackUrl)
            assertNull(got.portraitUrl)
        }
    }
}
