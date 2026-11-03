package com.music.bitchord.desktop

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkLoopMixRendererTest {
    private val rate = 8_000

    /** Deterministic noise: something a correlation can't mistake for a shifted copy of itself. */
    private fun noise(i: Long): Float {
        var x = i * 6364136223846793005L + 1442695040888963407L
        x = x xor (x ushr 33)
        x *= -0x61c8864680b583ebL
        x = x xor (x ushr 29)
        return ((x and 0xFFFF).toFloat() / 0xFFFF - 0.5f)
    }

    private fun deck(
        frames: Int,
        knotRate: Double = 1.0,
        loopStart: Long = 0,
        loopEnd: Long = frames.toLong(),
        releaseAt: Double = 1e18,
        runOn: Boolean = true,
        startPosition: Double = 4_000.0,
    ): ForkLoopDeck {
        val knots = 40
        val step = 1_000.0
        return ForkLoopDeck(
            left = FloatArray(frames) { noise(it.toLong()) },
            right = FloatArray(frames) { noise(it.toLong() + 1_000_000) },
            regionStart = 10.0,
            loopStart = loopStart,
            loopEnd = loopEnd,
            releaseAt = releaseAt,
            knotFrames = DoubleArray(knots + 1) { it * step },
            knotPositions = DoubleArray(knots + 1) { startPosition + it * step * knotRate },
            runOn = runOn,
            stopAt = 1e9,
            preroll = 0.4,
            level = 1f,
            sampleRate = rate,
        )
    }

    @Test
    fun `an envelope interpolates and holds its ends, linearly or in the log domain`() {
        val linear = ForkLoopEnvelope(doubleArrayOf(0.0, 1.0), floatArrayOf(0f, 1f))
        assertEquals(0f, linear.at(-5.0))
        assertEquals(0.25f, linear.at(0.25), 1e-6f)
        assertEquals(1f, linear.at(9.0))
        val sweep = ForkLoopEnvelope(doubleArrayOf(0.0, 1.0), floatArrayOf(20f, 2000f), exponential = true)
        assertEquals(200f, sweep.at(0.5), 0.01f)
    }

    @Test
    fun `at rate 1 a deck is its source sample for sample`() {
        val deck = deck(60_000)
        val left = FloatArray(4_000)
        val right = FloatArray(4_000)
        deck.renderInto(0, 4_000, left, right)
        for (f in 0 until 4_000) {
            val index = 4_000 + f
            assertEquals(deck.left[index], left[f], 1e-5f, "frame $f")
            assertEquals(deck.right[index], right[f], 1e-5f, "frame $f")
        }
    }

    @Test
    fun `rendering in blocks gives the same audio as in one go`() {
        val whole = deck(60_000, knotRate = 0.93)
        val parts = deck(60_000, knotRate = 0.93)
        val a = FloatArray(6_000)
        val b = FloatArray(6_000)
        whole.renderInto(0, 6_000, a, FloatArray(6_000))
        var at = 0
        while (at < 6_000) {
            val n = minOf(777, 6_000 - at)
            val l = FloatArray(n)
            parts.renderInto(at.toLong(), n, l, FloatArray(n))
            System.arraycopy(l, 0, b, at, n)
            at += n
        }
        for (i in 0 until 6_000) assertEquals(a[i], b[i], 1e-6f, "frame $i")
    }

    @Test
    fun `off rate 1 a deck never strays from its knots by more than its tolerance`() {
        val deck = deck(80_000, knotRate = 0.9)
        for (f in 2_000L until 30_000L step 97) {
            val error = abs(deck.sourceAt(f) - deck.heard(f.toDouble()))
            assertTrue(error <= deck.toleranceFrames + 0.1 * deck.hop + 2, "frame $f is $error off")
        }
    }

    @Test
    fun `positions wrap on the loop and carry on from its end after the release`() {
        val deck = deck(60_000, loopStart = 1_000, loopEnd = 3_000, releaseAt = 9_000.0)
        assertEquals(1_500, deck.sampleAt(1_500))
        assertEquals(1_500, deck.sampleAt(3_500))
        assertEquals(2_999, deck.sampleAt(8_999))
        assertEquals(3_000, deck.sampleAt(9_000))
        assertEquals(3_100, deck.sampleAt(9_100))
        assertEquals(10.0 + 1_500.0 / rate, deck.trackSecondsAt(3_500.0), 1e-9)
        assertEquals(10.0 + 3_100.0 / rate, deck.trackSecondsAt(9_100.0), 1e-9)
        deck.shift = 7
        assertEquals(1_507, deck.sampleAt(3_500))
    }

    @Test
    fun `a deck fades out over its fade from its fade frame, then is silent`() {
        val deck = deck(60_000).apply {
            fadeOutFrame = 2_000
            fadeFrames = 200
        }
        val left = FloatArray(3_000)
        deck.renderInto(0, 3_000, left, FloatArray(3_000))
        assertEquals(deck.left[4_000 + 1_999], left[1_999], 1e-5f)
        assertEquals(deck.left[4_000 + 2_100] * 0.5f, left[2_100], 1e-4f)
        for (f in 2_200 until 3_000) assertEquals(0f, left[f])
    }
}
