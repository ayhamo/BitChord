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
        // the curves carry the speeds (extension L2): A steady at x1.08, B's glide back ends at its x0.96, not x1
        val c = p.curves!!
        assertEquals(listOf(listOf(0.0, 1.08), listOf(p.durationSec, 1.08)), c.out["rate"]!!.map { it.toList() })
        val inn = c.`in`["rate"]!!
        assertEquals(p.rate, inn[0][1], 1e-9)
        assertEquals(0.96, inn.last()[1], 0.0)
    }

    /** A blend at x1 with B at a speed of its own: B glides back to its speed after the blend (extension 0ab9b80 L2). */
    @Test
    fun aBlendAtX1GlidesBackToBsOwnSpeed() {
        val p = ForkV2Planner.Plan()
        p.style = "blend"
        p.durationSec = 8.0
        p.speedIn = 0.97
        p.rate = 1.0
        val inn = ForkV2Curves.blend(p).`in`["rate"]!!.map { it.toList() }
        val sec = kotlin.math.ceil(kotlin.math.abs(1.0 - 0.97) / ForkV2Mix.GLIDE_STEP) * ForkV2Mix.GLIDE_STEP_SEC
        assertEquals(listOf(listOf(0.0, 1.0), listOf(8.0, 1.0), listOf(8.0 + sec, 0.97)), inn)
    }

    /** A crossfade at playlist speeds: its curves show A at speedOut and B at speedIn throughout (automix.js blendCurves). */
    @Test
    fun aCrossfadesCurvesCarryBothSpeeds() {
        val p = ForkV2Planner.Plan()
        p.style = "crossfade"
        p.durationSec = 6.0
        p.speedOut = 0.97
        p.speedIn = 1.25
        p.rate = 1.25
        p.outRate = 0.97
        val c = ForkV2Curves.blend(p)
        assertEquals(listOf(listOf(0.0, 0.97), listOf(6.0, 0.97)), c.out["rate"]!!.map { it.toList() })
        assertEquals(listOf(listOf(0.0, 1.25), listOf(6.0, 1.25)), c.`in`["rate"]!!.map { it.toList() })
        // with no speeds: no tempo curve at all, as before
        val q = ForkV2Planner.Plan()
        q.style = "crossfade"
        q.durationSec = 6.0
        val d = ForkV2Curves.blend(q)
        assertNull(d.out["rate"])
        assertNull(d.`in`["rate"])
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
