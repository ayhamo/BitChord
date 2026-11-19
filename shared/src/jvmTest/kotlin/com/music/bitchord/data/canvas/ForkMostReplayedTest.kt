package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The player menu's most replayed jump: which songs get the row, and where a curve sends it. */
class ForkMostReplayedTest {

    private fun curve(peak: IntRange?): List<ForkBestPart.Marker> =
        List(100) { i -> ForkBestPart.Marker(i * 2_000L, 2_000L, if (peak != null && i in peak) 0.9 else 0.2) }

    @Test
    fun onlyYouTubeIdsGetTheRow() {
        assertTrue(ForkMostReplayed.eligible("dQw4w9WgXcQ"))
        assertFalse(ForkMostReplayed.eligible("navidrome:123"))
        assertFalse(ForkMostReplayed.eligible("content://media/external/audio/media/42"))
        assertFalse(ForkMostReplayed.eligible(""))
    }

    @Test
    fun aPeakIsWhereTheJumpLands() {
        val jump = assertIs<ForkMostReplayed.Outcome.Jump>(ForkMostReplayed.outcomeOf(curve(50..55)))
        // the canvas's own pick on the same curve, at the start of the rise (marker 50 = 100 s)
        assertEquals(ForkBestPart.partStart(curve(50..55), 0), jump.ms)
        assertEquals(100_000L, jump.ms)
        assertEquals("Most replayed part, 1:40", ForkMostReplayed.message(jump))
    }

    @Test
    fun noCurveOrAFlatOneIsSaidSo() {
        assertEquals(ForkMostReplayed.Outcome.NoCurve, ForkMostReplayed.outcomeOf(null))
        assertEquals(ForkMostReplayed.Outcome.NothingStandsOut, ForkMostReplayed.outcomeOf(curve(null)))
        assertEquals(ForkMostReplayed.NO_CURVE, ForkMostReplayed.message(ForkMostReplayed.Outcome.NoCurve))
        assertEquals(ForkMostReplayed.Outcome.NoCurve, ForkMostReplayed.find("not-a-youtube-id"))
    }

    @Test
    fun theTimeReadsAsMinutesAndSeconds() {
        assertEquals("Most replayed part, 0:05", ForkMostReplayed.jumped(5_400))
        assertEquals("Most replayed part, 12:00", ForkMostReplayed.jumped(720_000))
    }
}
