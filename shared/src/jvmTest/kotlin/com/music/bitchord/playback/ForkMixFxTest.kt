package com.music.bitchord.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Effects lane's returns and the shelf's mid band and loop (editor.md §12, §13). */
class ForkMixFxTest {

    private val rate = 8_000

    /** The phone's path, mono: one silent block with the sends aimed (so the next starts at them), then [x]. */
    private fun phone(fx: ForkMixFx, x: FloatArray) {
        val pre = FloatArray(256)
        fx.process(pre, pre.size, 1, rate)
        fx.process(x, x.size, 1, rate)
    }

    @Test
    fun theDelayRepeatsEachTimeAtItsFeedback() {
        val fx = ForkMixFx()
        fx.times(0.375, 0.1) // 800 frames
        fx.aim(0f, 0f, 1f, 0f)
        val x = FloatArray(4_000).also { it[0] = 1f }
        phone(fx, x)
        assertEquals(1f, x[0], 1e-6f) // the dry sound passes
        assertEquals(1f, x[800], 1e-5f)
        assertEquals(0.3f, x[1_600], 1e-5f)
        assertEquals(0.09f, x[2_400], 1e-5f)
        assertEquals(0f, x[1_200], 1e-6f)
    }

    @Test
    fun theEchoRepeatsAtFeedbackThroughItsLowPass() {
        val fx = ForkMixFx()
        fx.times(0.1, 0.25)
        fx.aim(1f, 0f, 0f, 0f)
        val x = FloatArray(4_000)
        for (i in 0 until 400) x[i] = 0.5f // a 50 ms block of DC: the 3 kHz low-pass passes it whole
        phone(fx, x)
        assertEquals(0.5f, x[1_000], 0.01f)
        assertEquals(0.5f * 0.45f, x[1_800], 0.01f)
        assertEquals(0.5f * 0.45f * 0.45f, x[2_600], 0.01f)
    }

    @Test
    fun theReturnsRingOnAfterTheSendStopsThenLeaveTheBlockAlone() {
        val fx = ForkMixFx()
        fx.times(0.1, 0.1)
        fx.aim(0.8f, 0.8f, 0.8f, 0f)
        val x = FloatArray(1_024) { (0.5 * sin(2 * PI * 220 * it / rate)).toFloat() }
        phone(fx, x)
        fx.rest()
        var rang = false
        var blocks = 0
        while (blocks < 200) {
            val b = FloatArray(1_024)
            fx.process(b, b.size, 1, rate)
            if (b.any { it != 0f }) rang = true
            blocks++
            if (!fx.ringing) break
        }
        assertTrue(rang)
        assertFalse(fx.ringing)
        assertTrue(blocks * 1_024 < 4 * rate, "rang $blocks blocks")
        val quiet = FloatArray(512) { 0.25f }
        val copy = quiet.copyOf()
        fx.process(quiet, quiet.size, 1, rate)
        assertContentEquals(copy, quiet)
        assertFalse(fx.busy)
    }

    @Test
    fun theReverbSpreadsAnImpulseOverAboutItsLengthAndDecays() {
        val fx = ForkMixFx()
        fx.aim(0f, 1f, 0f, 0f)
        val total = (rate * 3.0).toInt()
        val out = FloatArray(total)
        out[0] = 1f
        val pre = FloatArray(256)
        fx.process(pre, pre.size, 1, rate)
        var at = 0
        while (at < total) {
            val n = minOf(1_024, total - at)
            val b = out.copyOfRange(at, at + n)
            fx.process(b, n, 1, rate)
            b.copyInto(out, at)
            if (at == 0) fx.rest()
            at += n
        }
        fun energy(from: Double, to: Double): Double {
            var s = 0.0
            for (i in (from * rate).toInt() until (to * rate).toInt()) s += out[i].toDouble() * out[i]
            return s
        }
        val early = energy(0.01, 0.5)
        val late = energy(1.9, 2.3)
        assertTrue(early > 0 && late > 0, "early $early late $late")
        assertTrue(early > late * 100, "early $early late $late") // 60 dB over 2.4 s
        assertTrue(energy(2.6, 3.0) < late / 10)
    }

