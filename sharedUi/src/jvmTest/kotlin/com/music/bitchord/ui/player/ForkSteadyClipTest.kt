package com.music.bitchord.ui.player

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ForkSteadyClipTest {
    private val apple = CanvasArtwork(
        url = "https://apple/cover.m3u8",
        fallbackUrl = "https://apple/cover.mp4",
        title = "Song",
        artist = "Artist",
        source = CanvasSource.OTHER,
        portraitUrl = "https://apple/tall.m3u8",
    )
    private val spotify = CanvasArtwork(url = "https://spotify/canvas.mp4", title = "Song", artist = "Artist", source = CanvasSource.SPOTIFY)

    @Test
    fun `the backdrop takes the first clip as its sleeve`() {
        val combined = forkWithBackdrop(apple, spotify)!!
        assertEquals(spotify.url, combined.url)
        assertEquals(CanvasSource.SPOTIFY, combined.source)
        assertEquals(apple.url, combined.sleeveUrl)
    }

    @Test
    fun `without a backdrop or a first clip nothing is combined`() {
        assertSame(apple, forkWithBackdrop(apple, null))
        assertNull(forkWithBackdrop(null, spotify))
    }

    @Test
    fun `a folded backdrop keeps the clip already playing`() {
        // What forkInSleeve makes of the combined answer where the backdrop can't fill the screen.
        val folded = forkWithBackdrop(apple, spotify)!!.let {
            it.copy(url = it.sleeveUrl!!, fallbackUrl = null, source = CanvasSource.OTHER, sleeveUrl = null, portraitUrl = null)
        }
        assertSame(apple, forkSteadyClip(apple, folded, folded = true))
    }

    @Test
    fun `an ordinary answer with the same url is taken as it comes`() {
        val withoutTall = apple.copy(portraitUrl = null)
        assertSame(apple, forkSteadyClip(withoutTall, apple, folded = false))
    }

    @Test
    fun `a different clip replaces the last one`() {
        assertSame(spotify, forkSteadyClip(apple, spotify, folded = true))
        assertNull(forkSteadyClip(apple, null, folded = false))
        assertSame(apple, forkSteadyClip(null, apple, folded = true))
    }
}
