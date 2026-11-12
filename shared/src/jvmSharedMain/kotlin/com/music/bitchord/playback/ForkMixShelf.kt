package com.music.bitchord.playback

import com.music.bitchord.playback.smart.ForkAutoDjCurves
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Fork: the filters a Web / Advanced AutoDJ mix rides on each song ([ForkAutoDjCurves]): a 250 Hz
 * low shelf (the bass swap), a 4 kHz high shelf (the highs crossing), and the low- and high-pass
 * cuts, as the extension's `BiquadFilterNode`s (Web Audio's shelf: RBJ cookbook, slope 1; its
 * lowpass / highpass: one second-order section, Q 1/sqrt 2, so 12 dB per octave). The cuts used to
 * be the apps' transition filters: two sections, 24 dB/oct (BitChord's DJ slope), twice as steep as
 * the extension's. Those stay open during these mixes now; BitChord's own style still uses them.
 *
 * Gains are aimed with [set] from any thread and glide there per 64-frame block (at most 0.5 dB a
 * block, ~25 ms for the full 12 dB swap) so a jump is never a click. At 0 dB and settled the block
 * is passed through untouched, not a sample read, so every other mix stays byte-identical.
 */
class ForkMixShelf {

    @Volatile private var targetLowDb = 0f
    @Volatile private var targetHighDb = 0f
    @Volatile private var targetLowPassHz = OPEN_HZ
    @Volatile private var targetHighPassHz = OFF_HZ
    @Volatile private var snapPending = false

    private var lowDb = 0f
    private var highDb = 0f
    private var lowPassHz = OPEN_HZ
    private var highPassHz = OFF_HZ
    private var channels = 0
    private var rate = 0
    private var dirty = false

