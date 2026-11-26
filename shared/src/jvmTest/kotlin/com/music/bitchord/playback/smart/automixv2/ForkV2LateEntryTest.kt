package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.EARLIEST_LEAD
import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B's late entry (mix-file.md rule 2 [all], window.in.delaySec; the extension's 0a9559a), as the PC's tests. */
class ForkV2LateEntryTest {

    private fun ov(json: String) = ForkV2MixOverride.fromJson(ForkV2Json.parse(json))!!

    private fun late(delay: Double?) = ov(
        """{"preset":"smooth","window":{"in":{"sec":0${if (delay != null) ""","delaySec":$delay""" else ""}}}}""",
    )

    private val a = song("a", 120.0, 200.0, 0.9)
    private val b = song("b", 120.0, 180.0, 0.9)

    private fun plan(o: ForkV2MixOverride?, from: ForkV2Analysis? = a) =
        ForkV2Planner.plan(ForkV2Planner.Settings(), "a", "b", from, b, 200.0, 60.0, 60.0 + EARLIEST_LEAD, null, o)

    private fun transition(p: ForkV2Planner.Plan) = ForkV2Transition(p, ForkV2Mix.blend(p, "a", "b", 200.0))

    @Test
    fun theDelayRoundTripsOnTheMixInOnly() {
        val o = ForkV2MixOverride.normalize(
            ov("""{"preset":"smooth","window":{"out":{"sec":100,"delaySec":3},"in":{"sec":0,"delaySec":1.9724}}}"""),
        )!!
        assertEquals(1.972, o.`in`!!.delaySec)
        assertEquals(0.0, o.out!!.delaySec) // ignored on window.out
        val back = ForkV2MixOverride.fromJson(ForkV2Json.parse(ForkV2Json.write(o.toJson())))!!
        assertEquals(1.972, back.`in`!!.delaySec)
        @Suppress("UNCHECKED_CAST")
        val w = o.toJson()["window"] as Map<String, Map<String, Any?>>
        assertFalse("delaySec" in w["out"]!!)
        assertEquals(1.972, w["in"]!!["delaySec"])
        // 1 ms to 60 s
        assertEquals(60.0, ForkV2MixOverride.normalize(late(75.0))!!.`in`!!.delaySec)
        assertEquals(0.0, ForkV2MixOverride.normalize(late(0.0004))!!.`in`!!.delaySec)
        @Suppress("UNCHECKED_CAST")
        assertFalse("delaySec" in ((ForkV2MixOverride.normalize(late(0.0004))!!.toJson()["window"] as Map<String, Map<String, Any?>>)["in"]!!))
    }

    @Test
    fun thePlanCarriesItClampedToTheMixLessHalfASecond() {
        val p = plan(late(2.5))
        assertTrue(p.style == "blend" || p.style == "crossfade", p.style)
        assertEquals(2.5, p.inDelaySec)
        assertTrue(p.warnings.orEmpty().none { it.contains("late start") }, p.warnings.toString())
        assertEquals(p.mixInTrackTime - 2.5 * p.rate, p.inStart(), 1e-12)

        val long = plan(late(59.0))
        val most = long.durationSec - 0.5
        assertEquals(Math.round(most * 1e3) / 1e3, long.inDelaySec)
        assertTrue(long.warnings.orEmpty().any { it == "B's late start of 59 s is past the mix's end: ${forkV2Format("%.1f", most)} s" }, long.warnings.toString())

        // a loop mix never plays it
        assertEquals(0.0, plan(ov("""{"preset":"loop","window":{"in":{"sec":0,"delaySec":2}}}""")).inDelaySec)
        // nothing set: none
        assertEquals(0.0, plan(late(null)).inDelaySec)
    }

    @Test
    fun aFallbackStartsBWithTheMixAndSaysSo() {
        val p = plan(late(2.5), from = null)
        assertEquals(0.0, p.inDelaySec)
        assertTrue(p.fallback || p.style !in listOf("blend", "crossfade"), p.toString())
        assertTrue(p.warnings.orEmpty().any { it == "B's late start (2.5 s) isn't played in this kind of mix: B starts with it" }, p.warnings.toString())
        assertFalse(ForkV2Planner.kickLockAllowed(plan(late(2.5))))
    }

