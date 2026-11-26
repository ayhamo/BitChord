package com.music.bitchord.data.canvas

import com.music.bitchord.data.canvas.ForkCanvasRules.Source
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The canvas rulebook's §13 settings: storage, the order's moves, Apple's renditions, the lookup keys. */
class ForkCanvasSettingsTest {

    private class MapStore : ForkCanvasSettings.Store {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @BeforeTest fun reset() = ForkCanvasSettings.resetForTest()
    @AfterTest fun after() = ForkCanvasSettings.resetForTest()

    @Test
    fun storedOrderAndSourcesRoundTrip() {
        val order = listOf(Source.APPLE_MUSIC, Source.SPOTIFY, Source.YOUTUBE_MUSIC_VIDEO)
        assertEquals(order, ForkCanvasSettings.parseOrder(ForkCanvasSettings.storeOrder(order)))
        assertEquals("", ForkCanvasSettings.storeEnabled(Source.entries))
        assertEquals("NONE", ForkCanvasSettings.storeEnabled(emptyList()))
        assertEquals(listOf(Source.SPOTIFY), ForkCanvasSettings.parseEnabled(ForkCanvasSettings.storeEnabled(listOf(Source.SPOTIFY))))
    }

    @Test
    fun movesSwapNeighboursAndTheEndsStay() {
        val d = ForkCanvasRules.DEFAULT_ORDER
        assertEquals(d, ForkCanvasSettings.moved(d, d.first(), up = true))
        assertEquals(d, ForkCanvasSettings.moved(d, d.last(), up = false))
        assertEquals(listOf(d[1], d[0], d[2]), ForkCanvasSettings.moved(d, d[0], up = false))
    }

    @Test
    fun loadSaveAndTheVideoSwitch() {
        val store = MapStore()
        // An install from before the list: the music video follows "Music video canvas".
        ForkCanvasSettings.load(store, videoOn = false)
        assertFalse(ForkCanvasSettings.videoOn())
        val before = ForkCanvasSettings.generation.value
        ForkCanvasSettings.setOn(Source.YOUTUBE_MUSIC_VIDEO, true)
        assertTrue(ForkCanvasSettings.videoOn())
        assertEquals("", store.map[ForkCanvasSettings.KEY_ENABLED])
        assertTrue(ForkCanvasSettings.generation.value > before)
        ForkCanvasSettings.move(Source.APPLE_MUSIC, up = true)
        assertEquals("video,apple,spotify", store.map[ForkCanvasSettings.KEY_ORDER])
        ForkCanvasSettings.setVideoQuality(ForkCanvasSettings.VideoQuality.P1080)
        ForkCanvasSettings.setArtistImages(false)
        ForkCanvasSettings.resetForTest()
        ForkCanvasSettings.load(store, videoOn = false)
        assertEquals(listOf(Source.YOUTUBE_MUSIC_VIDEO, Source.APPLE_MUSIC, Source.SPOTIFY), ForkCanvasSettings.order.value)
        assertEquals(ForkCanvasSettings.VideoQuality.P1080, ForkCanvasSettings.videoQuality.value)
        assertFalse(ForkCanvasSettings.artistImages.value)
        // Artist videos follow the images until set.
        assertFalse(ForkCanvasSettings.artistVideosOn())
        ForkCanvasSettings.setArtistVideos(true)
        assertTrue(ForkCanvasSettings.artistVideosOn())
    }

    @Test
    fun backdropsBesideACoverAreThoseOrderedBeforeIt() {
        assertEquals(listOf(Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY), ForkCanvasSettings.backdropsBesideCover(ForkCanvasRules.DEFAULT_ORDER))
        assertEquals(emptyList(), ForkCanvasSettings.backdropsBesideCover(listOf(Source.APPLE_MUSIC, Source.SPOTIFY, Source.YOUTUBE_MUSIC_VIDEO)))
        assertEquals(listOf(Source.SPOTIFY), ForkCanvasSettings.backdropsBesideCover(listOf(Source.SPOTIFY, Source.APPLE_MUSIC, Source.YOUTUBE_MUSIC_VIDEO)))
        assertEquals(listOf(Source.SPOTIFY), ForkCanvasSettings.backdropsBesideCover(listOf(Source.SPOTIFY)))
    }

    @Test
    fun theKeysCarryTheQualities() {
        assertEquals("", ForkCanvasSettings.videoKeySuffix())
        assertEquals("|q=720", ForkCanvasSettings.appleKeySuffix())
        ForkCanvasSettings.setVideoMode(ForkCanvasSettings.VideoMode.FULL)
        assertEquals("|q=480|full", ForkCanvasSettings.videoKeySuffix())
        ForkCanvasSettings.setCoverQuality(ForkCanvasSettings.CoverQuality.ORIGINAL)
        assertEquals("|q=orig", ForkCanvasSettings.appleKeySuffix())
        val a = ForkCanvasSettings.keySuffix()
        ForkCanvasSettings.setVideoQuality(ForkCanvasSettings.VideoQuality.AUTO)
        assertNotEquals(a, ForkCanvasSettings.keySuffix())
    }

    @Test
    fun anHlsMasterListIsReadAndOnlyAppleIsTrusted() {
        val master = "https://mvod.itunes.apple.com/itunes-assets/x/master.m3u8"
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=700000,BANDWIDTH=800000,RESOLUTION=480x480
            p480.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=720x720
            p720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1080x1080
            https://evil.example.com/p1080.m3u8
        """.trimIndent()
        val all = ForkCanvasSettings.variants(text, master)
        assertEquals(2, all.size)
        assertEquals(800000L, all[0].bandwidth)
        assertEquals("https://mvod.itunes.apple.com/itunes-assets/x/p720.m3u8", all[1].uri)
        assertEquals(all[1].uri, ForkCanvasSettings.variantUrl(master, 720) { text })
        assertEquals(all[0].uri, ForkCanvasSettings.variantUrl(master, 360) { text })
        assertEquals(master, ForkCanvasSettings.variantUrl(master, 720) { null })
        assertTrue(ForkCanvasSettings.trusted(master))
        assertFalse(ForkCanvasSettings.trusted("http://mvod.itunes.apple.com/x.m3u8"))
        assertFalse(ForkCanvasSettings.trusted("https://apple.com.evil.example/x.m3u8"))
        assertEquals("https://evil.example.com/a.m3u8", ForkCanvasSettings.variantUrl("https://evil.example.com/a.m3u8", 720) { text })
    }

    /** Audit 2026-11-26: the canvas menus list the sources in the player's order, "Prioritise Spotify Canvas" included. */
    @Test
    fun theMenuOrderIsThePlayersOrder() {
        ForkCanvasSettings.setOrder(listOf(Source.APPLE_MUSIC, Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY))
        assertEquals(ForkCanvasSettings.effectiveOrder(true, true, spotifyFirst = true), ForkCanvasSettings.menuOrder(true, true, true, spotifyFirst = true))
        assertEquals(Source.SPOTIFY, ForkCanvasSettings.menuOrder(true, true, true, spotifyFirst = true).first())
        assertEquals(Source.APPLE_MUSIC, ForkCanvasSettings.menuOrder(true, true, true, spotifyFirst = false).first())
        assertFalse(Source.YOUTUBE_MUSIC_VIDEO in ForkCanvasSettings.menuOrder(true, true, videosOn = false, spotifyFirst = true))
    }
}