    // Per channel, two biquads in transposed direct form II: z1, z2 each.
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)
    private val lowCoef = FloatArray(5)
    private val highCoef = FloatArray(5)

    // The cuts: per channel, one state-variable section each (two integrator states), the transition
    // filters' own section, so a cutoff can move every block without a click.
    private var lowPassState = FloatArray(0)
    private var highPassState = FloatArray(0)
    private val lowPassCoef = FloatArray(3)
    private val highPassCoef = FloatArray(3)

    /**
     * Aims the shelves, in dB (0 = flat), and the cuts, in Hz ([OPEN_HZ] / [OFF_HZ]: no cut), clamped
     * to the range the transition filters take.
     */
    fun set(lowDb: Float, highDb: Float, lowPassHz: Float = OPEN_HZ, highPassHz: Float = OFF_HZ) {
        targetLowDb = lowDb.coerceIn(-24f, 0f)
        targetHighDb = highDb.coerceIn(-24f, 0f)
        targetLowPassHz = lowPassHz.coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = highPassHz.coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    /** All flat and open. */
    fun open() = set(0f, 0f)

    /** Jumps to these settings with no glide and no history (a new mix on a deck that held another's). */
    fun snap(lowDb: Float, highDb: Float, lowPassHz: Float = OPEN_HZ, highPassHz: Float = OFF_HZ) {
        set(lowDb, highDb, lowPassHz, highPassHz)
        snapPending = true
    }

    /**
     * Starts flat and open with no history, then glides to these settings: a deck that is already
     * playing when a mix begins. A [snap] there would jump an audible song straight into the curve's
     * first cut (Advanced AutoDJ's filter mix starts the outgoing low-pass at 7 kHz): a click and a
     * sudden dulling, where the transition filters this replaced glided in from open.
     */
    fun startOpen(lowDb: Float, highDb: Float, lowPassHz: Float = OPEN_HZ, highPassHz: Float = OFF_HZ) {
        set(lowDb, highDb, lowPassHz, highPassHz)
        openPending = true
    }

    @Volatile private var openPending = false

    /** A seek: the filters' history is from before it, so the next block starts clean at the aimed gains. */
    fun flush() {
        snapPending = true
    }

    /** The sink is torn down: flat, with no history. */
    fun reset() = snap(0f, 0f)

    val isShaping: Boolean
        get() = !flat(targetLowDb) || !flat(targetHighDb) || !flat(lowDb) || !flat(highDb) ||
            cutting(targetLowPassHz, targetHighPassHz) || cutting(lowPassHz, highPassHz)

    /** Interleaved [samples], [sampleCount] of them, in place. */
    fun process(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        if (channelCount < 1 || sampleRate <= 0 || sampleCount <= 0) return
        if (openPending) {
            openPending = false
            lowDb = 0f
            highDb = 0f
            lowPassHz = OPEN_HZ
            highPassHz = OFF_HZ
            clearHistory()
        }
        if (snapPending) {
            snapPending = false
            lowDb = targetLowDb
            highDb = targetHighDb
            lowPassHz = targetLowPassHz
            highPassHz = targetHighPassHz
            clearHistory()
        }
        if (!isShaping) {
            if (dirty) {
                clearHistory()
                dirty = false
            }
            return
        }
        if (channelCount != channels || sampleRate != rate || lowState.size != channelCount * 2) {
            channels = channelCount
            rate = sampleRate
            lowState = FloatArray(channelCount * 2)
            highState = FloatArray(channelCount * 2)
            lowPassState = FloatArray(channelCount * 2)
            highPassState = FloatArray(channelCount * 2)
        }
        dirty = true
        val frames = sampleCount / channelCount
        var frame = 0
        while (frame < frames) {
            lowDb = step(lowDb, targetLowDb)
            highDb = step(highDb, targetHighDb)
            lowPassHz = settle(glide(lowPassHz, targetLowPassHz), targetLowPassHz)
            highPassHz = settle(glide(highPassHz, targetHighPassHz), targetHighPassHz)
            shelf(lowCoef, ForkAutoDjCurves.LOW_SHELF_HZ, lowDb.toDouble(), low = true)
            shelf(highCoef, ForkAutoDjCurves.HIGH_SHELF_HZ, highDb.toDouble(), low = false)
            val lowOn = !flat(lowDb)
            val highOn = !flat(highDb)
            val lowPassOn = lowPassHz < OPEN_HZ
            val highPassOn = highPassHz > OFF_HZ
            if (lowPassOn) section(lowPassCoef, lowPassHz) else lowPassState.fill(0f)
            if (highPassOn) section(highPassCoef, highPassHz) else highPassState.fill(0f)
            val end = minOf(frames, frame + BLOCK)
            for (f in frame until end) {
                val base = f * channelCount
                for (c in 0 until channelCount) {
                    var x = samples[base + c]
                    if (highPassOn) x = svf(highPassCoef, highPassState, c, x, high = true)
                    if (lowPassOn) x = svf(lowPassCoef, lowPassState, c, x, high = false)
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
                clearHistory()
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

    private fun clearHistory() {
        lowState.fill(0f)
        highState.fill(0f)
        lowPassState.fill(0f)
        highPassState.fill(0f)
    }

    /** One topology-preserving state-variable section (Q 1/sqrt 2) at [hz]: a1, a2, a3 into [k]. */
    private fun section(k: FloatArray, hz: Float) {
        val g = tan(PI * hz.coerceIn(MIN_HZ, rate * MAX_CUTOFF_FRACTION) / rate).toFloat()
        val a1 = 1f / (1f + g * (g + BUTTERWORTH_K))
        k[0] = a1
        k[1] = g * a1
        k[2] = g * k[1]
    }

    /** [x] through [channel]'s section: its low-pass output, or with [high] its high-pass one. */
    private fun svf(k: FloatArray, z: FloatArray, channel: Int, x: Float, high: Boolean): Float {
        val i = channel * 2
        val ic1 = z[i]
        val ic2 = z[i + 1]
        val v3 = x - ic2
        val v1 = k[0] * ic1 + k[1] * v3
        val v2 = ic2 + k[1] * ic1 + k[2] * v3
        z[i] = 2f * v1 - ic1
        z[i + 1] = 2f * v2 - ic2
        return if (high) x - BUTTERWORTH_K * v1 - v2 else v2
    }

    /** A cutoff's step towards [target] per block, in log frequency, as the transition filters glide. */
    private fun glide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_HZ))
        val to = ln(target.coerceAtLeast(MIN_HZ))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    /** Lands on [target] once within [SETTLED_HZ] of it, so an opening cut ends switched off. */
    private fun settle(current: Float, target: Float): Float = if (abs(current - target) <= SETTLED_HZ) target else current

    private fun cutting(lowPass: Float, highPass: Float) = lowPass < OPEN_HZ || highPass > OFF_HZ

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

    companion object {
        /** A low-pass here is no cut, as the transition filters' `OPEN_HZ`. */
        const val OPEN_HZ = 20_000f

        /** A high-pass here is no cut, as the transition filters' `OFF_HZ`. */
        const val OFF_HZ = 20f

        private const val MIN_HZ = 10f
        private const val MAX_HIGH_PASS_HZ = 2_000f
        private const val MAX_CUTOFF_FRACTION = 0.45f
        private const val BLOCK = 64
        private const val MAX_STEP_DB = 0.5f

        /** The transition filters' glide per 64-frame block, so a cut moves as fast as it did there. */
        private const val GLIDE_RATE = 0.05f
        private const val SETTLED_HZ = 1f

        /** 1/Q of a second-order Butterworth section (Q = 1/sqrt 2): the extension's 12 dB/oct. */
        private val BUTTERWORTH_K = sqrt(2.0).toFloat()
    }
}
