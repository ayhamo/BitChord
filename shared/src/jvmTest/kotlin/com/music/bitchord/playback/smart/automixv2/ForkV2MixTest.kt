package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.EARLIEST_LEAD
import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.loopPlan
import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.song
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone's MixTest.java, ported. */
class ForkV2MixTest {

    @Test
    fun laneSetLinearAndExponential() {
        val l = ForkV2Mix.Lane(20.0)
        assertEquals(20.0, l.at(5.0), 0.0) // no events: its resting value
        l.set(20.0, 0.0).exp(2000.0, 10.0).lin(0.0, 20.0).set(7.0, 30.0)
        assertEquals(20.0, l.at(-1.0), 0.0)
        assertEquals(200.0, l.at(5.0), 1e-9) // halfway up an exponential ramp: the geometric mean
        assertEquals(2000.0, l.at(10.0), 1e-9)
        assertEquals(1000.0, l.at(15.0), 1e-9) // halfway down a linear ramp
        assertEquals(0.0, l.at(25.0), 0.0) // a set holds the previous value until its time
        assertEquals(7.0, l.at(30.0), 0.0)
        assertEquals(7.0, l.at(1e6), 0.0)
    }

    @Test
    fun laneCurveIsSpreadEvenly() {
        val l = ForkV2Mix.Lane(0.0).curve(floatArrayOf(0f, 1f, 0f), 10.0, 2.0)
        assertEquals(0.0, l.at(10.0), 0.0)
        assertEquals(0.5, l.at(10.5), 1e-9)
        assertEquals(1.0, l.at(11.0), 1e-9)
        assertEquals(0.0, l.at(12.0), 1e-9)
    }

    @Test
    fun equalPowerShapes() {
        val inn = ForkV2Mix.ep(true)
        val out = ForkV2Mix.ep(false)
        assertEquals(ForkV2Mix.EP, inn.size)
        assertEquals(0.0, inn[0].toDouble(), 1e-7)
        assertEquals(1.0, inn[ForkV2Mix.EP - 1].toDouble(), 1e-7)
        for (i in 0 until ForkV2Mix.EP) assertEquals(1.0, (inn[i] * inn[i] + out[i] * out[i]).toDouble(), 1e-6)
    }

    @Test
    fun djShapesStayInRange() {
        for (style in arrayOf("blend", "filter")) {
            for (clash in doubleArrayOf(0.0, 0.5, 1.0)) {
                val s = ForkV2Mix.djShape(style, clash, 16.0, 124.0)
                val n = s[0].size
                for (lane in s) assertEquals(n, lane.size)
                for (i in 0 until n) {
                    assertTrue(s[0][i] >= 0 && s[0][i] <= 1.0001)
                    assertTrue(s[1][i] >= 0 && s[1][i] <= 1.0001)
                    for (k in 2 until 5) assertTrue(s[k][i] >= 19.9 && s[k][i] <= 20000.1)
                }
                assertEquals(0.0, s[0][0].toDouble(), 1e-6) // incoming silent at the start
                assertEquals(1.0, s[1][0].toDouble(), 1e-6) // outgoing full at the start
                assertEquals(0.0, s[1][n - 1].toDouble(), 1e-6) // and gone at the end
            }
        }
    }

    private fun basePlan(nextBpm: Double): ForkV2Planner.Plan =
        ForkV2Planner.plan(ForkV2Planner.Settings(), "a", "b", song("a", 120.0, 200.0, 0.9), song("b", nextBpm, 180.0, 0.9), 200.0, 60.0, 60 + EARLIEST_LEAD, null)

