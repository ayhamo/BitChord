package com.music.bitchord.playback.smart.automixv2

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's BeatGridTest.java, ported. */
class ForkV2BeatGridTest {

    private fun ticks(first: Double, interval: Double, n: Int) = DoubleArray(n) { first + it * interval }

    @Test
    fun pickPeaksFindsSymmetricPeaksExactly() {
        val l = FloatArray(40) { -1f }
        for (c in intArrayOf(5, 20, 33)) {
            l[c - 1] = 1f
            l[c] = 3f
            l[c + 1] = 1f
        }
        assertContentEquals(doubleArrayOf(5.0, 20.0, 33.0), ForkV2BeatGrid.pickPeaks(l))
    }

    @Test
    fun pickPeaksRefinesTowardsTheHigherNeighbour() {
        val l = FloatArray(20) { -1f }
        l[9] = 1f
        l[10] = 3f
        l[11] = 2f
        val p = ForkV2BeatGrid.pickPeaks(l)
        assertEquals(1, p.size)
        assertTrue(p[0] > 10 && p[0] <= 10.5)
    }

    @Test
    fun pickPeaksIgnoresNonPositiveLogits() {
        val l = FloatArray(30) { -2f }
        l[15] = -0.5f
        assertEquals(0, ForkV2BeatGrid.pickPeaks(l).size)
    }

    @Test
    fun tempoFromRegularBeats() {
        assertEquals(120.0, ForkV2BeatGrid.tempoFromBeats(ticks(0.2, 0.5, 16)), 1e-9)
        assertEquals(0.0, ForkV2BeatGrid.tempoFromBeats(ticks(0.2, 0.5, 5)), 0.0) // too few beats
        assertEquals(0.0, ForkV2BeatGrid.tempoFromBeats(ticks(0.0, 2.0, 16)), 0.0) // 30 BPM: out of range
    }

    @Test
    fun gridConfidenceIsCappedAndZeroForTooFewBeats() {
        val beats = ticks(0.0, 0.5, 32)
        val strong = DoubleArray(32) { 20.0 }
        assertEquals(0.95, ForkV2BeatGrid.gridConfidence(beats, strong), 1e-9)
        assertEquals(0.0, ForkV2BeatGrid.gridConfidence(ticks(0.0, 0.5, 4), strong), 0.0)
    }

    @Test
    fun fitGridRecoversIntervalAndPhase() {
        val fit = ForkV2BeatGrid.fitGrid(ticks(10.13, 0.4687, 64))
        assertNotNull(fit)
        assertEquals(0.4687, fit[0], 1e-4)
        // the offset is the phase within one beat: 10.13 mod 0.4687
        assertEquals(10.13 % 0.4687, fit[1], 2e-3)
        assertNull(ForkV2BeatGrid.fitGrid(ticks(0.0, 0.5, 5)))
    }

    @Test
    fun normalizeIntervalFoldsInto70To180Bpm() {
        assertEquals(0.5, ForkV2BeatGrid.normalizeInterval(0.25), 1e-12) // 240 -> 120
        assertEquals(0.5, ForkV2BeatGrid.normalizeInterval(1.0), 1e-12) // 60 -> 120
        assertEquals(0.6, ForkV2BeatGrid.normalizeInterval(0.6), 1e-12) // 100 stays
        assertTrue(ForkV2BeatGrid.normalizeInterval(Double.NaN).isNaN())
    }

    @Test
    fun trackBeatsSnapsDownbeatsToBeats() {
        val frames = 50 * 20 // 20 s at 50 fps
        val beat = FloatArray(frames) { -5f }
        val down = FloatArray(frames) { -5f }
        var f = 10
        while (f < frames - 1) { // a beat every 0.5 s from 0.2 s
            beat[f] = 5f
            if (((f - 10) / 25) % 4 == 0) down[f + 1] = 5f // a frame late: snapped to the beat
            f += 25
        }
        val s = ForkV2BeatGrid.trackBeats(beat, down, 30.0)
        assertNotNull(s)
        assertEquals(120.0, s.bpm, 1e-6)
        assertEquals(30.2, s.beats[0], 1e-9)
        assertEquals(30.2, s.downbeats[0], 1e-9)
        assertEquals(2.0, s.downbeats[1] - s.downbeats[0], 1e-9)
        val g = ForkV2BeatGrid.localGrid(s)
        assertEquals(120.0, g.bpm, 0.05)
        assertEquals(30.2, g.firstDownbeatSec, 1e-9)
        assertTrue(g.gridConfidence >= 0 && g.gridConfidence <= 1)
    }

    @Test
    fun energyCurveIsNormalisedToItsPeak() {
        val pcm = FloatArray(1000) { if (it < 500) 0.1f else 0.4f }
        val c = ForkV2BeatGrid.energyCurve(ForkV2BeatGrid.of(pcm), 100.0, 1.0)
        assertEquals(10, c.size)
        assertEquals(0.25, c[0], 1e-6)
        assertEquals(1.0, c[9], 1e-6)
    }

    @Test
    fun introOutroFromEnergy() {
        val curve = doubleArrayOf(0.1, 0.2, 0.9, 0.9, 0.9, 0.9, 0.9, 0.3, 0.1, 0.1)
        val io = ForkV2BeatGrid.introOutro(curve, 10.0, 100.0)
        assertEquals(20.0, io[0], 1e-9)
        assertEquals(70.0, io[1], 1e-9)
    }

    @Test
    fun contentEndFindsTrailingSilence() {
        val sr = 1000
        val pcm = ShortArray(sr * 25)
        for (i in 0 until sr * 20) pcm[i] = (sin(i * 0.3) * 10000).toInt().toShort()
        val end = ForkV2BeatGrid.contentEnd(ForkV2BeatGrid.of(pcm, pcm.size), sr.toDouble())
        assertEquals(20.0, end, 0.25)
        assertTrue(ForkV2BeatGrid.contentEnd(ForkV2BeatGrid.of(ShortArray(100), 100), sr.toDouble()).isNaN())
    }

    @Test
    fun camelotWheel() {
        val a = ForkV2Analysis()
        assertTrue(ForkV2BeatGrid.camelot("C major", a))
        assertEquals(8, a.camelotNum)
        assertEquals('B', a.camelotLetter)
        assertTrue(ForkV2BeatGrid.camelot("A minor", a))
        assertEquals(8, a.camelotNum)
        assertEquals('A', a.camelotLetter)
        // KeyStrength spells keys with the real sharp and flat signs
        assertTrue(ForkV2BeatGrid.camelot("E♭ minor", a))
        assertEquals(2, a.camelotNum)
        assertTrue(ForkV2BeatGrid.camelot("F♯ major", a))
        assertEquals(2, a.camelotNum)
        assertEquals('B', a.camelotLetter)
        assertTrue(ForkV2BeatGrid.camelot("A♭ minor", a))
        assertEquals(1, a.camelotNum)
        assertFalse(ForkV2BeatGrid.camelot("H major", a))
        assertFalse(ForkV2BeatGrid.camelot("C", a))
        assertFalse(ForkV2BeatGrid.camelot(null, a))
    }
}
