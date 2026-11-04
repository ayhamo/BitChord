package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.LyricsShareLine
import com.music.bitchord.ui.player.LyricsShareRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fork: the PC's lyrics share card (ForkLyricsShare.kt). */
class ForkLyricsShareTest {

    @Test
    fun theFileIsNamedAsThePhoneNamesItAndSafeOnWindows() {
        assertEquals("bitchord-lyrics-bohemian-rhapsody.png", ForkLyricsShare.fileName("Bohemian Rhapsody"))
        assertEquals("bitchord-lyrics-acdc-what.png", ForkLyricsShare.fileName("AC/DC: What?"))
        assertEquals("bitchord-lyrics.png", ForkLyricsShare.fileName("  "))
        assertEquals("card.png", ForkLyricsShare.withPngExtension("card"))
        assertEquals("card.PNG", ForkLyricsShare.withPngExtension("card.PNG"))
    }

    @Test
    fun picturesIsTheDefaultFolderWhenThereIsOne() {
        val home = Files.createTempDirectory("home").toFile()
        try {
            assertEquals(home, ForkLyricsShare.picturesDir(home.path))
            val pictures = File(home, "Pictures").apply { mkdirs() }
            assertEquals(pictures, ForkLyricsShare.picturesDir(home.path))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun hslRoundTripsAndTheBackdropColoursStayInTheirBand() {
        val color = 0xFF3A1C71.toInt()
        val (h, s, l) = ForkLyricsShare.colorToHsl(color)
        assertEquals(color, ForkLyricsShare.hslToColor(h, s, l))
        val (_, _, tunedL) = ForkLyricsShare.colorToHsl(ForkLyricsShare.tuned(0xFFF8F8F0.toInt()))
        assertTrue(tunedL in 0.27f..0.59f)
        val (_, _, dimL) = ForkLyricsShare.colorToHsl(ForkLyricsShare.dimmed(color))
        assertTrue(dimL in 0.09f..0.11f)
    }

    private fun request(lines: List<LyricsShareLine>) = LyricsShareRequest(
        song = Song(videoId = "x", title = "Song", artist = "Artist", thumbnailUrl = null),
        artworkUrl = null,
        lines = lines,
    )

    /**
     * Needs Skia's native library for this OS; the Docker build runs on Linux with only the
     * Windows natives on the classpath, so there it is skipped (and says so).
     */
    private fun skiaLoads(): Boolean = runCatching { org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1) }
        .onFailure { println("ForkLyricsShareTest: Skia natives missing here, render test skipped ($it)") }
        .isSuccess

    @Test
    fun theCardIs1080WideNoTallerThan916AndAStoryIsTheWholeFrame() = runBlocking {
        if (!skiaLoads()) return@runBlocking
        val lines = listOf(
            LyricsShareLine("Nie dzwoń do mnie teraz, nie przeszkadzaj mi", "Don't call me now"),
            LyricsShareLine("", null, isGap = true),
            LyricsShareLine("一緒に歌おう", null),
        )
        val card = ForkLyricsShare.render(request(lines), story = false)
        assertEquals(ForkLyricsShare.CARD_W, card.width)
        assertTrue(card.height < ForkLyricsShare.MAX_CARD_H, "a short passage makes a short card (${card.height})")
        // PNG signature.
        assertEquals(0x89.toByte(), card.png[0])
        assertEquals('P'.code.toByte(), card.png[1])
        val story = ForkLyricsShare.render(request(lines), story = true)
        assertEquals(ForkLyricsShare.MAX_CARD_H.toInt(), story.height)
    }
}