    @Test
    fun theCurvesAreTheSameWithoutTheDelay() {
        val with = transition(plan(late(3.0)))
        val without = transition(plan(late(null)))
        assertEquals(without.mix.t0, with.mix.t0)
        assertEquals(without.mix.D, with.mix.D)
        val f = ForkV2Transition.Frame()
        val g = ForkV2Transition.Frame()
        var t = without.mix.t0 - 1
        while (t <= without.mix.t0 + without.mix.D + 1) {
            with.frameAt(t, f)
            without.frameAt(t, g)
            assertEquals(g.gainIn, f.gainIn, "gain in at $t")
            assertEquals(g.gainOut, f.gainOut, "gain out at $t")
            assertEquals(g.inLowDb, f.inLowDb)
            assertEquals(g.inHighPassHz, f.inHighPassHz)
            assertEquals(g.outLowPassHz, f.outLowPassHz)
            assertEquals(without.mix.out.trackAt(t), with.mix.out.trackAt(t), 0.0)
            t += 0.05
        }
    }

    @Test
    fun theDecksFirstPointIsAtT0PlusTheDelayAndNothingBefore() {
        val p = plan(late(3.0))
        val t = transition(p)
        val m = t.mix
        val rate = p.rate
        assertEquals(m.t0 + 3.0, m.`in`.startAt)
        assertEquals(m.t0 + 3.0, m.`in`.knotT[0])
        assertEquals(p.mixInTrackTime, m.`in`.knotP[0])
        assertEquals(p.mixInTrackTime + (m.D - 3.0) * rate, m.`in`.unwrappedAt(m.t0 + m.D), 1e-9)
        // held from the blend's start: B cued at its mix-in, its seconds counted from the mix's start
        assertEquals(3.0, t.incomingHoldSeconds, 1e-12)
        assertEquals(p.mixInTrackTime, t.incomingCue, 1e-12)
        assertEquals(transition(plan(late(null))).incomingSongSeconds, t.incomingSongSeconds, 1e-9)
        val tp = ForkV2Automix.toTransitionPlan(t, 60.0)
        assertEquals(p.mixInTrackTime, tp.incomingCueTime, 1e-12)
        assertEquals(p.mixInTrackTime + (m.D - 3.0) * rate, tp.incomingHandoffTime, 1e-9)
        // a blend opened late (the PC's decoder lead, a seek): still held until B's start, then B from where it is
        val early = t.startingAt(1.0)
        assertEquals(2.0, early.incomingHoldSeconds, 1e-12)
        assertEquals(p.mixInTrackTime, early.incomingCue, 1e-12)
        val after = t.startingAt(5.0)
        assertEquals(0.0, after.incomingHoldSeconds)
        assertEquals(p.mixInTrackTime + 2.0 * rate, after.incomingCue, 1e-9)
        // without a delay: never held, as before
        val none = transition(plan(late(null)))
        assertEquals(Double.NEGATIVE_INFINITY, none.mix.`in`.startAt)
        assertEquals(0.0, none.incomingHoldSeconds)
    }

    @Test
    fun aLateBWithAMixInPastItsStartIsSilentUntilThenAndNeverBeforeItsMixIn() {
        val o = ov("""{"preset":"smooth","window":{"in":{"sec":30,"delaySec":3}}}""")
        val p = plan(o)
        assertEquals(30.0, p.mixInTrackTime, 1e-9)
        assertEquals(3.0, p.inDelaySec)
        val t = transition(p)
        val m = t.mix
        // the deck starts at its mix-in at t0 + 3 s: no pre-roll inside the curve
        assertEquals(m.t0 + 3.0, m.`in`.startAt)
        assertEquals(30.0, m.`in`.knotP[0])
        assertTrue(m.`in`.knotP.all { it >= 30.0 }, m.`in`.knotP.toList().toString())
        assertEquals(30.0 - 3.0 * p.rate, m.`in`.unwrappedAt(m.t0), 1e-9) // (the virtual clock only, never played)
        // the player is cued at the mix-in, and a blend opened, paused or skipped into during the hold finds B there
        val tp = ForkV2Automix.toTransitionPlan(t, 60.0)
        assertEquals(30.0, tp.incomingCueTime, 1e-12)
        assertEquals(30.0, t.incomingCue, 1e-12)
        for (offset in listOf(0.5, 1.0, 2.9)) assertEquals(30.0, t.startingAt(offset).incomingCue, 1e-12)
        assertEquals(30.0 + 1.0 * p.rate, t.startingAt(4.0).incomingCue, 1e-9)
        assertEquals(30.0 + (m.D - 3.0) * p.rate, tp.incomingHandoffTime, 1e-9)
        assertTrue(m.`in`.needFrom <= 30.0)
    }
}
