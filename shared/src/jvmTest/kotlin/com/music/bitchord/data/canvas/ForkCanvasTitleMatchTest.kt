package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: titles in any script are compared on their letters (CanvasArtwork.matches, titlesMatch). */
class ForkCanvasTitleMatchTest {

    @Test
    fun `a Latin artist's Japanese titles are told apart`() {
        // Both folded to "" with the a-z0-9 rule, so another song's clip passed the check.
        val clip = CanvasArtwork(url = "u", title = "残響散歌", artist = "Aimer")
        assertFalse(clip.matches("カタオモイ", "Aimer", null))
        assertTrue(clip.matches("残響散歌", "Aimer", null))
    }

    @Test
    fun `case accents and punctuation still fold away`() {
        assertTrue(titlesMatch("Crazy in Love (feat. Jay-Z)", "CRAZY IN LOVE feat Jay Z"))
        assertTrue(titlesMatch("Beyoncé", "beyonce"))
        assertEquals("мата хари", "Мата-Хари!".normalizeTitleForMatch())
    }

    @Test
    fun `a wanted title that folds to nothing matches nothing`() {
        assertFalse(titlesMatch("?!", "?!"))
        assertFalse(titlesMatch("", "..."))
        assertFalse(CanvasArtwork(url = "u", title = "...", artist = "Aimer").matches("...", "Aimer", null))
    }

    @Test
    fun `an unknown wanted title is still not held against the clip`() {
        assertTrue(CanvasArtwork(url = "u", title = "残響散歌", artist = "Aimer").matches("", "Aimer", null))
    }
}
