package com.music.bitchord.playback.smart.automixv2

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** versions.md "No effect three times in a row": a move counts at its mix's real end, once; a called-off mix never. */
class ForkV2MovesTest {

    private fun plan(style: String) = ForkV2Planner.Plan().apply { this.style = style }

    @BeforeTest
    fun start() = ForkV2Moves.clear()

    @AfterTest
    fun end() = ForkV2Moves.clear()

    @Test
    fun aMixCalledOffNeverCounts() {
        val filter = plan("filter")
        repeat(3) { ForkV2Moves.ended(Any(), filter, reachedEnd = false) }
        assertEquals(emptyList(), ForkV2Moves.recentMoves())
        assertNull(ForkV2Moves.avoidedMove(), "three aborted filter mixes don't make a filter mix a trick")
    }

    @Test
    fun aMixThatRanOutCountsOnceHoweverOftenItIsSaid() {
        val filter = plan("filter")
        val mix = Any()
        ForkV2Moves.ended(mix, filter, reachedEnd = false) // a tick before the end
        ForkV2Moves.ended(mix, filter, reachedEnd = true)
        ForkV2Moves.ended(mix, filter, reachedEnd = true)
        assertEquals(listOf("filter"), ForkV2Moves.recentMoves())
        ForkV2Moves.ended(Any(), filter, reachedEnd = true)
        assertEquals("filter", ForkV2Moves.avoidedMove())
    }

    @Test
    fun anAbortedMixBetweenTwoHeardOnesDoesNotBreakOrMakeTheRun() {
        val filter = plan("filter")
        ForkV2Moves.ended(Any(), filter, reachedEnd = true)
        ForkV2Moves.ended(Any(), plan("blend").apply { shape = "echo" }, reachedEnd = false)
        ForkV2Moves.ended(Any(), filter, reachedEnd = true)
        assertEquals("filter", ForkV2Moves.avoidedMove())
    }
}
