package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class ForkChunkedResamplerTest {

    /**
     * A Kotlin copy of `ResamplePolyphase` in `native/analyzer/resampler.cpp` (the native library
     * is not loaded in unit tests): same kernel, same normalised rows, float sums inside and
     * clamped, renormalised double sums at the edges. What the chunked path relies on is the
     * shape of it: output `i` reads the input around `i * down / up` with row `(i * down) mod up`.
     */
    private fun polyphase(input: FloatArray, inputRate: Double, outputRate: Double): FloatArray? {
        if (input.isEmpty() || inputRate <= 0 || outputRate <= 0) return null
        val inRate = inputRate.toLong()
        val outRate = outputRate.toLong()
        var a = inRate
        var b = outRate
        while (b != 0L) { val t = a % b; a = b; b = t }
        val up = outRate / a
        val down = inRate / a
        val ratio = up.toDouble() / down
        val cutoff = 0.5 * min(1.0, ratio)
        val halfWidth = 32.0 / (2.0 * cutoff)
        val count = floor(input.size * ratio).toInt()
        if (count == 0) return null
        val reach = ceil(halfWidth).toLong() + 1
        val taps = (2 * reach + 1).toInt()
        val weights = FloatArray((up * taps).toInt())
        for (phase in 0 until up.toInt()) {
            val fraction = phase.toDouble() / up
            val row = DoubleArray(taps) { k -> kernel(fraction - (k - reach), cutoff, halfWidth) }
            val sum = row.sum()
            for (k in 0 until taps) weights[phase * taps + k] = (if (sum != 0.0) row[k] / sum else 0.0).toFloat()
        }
        val size = input.size.toLong()
        return FloatArray(count) { index ->
            val position = index.toLong() * down
            val whole = position / up
            val phase = (position % up).toInt()
            val first = whole - reach
            if (first >= 0 && first + taps <= size) {
                var sum = 0f
                for (k in 0 until taps) sum += weights[phase * taps + k] * input[(first + k).toInt()]
                sum
            } else {
                var sum = 0.0
                var weightSum = 0.0
                for (k in 0 until taps) {
                    val tap = (first + k).coerceIn(0, size - 1).toInt()
                    sum += weights[phase * taps + k].toDouble() * input[tap]
                    weightSum += weights[phase * taps + k]
                }
                (if (weightSum != 0.0) sum / weightSum else 0.0).toFloat()
            }
        }
    }

    private fun kernel(offset: Double, cutoff: Double, halfWidth: Double): Double {
        val x = abs(offset)
        if (x >= halfWidth) return 0.0
        val position = (x + halfWidth) / (2.0 * halfWidth)
        val window = 0.42 - 0.5 * cos(2.0 * PI * position) + 0.08 * cos(4.0 * PI * position)
        val scaled = PI * 2.0 * cutoff * x
        val sinc = if (abs(2.0 * cutoff * x) < 1e-12) 1.0 else sin(scaled) / scaled
        return 2.0 * cutoff * sinc * window
    }

    /** Sines (one above the 5.5 kHz output Nyquist, which the filter must remove), clicks and noise. */
    private fun signal(seconds: Double, rate: Int, seed: Int = 7): FloatArray {
        val random = Random(seed)
        val n = (seconds * rate).toInt()
        val clickEvery = rate / 2 + 37
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.3 * sin(2 * PI * 110.0 * t) + 0.2 * sin(2 * PI * 1234.5 * t) + 0.15 * sin(2 * PI * 9000.0 * t)
            if (i % clickEvery == 0) v += 0.9
            v += 0.02 * (random.nextDouble() - 0.5)
            v.toFloat()
        }
    }

    /** Feeds [input] in random-sized chunks, a few of them much larger than a block. */
    private fun chunked(
        input: FloatArray,
        rate: Int,
        outputRate: Double,
        seed: Int,
        blockSeconds: Double = ForkChunkedResampler.BLOCK_SECONDS,
    ): ForkChunkedResampler.Result {
        val random = Random(seed)
        val resampler = ForkChunkedResampler(outputRate, input.size.toDouble() / rate, ::polyphase, blockSeconds)
        var at = 0
        while (at < input.size) {
            val size = if (random.nextInt(50) == 0) random.nextInt(1, 600_000) else random.nextInt(1, 8193)
            val end = min(input.size, at + size)
            resampler.push(input.copyOfRange(at, end), rate)
            at = end
        }
        return resampler.finish()
    }

    private fun assertSame(expected: FloatArray, actual: FloatArray?) {
        assertNotNull(actual)
        actual!!
        assertEquals("output length", expected.size, actual.size)
        var worst = 0.0
        var worstAt = -1
        for (i in expected.indices) {
            val d = abs(expected[i] - actual[i]).toDouble()
            if (d > worst) { worst = d; worstAt = i }
        }
        assertTrue("max difference $worst at sample $worstAt", worst <= 1e-6)
    }

    @Test
    fun fourMinutesAt48kMatchesOneCall() {
        val input = signal(240.0, 48_000)
        val whole = polyphase(input, 48_000.0, 11_025.0)!!
        val streamed = chunked(input, 48_000, 11_025.0, seed = 1)
        assertTrue(streamed.decoded)
        assertEquals(240.0, streamed.inputSeconds, 1e-9)
        assertSame(whole, streamed.samples)
    }

    @Test
    fun smallBlocksAt44kMatchOneCall() {
        // Blocks barely past the minimum, so there are hundreds of block seams to get wrong.
        val input = signal(75.0, 44_100, seed = 3)
        val whole = polyphase(input, 44_100.0, 11_025.0)!!
        for (seed in 1..3) assertSame(whole, chunked(input, 44_100, 11_025.0, seed, blockSeconds = 0.05).samples)
    }

    @Test
    fun oddRatesAndUpsamplingMatchOneCall() {
        for (rate in intArrayOf(96_000, 32_000, 22_050, 8_000)) {
            val input = signal(20.0, rate, seed = rate)
            val whole = polyphase(input, rate.toDouble(), 11_025.0)!!
            assertSame(whole, chunked(input, rate, 11_025.0, seed = rate, blockSeconds = 0.7).samples)
        }
    }

    @Test
    fun aShortTrackInOneChunkMatchesOneCall() {
        val input = signal(0.3, 48_000)
        assertSame(polyphase(input, 48_000.0, 11_025.0)!!, chunked(input, 48_000, 11_025.0, seed = 9).samples)
    }

    @Test
    fun theFilterStillRemovesWhatWouldAlias() {
        // 9 kHz is above the 5.5 kHz output Nyquist: resampled, only the in-band tones remain,
        // so the output's RMS is that of the 110 Hz and 1234.5 Hz sines (~0.255), not ~0.29.
        val rate = 48_000
        val n = 30 * rate
        val input = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (0.3 * sin(2 * PI * 110.0 * t) + 0.2 * sin(2 * PI * 1234.5 * t) + 0.15 * sin(2 * PI * 9000.0 * t)).toFloat()
        }
        val out = chunked(input, rate, 11_025.0, seed = 5, blockSeconds = 0.5).samples!!
        val middle = out.copyOfRange(11_025, out.size - 11_025)
        val rms = kotlin.math.sqrt(middle.sumOf { it.toDouble() * it } / middle.size)
        assertEquals(kotlin.math.sqrt((0.3 * 0.3 + 0.2 * 0.2) / 2), rms, 0.003)
    }

    @Test
    fun aRateAlreadyAtTheTargetIsPassedThrough() {
        val input = signal(5.0, 11_025)
        val out = chunked(input, 11_025, 11_025.0, seed = 2)
        assertTrue(out.samples!!.contentEquals(input))
    }

    @Test
    fun aRateThePolyphasePathDoesNotTakeIsResampledInOneCall() {
        // 44 056 Hz into 11 025 Hz needs 11 025 phases, past the native side's 1024: collected
        // and handed over whole, as before.
        var calls = 0
        val input = signal(3.0, 44_056)
        val resampler = ForkChunkedResampler(11_025.0, 3.0, { s, _, _ -> calls++; FloatArray(s.size / 4) })
        input.toList().chunked(4096).forEach { resampler.push(it.toFloatArray(), 44_056) }
        val out = resampler.finish()
        assertEquals(1, calls)
        assertEquals(input.size / 4, out.samples!!.size)
    }

    @Test
    fun aFailedResampleStillCountsTheDecode() {
        val resampler = ForkChunkedResampler(11_025.0, 30.0, { _, _, _ -> null }, blockSeconds = 0.5)
        repeat(30 * 48) { resampler.push(FloatArray(1000), 48_000) }
        val out = resampler.finish()
        assertTrue(out.decoded)
        assertNull(out.samples)
        assertEquals(30.0, out.inputSeconds, 1e-9)
    }

    @Test
    fun aRateChangeMidStreamIsNoResultNotAShortDecode() {
        val resampler = ForkChunkedResampler(11_025.0, 10.0, ::polyphase)
        resampler.push(FloatArray(48_000), 48_000)
        resampler.push(FloatArray(44_100), 44_100)
        val out = resampler.finish()
        assertFalse(out.decoded)
        assertNull(out.samples)
    }
}
