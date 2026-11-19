package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.LoopMix
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The extension's two hard rules on what the players receive, not just on the port's own answer
 * ([ForkV2SpecFixturesTest] checks that): the two fixture pairs planned through the fork's glue as
 * the apps call it ([ForkV2Automix.planBlend] and [ForkV2Automix.toTransitionPlan] for the blend,
 * [ForkV2Loop.decide] and [ForkV2Loop.toLoopMixPlan] for the loop mix their renderers play).
 * - Pair 1 (Vaina Loca into SI NO ES CONTIGO): a [LoopMix.Plan] comes back, the port's loops
 *   field for field.
 * - Pair 2 (Stereo Love into Midnight Sun, tempos about 12% apart): no loop plan; the players get
 *   the Automix v2 blend ([com.music.bitchord.playback.smart.TransitionPlan.forkV2]).
 *
 * Reads the spec where the Docker build mounts it (/spec, or RULEBOOK_SPEC), as the fixtures test.
 */
@Suppress("UNCHECKED_CAST")
class ForkV2AutomixPairTest {

    @AfterTest
    fun reset() = ForkV2Automix.forget()

    private class Planned(val decision: ForkV2Loop.Decision, val blend: ForkV2Transition, val pos: Double)

    private fun plan(pair: Map<String, Any?>, phraseBars: Int = 0): Planned {
        val songs = pair["songs"] as List<Any?>
        val a = songs[0] as Map<String, Any?>
        val b = songs[1] as Map<String, Any?>
        val from = ForkV2Analysis.fromJson(a["analysis"] as Map<String, Any?>)
        val to = ForkV2Analysis.fromJson(b["analysis"] as Map<String, Any?>)
        val pos = max(30.0, from.durationSec - 95)
        val blend = ForkV2Automix.planBlend(phraseBars, a["id"] as String, b["id"] as String, from, to, from.durationSec, pos, null)
        val sung = ForkV2Planner.Sung()
        sung.out = spans(a["sung"])
        sung.`in` = spans(b["sung"])
        val d = ForkV2Loop.decide(from, audio(a), to, audio(b), pos + ForkV2Planner.LOOP_SETUP_SEC, sung, blend.plan, false)
        return Planned(d, blend, pos)
    }

    @Test
    fun pairOneLoopsAndThePlayersGetThePortsLoops() {
        val p = plan(pairFile("1-"))
        val loop = assertNotNull(p.decision.plan, "pair 1 must play a loop mix: ${p.decision.why}")
        val lp = assertNotNull(p.decision.loop)
        assertTrue(p.decision.choice!!.loop)
        assertEquals(lp.out!!.loopStart, loop.out.loopStart, 1e-12)
        assertEquals(lp.out!!.loopEnd, loop.out.loopEnd, 1e-12)
        assertEquals(lp.`in`!!.loopStart, loop.inn.loopStart, 1e-12)
        assertEquals(lp.`in`!!.loopEnd, loop.inn.loopEnd, 1e-12)
        assertEquals(lp.out!!.beatSec, loop.out.beatSec, 1e-12)
        assertEquals(lp.`in`!!.beatSec, loop.inn.beatSec, 1e-12)
        assertEquals(lp.fold, loop.fold)
        assertEquals(lp.enterBars, loop.enterBars)
        assertEquals(lp.rampBars, loop.rampBars)
        assertEquals(lp.totalBars, loop.totalBars)
        assertEquals(loop.totalBars, loop.enterBars + loop.rampBars + loop.exitBars)
        assertTrue(loop.out.loopStart >= p.pos + ForkV2Planner.LOOP_SETUP_SEC - 1e-9, "the loop is not in what has played")
    }

    @Test
    fun pairTwoNeverLoopsAndThePlayersGetTheBlend() {
        val p = plan(pairFile("2-"))
        assertNull(p.decision.plan, "pair 2 must never play a loop mix")
        val tp = ForkV2Automix.toTransitionPlan(p.blend, p.pos)
        assertSame(p.blend, tp.forkV2, "the players read the Automix v2 blend")
        assertTrue(tp.reason.contains("automix-v2"), tp.reason)
        assertEquals(p.blend.plan.mixOutTrackTime, tp.transitionStart, 1e-9)
        assertTrue(tp.fadeSeconds > 0)
        // 12% apart is past the glide's 8%: no per-beat rates for the players to follow.
        assertTrue(!p.blend.glides, "no glide at 12% apart")
        // The phone's wall span (fade x incoming / outgoing rate) is the incoming song's span.
        assertTrue(abs(tp.fadeSeconds * tp.incomingPlaybackRate / tp.outgoingPlaybackRate - p.blend.incomingSongSeconds) < 0.01)
    }

    /**
     * A fixed 8 or 16-bar length chosen in Settings sizes Automix v2's blend only, as the extension's
     * "Blend length" does (ForkV2LoopGate): the gate lets the pair through and the two hard rules still
     * hold with the blend planned at that length.
     */
    @Test
    fun aFixedLengthKeepsBothHardRulesUnderAutomixV2() {
        for (bars in listOf(8, 16)) {
            assertTrue(!ForkV2LoopGate.fixedLengthRefuses(bars, com.music.bitchord.playback.smart.AutomixStyle.V2))
            val one = plan(pairFile("1-"), bars)
            assertNotNull(one.decision.plan, "pair 1 must still loop with a fixed $bars-bar length: ${one.decision.why}")
            ForkV2Automix.forget()
            val two = plan(pairFile("2-"), bars)
            assertNull(two.decision.plan, "pair 2 must never loop, fixed $bars-bar length or not")
            ForkV2Automix.forget()
        }
    }

    /** As the fixtures test: the decoded song as the extension's loop search reads it. */
    private fun audio(song: Map<String, Any?>): ForkV2Planner.Audio {
        val loud = ForkV2Json.nums(song["loudness20Hz"])!!
        val decoded = ForkV2Json.num(song, "decodedSec")
        val rate = 320
        val data = FloatArray(loud.size * 16)
        for (w in loud.indices) data.fill(loud[w].toFloat(), w * 16, w * 16 + 16)
        return object : ForkV2Planner.Audio {
            override fun duration(): Double = decoded

            override fun rms(from: Double, to: Double): Double {
                val s = max(0.0, floor(from * rate)).toInt()
                val e = min(data.size.toDouble(), floor(to * rate)).toInt()
                var ss = 0.0
                var n = 0
                var i = s
                while (i < e) {
                    ss += data[i] * data[i]
                    n++
                    i += 16
                }
                return if (n > 0) sqrt(ss / n) else 0.0
            }
        }
    }

    private fun spans(o: Any?): Array<DoubleArray>? {
        if (o !is List<*>) return null
        return Array(o.size) { ForkV2Json.nums(o[it])!! }
    }

    private fun pairFile(prefix: String): Map<String, Any?> {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec", "automix/pairs")
        val f = dir.listFiles { _, n -> n.startsWith(prefix) && n.endsWith(".json") }?.firstOrNull()
            ?: run { org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true"); fail("no $prefix pair in $dir (mount the extension's spec at /spec)") }
        return ForkV2Json.parse(f.readText()) as Map<String, Any?>
    }
}
