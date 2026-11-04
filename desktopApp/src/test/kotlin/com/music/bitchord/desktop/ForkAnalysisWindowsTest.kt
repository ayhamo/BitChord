package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.BeatTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork: the PC analyser's model windows and its queue (from Desktop Revived d77475ba / 573478be). */
class ForkAnalysisWindowsTest {

    private val span = 22.0

    @Test
    fun twoWindowsAtTheHeadAndThreeAtTheTail() {
        val duration = 240.0
        val headEnd = 2 * span - 1.0
        val tailStart = duration - (3 * span - 2 * 1.0)
        val windows = ForkAnalysisWindows.windows(headEnd, tailStart, duration, span, 1.0)
        assertEquals(5, windows.size)
        assertEquals(0.0, windows.first().first)
        assertEquals(duration, windows.last().second, 1e-9)
        // Neighbours overlap by the overlap, no window is longer than one model input.
        windows.zipWithNext().take(1).forEach { (a, b) -> assertEquals(1.0, a.second - b.first, 1e-9) }
        assertTrue(windows.all { it.second - it.first <= span + 1e-9 })
    }

    @Test
    fun aShortSongIsOneSpan() {
        val windows = ForkAnalysisWindows.windows(43.0, 0.0, 30.0, span, 1.0)
        assertEquals(2, windows.size)
        assertEquals(30.0, windows.last().second, 1e-9)
    }

    @Test
    fun theSeamKeepsEachWindowsBeatsUpToTheOverlapsMidpoint() {
        fun grid(beats: List<Double>) = BeatTracker.Grid(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }, 120.0, 0.5, beats.first(), 0.8)
        val first = ForkAnalysisWindows.Graded(grid((0 until 44).map { it * 0.5 }), start = 0.0, seconds = 22.0)
        val second = ForkAnalysisWindows.Graded(grid((42 until 86).map { it * 0.5 }), start = 21.0, seconds = 22.0)
        val all = ForkAnalysisWindows.seam(listOf(second, first))
        // No beat counted twice across the 1 s overlap.
        assertEquals(all.beats.distinct().size, all.beats.size)
        val side = ForkAnalysisWindows.side(all, 0.0, 43.0)!!
        assertEquals(120.0, side.bpm, 0.5)
        assertNull(ForkAnalysisWindows.side(all, 100.0, 200.0))
    }

    @Test
    fun theQueueHoldsOnlyThePlayingSongAndTheNextPlayingFirst() {
        val queue = ForkAnalysisQueue<String>()
        queue.focus("a", "b")
        assertTrue(queue.offer("b", "next"))
        assertTrue(queue.offer("a", "playing"))
        assertEquals(false, queue.offer("c", "other"))
        assertEquals("a", queue.poll()?.first)
        queue.focus("b", "d")
        assertEquals(listOf("b"), queue.pendingIds())
        assertEquals(false, queue.isRelevant("a"))
    }
}
