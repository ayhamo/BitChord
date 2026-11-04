package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import com.music.bitchord.data.ForkAppleArtistArt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fork: the colours the PC's artist page takes from Apple's art (ForkArtistHeader.kt). */
class ForkArtistHeaderTest {

    private fun art(background: Int, keyColor: Int?) = ForkAppleArtistArt(
        heroUrl = "https://x/hero.jpg",
        heroAspect = 2f,
        logoUrl = null,
        logoAspect = 1f,
        background = background,
        accent = background,
        keyColor = keyColor,
    )

    @Test
    fun playIsApplesFillWithThePageColourOnIt() {
        val (fill, glyph) = ForkArtistHeader.playColors(art(0xFF101820.toInt(), 0xFFFFC0CB.toInt()))
        assertEquals(Color(0xFFFFC0CB), fill)
        assertEquals(Color(0xFF101820), glyph)
    }

    @Test
    fun withoutAKeyColourPlayIsWhiteAndStillReads() {
        // A pale page on a white pill would vanish: the glyph goes black.
        val (fill, glyph) = ForkArtistHeader.playColors(art(0xFFF4F4F4.toInt(), null))
        assertEquals(Color.White, fill)
        assertEquals(Color.Black, glyph)
    }

    @Test
    fun thePageTintIsApplesColourOverTheAppsSurface() {
        // Part of the way to Apple's red, nowhere near all of it, so white text still reads.
        val tint = ForkArtistHeader.pageTint(art(0xFFFF0000.toInt(), null))
        assertTrue(tint.red > DesktopSurface.red + 0.1f && tint.red < 0.7f, "red ${tint.red}")
        assertTrue(tint.red > tint.green + 0.1f && tint.red > tint.blue + 0.1f, "tint $tint")
    }
}
