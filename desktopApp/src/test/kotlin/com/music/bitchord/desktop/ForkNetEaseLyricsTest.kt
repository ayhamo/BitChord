package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.LyricsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: [ForkNetEaseLyrics] against saved NetEase answers (`fork-netease/`, the shape measured
 * 2026-11-04 from `api/search/get` and `api/song/lyric`, with made-up words), and its place in the
 * PC's source order. No network.
 */
class ForkNetEaseLyricsTest {

    private fun fixture(name: String) = javaClass.getResource("/fork-netease/$name")!!.readText()

    @Test
    fun theHitIsTheOneThatRunsToTheTracksLength() {
        val search = fixture("search.json")
        assertEquals(22605222L, ForkNetEaseLyrics.pickSong(search, "Creep", 239))
        // The acoustic take's length picks the acoustic take, not the first hit.
        assertEquals(2034734854L, ForkNetEaseLyrics.pickSong(search, "Creep", 259))
        // Nothing that long: no answer rather than someone else's words.
        assertNull(ForkNetEaseLyrics.pickSong(search, "Creep", 400))
        // No length: only a hit named as asked.
        assertEquals(22605222L, ForkNetEaseLyrics.pickSong(search, "Creep", 0))
        assertNull(ForkNetEaseLyrics.pickSong(search, "Something else", 0))
        assertNull(ForkNetEaseLyrics.pickSong("{\"code\":200}", "Creep", 239))
    }

    @Test
    fun yrcGivesWordTimingsWithoutTheCreditLines() {
        val lines = ForkNetEaseLyrics.linesFrom(fixture("lyric-yrc.json"))!!
        val sung = lines.filterNot { it.isGap }
        assertEquals(listOf("Yeah", "Walking down the road", "On my own"), sung.map { it.text })
        assertTrue(sung.all { it.isWordSynced })
        assertEquals(27_360L, sung[1].words.first().startMs)
        // The intro break the cut credits sat in is back.
        assertTrue(lines.first().isGap)
        assertEquals(0L, lines.first().timeMs)
    }

    @Test
    fun lrcIsTheFallbackAndASungColonIsNotACredit() {
        val lines = ForkNetEaseLyrics.linesFrom(fixture("lyric-lrc.json"))!!
        val sung = lines.filterNot { it.isGap }
        assertEquals(listOf("When the morning came", "Baby: hold the line"), sung.map { it.text })
        assertEquals(18_400L, sung.first().timeMs)
    }

    @Test
    fun theInstrumentalNoticeIsNoLyrics() {
        assertNull(ForkNetEaseLyrics.linesFrom(fixture("lyric-instrumental.json")))
        assertNull(ForkNetEaseLyrics.linesFrom("not json"))
    }

    @Test
    fun aFreshOrderPutsNetEaseAfterLyricsPlus() {
        val order = ForkNetEaseLyrics.ordered(emptyList())
        assertEquals(LyricsSource.LYRICS_PLUS.label, order[order.indexOf(ForkNetEaseLyrics.LABEL) - 1])
        assertEquals(order, DesktopLyricsClient.sources.map { it.name })
    }

    @Test
    fun aSavedOrderKeepsNetEaseWhereItWasDragged() {
        val saved = listOf("LRCLIB", ForkNetEaseLyrics.LABEL, "lrc.red")
        val order = ForkNetEaseLyrics.ordered(saved)
        assertEquals("LRCLIB", order[order.indexOf(ForkNetEaseLyrics.LABEL) - 1])
        assertEquals(1, order.count { it == ForkNetEaseLyrics.LABEL })
        // An order saved before NetEase existed gets it at its default place.
        val old = ForkNetEaseLyrics.ordered(listOf(LyricsSource.LYRICS_PLUS.label, "LRCLIB"))
        assertEquals(LyricsSource.LYRICS_PLUS.label, old[old.indexOf(ForkNetEaseLyrics.LABEL) - 1])
    }
}
