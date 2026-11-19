package com.music.bitchord.data.canvas

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fork: a downloaded song's clips by its id (canvas rulebook rules 1.5 and 2.5, [ForkDownloadedCanvas]):
 * Spotify's canvas before the art clip, a saved clip answering without a lookup, and only a clip that
 * is the song kept, never a miss. The key strings themselves are the rulebook's `cache` cases
 * (`ForkCanvasCasesTest`).
 */
class ForkDownloadedCanvasTest {

    private val saved = HashMap<String, CanvasArtwork>()

    /** The store's entry for an artwork, as `ForkCanvasStore` writes it (JSON with the url and source). */
    private fun read(key: String): ForkCanvasRules.Saved? = saved[key]?.let {
        ForkCanvasRules.Saved.Clip("""{"url":"${it.url}","source":"${it.source.name}"}""")
    }

    private fun put(key: String, a: CanvasArtwork) { saved[key] = a }

    private val spotify = CanvasArtwork(url = "https://canvaz.scdn.co/a.mp4", source = CanvasSource.SPOTIFY)
    private val cover = CanvasArtwork(url = "https://mvod.itunes.apple.com/b.m3u8", source = CanvasSource.OTHER)

    @Test
    fun keysFollowTheSwitchesInRuleOrder() {
        assertEquals(listOf("spid:vid1", "id:vid1"), ForkDownloadedCanvas.keys("vid1", spotify = true, covers = true))
        assertEquals(listOf("id:vid1"), ForkDownloadedCanvas.keys("vid1", spotify = false, covers = true))
        assertEquals(listOf("spid:vid1"), ForkDownloadedCanvas.keys("vid1", spotify = true, covers = false))
        assertEquals(emptyList(), ForkDownloadedCanvas.keys("", spotify = true, covers = true))
    }

    @Test
    fun spotifysSavedCanvasComesBeforeTheArtClip() {
        saved["id:vid1"] = cover
        val keys = ForkDownloadedCanvas.keys("vid1", spotify = true, covers = true)
        assertEquals(cover.url, ForkDownloadedCanvas.saved(keys, ::read)?.url)
        saved["spid:vid1"] = spotify
        assertEquals(spotify.url, ForkDownloadedCanvas.saved(keys, ::read)?.url)
        assertEquals(CanvasSource.SPOTIFY, ForkDownloadedCanvas.saved(keys, ::read)?.source)
        assertNull(ForkDownloadedCanvas.saved(ForkDownloadedCanvas.keys("vid2", spotify = true, covers = true), ::read))
    }

    @Test
    fun aSavedClipAnswersWithoutTheLookup() = runBlocking {
        saved["spid:vid1"] = spotify
        var asked = 0
        val got = ForkDownloadedCanvas.through("spid:vid1", { true }, ::put, ::read) { asked++; null }
        assertEquals(spotify.url, got?.url)
        assertEquals(0, asked)
    }

    @Test
    fun onlyAClipThatIsTheSongIsKept() = runBlocking {
        ForkDownloadedCanvas.through("spid:vid1", { false }, ::put, ::read) { spotify }
        assertNull(saved["spid:vid1"], "a clip the match turned away is not kept by id")
        ForkDownloadedCanvas.through("spid:vid1", { true }, ::put, ::read) { null }
        assertNull(saved["spid:vid1"], "a miss is not kept by id")
        assertEquals(spotify.url, ForkDownloadedCanvas.through("spid:vid1", { true }, ::put, ::read) { spotify }?.url)
        assertEquals(spotify, saved["spid:vid1"])
    }

    @Test
    fun aSongThatIsNotDownloadedIsTheLookupAlone() = runBlocking {
        assertEquals(cover, ForkDownloadedCanvas.through(null, { true }, ::put, ::read) { cover })
        assertEquals(emptyMap(), saved)
    }
}
