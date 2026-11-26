package com.music.bitchord.playback.smart

import com.music.bitchord.playback.smart.ForkAddonCopyProgress.Stage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The progress row's addon-copy line (editor.md §11 rule 6): its words, its place after the analysis lines, and its ends. */
class ForkAddonCopyProgressTest {

    private var now = 0L

    @BeforeTest
    fun start() {
        ForkAnalysisProgress.clear()
        ForkAddonCopyProgress.clockMs = { now }
    }

    private val realClock = ForkAddonCopyProgress.clockMs

    @AfterTest
    fun reset() {
        ForkAddonCopyProgress.clockMs = realClock
        ForkAnalysisProgress.clear()
        ForkMixPicks.style = AutomixStyle.BITCHORD
    }

    private fun row(cur: String? = "cur", next: String? = "next", loop: Boolean = false) =
        ForkAnalysisProgress.rowFor(cur, next, loop)

    @Test
    fun eachStageHasItsWordsAndAMovingBar() {
        ForkAddonCopyProgress.set("cur", Stage.ASKING)
        assertEquals("Asking your addons…", row()!!.text)
        assertTrue(row()!!.fill!!.isNaN())
        ForkAddonCopyProgress.set("cur", Stage.FETCHING, lossless = true)
        assertEquals("Getting the lossless copy…", row()!!.text)
        ForkAddonCopyProgress.set("cur", Stage.LINING_UP)
        assertEquals("Lining up the lossless copy with YouTube's…", row()!!.text, "a later stage keeps what the copy is")
        ForkAddonCopyProgress.set("cur", Stage.SWITCHING)
        assertEquals("Switching to the lossless copy…", row()!!.text)
        assertTrue(row()!!.fill!!.isNaN())
        ForkAddonCopyProgress.end("cur")
        assertNull(row(), "heard: nothing")
    }

    @Test
    fun aLossyCopyIsTheAddonsCopy() {
        ForkAddonCopyProgress.set("cur", Stage.FETCHING, lossless = false)
        assertEquals("Getting the addon's copy…", row()!!.text)
        ForkAddonCopyProgress.set("cur", Stage.LINING_UP, lossless = false)
        assertEquals("Lining up the addon's copy with YouTube's…", row()!!.text)
        ForkAddonCopyProgress.set("cur", Stage.SWITCHING)
        assertEquals("Switching to the addon's copy…", row()!!.text)
    }

    @Test
    fun losslessIsTheCodecOrAStatedBitDepth() {
        listOf("flac", "FLAC", "audio/flac", "alac", "wav", "aiff", "pcm_s24le", "audio/flac; rate=44100").forEach {
            assertTrue(ForkAddonCopyProgress.losslessCopy(it, null), it)
        }
        assertTrue(ForkAddonCopyProgress.losslessCopy(null, 24))
        assertTrue(ForkAddonCopyProgress.losslessCopy("mp3", 16), "a stated bit depth")
        listOf("opus", "mp3", "aac", "audio/mp4", "eac3-joc", null).forEach {
            assertFalse(ForkAddonCopyProgress.losslessCopy(it, null), "$it")
        }
        assertFalse(ForkAddonCopyProgress.losslessCopy("opus", 0))
    }

    @Test
    fun theNextSongsCopyIsNeverShown() {
        ForkAddonCopyProgress.set("next", Stage.FETCHING, lossless = true)
        assertNull(row())
        assertNull(row(cur = null))
        // once it is the playing song, its copy is
        assertEquals("Getting the lossless copy…", row(cur = "next", next = "after")!!.text)
    }

    @Test
    fun itComesAfterBothSongsAnalysisLinesAndBeforeTheLoopSearch() {
        ForkAddonCopyProgress.set("cur", Stage.ASKING)
        ForkAnalysisProgress.stage("cur", ForkAnalysisProgress.Stage.MODELS)
        assertTrue(row(loop = true)!!.text.startsWith("Analysing this song"))
        ForkAnalysisProgress.ended("cur", usable = true)
        ForkAnalysisProgress.stage("next", ForkAnalysisProgress.Stage.DECODING)
        assertTrue(row(loop = true)!!.text.startsWith("Analysing the next song"), "the next song's analysis is an analysis line too")
        ForkAnalysisProgress.ended("next", usable = false)
        assertTrue(row(loop = true)!!.text.startsWith("Analysis of the next song failed"), "an outcome has something to say")
        ForkAnalysisProgress.stage("next", ForkAnalysisProgress.Stage.QUEUED)
        ForkAnalysisProgress.ended("next", usable = true)
        assertEquals("Asking your addons…", row(loop = true)!!.text, "then the copy, ahead of the loop search")
        ForkAddonCopyProgress.end("cur")
        assertEquals("Looking for a loop mix…", row(loop = true)!!.text)
    }

    @Test
    fun anEndForAnotherStageLeavesTheLineAlone() {
        ForkAddonCopyProgress.set("cur", Stage.SWITCHING, lossless = true)
        ForkAddonCopyProgress.end("cur", only = setOf(Stage.ASKING, Stage.FETCHING, Stage.LINING_UP))
        assertEquals(Stage.SWITCHING, ForkAddonCopyProgress.stageOf("cur"), "the pass ending doesn't end a take-over still running")
        ForkAddonCopyProgress.end("cur", only = setOf(Stage.SWITCHING))
        assertNull(ForkAddonCopyProgress.stageOf("cur"))
    }

    @Test
    fun theSongMovingOnDropsTheOldSongsCopy() {
        ForkAnalysisProgress.notePair("a", "b")
        ForkAddonCopyProgress.set("a", Stage.SWITCHING)
        ForkAddonCopyProgress.set("b", Stage.ASKING)
        ForkAnalysisProgress.notePair("b", "c")
        assertNull(ForkAddonCopyProgress.stageOf("a"))
        assertEquals(Stage.ASKING, ForkAddonCopyProgress.stageOf("b"), "the next song becoming the playing one keeps its race")
    }

    @Test
    fun aTakeOverIsNotShownPastItsLongest() {
        ForkAddonCopyProgress.set("cur", Stage.SWITCHING, lossless = true)
        now += ForkAddonCopyProgress.SWITCH_MAX_MS
        assertEquals("Switching to the lossless copy…", row()!!.text)
        ForkAddonCopyProgress.set("cur", Stage.SWITCHING) // the same stage again keeps its start
        now += 1
        assertNull(row())
    }

    @Test
    fun aMoveTicksTheRowAndARepeatDoesNot() {
        val before = ForkAnalysisProgress.changes.value
        ForkAddonCopyProgress.set("cur", Stage.ASKING)
        val moved = ForkAnalysisProgress.changes.value
        assertTrue(moved > before)
        ForkAddonCopyProgress.set("cur", Stage.ASKING)
        ForkAddonCopyProgress.end("other")
        assertEquals(moved, ForkAnalysisProgress.changes.value)
        ForkAddonCopyProgress.end("cur")
        assertTrue(ForkAnalysisProgress.changes.value > moved)
    }
}
