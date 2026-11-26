package com.music.bitchord.data.canvas

import com.music.bitchord.data.canvas.ForkCanvasRules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** canvas.md §15 rule 11 [all]: a song's switches say they are this song's, and name the menu's switch for all songs. */
class ForkCanvasWordingTest {

    @Test
    fun theSongsSwitchesSayTheyAreThisSongsOnly() {
        assertEquals("Music video on this song", ForkCanvasChoices.switchTitle(Source.YOUTUBE_MUSIC_VIDEO))
        assertEquals("Spotify canvas on this song", ForkCanvasChoices.switchTitle(Source.SPOTIFY))
        assertEquals("Animated cover on this song", ForkCanvasChoices.switchTitle(Source.APPLE_MUSIC))
        assertEquals("Off hides it for this song only. Backdrop videos in the menu is for all songs.", ForkCanvasChoices.switchNote(Source.YOUTUBE_MUSIC_VIDEO))
        assertEquals("Off hides it for this song only. Backdrop videos in the menu is for all songs.", ForkCanvasChoices.switchNote(Source.SPOTIFY))
        assertEquals("Off hides it for this song only. Animated covers in the menu is for all songs.", ForkCanvasChoices.switchNote(Source.APPLE_MUSIC))
    }
}
