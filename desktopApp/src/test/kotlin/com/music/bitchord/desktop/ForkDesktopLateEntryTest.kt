package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.automixv2.ForkV2Analysis
import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import com.music.bitchord.playback.smart.automixv2.ForkV2Json
import com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride
import com.music.bitchord.playback.smart.automixv2.ForkV2Transition
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B's late entry on the PC (mix-file.md rule 2): no incoming deck audio before t0 + delay. */
class ForkDesktopLateEntryTest {

    private fun song(id: String, dur: Double) = ForkV2Analysis().apply {
        fileKey = id
        durationSec = dur
        bpm = 120.0
        beatIntervalSec = 0.5
        firstDownbeatSec = 0.5
        gridConfidence = 0.9
        tailGrid = ForkV2Analysis.LocalGrid().also {
            it.bpm = 120.0
            it.beatIntervalSec = 0.5
            it.firstDownbeatSec = 0.5
            it.gridConfidence = 0.9
        }
        energy = 0.5
        introEndSec = 8.0
    }

    private fun transition(delay: Double?, mixIn: Int = 0): ForkV2Transition {
        val late = if (delay != null) ""","delaySec":$delay""" else ""
        val ov = ForkV2MixOverride.fromJson(ForkV2Json.parse("""{"preset":"smooth","window":{"in":{"sec":$mixIn$late}}}"""))
        return ForkV2Automix.planBlend(8, "a", "b", song("a", 200.0), song("b", 180.0), 200.0, 60.0, ov)
    }

    private val channels = 2

    private fun blocks(t: ForkV2Transition): List<Int> {
        val tp = ForkV2Automix.toTransitionPlan(t, t.plan.mixOutTrackTime)
        val total = (t.outputSeconds * 48_000).toInt() * channels
        val held = ArrayList<Int>()
        var elapsed = 0
        while (elapsed < total) {
            val count = minOf(1024, total - elapsed)
            held += ForkDesktopV2.heldSamples(tp, elapsed, total, count, channels)
            elapsed += count
        }
        return held
    }

    private fun expected(t: ForkV2Transition, hold: Double): Int =
        ceil(hold / t.outputSeconds * ((t.outputSeconds * 48_000).toInt())).toInt() * channels

    @Test
    fun theIncomingDeckIsSilentAndUnreadUntilItsStart() {
        val t = transition(3.0)
        assertEquals(3.0, t.plan.inDelaySec)
        val held = blocks(t)
        val all = expected(t, 3.0)
        assertTrue(all in 288_000..288_002, "$all")
        assertEquals(all, held.sum())
        val boundary = all / 1024
        for (i in 0 until boundary) assertEquals(1024, held[i], "block $i")
        assertEquals(all - boundary * 1024, held[boundary])
        for (i in boundary + 1 until held.size) assertEquals(0, held[i], "block $i")
        assertTrue(held.all { it % channels == 0 }, "whole frames")
    }

    @Test
    fun withoutADelayNothingIsHeldAndOpenedLateOnlyTheRest() {
        assertEquals(0, blocks(transition(null)).sum())
        val t = transition(3.0)
        // opened 1 s into the mix (the decoder's lead): 2 s still held
        val early = t.startingAt(1.0)
        assertEquals(expected(early, 2.0), blocks(early).sum())
        assertEquals(0, blocks(t.startingAt(4.0)).sum())
    }

    @Test
    fun withAMixInPastItsStartBIsCuedThereAndTheDisplayWaitsForIt() {
        val t = transition(3.0, mixIn = 30)
        assertEquals(30.0, t.plan.mixInTrackTime, 1e-9)
        val tp = ForkV2Automix.toTransitionPlan(t, t.plan.mixOutTrackTime)
        // the incoming deck is opened at its mix-in (armIncomingBlend seeks there), nothing before it is read
        assertEquals(30.0, tp.incomingCueTime, 1e-12)
        assertEquals(30.0, t.startingAt(1.0).incomingCue, 1e-12)
        assertEquals(expected(t, 3.0), blocks(t).sum())
        // the player shows B, and a pause keeps it, only once B has started
        val hold = (3.0 / t.outputSeconds).toFloat()
        assertEquals(hold, ForkDesktopV2.afterLateEntry(tp, 0.1f))
        assertEquals(0.9f, ForkDesktopV2.afterLateEntry(tp, 0.9f))
        val none = ForkV2Automix.toTransitionPlan(transition(null, mixIn = 30), 150.0)
        assertEquals(0.1f, ForkDesktopV2.afterLateEntry(none, 0.1f))
    }

