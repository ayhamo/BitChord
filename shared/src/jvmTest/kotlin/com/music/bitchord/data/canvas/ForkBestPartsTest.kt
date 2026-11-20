package com.music.bitchord.data.canvas

import com.music.bitchord.data.lyrics.LyricLine
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Prism's best part as ported: the chorus from synced lyrics, the snap, the button's window, your own. */
class ForkBestPartsTest {

    private val dir = Files.createTempDirectory("bestparts").toFile()

    @AfterTest
    fun cleanUp() {
        ForkBestParts.resetForTest(null)
        dir.deleteRecursively()
    }

    private fun lyrics(vararg lines: Pair<Long, String>) = lines.map { LyricLine(it.first, it.second) }

    private val song = lyrics(
        2_000L to "Intro line here",
        12_000L to "First verse goes along",
        20_000L to "Second verse line too",
        40_000L to "This is the chorus now",
        44_000L to "Sing it out loud again",
        60_000L to "Another verse comes in",
        80_000L to "This is the chorus now",
        84_000L to "Sing it out loud again",
        120_000L to "This is the chorus now",
    )

    @Test
    fun theChorusIsTheFirstRepeatedBlock() {
        assertEquals(40_000L, ForkBestParts.chorusMs(song, 200_000))
        // nothing repeats, too few lines, or too short a song: none
        assertNull(ForkBestParts.chorusMs(lyrics(10_000L to "one line", 20_000L to "two line", 30_000L to "three line", 40_000L to "four line"), 200_000))
        assertNull(ForkBestParts.chorusMs(song.take(3), 200_000))
        assertNull(ForkBestParts.chorusMs(song, 50_000))
    }

    @Test
    fun theSnapGoesToALineNearby() {
        val starts = listOf(10_000L, 40_000L, 80_000L)
        assertEquals(39_750L, ForkBestParts.snap(41_000, starts)) // a line just before
        assertEquals(39_750L, ForkBestParts.snap(39_000, starts)) // a line just after
        assertEquals(59_000L, ForkBestParts.snap(60_000, starts)) // none near: a second before
        assertEquals(0L, ForkBestParts.snap(500, emptyList()))
    }

    @Test
    fun theStartPrefersYourOwnThenThePeakThenTheChorus() {
        assertEquals(5_000L, ForkBestParts.start(5_000L, 70_000, song, 200_000))
        assertEquals(79_750L, ForkBestParts.start(null, 80_500, song, 200_000))
        assertEquals(39_750L, ForkBestParts.start(null, -1, song, 200_000))
        assertNull(ForkBestParts.start(null, -1, emptyList(), 200_000))
    }

    @Test
    fun theButtonShowsEarlyAndWhileThePartIsAhead() {
        assertTrue(ForkBestParts.shows(5_000, 60_000, 0))
        assertFalse(ForkBestParts.shows(31_000, 60_000, 0)) // past the first 30 s
        assertTrue(ForkBestParts.shows(31_000, 60_000, 20_000)) // 15 s from when it was known
        assertFalse(ForkBestParts.shows(46_000, 90_000, 40_000)) // never after 45 s
        assertFalse(ForkBestParts.shows(10_000, 12_000, 0)) // under 3 s ahead
    }

    @Test
    fun yourOwnIsKeptAndTheButtonFollows() {
        ForkBestParts.resetForTest(dir)
        val id = "dQw4w9WgXcQ"
        ForkBestParts.setCurrent(ForkBestParts.Current(id, 40_000, 40_000, 0, mine = false))
        ForkBestParts.setMine(id, 90_000)
        assertEquals(90_000L, ForkBestParts.mine(id))
        assertEquals(90_000L, ForkBestParts.current.value?.startMs)
        assertTrue(ForkBestParts.current.value!!.mine)
        ForkBestParts.resetForTest(dir)
        assertEquals(90_000L, ForkBestParts.mine(id))
        ForkBestParts.setCurrent(ForkBestParts.Current(id, 90_000, 40_000, 0, mine = true))
        ForkBestParts.setMine(id, null)
        assertNull(ForkBestParts.mine(id))
        assertEquals(40_000L, ForkBestParts.current.value?.startMs)
    }

    @Test
    fun lyricsAreKeptOnlyWhenSynced() {
        ForkBestParts.noteLyrics("a", lyrics(0L to "unsynced", 0L to "lines"))
        assertTrue(ForkBestParts.lines("a").isEmpty())
        ForkBestParts.noteLyrics("b", song)
        assertEquals(song, ForkBestParts.lines("b"))
    }

    @Test
    fun theMostReplayedJumpSnapsToALine() {
        val curve = List(100) { i -> ForkBestPart.Marker(i * 2_000L, 2_000L, if (i in 50..55) 0.9 else 0.2) }
        val jump = assertIs<ForkMostReplayed.Outcome.Jump>(ForkMostReplayed.outcomeOf(curve, lyrics(98_000L to "a line", 130_000L to "another")))
        assertEquals(97_750L, jump.ms)
        assertEquals(100_000L, assertIs<ForkMostReplayed.Outcome.Jump>(ForkMostReplayed.outcomeOf(curve)).ms)
    }
}
