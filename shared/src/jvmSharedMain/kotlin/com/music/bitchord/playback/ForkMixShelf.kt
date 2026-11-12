package com.music.bitchord.playback

import com.music.bitchord.playback.smart.ForkAutoDjCurves
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fork: the two shelves a Web AutoDJ mix rides on each song ([ForkAutoDjCurves]): a 250 Hz
 * low shelf (the bass swap) and a 4 kHz high shelf (the highs crossing), the extension's
 * `BiquadFilterNode`s (Web Audio's shelf: RBJ cookbook, slope 1). The transition filters cut; these
 * only turn a band down, which is what the extension's version-1 blend does.
 *
 * Gains are aimed with [set] from any thread and glide there per 64-frame block (at most 0.5 dB a
 * block, ~25 ms for the full 12 dB swap) so a jump is never a click. At 0 dB and settled the block
 * is passed through untouched, not a sample read, so every other mix stays byte-identical.
 */
class ForkMixShelf {

    @Volatile private var targetLowDb = 0f
    @Volatile private var targetHighDb = 0f
    @Volatile private var snapPending = false

    private var lowDb = 0f
    private var highDb = 0f
    private var channels = 0
    private var rate = 0
    private var dirty = false

    // Per channel, two biquads in transposed direct form II: z1, z2 each.
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)
    private val lowCoef = FloatArray(5)
    private val highCoef = FloatArray(5)

    /** Aims the shelves, in dB (0 = flat). */
    fun set(lowDb: Float, highDb: Float) {
        targetLowDb = lowDb.coerceIn(-24f, 0f)
        targetHighDb = highDb.coerceIn(-24f, 0f)
    }

    /** Both flat. */
    fun open() = set(0f, 0f)

    /** Jumps to [lowDb] / [highDb] with no glide and no history (a new mix on a deck that held another's). */
    fun snap(lowDb: Float, highDb: Float) {
        set(lowDb, highDb)
        snapPending = true
    }

    /** A seek: the filters' history is from before it, so the next block starts clean at the aimed gains. */
    fun flush() {
        snapPending = true
    }

    /** The sink is torn down: flat, with no history. */
    fun reset() = snap(0f, 0f)

    val isShaping: Boolean get() = !flat(targetLowDb) || !flat(targetHighDb) || !flat(lowDb) || !flat(highDb)

    /** Interleaved [samples], [sampleCount] of them, in place. */
    fun process(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        if (channelCount < 1 || sampleRate <= 0 || sampleCount <= 0) return
        if (snapPending) {
            snapPending = false
            lowDb = targetLowDb
            highDb = targetHighDb
            lowState.fill(0f)
            highState.fill(0f)
        }
        if (!isShaping) {
            if (dirty) {
                lowState.fill(0f)
                highState.fill(0f)
                dirty = false
            }
            return
        }
        if (channelCount != channels || sampleRate != rate || lowState.size != channelCount * 2) {
            channels = channelCount
            rate = sampleRate
            lowState = FloatArray(channelCount * 2)
            highState = FloatArray(channelCount * 2)
        }
        dirty = true
        val frames = sampleCount / channelCount
        var frame = 0
        while (frame < frames) {
            lowDb = step(lowDb, targetLowDb)
            highDb = step(highDb, targetHighDb)
            shelf(lowCoef, ForkAutoDjCurves.LOW_SHELF_HZ, lowDb.toDouble(), low = true)
            shelf(highCoef, ForkAutoDjCurves.HIGH_SHELF_HZ, highDb.toDouble(), low = false)
            val lowOn = !flat(lowDb)
            val highOn = !flat(highDb)
            val end = minOf(frames, frame + BLOCK)
            for (f in frame until end) {
                val base = f * channelCount
                for (c in 0 until channelCount) {
                    var x = samples[base + c]
                    if (lowOn) x = biquad(lowCoef, lowState, c, x)
                    if (highOn) x = biquad(highCoef, highState, c, x)
                    samples[base + c] = x
                }
            }
            frame = end
        }
    }

    /**
     * The 16-bit sink's path: native-order PCM16 between [buffer]'s position and limit, in place
     * (position untouched). With float output off — most Bluetooth routes — Media3 runs the
     * transition filter on 16-bit samples, so without this the bass swap and highs crossing were
     * never heard there. Flat and settled, nothing is read.
     */
    fun process16(buffer: ByteBuffer, channelCount: Int, sampleRate: Int) {
        if (!isShaping && !snapPending) {
            if (dirty) {
                lowState.fill(0f)
                highState.fill(0f)
                dirty = false
            }
            return
        }
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val start = view.position()
        val count = view.remaining() / 2
        if (count == 0) return
        if (scratch.size < count) scratch = FloatArray(count)
        for (i in 0 until count) scratch[i] = view.getShort(start + i * 2) / 32768f
        process(scratch, count, channelCount, sampleRate)
        for (i in 0 until count) {
            val s = (scratch[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
            view.putShort(start + i * 2, s.toShort())
        }
    }

    private var scratch = FloatArray(0)

    private fun biquad(k: FloatArray, z: FloatArray, channel: Int, x: Float): Float {
        val i = channel * 2
        val y = k[0] * x + z[i]
        z[i] = k[1] * x - k[3] * y + z[i + 1]
        z[i + 1] = k[2] * x - k[4] * y
        return y
    }

    /** RBJ shelf, slope 1, normalised: b0, b1, b2, a1, a2 into [k]. */
    private fun shelf(k: FloatArray, hz: Double, db: Double, low: Boolean) {
        val a = 10.0.pow(db / 40)
        val w0 = 2 * PI * (hz.coerceAtMost(rate * 0.45)) / rate
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0) // slope 1: (A + 1/A)(1/S - 1) + 2 = 2
        val sa = 2 * sqrt(a) * alpha
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        if (low) {
            b0 = a * ((a + 1) - (a - 1) * cw + sa)
            b1 = 2 * a * ((a - 1) - (a + 1) * cw)
            b2 = a * ((a + 1) - (a - 1) * cw - sa)
            a0 = (a + 1) + (a - 1) * cw + sa
            a1 = -2 * ((a - 1) + (a + 1) * cw)
            a2 = (a + 1) + (a - 1) * cw - sa
        } else {
            b0 = a * ((a + 1) + (a - 1) * cw + sa)
            b1 = -2 * a * ((a - 1) + (a + 1) * cw)
            b2 = a * ((a + 1) + (a - 1) * cw - sa)
            a0 = (a + 1) - (a - 1) * cw + sa
            a1 = 2 * ((a - 1) - (a + 1) * cw)
            a2 = (a + 1) - (a - 1) * cw - sa
        }
        k[0] = (b0 / a0).toFloat()
        k[1] = (b1 / a0).toFloat()
        k[2] = (b2 / a0).toFloat()
        k[3] = (a1 / a0).toFloat()
        k[4] = (a2 / a0).toFloat()
    }

    private fun step(current: Float, target: Float): Float {
        val d = target - current
        return if (abs(d) <= MAX_STEP_DB) target else current + if (d > 0) MAX_STEP_DB else -MAX_STEP_DB
    }

    private fun flat(db: Float) = abs(db) < 0.01f

    private companion object {
        const val BLOCK = 64
        const val MAX_STEP_DB = 0.5f
    }
}