    /** Audit 2026-11-26 3a #3: B's curve up but B held silent must not trim A (the trim is 1 / sqrt(out + into)). */
    @Test
    fun aHeldBMakesNoRoomInTheHeadroomTrim() {
        val t = transition(3.0)
        val tp = ForkV2Automix.toTransitionPlan(t, t.plan.mixOutTrackTime)
        val gains = FloatArray(2)
        // 2 s into the mix: inside the 3 s hold, and smooth's incoming curve is already up
        val progress = (2.0 / t.outputSeconds).toFloat()
        assertTrue(ForkDesktopV2.gains(tp, progress, gains))
        assertTrue(gains[1] > 0.05f, "into ${gains[1]}")
        val total = (t.outputSeconds * 48_000).toInt() * channels
        val elapsed = (2.0 * 48_000).toInt() * channels
        val held = ForkDesktopV2.heldSamples(tp, elapsed, total, 1024, channels)
        assertEquals(1024, held)
        ForkDesktopV2.lateRise(null, held, 1024, channels, 48_000)
        val into = ForkDesktopV2.heardInto(gains[1], 0, held, channels, 48_000)
        assertEquals(0f, into)
        // A alone at full gain: no trim
        assertEquals(1f, 1f / kotlin.math.sqrt((1f + into).coerceAtLeast(1f)))
    }

    /**
     * mix-file.md rule 2 "[all] A while B is held" (extension 6dcaf08): the trim reads B's heard gain (0 while held, its
     * 10 ms rise, then its curve). A is never under its own curve during the hold, the output's power steps at most
     * 1 dB per 10 ms around B's entry, and both are on their usual (trimmed) curves 20 ms after it.
     */
    @Test
    fun theTrimFollowsBInWithNoStep() {
        val ch = 2
        val rate = 48_000
        val block = 1024
        val sub = 64 * ch // the engine's sub-block (TransitionFilter.GLIDE_FRAMES frames)
        val out = 1f
        val curve = 1f // the deepest case: out + into = 2, a 3 dB trim
        val entryBlock = 4
        val entryAt = 512 // B's entry, in samples into that block
        val trims = ArrayList<Float>()
        val powers = ArrayList<Double>()
        val heldTrims = ArrayList<Float>()
        for (b in 0 until 16) {
            val held = when {
                b < entryBlock -> block
                b == entryBlock -> entryAt
                else -> 0
            }
            val samples = FloatArray(block) { curve } // B at its curve, before the rise is applied
            ForkDesktopV2.lateRise(samples, held, block, ch, rate)
            var index = 0
            while (index < block) {
                val heard = ForkDesktopV2.heardInto(curve, index, held, ch, rate)
                val trim = 1f / kotlin.math.sqrt((out + heard).coerceAtLeast(1f))
                val bOut = if (index < held) 0f else samples[index]
                if (index < held) heldTrims += trim
                trims += trim
                powers += (trim * trim * (out * out + bOut * bOut)).toDouble()
                index += sub
            }
        }
        assertTrue(heldTrims.isNotEmpty() && heldTrims.all { it == 1f }, "A untrimmed while B is held")
        val perTenMs = Math.ceil(0.010 * rate / (sub / ch)).toInt()
        val db = powers.map { 10 * kotlin.math.log10(it) }
        for (i in perTenMs until db.size) {
            val step = kotlin.math.abs(db[i] - db[i - perTenMs])
            assertTrue(step <= 1.0, "the output moved $step dB in 10 ms at sub-block $i")
        }
        // 20 ms after the entry: both on their usual curves, A at the full 3 dB trim
        val entryIndex = (entryBlock * block + entryAt) / sub
        val after = entryIndex + Math.ceil(0.020 * rate / (sub / ch)).toInt()
        assertEquals((1 / Math.sqrt(2.0)).toFloat(), trims[after], 1e-6f)
    }

    @Test
    fun bRisesToTheCurveWithinTenMillisecondsOfItsStart() {
        val ch = 2
        // a block wholly in the wait: not read (null), the rise armed
        ForkDesktopV2.lateRise(null, 1024, 0, ch, 48_000)
        // the block B starts in: 100 frames still held (silent), then the rise
        val b = FloatArray(1024) { 1f }
        for (i in 0 until 200) b[i] = 0f
        ForkDesktopV2.lateRise(b, 200, 1024, ch, 48_000)
        assertEquals(0f, b[200])
        assertEquals(1f / 480, b[202], 1e-6f)
        assertEquals(b[202], b[203])
        // carried into the next block: 412 frames done here, 68 to go
        val next = FloatArray(1024) { 1f }
        ForkDesktopV2.lateRise(next, 0, 1024, ch, 48_000)
        assertEquals(412f / 480, next[0], 1e-6f)
        assertEquals(1f, next[68 * 2])
        assertTrue((68 until 512).all { next[it * 2] == 1f })
        // then nothing more
        val after = FloatArray(64) { 1f }
        ForkDesktopV2.lateRise(after, 0, 64, ch, 48_000)
        assertTrue(after.all { it == 1f })
    }
}
