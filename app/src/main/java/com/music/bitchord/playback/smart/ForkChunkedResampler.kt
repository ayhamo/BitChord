package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Resamples a whole track to the DSP analyzer's rate while it is being decoded, so the
 * full-rate mono track is never held at once (fork; STATUS item 7).
 *
 * Why: [TrackAnalyzer]'s whole-track pass used to decode the track to mono at the container's
 * rate into a list of chunks, copy those into one array (`AudioDecoder.flatten`) and only then
 * resample it to ~11 kHz. A 4.5-minute Dolby/long Navidrome song at 48 kHz is 12.95 M samples,
 * 51.8 MB as floats, held twice during the copy: about 104 MB at the peak. On the owner's Samsung
 * (Android 15, 256 MB heap) that failed now and then with
 * `OutOfMemoryError: Failed to allocate a 51822352 byte allocation with 46416544 free bytes`,
 * depending on what else the heap held at the time. Fed chunk by chunk, this keeps one block of
 * full-rate input (a few seconds, about 1.5 MB) plus the low-rate result, 11.9 MB for that song,
 * briefly twice while [finish] trims it to its exact length.
 *
 * The output is the same as resampling the whole signal in one call, not an approximation of it.
 * The native resampler (`native/analyzer/resampler.cpp`, `ResamplePolyphase`) is a windowed-sinc
 * polyphase filter: output sample `i` sits at input position `i * down / up` and reads only the
 * input within `reach` samples of it, with the filter row picked by `(i * down) mod up`. So a
 * block of input that starts at a multiple of `down` yields, for every output whose taps lie wholly
 * inside the block, exactly the sample the whole-signal call computes: same row, same inputs, same
 * code path. Each block is resampled with [margin] input samples of overlap on both sides, only
 * the outputs whose taps are inside are kept, and the next block starts early enough to recompute
 * the ones that were not. The first block starts at the track's first sample and the last ends at
 * its last, so the clamped edges come out as the whole-signal call clamps them too. Nothing is
 * filtered differently: no seams, no clicks, and the same anti-alias filter.
 *
 * [margin] is the native filter's reach plus a safety allowance. It depends on the native
 * `kResamplerZeroCrossings` (32): if that grows past the allowance, block edges would start
 * differing from the whole-signal call, so change [ZERO_CROSSINGS] with it.
 *
 * Rates the native side would not handle with that polyphase path (not whole numbers, or more
 * than its 1024 phases), and rates already within 1 Hz of the target (which [TrackAnalyzer]
 * never resampled), fall back to collecting the input and handling it as before.
 *
 * [resample] is `TrackFeatures::resample` in the app; a test hands in a Kotlin copy of the
 * polyphase filter, since the native library is not loaded in unit tests.
 */
