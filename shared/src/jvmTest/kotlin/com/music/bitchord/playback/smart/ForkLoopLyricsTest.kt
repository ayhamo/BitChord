package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** versions.md "The loop mix's baseline" rule 6, the extension's own cases (test_automix_plan.mjs). */
class ForkLoopLyricsTest {

    @Test
    fun creditTitleAndSymbolLinesAreNotSung() {
        val title = "SI NO ES CONTIGO"
        val artist = "Cris Mj"
        for (t in listOf(
            "作曲: Cristopher Andrés Álvarez Garcia", "作词：X", "编曲 : Y", "Letra: Cris Mj", "Música: Cris Mj", "Lyrics by Someone",
            "Written by A & B", "Prod. by Y", "Produced by Z", "Mixed by W", "SI NO ES CONTIGO", "Cris Mj", "Cris Mj - Si No Es Contigo",
            "♪ ♪", "...",
        )) assertTrue(ForkLoopLyrics.notSung(t, 1.0, title, artist), t)
        for (t in listOf(
            "Si no es contigo no es con nadie", "Written in the stars", "Music is my life", "Lyrics of my heart",
            "Words don't come easy", "Cris Mj está aquí",
        )) assertFalse(ForkLoopLyrics.notSung(t, 1.0, title, artist), t)
        assertTrue(ForkLoopLyrics.notSung("hola", -0.5, title, artist), "a start before 0")
    }
}