    @Test
    fun thePcPathMatchesThePhonePath() {
        val a = ForkMixFx()
        val b = ForkMixFx()
        for (f in listOf(a, b)) {
            f.times(0.1, 0.1)
        }
        a.aim(0.5f, 0f, 0.5f, 0f)
        val x = FloatArray(2_000).also { it[3] = 1f }
        val pre = FloatArray(256)
        a.process(pre, pre.size, 1, rate)
        val phone = x.copyOf()
        a.process(phone, phone.size, 1, rate)
        // the PC: the dry block mixed by the engine, the sends fed per sample, the returns added
        b.begin(pre.size, 1, rate)
        b.render(FloatArray(pre.size), pre.size, 0f, 0f, 0f, 0f)
        val pc = x.copyOf()
        b.begin(pc.size, 1, rate)
        for (i in pc.indices) b.send(i, x[i], 0.5f, 0f, 0.5f)
        b.render(pc, pc.size, 0f, 0f, 0f, 0f)
        for (i in pc.indices) assertEquals(phone[i], pc[i], 1e-5f, "at $i")
    }

    /** The hall's impulse and spectra are made once per rate, not at every set-up (audit 2026-11-26 part 1b #1). */
    @Test
    fun theHallIsMadeOncePerRate() {
        val a = ForkMixFx.reverbFor(rate)!!
        val b = ForkMixFx.reverbFor(rate)!!
        assertTrue(a !== b, "each user gets its own running state")
        assertTrue(a.hall === b.hall, "the impulse's spectra are shared")
    }

    /**
     * A clear on the same format (a seek, a flush) silences everything without a new set-up: what rings is gone, and the
     * returns then play a new send exactly as a fresh one does.
     */
    @Test
    fun aClearSilencesTheReturnsAsAFreshOneStarts() {
        fun run(fx: ForkMixFx): FloatArray {
            fx.times(0.1, 0.1)
            fx.aim(0.7f, 0.7f, 0.7f, 0f)
            val x = FloatArray(4_096) { (0.5 * sin(2 * PI * 330 * it / rate)).toFloat() }
            phone(fx, x)
            return x
        }
        val used = ForkMixFx()
        run(used)
        used.clear()
        val after = FloatArray(1_024)
        used.process(after, after.size, 1, rate)
        assertTrue(after.all { it == 0f }, "nothing rings after a clear")
        assertFalse(used.ringing)
        val again = run(used)
        val fresh = run(ForkMixFx())
        for (i in again.indices) assertEquals(fresh[i], again[i], 1e-6f, "at $i")
    }

    @Test
    fun noisePlaysWhileAimedAndStopsAfter() {
        val fx = ForkMixFx()
        fx.aim(0f, 0f, 0f, 1f)
        val pre = FloatArray(256)
        fx.process(pre, pre.size, 1, rate)
        val x = FloatArray(2_000)
        fx.process(x, x.size, 1, rate)
        val peak = x.maxOf { abs(it) }
        assertTrue(peak > 0.05f && peak < 0.5f, "peak $peak")
        fx.rest()
        val ramp = FloatArray(1_000)
        fx.process(ramp, ramp.size, 1, rate)
        val after = FloatArray(1_000)
        fx.process(after, after.size, 1, rate)
        assertTrue(after.all { it == 0f })
    }

    // ───────── the shelf's mid band and loop ─────────

    @Test
    fun aFlatMidBandLeavesTheBlockUntouched() {
        val shelf = ForkMixShelf()
        shelf.aimMid(0f)
        val x = FloatArray(4_000) { (0.5 * sin(2 * PI * 1_000 * (it / 2) / 48_000)).toFloat() }
        val copy = x.copyOf()
        shelf.process(x, x.size, 2, 48_000)
        assertContentEquals(copy, x)
    }

