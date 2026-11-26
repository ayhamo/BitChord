package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals

class ForkLoopSpansTest {

    private fun ends(vararg shapes: ForkLoopSpans.Shape) = ForkLoopSpans.spans(shapes.toList()).map { it.start to it.end }

    @Test
    fun aLineEndsAtItsLastWordItsOwnEndOrTheNextLineTenSecondsAtMostPadded() {
        val got = ends(
            ForkLoopSpans.Shape(10.0, 20.0, listOf(11.0, 12.5)), // the last word wins over the stated end
            ForkLoopSpans.Shape(30.0, 33.0, emptyList()), // its own end
            ForkLoopSpans.Shape(40.0, null, emptyList()), // the next line
            ForkLoopSpans.Shape(45.0, null, emptyList()), // the last line: 10 s
            ForkLoopSpans.Shape(5.0, 40.0, emptyList()), // sorted first; its own end capped at 10 s
        )
        val want = listOf(4.85 to 15.35, 9.85 to 12.85, 29.85 to 33.35, 39.85 to 45.35, 44.85 to 55.35)
        assertEquals(want.size, got.size)
        for ((w, g) in want.zip(got)) {
            assertEquals(w.first, g.first, 1e-9)
            assertEquals(w.second, g.second, 1e-9)
        }
    }

    @Test
    fun everySpanIsCertainToItsEnd() {
        ForkLoopSpans.spans(listOf(ForkLoopSpans.Shape(1.0, null, emptyList()))).forEach { assertEquals(it.end, it.certainUntil) }
    }

    @Test
    fun aLineNotSungOrBeforeZeroCountsForNothingAndEndsNoLine() {
        val got = ForkLoopSpans.spans(
            listOf(
                ForkLoopSpans.Shape(-1.0, 2.0, emptyList()),
                ForkLoopSpans.Shape(20.0, null, emptyList()),
                ForkLoopSpans.Shape(22.0, null, emptyList(), notSung = true),
                ForkLoopSpans.Shape(30.0, 32.0, emptyList()),
            ),
        )
        // the line at 20 s runs to the next sung line (30 s), not to the unsung one at 22 s
        val want = listOf(19.85 to 30.35, 29.85 to 32.35)
        assertEquals(want.size, got.size)
        for ((w, g) in want.zip(got)) {
            assertEquals(w.first, g.start, 1e-9)
            assertEquals(w.second, g.end, 1e-9)
        }
    }
}
