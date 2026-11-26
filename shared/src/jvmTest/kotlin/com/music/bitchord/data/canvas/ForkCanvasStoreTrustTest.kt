package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fork: a saved canvas passes the same trust a fresh one does ([ForkCanvasStore.playable]). The file
 * is not a trusted input, so a saved `http://` clip or a "Spotify" clip off Spotify's media host reads
 * back as nothing (asked again), while a good https entry still answers.
 */
class ForkCanvasStoreTrustTest {

    private val now = 1_000_000L

    private fun read(json: String): ForkCanvasRules.Saved? =
        ForkCanvasRules.readSaved(ForkCanvasRules.saved(json, now), ForkCanvasStore::playable, now)

    @Test
    fun aGoodHttpsEntryStillReads() {
        val spotify = """{"url":"https://canvaz.scdn.co/a.mp4","source":"SPOTIFY"}"""
        assertEquals(ForkCanvasRules.Saved.Clip(spotify), read(spotify))
        val apple = """{"url":"https://mvod.itunes.apple.com/b.m3u8","fallbackUrl":"https://mvod.itunes.apple.com/c.mp4","source":"APPLE"}"""
        assertEquals(ForkCanvasRules.Saved.Clip(apple), read(apple))
    }

    @Test
    fun aSavedHttpEntryReadsAsNothing() {
        assertNull(read("""{"url":"http://canvaz.scdn.co/a.mp4","source":"SPOTIFY"}"""))
        assertNull(read("""{"url":"http://mvod.itunes.apple.com/b.m3u8","source":"APPLE"}"""))
        assertNull(read("""{"url":"https://mvod.itunes.apple.com/b.m3u8","fallbackUrl":"http://x.example/c.mp4","source":"OTHER"}"""))
    }

    @Test
    fun aSpotifyEntryOffSpotifysHostReadsAsNothing() {
        assertNull(read("""{"url":"https://evil.example/a.mp4","source":"SPOTIFY"}"""))
        assertNull(read("""{"url":"https://scdn.co.evil.example/a.mp4","source":"SPOTIFY"}"""))
        assertNull(read("""{"url":"https://canvaz.scdn.co:8443/a.mp4","source":"SPOTIFY"}"""))
    }
}
