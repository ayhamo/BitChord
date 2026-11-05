package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Karaoke's centre canceller (ForkVocalCanceller): a mono source, or a stereo file whose two
 * channels carry the same signal, has nothing but centre, so cancelling it played as silence.
 */
class ForkKaraokeTest {

    private val frames = 44_100

    /** One second of interleaved stereo: [left] and [right] give each channel's sample at frame i. */
    private fun block(left: (Int) -> Float, right: (Int) -> Float): FloatArray =
        FloatArray(frames * 2).also { out ->
            for (i in 0 until frames) {
                out[i * 2] = left(i)
                out[i * 2 + 1] = right(i)
            }
        }

    private fun tone(hz: Double, i: Int) = (0.5 * sin(2 * PI * hz * i / 44_100.0)).toFloat()

    private fun rms(block: FloatArray, from: Int): Double {
        var sum = 0.0
        for (i in from until block.size) sum += block[i].toDouble() * block[i]
        return kotlin.math.sqrt(sum / (block.size - from))
    }

    private fun runInChunks(canceller: ForkVocalCanceller, samples: FloatArray, sourceChannels: Int?) {
        var at = 0
        while (at < samples.size) {
            val count = minOf(1_024, samples.size - at)
            val chunk = samples.copyOfRange(at, at + count)
            canceller.process(chunk, count, 2, sourceChannels, engaged = true)
            chunk.copyInto(samples, at)
            at += count
        }
    }

    @Test
    fun monoUpmixedToStereoPlaysUntouched() {
        val input = block({ tone(440.0, it) }, { tone(440.0, it) })
        val output = input.copyOf()
        runInChunks(ForkVocalCanceller(), output, sourceChannels = 2)
        val worst = input.indices.maxOf { abs(input[it] - output[it]) }
        assertTrue(worst < 1e-6f, "dual-mono block changed by $worst")
    }

    @Test
    fun aMonoSourceIsBypassedEvenWhenItsChannelsDiffer() {
        val input = block({ tone(440.0, it) }, { tone(660.0, it) })
        val output = input.copyOf()
        runInChunks(ForkVocalCanceller(), output, sourceChannels = 1)
        val worst = input.indices.maxOf { abs(input[it] - output[it]) }
        assertTrue(worst < 1e-6f, "mono-source block changed by $worst")
    }

    @Test
    fun aRealStereoMixLosesItsCentre() {
        // A centred "vocal" at 440 Hz plus a hard-left 660 Hz part.
        val input = block({ tone(440.0, it) + tone(660.0, it) }, { tone(440.0, it) })
        runInChunks(ForkVocalCanceller(), input, sourceChannels = 2)
        // After the first window and the glide, the right channel (centre only + half the side)
        // holds the side's -0.5 * 660 Hz part, and nothing near the full 440 Hz tone.
        val right = FloatArray(frames / 2) { input[(frames / 2 + it) * 2 + 1] }
        val centreLeft = rms(right, 0)
        assertTrue(centreLeft < 0.2, "centre not cancelled: rms $centreLeft")
        assertTrue(centreLeft > 0.1, "the side part should remain: rms $centreLeft")
    }

    @Test
    fun turningDualMonoAfterStereoGlidesBackToUntouched() {
        val canceller = ForkVocalCanceller()
        runInChunks(canceller, block({ tone(440.0, it) + tone(660.0, it) }, { tone(440.0, it) }), 2)
        val mono = block({ tone(440.0, it) }, { tone(440.0, it) })
        val output = mono.copyOf()
        runInChunks(canceller, output, 2)
        // The second half, well past one window and the glide, is the source again.
        val worst = (frames until frames * 2).maxOf { abs(mono[it] - output[it]) }
        assertTrue(worst < 1e-3f, "dual mono still cancelled after stereo: $worst")
    }
}
