package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Fork: the Automix loop mix's renderer for the PC, in plain Kotlin.
 *
 * The phone renders the loop mix natively (`native/loopmix/loop_mix_renderer.cpp`): two decks, each a
 * decoded region of one song, played through Signalsmith Stretch, looped on whole bars and following
 * the mix's tempo ramp beat by beat. Building that library for Windows (and Linux, and the cross-built
 * zip) is a toolchain the PC build does not have, so this is the same renderer with the same inputs —
 * the shared `LoopMix` clock's knots, the loop bounds, the gain, bass and high-pass automation — and
 * one difference: the stretch is a WSOLA keyed to absolute positions instead of a phase vocoder.
 *
 * Why WSOLA is enough here, and why "keyed to absolute positions": a deck's heard position at every
 * mix frame is known exactly in advance (the knots). Each grain is read from the source where that
 * position says it should be, then nudged by at most [ForkLoopDeck.toleranceFrames] to the offset that
 * continues the previous grain best. So the error never accumulates — however long the ramp, a deck is
 * never more than a few milliseconds from its knot — and where the deck plays at rate 1 (the outgoing
 * loop before the ramp, the incoming song after its release) a grain is exactly the previous one's
 * continuation and Hann windows at half overlap sum to one: the output is the source sample for sample.
 * That is what makes the two splices clean: the outgoing song hands over to its own deck, and the
 * incoming deck hands over to its own song, both on identical audio.
 */

/** A piecewise-linear automation curve over mix seconds, holding its end values (the native `Envelope`). */
internal class ForkLoopEnvelope(
    private val times: DoubleArray,
    private val values: FloatArray,
    /** Interpolated in the log domain: a frequency sweep. */
    private val exponential: Boolean = false,
) {
    fun at(time: Double): Float {
        if (times.isEmpty()) return 0f
        if (time <= times.first()) return values.first()
        if (time >= times.last()) return values.last()
        // A handful of points: a scan is cheaper than a search's branches, as natively.
        var i = 1
        while (i < times.size && times[i] < time) i++
        val t0 = times[i - 1]
        val t1 = times[i]
        val f = if (t1 > t0) (time - t0) / (t1 - t0) else 1.0
        val v0 = values[i - 1].toDouble()
        val v1 = values[i].toDouble()
        if (exponential && v0 > 0 && v1 > 0) return (v0 * (v1 / v0).pow(f)).toFloat()
        return (v0 + (v1 - v0) * f).toFloat()
    }
}

/** RBJ biquad, transposed direct form II, two channels — the native `Biquad`, coefficient for coefficient. */
internal class ForkLoopBiquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private val z1 = FloatArray(2)
    private val z2 = FloatArray(2)

    /** RBJ cookbook shelf, slope 1: the Web Audio lowshelf the web player's EQ uses. */
    fun lowShelf(sampleRate: Double, frequency: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * frequency / sampleRate
        val cosw = cos(w0)
        val sinw = sin(w0)
        val alpha = sinw / 2 * sqrt(2.0)
        val sq = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosw + sq
        b0 = (a * ((a + 1) - (a - 1) * cosw + sq) / a0).toFloat()
        b1 = (2 * a * ((a - 1) - (a + 1) * cosw) / a0).toFloat()
        b2 = (a * ((a + 1) - (a - 1) * cosw - sq) / a0).toFloat()
        a1 = (-2 * ((a - 1) + (a + 1) * cosw) / a0).toFloat()
        a2 = (((a + 1) + (a - 1) * cosw - sq) / a0).toFloat()
    }

    fun highPass(sampleRate: Double, frequency: Double, q: Double) {
        val w0 = 2 * PI * frequency / sampleRate
        val cosw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        b0 = ((1 + cosw) / 2 / a0).toFloat()
        b1 = (-(1 + cosw) / a0).toFloat()
        b2 = b0
        a1 = (-2 * cosw / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
    }

    fun bypass() {
        b0 = 1f
        b1 = 0f
        b2 = 0f
        a1 = 0f
        a2 = 0f
    }

    fun tick(channel: Int, x: Float): Float {
        val y = b0 * x + z1[channel]
        z1[channel] = b1 * x - a1 * y + z2[channel]
        z2[channel] = b2 * x - a2 * y
        return y
    }
}

