package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.plan
import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A song's playlist speed (spotify-mixes.md rule 10): which speed a song plays at, and the plan that carries it. */
class ForkSongSpeedTest {

    private fun ov(out: Double, `in`: Double) = ForkV2MixOverride().apply { speedOut = out; speedIn = `in` }

    @Test
    fun theMixBringingASongInGivesItsSpeedElseTheOneTakingItOut() {
        val mixes = mapOf("a>b" to ov(1.1, 0.95), "b>c" to ov(1.2, 1.0))
        val of = { x: String, y: String -> mixes["$x>$y"] }
        assertEquals(0.95, ForkV2MixOverride.songSpeed("b", "a", "c", of))
        assertEquals(1.2, ForkV2MixOverride.songSpeed("b", null, "c", of))
        assertEquals(1.1, ForkV2MixOverride.songSpeed("a", null, "b", of))
        assertEquals(1.0, ForkV2MixOverride.songSpeed("c", "b", null, of))
    }

    @Test
    fun offItPlaysEverySongAtX1() {
        ForkSongSpeed.on = false
        assertEquals(1.0, ForkSongSpeed.of("a", null, "b"))
        assertNull(ForkSongSpeed.pair("a", "b"))
        assertNull(ForkSongSpeed.loopRefusal("a", "b"))
    }

    @Test
    fun aBlendPlaysAAtItsSpeedAndBAtAsPlayedTempoWithNoRampOrGlide() {
        val s = ForkV2Planner.Settings()
        s.speedOut = 1.08
        s.speedIn = 0.96
        // a tempo gap the blend would otherwise stretch or glide over
        val p = plan(s, song("a", 120.0, 200.0, 0.9), song("b", 128.0, 180.0, 0.9), 60.0)
        assertEquals(1.08, p.outRate, 0.0)
        assertEquals(0.96, p.speedIn, 0.0)
        assertEquals(1.08, p.speedOut, 0.0)
        // B at A's played tempo, 1.08 x 120 / 128 (spotify-mixes.md rule 10, 333b2fc5)
        assertEquals("blend", p.style)
        assertEquals(1.08 * 120 / 128, p.rate, 1e-9)
        assertEquals(0, p.rampBeats)
        assertNull(p.glide)
    }

    @Test
    fun withoutSpeedsThePlanIsAsBefore() {
        val p = plan(ForkV2Planner.Settings(), song("a", 120.0, 200.0, 0.9), song("b", 120.0, 180.0, 0.9), 60.0)
        assertEquals(1.0, p.speedOut, 0.0)
        assertEquals(1.0, p.speedIn, 0.0)
        assertEquals(1.0, p.rate, 0.0)
        assertEquals(1.0, p.outRate, 0.0)
    }
}
