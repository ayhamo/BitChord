package com.music.bitchord.playback.smart

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The progress row's state: one atomic change at a time, and "over 8 minutes" ended by a usable analysis. */
class ForkAnalysisProgressTest {

    @AfterTest
    fun reset() {
        ForkAnalysisProgress.clear()
        ForkMixPicks.style = AutomixStyle.BITCHORD
    }

    @Test
    fun aUsableAnalysisEndsTooLongAndTheRowIsV2Only() {
        ForkAnalysisProgress.clear()
        ForkMixPicks.style = AutomixStyle.V2
        assertTrue(ForkAnalysisProgress.tooLong("long", 600.0))
        assertTrue(ForkAnalysisProgress.isTooLong("long"))
        ForkMixPicks.style = AutomixStyle.BITCHORD
        assertNull(ForkAnalysisProgress.rowFor("long", null, false), "BitChord's style analyses any length: no too-long row")
        ForkAnalysisProgress.ended("long", usable = true) // analysed under BitChord's style
        ForkMixPicks.style = AutomixStyle.V2
        assertFalse(ForkAnalysisProgress.isTooLong("long"))
        assertNull(ForkAnalysisProgress.rowFor("long", null, false))
    }

    @Test
    fun aQueuedWriteRacingAnEndNeverLosesAnotherSongsEnd() {
        repeat(200) { round ->
            ForkAnalysisProgress.clear()
            val ids = (0 until 8).map { "r$round-$it" }
            ids.forEach { ForkAnalysisProgress.stage(it, ForkAnalysisProgress.Stage.SAVING) }
            val go = CountDownLatch(1)
            // the worker ends each song while the player's tick queues another
            val worker = thread { go.await(); ids.forEach { ForkAnalysisProgress.ended(it, usable = true) } }
            val tick = thread { go.await(); repeat(8) { ForkAnalysisProgress.queued("tick$round-$it") } }
            go.countDown()
            worker.join()
            tick.join()
            ids.forEach { assertNull(ForkAnalysisProgress.rowFor(it, null, false), "$it ended but its row came back") }
            (0 until 8).forEach { assertEquals(0f, ForkAnalysisProgress.rowFor("tick$round-$it", null, false)?.fill, "the tick's queued song was lost") }
        }
    }

    /** The PC's hooks: a queued song dropped from the queue ends stopped; a failure that threw nothing ends failed. */
    @Test
    fun aDroppedQueuedSongIsStoppedAndASilentFailureIsAFailure() {
        ForkAnalysisProgress.clear()
        ForkAnalysisProgress.queued("dropped")
        ForkAnalysisProgress.ended("dropped", null, stopped = true)
        val row = ForkAnalysisProgress.rowFor("dropped", null, false)!!
        assertTrue(row.text.contains("stopped"), row.text)
        ForkAnalysisProgress.queued("dropped") // wanted again: the note goes, it is waiting once more
        assertEquals(0f, ForkAnalysisProgress.rowFor("dropped", null, false)?.fill)
        ForkAnalysisProgress.stage("silent", ForkAnalysisProgress.Stage.DECODING)
        ForkAnalysisProgress.ended("silent", usable = false)
        val failed = ForkAnalysisProgress.rowFor("silent", null, false)!!
        assertTrue(failed.text.contains("failed"), failed.text)
    }

    /** editor.md §11 rule 3: a retry stopped or decoded short reads as that, not as the earlier pass's failure. */
    @Test
    fun aRetryThatIsStoppedOrShortDropsTheEarlierFailure() {
        ForkAnalysisProgress.clear()
        ForkAnalysisProgress.queued("x")
        ForkAnalysisProgress.failed("x", IllegalStateException("decoder exploded"))
        ForkAnalysisProgress.ended("x", false)
        assertTrue(ForkAnalysisProgress.rowFor("x", null, false)!!.text.contains("failed: decoder exploded"))
        ForkAnalysisProgress.queued("x") // tried again
        ForkAnalysisProgress.ended("x", null, stopped = true)
        val stopped = ForkAnalysisProgress.rowFor("x", null, false)!!.text
        assertTrue(stopped.contains("stopped") && !stopped.contains("failed"), stopped)
        ForkAnalysisProgress.queued("x")
        ForkAnalysisProgress.ended("x", null, short = true)
        val short = ForkAnalysisProgress.rowFor("x", null, false)!!.text
        assertTrue(short.contains("too short") && !short.contains("failed"), short)
    }
}