    @Test
    fun blendKnotsAndGainsFollowThePlan() {
        val p = basePlan(120.0)
        val m = ForkV2Mix.blend(p, "a", "b", 200.0)
        assertEquals(p.mixOutTrackTime - ForkV2Mix.HAND_LEAD, m.takeoverSong, 1e-9)
        assertEquals(ForkV2Mix.HAND_LEAD, m.t0, 1e-9)
        assertEquals(p.durationSec, m.D, 1e-9)
        // the outgoing deck plays on from the takeover at rate 1, reaching the blend on time
        assertEquals(m.takeoverSong, m.out.trackAt(0.0), 1e-9)
        assertEquals(p.mixOutTrackTime, m.out.trackAt(m.t0), 1e-9)
        assertEquals(1.0, m.out.rateAt(m.t0 + 1), 1e-9)
        // the incoming deck starts at its cue when the blend starts
        assertEquals(p.mixInTrackTime, m.`in`.trackAt(m.t0), 1e-9)
        assertEquals(p.mixInTrackTime + 2, m.`in`.trackAt(m.t0 + 2), 1e-9)
        // equal power gains (version 1)
        assertEquals(1.0, m.out.gain.at(m.t0), 1e-6)
        assertEquals(sqrt(0.5), m.out.gain.at(m.t0 + m.D / 2), 1e-6)
        assertEquals(sqrt(0.5), m.`in`.gain.at(m.t0 + m.D / 2), 1e-6)
        assertEquals(0.0, m.out.gain.at(m.t0 + m.D), 0.0)
        assertEquals(1.0, m.`in`.gain.at(m.t0 + m.D), 0.0)
        // the player is asked for the next song before the deck needs it, and before the outgoing song ends
        assertTrue(m.askAt <= m.elementAt - ForkV2Mix.NEXT_LEAD)
        assertTrue(m.askAt < m.outEnd)
        assertTrue(m.`in`.needFrom <= p.mixInTrackTime && m.`in`.needTo > m.`in`.trackAt(m.elementAt))
    }

    @Test
    fun speedUpRampReachesTheBlendOnTheBeat() {
        val p = basePlan(126.0)
        val m = ForkV2Mix.blend(p, "a", "b", 200.0)
        assertEquals(p.mixOutTrackTime, m.out.trackAt(m.t0), 1e-9)
        assertEquals(p.outRate, m.out.rateAt(m.t0 + 0.5), 1e-9)
        // during the ramp it never runs slower than 1 or faster than the target
        var t = ForkV2Mix.HAND_LEAD
        while (t < m.t0) {
            val r = m.out.rateAt(t)
            assertTrue(r >= 1 - 1e-9 && r <= p.outRate + 1e-9)
            t += 0.05
        }
    }

    @Test
    fun theIncomingDeckGlidesBackAsThePlansCurvesSay() {
        val p = basePlan(126.0)
        p.rate = 1.05 // the next song stretched 5% through the blend
        val m = ForkV2Mix.blend(p, "a", "b", 200.0)
        val end = m.t0 + m.D
        assertEquals(1.05, m.`in`.rateAt(end - 0.1), 1e-9)
        // 0.25% every 0.25 s (about 20 steps over 5 s), never a jump bigger than one step
        val glideEnd = end + ceil(abs(p.rate - 1) / ForkV2Mix.GLIDE_STEP) * ForkV2Mix.GLIDE_STEP_SEC
        var prev = 1.05
        var t = end + 0.01
        while (t < glideEnd) {
            val r = m.`in`.rateAt(t)
            assertTrue(r <= prev + 1e-9 && prev - r <= ForkV2Mix.GLIDE_STEP + 1e-9)
            prev = r
            t += 0.25
        }
        assertEquals(1.0, m.`in`.rateAt(glideEnd + 0.1), 1e-9)
        // the curves the overview draws end the glide at the same time
        val glide = ForkV2Curves.blend(p).`in`["rate"]!!
        assertEquals(glideEnd - m.t0, glide[glide.size - 1][0], 1e-9)
        assertTrue(m.elementAt >= glideEnd)
    }

