package com.music.bitchord.ui.player

import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: v1.10's Automix canvas follows "Animated covers" and the canvas settings (ForkAutoMixCanvasKey). */
class ForkAutoMixCanvasTest {

    private val apple = CanvasArtwork(url = "https://apple/cover.m3u8", source = CanvasSource.APPLE)
    private val spotify = CanvasArtwork(url = "https://spotify/canvas.mp4", source = CanvasSource.SPOTIFY)

    @Test
    fun aCoverClipNeedsAnimatedCoversAndASettingChangeIsANewKey() {
        assertTrue(ForkAutoMixCanvasKey(covers = true, generation = 0).allows(apple))
        assertFalse(ForkAutoMixCanvasKey(covers = false, generation = 0).allows(apple))
        // A full-screen clip is not this switch's (it retires before the mix).
        assertTrue(ForkAutoMixCanvasKey(covers = false, generation = 0).allows(spotify))
        // Either change restarts the effects that hold the promoted clip.
        assertNotEquals(ForkAutoMixCanvasKey(true, 0), ForkAutoMixCanvasKey(true, 1))
        assertNotEquals(ForkAutoMixCanvasKey(true, 0), ForkAutoMixCanvasKey(false, 0))
    }
}
