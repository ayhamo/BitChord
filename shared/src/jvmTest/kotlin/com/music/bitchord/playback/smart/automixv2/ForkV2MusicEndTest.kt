package com.music.bitchord.playback.smart.automixv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** analysis-cases.md "Where the song ends" [all] (48129eb): only 1 s or more before the file's end, never more than 20 s cut. */
class ForkV2MusicEndTest {

    private fun song(duration: Double, contentEnd: Double) = ForkV2Analysis().also {
        it.durationSec = duration
        it.contentEndSec = contentEnd
    }

    @Test
    fun theMusicsEndIsPlannedAgainstOnlyWhenItEndsASecondOrMoreBeforeTheFile() {
        assertEquals(197.0, ForkV2Planner.musicEnd(song(200.0, 197.0)).durationSec, 1e-12)
        assertEquals(198.9, ForkV2Planner.musicEnd(song(200.0, 198.9)).durationSec, 1e-12)
        // exactly 1 s or under: the file's end, as the PC's musicEnd (`end >= durationSec - 1`) keeps it
        val oneSecond = song(200.0, 199.0)
        assertSame(oneSecond, ForkV2Planner.musicEnd(oneSecond))
        val close = song(200.0, 199.5)
        assertSame(close, ForkV2Planner.musicEnd(close))
        val none = song(200.0, Double.NaN)
        assertSame(none, ForkV2Planner.musicEnd(none))
    }

    @Test
    fun neverMoreThanTwentySecondsAreCut() {
        assertEquals(180.0, ForkV2Planner.musicEnd(song(200.0, 150.0)).durationSec, 1e-12)
        assertEquals(180.0, ForkV2Planner.musicEnd(song(200.0, 180.0)).durationSec, 1e-12)
    }
}
