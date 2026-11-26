package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.EARLIEST_LEAD
import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * editor.md §6, ruled 2026-11-26 [all]: a placed mix-out window may run up to A's length − 0.3, through its fading
 * outro, not only to its music end (`contentEndSec`). Spotify's Lush Life → Lean On mix-out at 176.4 s was pulled
 * back to 163.4 s by the music-end limit. Only a length asked for without a window still has to fit before the music end.
 */
class ForkV2WindowToEndTest {

    private fun ov(json: String) = ForkV2MixOverride.fromJson(ForkV2Json.parse(json))!!

    /** A song of 201 s whose music ends at 182 s (a fading outro after it). */
    private val a = song("a", 120.0, 201.0, 0.9).apply { contentEndSec = 182.0 }
    private val b = song("b", 120.0, 180.0, 0.9)

    private fun plan(o: ForkV2MixOverride) =
        ForkV2Planner.plan(ForkV2Planner.Settings(), "a", "b", a, b, 201.0, 60.0, 60.0 + EARLIEST_LEAD, null, o)

    @Test
    fun aPlacedWindowRunsIntoTheFadingOutro() {
        val p = plan(ov("""{"preset":"smooth","bars":8,"window":{"out":{"sec":176.4}}}"""))
        // played at the nearest beat (a seconds-only window, editor.md §6), not pulled back to the music end's 165.7
        assertEquals(176.4, p.mixOutTrackTime, 0.26)
        assertTrue(p.warnings.orEmpty().none { it.contains("the mix-out") }, p.warnings.toString())
    }

    @Test
    fun aPlacedWindowStillEndsBeforeA() {
        val p = plan(ov("""{"preset":"smooth","bars":8,"window":{"out":{"sec":196.0}}}"""))
        assertTrue(p.mixOutTrackTime + p.durationSec <= 201.0 - 0.3 + 1e-6, "${p.mixOutTrackTime} + ${p.durationSec}")
        assertTrue(p.warnings.orEmpty().any { it.contains("the mix-out") }, p.warnings.toString())
    }

    /** editor.md §6, ruled 2026-11-26 [all]: B's placed mix-in may run until the window ends 0.3 s before B does (was 10 s). */
    @Test
    fun aPlacedMixInRunsUpToBsEnd() {
        val o = ov("""{"preset":"smooth","bars":8,"window":{"in":{"sec":160.0}}}""")
        val p = plan(o)
        // 180 s B, a 16 s window: 160 + 16 = 176 ends before 179.7, so it is kept (old limit: 180 - 10 - 16 = 154)
        assertEquals(160.0, p.mixInTrackTime, 0.26)
        assertTrue(p.warnings.orEmpty().none { it.contains("the mix-in") }, p.warnings.toString())
        val late = plan(ov("""{"preset":"smooth","bars":8,"window":{"in":{"sec":170.0}}}"""))
        assertTrue(late.mixInTrackTime + late.durationSec * (late.rate.takeIf { it > 0 } ?: 1.0) <= 180.0 - 0.3 + 0.26, "${late.mixInTrackTime}")
        assertEquals("the window has to end before B does", ForkV2EditorGeometry.LIMIT_WHY_IN[1])
    }

    @Test
    fun theEditorsLimitIsAsLengthLessTheSpan() {
        val m = ForkV2Planner.Plan().apply { style = "crossfade"; durationSec = 16.0 }
        val l = ForkV2EditorGeometry.windowLimits(m, a, b, 60.0)
        assertEquals(201.0 - 0.3 - 16.0, l.outHi, 1e-9)
        assertEquals("the window has to end before A does", ForkV2EditorGeometry.LIMIT_WHY_OUT[1])
    }
}