    @Test
    fun theMidBandCutsAThousandHertzAndLeavesTheBass() {
        fun level(hz: Double): Float {
            val shelf = ForkMixShelf()
            shelf.aimMid(-12f)
            var peak = 0f
            repeat(20) { blk ->
                val x = FloatArray(4_800) { i -> (0.5 * sin(2 * PI * hz * (blk * 2_400 + i / 2) / 48_000)).toFloat() }
                shelf.process(x, x.size, 2, 48_000)
                if (blk >= 15) peak = maxOf(peak, x.maxOf { abs(it) })
            }
            return peak
        }
        assertEquals(0.5f * 0.251f, level(1_000.0), 0.02f) // −12 dB
        assertEquals(0.5f, level(60.0), 0.03f)
    }

    @Test
    fun theLoopRepeatsTheFirstSecondsItHeard() {
        val shelf = ForkMixShelf()
        shelf.loopStart(0.01) // 80 frames at 8 kHz
        val x = FloatArray(400) { it.toFloat() / 1_000f }
        shelf.process(x, x.size, 1, rate)
        for (i in 0 until 400) assertEquals((i % 80) / 1_000f, x[i], 1e-6f, "at $i")
        shelf.loopStop()
        val y = FloatArray(100) { 0.3f }
        shelf.process(y, y.size, 1, rate)
        assertTrue(y.all { it == 0.3f })
    }

    /**
     * A stop the audio thread hasn't reached yet (the player retired before another block) doesn't hold back the next
     * mix's start on that sink: the stop is taken, then the new loop (audit 2026-11-26 part 1b #2).
     */
    @Test
    fun aLoopStartsAgainAfterAStopNoBlockHasSeen() {
        val shelf = ForkMixShelf()
        shelf.loopStart(0.01)
        shelf.process(FloatArray(400), 400, 1, rate)
        shelf.loopStop()
        shelf.loopStart(0.005) // 40 frames, with no block between
        val x = FloatArray(200) { it.toFloat() / 1_000f }
        shelf.process(x, x.size, 1, rate)
        for (i in 0 until 200) assertEquals((i % 40) / 1_000f, x[i], 1e-6f, "at $i")
    }

    /** A loop kept in one format isn't played into another (stereo now): it ends, and the deck plays as it is. */
    @Test
    fun aLoopEndsWhenTheFormatChanges() {
        val shelf = ForkMixShelf()
        shelf.loopStart(0.01)
        shelf.process(FloatArray(400) { 0.1f }, 400, 1, rate)
        val y = FloatArray(400) { 0.3f }
        shelf.process(y, y.size, 2, rate)
        assertTrue(y.all { it == 0.3f })
    }

    /**
     * The in-sink fader: its first aim starts there, a release glides back to 1 and lets go, and an aim after a release
     * or a reset starts there again (only the audio thread turns it on and off: audit 2026-11-26 part 1b #3).
     */
    @Test
    fun theFaderSnapsToAFirstAimAndLetsGoAfterARelease() {
        val shelf = ForkMixShelf()
        shelf.aimFader(0f)
        val a = FloatArray(400) { 0.5f }
        shelf.process(a, a.size, 1, rate)
        assertTrue(a.all { it == 0f }, "the first aim starts there")
        shelf.fxRest()
        repeat(4) { shelf.process(FloatArray(400) { 0.5f }, 400, 1, rate) } // 5 ms back up, at 8 kHz 40 frames
        val b = FloatArray(400) { 0.5f }
        shelf.process(b, b.size, 1, rate)
        assertTrue(b.all { it == 0.5f }, "released: untouched")
        shelf.aimFader(0f)
        val c = FloatArray(400) { 0.5f }
        shelf.process(c, c.size, 1, rate)
        assertTrue(c.all { it == 0f }, "a new mix's aim after a release starts there")
        shelf.reset()
        shelf.aimFader(0.5f)
        val d = FloatArray(400) { 0.5f }
        shelf.process(d, d.size, 1, rate)
        assertTrue(d.all { abs(it - 0.25f) < 1e-6f }, "after a reset, the aim starts there")
        shelf.reset()
        val e = FloatArray(400) { 0.5f }
        shelf.process(e, e.size, 1, rate)
        assertTrue(e.all { it == 0.5f }, "a reset lets go at once")
    }
}
