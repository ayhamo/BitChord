package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * analysis-cases.md "The key" [all] (107d0f51): [ForkKeyExtractor] against the extension's reference port,
 * `scripts/key_port.mjs` (1474937), run on the same synthetic chords. The expected numbers are that script's output
 * (its frame scaled to the rate as here: 1024 at 11 025 Hz, 4096 at 44 100 Hz).
 */
class ForkKeyExtractorTest {

    /** The reference's test chords: sines with a 0.5 Hz tremolo so frames differ, rounded to Float as the apps' buffer. */
    private fun chord(rate: Int, freqs: DoubleArray, amps: DoubleArray, sec: Double): FloatArray {
        val n = Math.round(rate * sec).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for (k in freqs.indices) v += amps[k] * sin(2 * PI * freqs[k] * t)
            (v * (0.75 + 0.25 * sin(2 * PI * 0.5 * t))).toFloat()
        }
    }

    private fun cMajor(rate: Int) = chord(rate, doubleArrayOf(261.63, 329.63, 392.0, 523.25, 130.81), doubleArrayOf(0.3, 0.2, 0.2, 0.08, 0.15), 6.0)
    private fun aMinor(rate: Int) =
        chord(rate, doubleArrayOf(220.0, 261.63, 329.63, 440.0, 110.0, 293.66), doubleArrayOf(0.3, 0.2, 0.2, 0.08, 0.15, 0.05), 6.0)

    private fun assertProfile(expected: DoubleArray, actual: DoubleArray) {
        for (i in 0 until 12) assertTrue(abs(expected[i] - actual[i]) < 1e-6, "bin $i: ${expected[i]} vs ${actual[i]}")
    }

    @Test
    fun theKeyAndItsStrengthAreTheReferencePorts() {
        val cases = listOf(
            Triple(11_025, cMajor(11_025), Pair("C major", 0.81490152402152016)),
            Triple(11_025, aMinor(11_025), Pair("A minor", 0.81279616037151259)),
            Triple(44_100, cMajor(44_100), Pair("C major", 0.81498127639839768)),
            Triple(44_100, aMinor(44_100), Pair("A minor", 0.81292508319053658)),
        )
        for ((rate, x, want) in cases) {
            val k = ForkKeyExtractor.detect(x, rate)!!
            assertEquals(want.first, k.name, "$rate Hz")
            assertTrue(abs(want.second - k.strength) < 1e-6, "$rate Hz ${want.first}: ${k.strength}")
        }
    }

    @Test
    fun theSongProfileIsTheReferencePorts() {
        // bin 0 is A; the mean profile over the largest bin, bins under 0.2 set to 0
        assertProfile(
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.3141062155978615, 0.0, 0.0, 0.29177101096536734, 0.0),
            ForkKeyExtractor.profile(cMajor(11_025), 11_025, 1024)!!,
        )
        assertProfile(
            doubleArrayOf(1.0, 0.0, 0.0, 0.33524624722925483, 0.0, 0.0, 0.0, 0.29798607418060524, 0.0, 0.0, 0.0, 0.0),
            ForkKeyExtractor.profile(aMinor(44_100), 44_100, 4096)!!,
        )
    }

    @Test
    fun aFrameIs93MsAtAnyRateThatGivesAPowerOfTwo() {
        assertEquals(4096, ForkKeyExtractor.frameSize(44_100))
        assertEquals(1024, ForkKeyExtractor.frameSize(11_025))
        assertEquals(2048, ForkKeyExtractor.frameSize(22_050))
        assertEquals(0, ForkKeyExtractor.frameSize(48_000))
        assertNull(ForkKeyExtractor.detect(FloatArray(48_000), 48_000))
        assertNull(ForkKeyExtractor.detect(FloatArray(0), 11_025))
    }

    @Test
    fun theKeyTablesNameTheirOwnKeyAtFullStrengthAndATieIsMinor() {
        // "bgate" major moved to C (index 3), "bgate" minor to F# (index 9): each correlates 1 with itself
        val major = doubleArrayOf(1.0, 0.0, 0.42, 0.0, 0.53, 0.37, 0.0, 0.77, 0.0, 0.38, 0.21, 0.30)
        val minor = doubleArrayOf(1.0, 0.0, 0.36, 0.39, 0.0, 0.38, 0.0, 0.74, 0.27, 0.0, 0.42, 0.23)
        val c = ForkKeyExtractor.key(DoubleArray(12) { major[((it - 3) % 12 + 12) % 12] })
        assertEquals("C major", c.name)
        assertTrue(abs(c.strength - 1.0) < 1e-12)
        val fs = ForkKeyExtractor.key(DoubleArray(12) { minor[((it - 9) % 12 + 12) % 12] })
        assertEquals("F♯ minor", fs.name)
        // a flat profile correlates with nothing: both 0, a tie, so minor at A
        val flat = ForkKeyExtractor.key(DoubleArray(12) { 1.0 })
        assertEquals("A minor", flat.name)
        assertEquals(0.0, flat.strength)
    }

    @Test
    fun itsNamesGiveTheCamelotKey() {
        val a = ForkV2Analysis()
        assertTrue(ForkV2BeatGrid.camelot("F♯ major", a))
        assertEquals(2, a.camelotNum)
        assertEquals('B', a.camelotLetter)
        assertTrue(ForkV2BeatGrid.camelot("B♭ minor", a))
        assertEquals(3, a.camelotNum)
        assertEquals('A', a.camelotLetter)
    }
}