    @Test
    fun loopMixDecksWrapAndRelease() {
        val lp = loopPlan()!!
        val m = ForkV2Mix.loop(lp, "a", "b", 200.0)
        assertEquals("loop", m.style)
        assertEquals(190.5, m.takeoverSong, 1e-9)
        assertEquals(20.0, m.D, 1e-9)
        // the outgoing 4-bar loop (8 s) repeats
        assertEquals(190.5, m.out.trackAt(0.0), 1e-9)
        assertEquals(190.5, m.out.trackAt(8.0), 1e-9)
        assertEquals(191.5, m.out.trackAt(9.0), 1e-9)
        // the incoming loop repeats until its release, then plays on past it
        assertEquals(4.5, m.`in`.trackAt(12.0), 1e-9)
        assertEquals(8.5, m.`in`.trackAt(16.0), 1e-9)
        assertEquals(10.5, m.`in`.trackAt(18.0), 1e-9)
        assertEquals(1.0, m.out.gain.at(0.0), 1e-9)
        assertEquals(0.0, m.out.gain.at(16.0), 1e-9)
        assertEquals(1.0, m.`in`.gain.at(13.0), 1e-9)
        assertTrue(m.askAt <= m.elementAt - ForkV2Mix.NEXT_LEAD)
    }

    // the seek bar's pulse during a mix (crossfade.js stepPulse)

    @Test
    fun pulseIsFullOnTheBeatAndRestsBetween() {
        val beats = doubleArrayOf(0.0, 0.5, 1.0, 1.5)
        assertEquals(1.0, ForkV2Mix.pulse(beats, 0.5), 1e-9)
        assertEquals(1.0, ForkV2Mix.pulse(beats, 1.0), 1e-9)
        // halfway between two beats: cos = -1, so only the rest level
        assertEquals(ForkV2Mix.PULSE_REST, ForkV2Mix.pulse(beats, 0.75), 1e-9)
        var t = 0.0
        while (t < 1.5) {
            val v = ForkV2Mix.pulse(beats, t)
            assertTrue(v >= ForkV2Mix.PULSE_REST - 1e-9 && v <= 1 + 1e-9)
            t += 0.01
        }
    }

    @Test
    fun pulseFollowsATempoRamp() {
        // beats closing in (a tempo ramp): each one still lands at full brightness
        val beats = doubleArrayOf(0.0, 0.64, 1.26, 1.86, 2.44)
        for (b in doubleArrayOf(0.64, 1.26, 1.86)) assertEquals(1.0, ForkV2Mix.pulse(beats, b), 1e-9)
        assertEquals(ForkV2Mix.PULSE_REST, ForkV2Mix.pulse(beats, (1.26 + 1.86) / 2), 1e-9)
    }

    @Test
    fun pulseBreathesWithoutBeats() {
        assertEquals(1.0, ForkV2Mix.pulse(null, 0.0), 1e-9)
        assertEquals(ForkV2Mix.PULSE_REST, ForkV2Mix.pulse(null, ForkV2Mix.BREATH_SEC / 2), 1e-9)
        assertEquals(1.0, ForkV2Mix.pulse(null, ForkV2Mix.BREATH_SEC), 1e-9)
    }

    @Test
    fun blendBeatsCoverTheBlendOnTheOutgoingSpeed() {
        val m = ForkV2Mix.blend(basePlan(120.0), "a", "b", 200.0)
        val beats = m.beats!!
        assertTrue(beats.size > 4)
        for (k in 1 until beats.size) assertTrue(beats[k] > beats[k - 1], "rising at $k")
        // 120 BPM at rate 1: half a second apart through the blend, ending at its end
        assertEquals(0.5, beats[beats.size - 1] - beats[beats.size - 2], 1e-6)
        assertEquals(m.t0 + m.D, beats[beats.size - 1], 0.5)
        assertEquals(m.t0 + m.D, m.pulseEnd(), 1e-9)
    }

    @Test
    fun loopBeatsRunToTheHandover() {
        val m = ForkV2Mix.loop(loopPlan()!!, "a", "b", 200.0)
        val beats = m.beats!!
        assertTrue(beats.size > 4)
        assertEquals(0.0, beats[0], 1e-9)
        assertEquals(m.elementAt, m.pulseEnd(), 1e-9)
        assertEquals(m.elementAt, beats[beats.size - 1], 1e-6)
    }
}
