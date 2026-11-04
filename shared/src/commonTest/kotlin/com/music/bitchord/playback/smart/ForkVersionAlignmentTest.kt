package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkVersionAlignmentTest {

    private val rate = 4_000.0

    /** Noise under a loudness that changes every 50 ms, with the odd gap: something an envelope can follow. */
    private fun music(seconds: Double, seed: Int): FloatArray {
        val random = Random(seed)
        val samples = FloatArray((seconds * rate).toInt())
        val block = (rate * 0.05).toInt()
        var level = 0f
        for (i in samples.indices) {
            if (i % block == 0) level = if (random.nextInt(6) == 0) 0.02f else random.nextFloat()
            samples[i] = (random.nextFloat() * 2f - 1f) * level
        }
        return samples
    }

    private fun near(expected: Long, actual: Long?) {
        assertNotNull(actual)
        assertTrue(abs(actual - expected) <= 20, "expected about $expected ms, measured $actual ms")
    }

    @Test
    fun aVideoWithAnIntroLandsLaterInTheVideo() {
        val song = music(40.0, seed = 1)
        val intro = music(3.25, seed = 2).map { it * 0.1f }.toFloatArray()
        val video = intro + song
        near(3_250, ForkVersionAlignment.offsetMs(song, rate, video, rate))
    }

    @Test
    fun theWayBackIsTheSameShiftNegated() {
        val song = music(40.0, seed = 3)
        val video = music(5.0, seed = 4).map { it * 0.1f }.toFloatArray() + song
        near(-5_000, ForkVersionAlignment.offsetMs(video, rate, song, rate))
    }

    @Test
    fun differentRatesStillLineUp() {
        val song = music(30.0, seed = 5)
        // The same audio at half the rate: every other sample.
        val half = FloatArray(song.size / 2) { song[it * 2] }
        near(0, ForkVersionAlignment.offsetMs(song, rate, half, rate / 2))
    }

    @Test
    fun unrelatedAudioIsNotMeasured() {
        assertNull(ForkVersionAlignment.offsetMs(music(30.0, seed = 6), rate, music(30.0, seed = 7), rate))
    }

    @Test
    fun aPeakOnTheSearchEdgeIsRefused() {
        // The target starts 7 s into the source, but the source is only 10 s long: the best lag sits on
        // the edge the 3 s overlap allows, which is a window too short, not a measurement.
        val song = music(30.0, seed = 8)
        val source = song.copyOfRange(0, (10 * rate).toInt())
        val target = song.copyOfRange((7 * rate).toInt(), (20 * rate).toInt())
        assertNull(ForkVersionAlignment.offsetMs(source, rate, target, rate))
    }

    @Test
    fun nothingDecodedIsNull() {
        assertNull(ForkVersionAlignment.offsetMs(FloatArray(0), rate, music(10.0, seed = 9), rate))
    }

    @Test
    fun theVerdictsFollowThePhone() {
        val ok = ForkVersionAlignment.Correlation(lagFrames = 120, confidence = 0.8, floor = -500, ceiling = 500)
        assertEquals(1_200L, ForkVersionAlignment.offsetMs(ok))
        assertNull(ForkVersionAlignment.offsetMs(ok.copy(confidence = 0.39)))
        assertNull(ForkVersionAlignment.offsetMs(ok.copy(lagFrames = 500)))
        assertNull(ForkVersionAlignment.offsetMs(ok.copy(lagFrames = -500)))
    }
}