/**
 * One deck: a decoded region of a song (planar, at the renderer's rate), its loop, and its beat knots.
 * The fields are the native `Deck`'s; every position is in *nominal* region samples, the analysis's
 * track seconds counted from [regionStart], except where [shift] says the decoded audio really sits.
 */
internal class ForkLoopDeck(
    val left: FloatArray,
    val right: FloatArray,
    /** Track seconds of region sample 0. */
    val regionStart: Double,
    /** Loop bounds in region samples. */
    val loopStart: Long,
    val loopEnd: Long,
    /** Unwrapped region-sample position at which the loop lets go; huge for never. */
    val releaseAt: Double,
    /** Mix frames (0 = the outgoing loop start) and the unwrapped region-sample position heard at each. */
    val knotFrames: DoubleArray,
    val knotPositions: DoubleArray,
    /** After the last knot: carry on at rate 1 (the incoming song) or hold (the outgoing one, silent by then). */
    val runOn: Boolean,
    /** Mix seconds after which the deck renders nothing. */
    val stopAt: Double,
    /** Seconds of pre-roll before the first knot. */
    val preroll: Double,
    /** A plain gain on the deck (loudness matching between the two songs). */
    val level: Float,
    val sampleRate: Int,
) {
    var gain: ForkLoopEnvelope? = null
    var lowDb: ForkLoopEnvelope? = null
    var highPassHz: ForkLoopEnvelope? = null

    /**
     * Region samples the decoded audio is late by: sample `nominal + shift` holds what the analysis
     * calls `nominal`. Set once, before the first render, by the outgoing splice's own measurement
     * (see [ForkLoopMixSession.alignOut]): the playing decoder and the one that decoded the region can
     * disagree by a codec's priming, which is the one thing that would make the takeover audible.
     */
    var shift: Long = 0L

    /** Mix frame from which the deck fades out over [fadeFrames] (the incoming deck, to its own song). */
    var fadeOutFrame: Long = Long.MAX_VALUE
    var fadeFrames: Long = 1L

    /** Grain hop: 1/48 s (1000 frames at 48 kHz); grains are two hops long, Hann-windowed. */
    val hop: Int = (sampleRate / 48).coerceAtLeast(64)
    private val size = hop * 2
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }

    /**
     * How far a grain may sit from its knot to continue the one before it: 5 ms. Wider sounds smoother
     * on a big tempo step and lets two decks flam by that much; 5 ms is under what a kick is heard to
     * double at, and the error is per grain, never accumulated.
     */
    val toleranceFrames: Int = (sampleRate * 0.005).toInt().coerceAtLeast(8)

    private val startFrame: Double = (knotFrames.firstOrNull() ?: 0.0) - preroll * sampleRate
    private var finished = left.isEmpty() || knotFrames.size < 2

    // Grains, newest last: grain k covers mix frames [k * hop, k * hop + 2 * hop) and reads the source
    // from its (unwrapped) start. Rendering only ever asks for the newest two.
    private val ringK = LongArray(RING) { Long.MIN_VALUE }
    private val ringS = LongArray(RING)
    private var lastK = Long.MIN_VALUE
    private var lastS = 0L

    private val shelf = ForkLoopBiquad()
    private val highPass = ForkLoopBiquad()
    private var shelfDb = 0f
    private var highPassFreq = 0f

    // ---- positions ---------------------------------------------------------------------------

    /** The region sample an unwrapped position reads (the loop and its release applied), or -1 outside it. */
    fun sampleAt(unwrapped: Long): Int {
        var index = unwrapped
        val length = loopEnd - loopStart
        if (length > 0 && unwrapped >= loopEnd) {
            val release = releaseAt.toLong()
            index = if (unwrapped < release) {
                loopStart + (unwrapped - loopStart) % length
            } else {
                // Let go: the song carries on from the loop's end.
                loopEnd + (unwrapped - release)
            }
        }
        index += shift
        return if (index >= 0 && index < left.size) index.toInt() else -1
    }

    /** Track seconds an unwrapped position plays, in the analysis's time (the loop and its release applied). */
    fun trackSecondsAt(unwrapped: Double): Double {
        var position = unwrapped
        val length = (loopEnd - loopStart).toDouble()
        if (length > 0 && unwrapped >= loopEnd) {
            position = if (unwrapped < releaseAt) {
                loopStart + (unwrapped - loopStart).mod(length)
            } else {
                loopEnd + (unwrapped - releaseAt)
            }
        }
        return regionStart + position / sampleRate
    }

    /** Heard position (unwrapped samples) at mix frame [t]: on the knots, then run on at rate 1 or hold. */
    fun heard(t: Double): Double {
        if (knotFrames.isEmpty()) return 0.0
        val last = knotFrames.last()
        if (t > last) return if (runOn) knotPositions.last() + (t - last) else knotPositions.last()
        if (t <= knotFrames.first()) return knotPositions.first() + (t - knotFrames.first()) * knotRate(knotFrames.first())
        var i = upperBound(t)
        if (i >= knotFrames.size) i = knotFrames.size - 1
        val f = (t - knotFrames[i - 1]) / (knotFrames[i] - knotFrames[i - 1])
        return knotPositions[i - 1] + (knotPositions[i] - knotPositions[i - 1]) * f
    }

    /** Input samples per output frame around mix frame [t]. */
    fun rate(t: Double): Double {
        if (knotFrames.isEmpty()) return 1.0
        if (t > knotFrames.last()) return if (runOn) 1.0 else 0.0
        return knotRate(t)
    }

    private fun knotRate(t: Double): Double {
        if (knotFrames.size < 2) return 1.0
        val i = upperBound(t).coerceIn(1, knotFrames.size - 1)
        return (knotPositions[i] - knotPositions[i - 1]) / (knotFrames[i] - knotFrames[i - 1])
    }

    private fun upperBound(t: Double): Int {
        var low = 0
        var high = knotFrames.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (knotFrames[mid] <= t) low = mid + 1 else high = mid
        }
        return low
    }

    /** The unwrapped source position the deck plays at mix frame [frame], from the grain that is newest there. */
    fun sourceAt(frame: Long): Long {
        val k = Math.floorDiv(frame, hop.toLong())
        return grain(k) + (frame - k * hop)
    }

    // ---- WSOLA --------------------------------------------------------------------------------

    /** Where grain [k] reads from; grains are worked out in order and remembered, so this is cheap to ask twice. */
    fun grain(k: Long): Long {
        if (lastK != Long.MIN_VALUE && k <= lastK) {
            if (lastK - k < RING) {
                val slot = Math.floorMod(k, RING.toLong()).toInt()
                if (ringK[slot] == k) return ringS[slot]
            }
            // Further back than anything kept: start over there (only a test asks for that).
            lastK = Long.MIN_VALUE
        }
        if (lastK == Long.MIN_VALUE || k - lastK > RING) {
            lastK = k - 1
            lastS = (target(k - 1)).roundToLong()
            remember(lastK, lastS)
        }
        while (lastK < k) {
            val next = lastK + 1
            lastS = place(next, lastS)
            lastK = next
            remember(lastK, lastS)
        }
        return lastS
    }

    private fun remember(k: Long, s: Long) {
        val slot = Math.floorMod(k, RING.toLong()).toInt()
        ringK[slot] = k
        ringS[slot] = s
    }

    /** Where grain [k] should start for its centre to be heard where the knots say. */
    private fun target(k: Long): Double = heard((k * hop + hop).toDouble()) - hop

    private fun place(k: Long, previous: Long): Long {
        val target = target(k)
        val natural = previous + hop
        val gap = abs(target - natural)
        val rate = rate((k * hop + hop).toDouble())
        return when {
            // On the knots already, or at rate 1 within reach: the previous grain's own continuation,
            // which is the source sample for sample.
            gap <= 1.0 -> natural
            abs(rate - 1.0) < 1e-6 && gap <= toleranceFrames -> natural
            // A jump far past anything a grain could bridge (a deck starting, a hold): go there.
            gap > toleranceFrames * 4.0 || rate <= 0.0 -> target.roundToLong()
            else -> search(natural, target.roundToLong())
        }
    }

    private var refMono = FloatArray(0)
    private var candMono = FloatArray(0)

    /**
     * The start within [toleranceFrames] of [around] whose first hop best matches what the previous
     * grain would have played next ([natural]): normalised cross-correlation on the mono sum, every
     * lag on every 4th sample, then every sample around the best.
     */
    private fun search(natural: Long, around: Long): Long {
        val length = hop
        val tol = toleranceFrames
        if (refMono.size < length) refMono = FloatArray(length)
        val span = length + 2 * tol + 1
        if (candMono.size < span) candMono = FloatArray(span)
        for (i in 0 until length) refMono[i] = mono(natural + i)
        val from = around - tol
        for (i in 0 until span) candMono[i] = mono(from + i)
        // The knot's own position is the one to beat: on silence, or on a tie, the deck stays on time.
        var best = tol
        var bestScore = score(tol, length, 4)
        for (lag in 0..2 * tol) {
            val s = score(lag, length, 4)
            if (s > bestScore + 1e-9) {
                bestScore = s
                best = lag
            }
        }
        var fineBest = best
        var fineScore = score(best, length, 1)
        for (l in (best - 2).coerceAtLeast(0)..(best + 2).coerceAtMost(2 * tol)) {
            val s = score(l, length, 1)
            if (s > fineScore + 1e-9) {
                fineScore = s
                fineBest = l
            }
        }
        return from + fineBest
    }

    private fun score(lag: Int, length: Int, stride: Int): Double {
        var dot = 0.0
        var energy = 0.0
        var i = 0
        while (i < length) {
            val c = candMono[lag + i].toDouble()
            dot += refMono[i] * c
            energy += c * c
            i += stride
        }
        return if (energy <= 1e-12) 0.0 else dot / sqrt(energy)
    }

    private fun mono(unwrapped: Long): Float {
        val index = sampleAt(unwrapped)
        return if (index >= 0) left[index] + right[index] else 0f
    }

    // ---- rendering ----------------------------------------------------------------------------

    private var dryLeft = FloatArray(0)
    private var dryRight = FloatArray(0)

    /** Adds [frames] frames from mix frame [frame] into [mixLeft] / [mixRight]. Frames must be asked for in order. */
    fun renderInto(frame: Long, frames: Int, mixLeft: FloatArray, mixRight: FloatArray) {
        if (finished || frames <= 0) return
        val sr = sampleRate.toDouble()
        if (frame / sr >= stopAt) {
            finished = true
            return
        }
        if (frame + frames <= startFrame) return
        if (dryLeft.size < frames) {
            dryLeft = FloatArray(frames)
            dryRight = FloatArray(frames)
        }
        for (n in 0 until frames) {
            val f = frame + n
            if (f < startFrame) {
                dryLeft[n] = 0f
                dryRight[n] = 0f
                continue
            }
            val k = Math.floorDiv(f, hop.toLong())
            val offset = (f - k * hop).toInt()
            val older = grain(k - 1)
            val newer = grain(k)
            val wNew = window[offset]
            val wOld = window[offset + hop]
            val iNew = sampleAt(newer + offset)
            val iOld = sampleAt(older + offset + hop)
            dryLeft[n] = (if (iNew >= 0) left[iNew] * wNew else 0f) + (if (iOld >= 0) left[iOld] * wOld else 0f)
            dryRight[n] = (if (iNew >= 0) right[iNew] * wNew else 0f) + (if (iOld >= 0) right[iOld] * wOld else 0f)
        }

        // Gain, bass shelf and high-pass in control blocks; the gain ramps per sample — as natively.
        var at = 0
        while (at < frames) {
            val count = minOf(CONTROL_FRAMES, frames - at)
            val t0 = (frame + at) / sr
            val t1 = (frame + at + count) / sr
            lowDb?.let { envelope ->
                val db = envelope.at((t0 + t1) / 2)
                if (abs(db - shelfDb) > 0.01f) {
                    shelfDb = db
                    if (abs(db) < 0.01f) shelf.bypass() else shelf.lowShelf(sr, 250.0, db.toDouble())
                }
            }
            highPassHz?.let { envelope ->
                val hz = envelope.at((t0 + t1) / 2)
                if (abs(hz - highPassFreq) > 0.5f) {
                    highPassFreq = hz
                    if (hz <= 20.5f) highPass.bypass() else highPass.highPass(sr, hz.toDouble(), 0.7)
                }
            }
            val g0 = gain?.at(t0) ?: 1f
            val g1 = gain?.at(t1) ?: 1f
            for (i in 0 until count) {
                val n = at + i
                var g = (g0 + (g1 - g0) * (i.toFloat() / count)) * level
                val f = frame + n
                if (f >= fadeOutFrame) g *= ((fadeOutFrame + fadeFrames - f).toFloat() / fadeFrames).coerceIn(0f, 1f)
                var l = shelf.tick(0, dryLeft[n])
                var r = shelf.tick(1, dryRight[n])
                l = highPass.tick(0, l)
                r = highPass.tick(1, r)
                mixLeft[n] += l * g
                mixRight[n] += r * g
            }
            at += count
        }
        // Compared as a difference: with no fade set, `fadeOutFrame + fadeFrames` overflows past
        // Long.MAX_VALUE to negative and ended every such deck after its first block.
        if (fadeOutFrame != Long.MAX_VALUE && frame - fadeOutFrame >= fadeFrames) finished = true
    }

    private companion object {
        const val RING = 64

        /** Filter coefficients are recomputed at most this often (the native `kControlFrames`). */
        const val CONTROL_FRAMES = 64
    }
}

/** The two decks mixed to one interleaved stereo stream, on the mix's own frame clock. */
internal class ForkLoopMixRenderer(val sampleRate: Int) {
    val decks = arrayOfNulls<ForkLoopDeck>(2)
    private var mixLeft = FloatArray(0)
    private var mixRight = FloatArray(0)

    /** Renders [frames] stereo frames from mix frame [frame] into [interleaved] (overwritten). In order only. */
    fun render(frame: Long, interleaved: FloatArray, frames: Int) {
        if (mixLeft.size < frames) {
            mixLeft = FloatArray(frames)
            mixRight = FloatArray(frames)
        }
        java.util.Arrays.fill(mixLeft, 0, frames, 0f)
        java.util.Arrays.fill(mixRight, 0, frames, 0f)
        for (deck in decks) deck?.renderInto(frame, frames, mixLeft, mixRight)
        for (i in 0 until frames) {
            interleaved[2 * i] = mixLeft[i]
            interleaved[2 * i + 1] = mixRight[i]
        }
    }

    companion object {
        const val OUT = 0
        const val IN = 1

        /** Floor division for a frame position that may be fractional (a knot). */
        fun frameOf(seconds: Double, sampleRate: Int): Long = floor(seconds * sampleRate).toLong()
    }
}