internal class ForkChunkedResampler(
    private val outputRate: Double,
    expectedSeconds: Double,
    private val resample: (FloatArray, Double, Double) -> FloatArray?,
    private val blockSeconds: Double = BLOCK_SECONDS,
) {

    /**
     * What the decode came to: [samples] at the output rate (null when resampling failed, as the
     * one-call resample's null did) and how much input arrived. [decoded] is false when the decoder
     * reported no usable rate, which the old decode answered with no result at all; reading
     * [inputSeconds] then as a short decode would throw a good cached copy away.
     */
    class Result(val samples: FloatArray?, val inputSeconds: Double, val decoded: Boolean)

    private var inputRate = 0
    private var inputCount = 0L
    private var rateFailed = false
    private var failed = false

    // Streaming state (polyphase rates).
    private var streaming = false
    private var up = 0L
    private var down = 0L
    private var margin = 0L
    private var block = FloatArray(0)
    private var blockLength = 0
    private var blockStart = 0L

    private var output = FloatArray(0)
    private var outputLength = 0
    private val expectedOutput = if (expectedSeconds.isFinite() && expectedSeconds > 0) {
        ceil((expectedSeconds + SLACK_SECONDS) * outputRate).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else {
        0
    }

    // Fallback state: every chunk kept, as the decode did before.
    private var collected: ArrayList<FloatArray>? = null

    /** One decoded mono chunk at [rate]; the signature `AudioDecoder.streamRegion` calls with. */
    fun push(samples: FloatArray, rate: Int) {
        if (rateFailed || samples.isEmpty()) return
        if (inputRate == 0) {
            if (rate <= 0) {
                rateFailed = true
                fail()
                return
            }
            start(rate)
        } else if (rate != inputRate) {
            // The decoder reported a new rate mid-stream. Never seen (MediaCodec reports the
            // output format before the first buffer); the samples already resampled cannot be
            // re-read at another rate, so this decode gives no result rather than a wrong one.
            rateFailed = true
            fail()
            return
        }
        // Counted even after a failed resample, so a short decode is still told from a full one.
        inputCount += samples.size
        if (failed) return
        val chunks = collected
        if (chunks != null) {
            chunks += samples
            return
        }
        var offset = 0
        while (offset < samples.size && !failed) {
            val take = min(samples.size - offset, block.size - blockLength)
            samples.copyInto(block, blockLength, offset, offset + take)
            blockLength += take
            offset += take
            if (blockLength == block.size) process(final = false)
        }
    }

    /** Ends the decode and hands back the resampled track. */
    fun finish(): Result {
        val seconds = if (inputRate > 0) inputCount.toDouble() / inputRate else 0.0
        if (rateFailed) return Result(null, seconds, decoded = false)
        if (failed || inputCount == 0L) return Result(null, seconds, decoded = true)
        val chunks = collected
        if (chunks != null) {
            collected = null
            val all = FloatArray(chunks.sumOf { it.size })
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(all, at)
                at += chunk.size
            }
            chunks.clear()
            if (abs(inputRate - outputRate) <= 1.0) return Result(all, seconds, decoded = true)
            return Result(resample(all, inputRate.toDouble(), outputRate), seconds, decoded = true)
        }
        process(final = true)
        if (failed || outputLength == 0) return Result(null, seconds, decoded = true)
        block = FloatArray(0)
        val samples = if (outputLength == output.size) output else output.copyOf(outputLength)
        output = FloatArray(0)
        return Result(samples, seconds, decoded = true)
    }

    private fun start(rate: Int) {
        inputRate = rate
        val whole = outputRate.roundWhole()
        if (abs(rate - outputRate) <= 1.0 || whole == null) {
            collected = ArrayList()
            return
        }
        val divisor = gcd(rate.toLong(), whole)
        up = whole / divisor
        down = rate / divisor
        if (up > MAX_PHASES) {
            collected = ArrayList()
            return
        }
        // Mirrors ResamplePolyphase: cutoff, half width and reach in input samples.
        val cutoff = 0.5 * min(1.0, up.toDouble() / down.toDouble())
        val halfWidth = ZERO_CROSSINGS / (2.0 * cutoff)
        val reach = ceil(halfWidth).toLong() + 1
        margin = reach + MARGIN_ALLOWANCE
        val wanted = (blockSeconds * rate).toLong()
        // Several times the overlap and the phase period, so each block keeps most of its output.
        val length = max(wanted, 8 * (margin + down))
        block = FloatArray(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        output = FloatArray(max(expectedOutput, 1024))
        streaming = true
    }

    /**
     * Resamples the current block and keeps the outputs it computed exactly as the whole-signal
     * call would. With [final] the block runs to the end of the track, so everything left is kept.
     */
    private fun process(final: Boolean) {
        if (!streaming || blockLength == 0) return
        val input = if (blockLength == block.size) block else block.copyOf(blockLength)
        val resampled = resample(input, inputRate.toDouble(), outputRate)
        val n = blockLength.toLong()
        val expected = countFor(n)
        if (resampled == null || resampled.size.toLong() != expected) {
            // Null when the native library is missing; a different length would mean the native
            // side no longer resamples the way this relies on, so no result beats a wrong one.
            fail()
            return
        }
        val base = blockStart / down * up
        val from = outputLength - base
        val until = if (final) {
            // The whole-signal call's length, which rounds through a double the same way.
            min(expected, countFor(inputCount) - base)
        } else if (n > margin) {
            // Outputs j with floor(j * down / up) + margin < n: their taps end inside the block.
            min(expected, ((n - margin) * up + down - 1) / down)
        } else {
            0L
        }
        if (from < 0) {
            fail()
            return
        }
        if (until > from) append(resampled, from.toInt(), until.toInt())
        if (final) return

        // The next block starts at a multiple of `down` (so its phases line up with the whole
        // signal's) at or before the first input the next output still needs.
        val next = outputLength.toLong() * down / up - margin
        val newStart = max(blockStart, Math.floorDiv(next, down) * down).coerceAtLeast(0L)
        val drop = (newStart - blockStart).toInt()
        if (drop <= 0) {
            // Nothing could be kept (cannot happen at the block sizes [start] picks); grow
            // instead of spinning on the same block.
            block = block.copyOf(block.size * 2)
            return
        }
        block.copyInto(block, 0, drop, blockLength)
        blockLength -= drop
        blockStart = newStart
    }

    /** Output length the native side gives [inputs] samples: `floor(size * (up / down))` in doubles, as it computes it. */
    private fun countFor(inputs: Long): Long = floor(inputs.toDouble() * (up.toDouble() / down.toDouble())).toLong()

    private fun append(samples: FloatArray, from: Int, until: Int) {
        val count = until - from
        if (outputLength + count > output.size) {
            output = output.copyOf(max(outputLength + count, output.size + output.size / 4))
        }
        samples.copyInto(output, outputLength, from, until)
        outputLength += count
    }

    private fun fail() {
        failed = true
        collected = null
        block = FloatArray(0)
        output = FloatArray(0)
    }

    private fun Double.roundWhole(): Long? {
        val rounded = Math.round(this)
        return if (rounded >= 1 && abs(this - rounded) <= 1e-9) rounded else null
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val next = x % y
            x = y
            y = next
        }
        return x
    }

    companion object {
        /** `kResamplerZeroCrossings` in `native/analyzer/resampler.h`. */
        const val ZERO_CROSSINGS = 32.0

        /** `kMaxPhases` in `native/analyzer/resampler.cpp`: past it the native side takes another path. */
        const val MAX_PHASES = 1024L

        /** Extra overlap beyond the filter's reach, input samples. */
        const val MARGIN_ALLOWANCE = 64L

        /** Input per native call: 8 s is 1.5 MB at 48 kHz, and 45 calls for a 6-minute song. */
        const val BLOCK_SECONDS = 8.0

        /** Room in the output for the decoder's pre-roll and a container that understates its length. */
        const val SLACK_SECONDS = 2.0
    }
}
