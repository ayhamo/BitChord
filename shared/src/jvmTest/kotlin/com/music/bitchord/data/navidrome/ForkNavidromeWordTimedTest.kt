package com.music.bitchord.data.navidrome

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "word timed wins from bitchord, ignore navidrome first for this" (owner, 2026-11-25). */
class ForkNavidromeWordTimedTest {

    private val serverLines = listOf(LyricLine(1_000, "from the server"))
    private val serverWords = listOf(LyricLine(1_000, "from the server", words = listOf(LyricWord(1_000, 1_400, "from"), LyricWord(1_400, 2_000, "the server"))))
    private val onlineLines = listOf(LyricLine(1_000, "online"))
    private val onlineWords = listOf(LyricLine(1_000, "online", words = listOf(LyricWord(1_000, 1_800, "online"))))

    @Test
    fun wordTimedOnlineLyricsWinOverTheServersLineTimedOnes() {
        assertTrue(ForkNavidromeWordTimed.asksOnline(serverLines), "line-timed server lyrics still ask the sources")
        assertEquals(onlineWords, ForkNavidromeWordTimed.pick(serverLines, onlineWords))
    }

    @Test
    fun theServerKeepsItsPlaceOtherwise() {
        assertEquals(serverLines, ForkNavidromeWordTimed.pick(serverLines, onlineLines), "line-timed against line-timed: the server's")
        assertEquals(serverLines, ForkNavidromeWordTimed.pick(serverLines, null), "no online answer: the server's")
        assertFalse(ForkNavidromeWordTimed.asksOnline(serverWords), "word-timed server lyrics end the lookup")
        assertEquals(serverWords, ForkNavidromeWordTimed.pick(serverWords, onlineWords))
        assertFalse(ForkNavidromeWordTimed.asksOnline(null))
        assertEquals(onlineLines, ForkNavidromeWordTimed.pick(null, onlineLines), "nothing from the server: as before")
        assertNull(ForkNavidromeWordTimed.pick(null, null))
    }
}
