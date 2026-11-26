package com.music.bitchord.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fork: the effect returns and the noise of an Automix mix's Effects lane (the extension's spec/automix/editor.md §12,
 * crossfade.js fxBus, fxSends, hallImpulse, noiseBuffer), shared by the PC's mixer and the phone's sinks.
 *
 * - The decks' sends (each deck's sound after its fader, EQ and filters, times its send curve) feed three returns that
 *   play into the mix next to the decks and ring on after it: a tail is never cut at the window's end.
 *   - Echo: a delay of the echo time with feedback 0.45 through a 3 kHz low-pass (Web Audio's Q 0.5, which is in dB
 *     there), so each repeat is darker; level 1.
 *   - Delay: a delay of the delay time, feedback 0.3, no filter; level 1.
 *   - Reverb: a convolution with a generated 2.4 s impulse (stereo white noise decaying by 60 dB over 2.4 s), normalized
 *     as Web Audio's ConvolverNode normalizes one (its RMS, its -58 dB calibration at 44.1 kHz); level 0.7. Uniformly
 *     partitioned in 1024-frame blocks (FFT), so it is late by one block (21 ms at 48 kHz) and costs nothing once quiet.
 * - The noise: white noise through a 1.5 kHz high-pass at its level × 0.25 (−12 dB at 1), not under any fader. Two
 *   sources ([render]'s two levels: the outgoing and the incoming deck's), independent as the extension's two nodes are.
 *
 * Two ways in: the phone's sink runs one deck ([process]: the sends from [aim], taken from the block it is given), the
 * PC's mixer runs both into one set of returns ([begin], [send], [render]). Audio thread only, except the aims.
 */
class ForkMixFx {

    @Volatile private var aimSends = FloatArray(3)
    @Volatile private var aimNoise = 0f
    @Volatile private var echoSec = 0.375 // (DelayNode's delayTime as the extension creates it)
    @Volatile private var delaySec = 0.25
    @Volatile private var clearPending = false

    /** Whether a return still rings or noise plays: the deck's block isn't passed through untouched. */
    @Volatile var ringing = false
        private set

    /** The phone's one deck: its sends (echo, reverb, delay, 0..1) and its noise (0..1), any thread. */
    fun aim(echo: Float, reverb: Float, delay: Float, noise: Float) {
        aimSends = floatArrayOf(clean(echo), clean(reverb), clean(delay))
        aimNoise = clean(noise)
    }

    /** The echo's and the delay's seconds (editor.md §12 rule 3: set at the window's start; up to 3 s, as the extension's). */
    fun times(echoSec: Double, delaySec: Double) {
        if (echoSec > 0 && echoSec.isFinite()) this.echoSec = min(MAX_DELAY_SEC, echoSec)
        if (delaySec > 0 && delaySec.isFinite()) this.delaySec = min(MAX_DELAY_SEC, delaySec)
    }

    /** The mix is over: nothing sent, no noise; the returns ring on by themselves. */
    fun rest() = aim(0f, 0f, 0f, 0f)

    /** A seek or a torn-down sink: silent at once, with no history. */
    fun clear() {
        rest()
        clearPending = true
    }

    /** Whether [process] would touch a block: something is aimed, or a return rings. */
    val busy: Boolean get() = ringing || aimNoise > 0f || aimSends.any { it > 0f }

    private fun clean(v: Float) = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

    // ───────── the one-deck path (the phone's sink) ─────────

    private val lastSends = FloatArray(3)
    private var lastNoise = 0f

    /**
     * The phone's deck: [samples] (interleaved, after the deck's fader) sent to the returns as aimed, ramped from the last
     * block's sends; the returns and the noise added to them in place. Not busy: untouched.
     */
    fun process(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        if (channelCount < 1 || sampleRate <= 0 || sampleCount <= 0) return
        val sends = aimSends
        val noise = aimNoise
        if (!ringing && !clearPending && noise == 0f && lastNoise == 0f && sends.all { it == 0f } && lastSends.all { it == 0f }) return
        val frames = sampleCount / channelCount
        begin(frames, channelCount, sampleRate)
        if (sends.any { it > 0f } || lastSends.any { it > 0f }) {
            for (k in 0..2) {
                val a = lastSends[k]
                val b = sends[k]
                if (a == 0f && b == 0f) continue
                val into = inputs[k]
                fed[k] = true
                for (f in 0 until frames) {
                    val g = a + (b - a) * (f + 1) / frames
                    val base = f * channelCount
                    for (c in 0 until channelCount) into[base + c] += samples[base + c] * g
                }
            }
        }
        System.arraycopy(sends, 0, lastSends, 0, 3)
        render(samples, frames, lastNoise, noise, 0f, 0f)
        lastNoise = noise
    }

    // ───────── the two-deck path (the PC's mixer) ─────────

    private var inputs = arrayOf(FloatArray(0), FloatArray(0), FloatArray(0))
    private val fed = BooleanArray(3)
    private var channels = 0
    private var rate = 0

    /** A block of [frames] frames starts: nothing sent to the returns yet. */
    fun begin(frames: Int, channelCount: Int, sampleRate: Int) {
        val n = frames * channelCount
        if (inputs[0].size < n) inputs = arrayOf(FloatArray(n), FloatArray(n), FloatArray(n))
        for (k in 0..2) inputs[k].fill(0f, 0, n)
        fed.fill(false)
        if (channelCount != channels || sampleRate != rate || clearPending) {
            clearPending = false
            setUp(channelCount, sampleRate)
        }
    }

    /** Sample [i] of the block (interleaved) sends [x] at these levels (0: none). */
    fun send(i: Int, x: Float, echo: Float, reverb: Float, delay: Float) {
        if (echo > 0f) { inputs[0][i] += x * echo; fed[0] = true }
        if (reverb > 0f) { inputs[1][i] += x * reverb; fed[1] = true }
        if (delay > 0f) { inputs[2][i] += x * delay; fed[2] = true }
    }

    private val noiseFrom = FloatArray(2)
    private val noiseTo = FloatArray(2)

    /**
     * The returns of what was sent since [begin] and the noise (two sources, each ramped from its first level to its
     * second over the block) added to [out], [frames] frames of the [begin]'s format.
     */
    fun render(out: FloatArray, frames: Int, noiseAFrom: Float, noiseATo: Float, noiseBFrom: Float, noiseBTo: Float) {
        val ch = channels
        if (ch == 0) return
        noiseFrom[0] = noiseAFrom; noiseTo[0] = noiseATo; noiseFrom[1] = noiseBFrom; noiseTo[1] = noiseBTo
        for (k in 0..2) if (fed[k]) {
            quiet[k] = 0
            if (tail[k] == 0) tail[k] = Int.MAX_VALUE / 4 // (its own length is set as it first runs)
        }
        if (quiet[0] < tail[0]) runDelay(0, out, frames, ECHO_FEEDBACK, tone = true)
        if (quiet[2] < tail[2]) runDelay(2, out, frames, DELAY_FEEDBACK, tone = false)
        if (quiet[1] < tail[1]) runReverb(out, frames)
        for (s in 0..1) if (noiseFrom[s] > 0f || noiseTo[s] > 0f) runNoise(s, out, frames)
        for (k in 0..2) if (quiet[k] < tail[k]) quiet[k] += frames
        ringing = (0..2).any { quiet[it] < tail[it] } || noiseTo[0] > 0f || noiseTo[1] > 0f
    }

    // ───────── state ─────────

    /** Frames since a return was last fed, and how long it rings after (−60 dB or so). */
    private val quiet = IntArray(3) { Int.MAX_VALUE / 2 }
    private val tail = IntArray(3)

    /** Echo [0] and delay [2]: per channel a ring of [MAX_DELAY_SEC] + a block. */
    private var lines = arrayOf<Array<FloatArray>>(emptyArray(), emptyArray(), emptyArray())
    private val writeAt = IntArray(3)
    private var toneState = FloatArray(0)
    private val toneCoef = FloatArray(5)

    private var noiseState = Array(2) { FloatArray(0) }
    private val noiseCoef = FloatArray(5)
    private val noiseSeed = intArrayOf(0x1234567, 0x7654321)

    private var reverb: Convolver? = null

    private fun setUp(channelCount: Int, sampleRate: Int) {
        channels = channelCount
        rate = sampleRate
        val len = (MAX_DELAY_SEC * sampleRate).toInt() + 8192
        lines = arrayOf(Array(channelCount) { FloatArray(len) }, emptyArray(), Array(channelCount) { FloatArray(len) })
        writeAt.fill(0)
        toneState = FloatArray(channelCount * 2)
        lowpass(toneCoef, 3000.0, webQ(0.5), sampleRate)
        noiseState = Array(2) { FloatArray(channelCount * 2) }
        highpass(noiseCoef, 1500.0, webQ(0.7), sampleRate)
        reverb = reverbFor(sampleRate)?.also { it.reset() }
        quiet.fill(Int.MAX_VALUE / 2)
        ringing = false
        lastSends.fill(0f)
        lastNoise = 0f
    }

    /** A feedback delay on return [k]: its line read [the time] back, fed the send plus [fb] of what it played. */
    private fun runDelay(k: Int, out: FloatArray, frames: Int, fb: Float, tone: Boolean) {
        val ch = channels
        val line = lines[k]
        val len = line[0].size
        val sec = if (k == 0) echoSec else delaySec
        val d = max(1, min(len - 1, (sec * rate).roundToInt()))
        tail[k] = d * (if (k == 0) ECHO_REPEATS else DELAY_REPEATS) + TAIL_MARGIN
        val into = inputs[k]
        var w = writeAt[k]
        for (f in 0 until frames) {
            var r = w - d
            if (r < 0) r += len
            val base = f * ch
            for (c in 0 until ch) {
                var y = line[c][r]
                if (tone) y = biquad(toneCoef, toneState, c, y)
                out[base + c] += y
                line[c][w] = into[base + c] + fb * y
            }
            if (++w == len) w = 0
        }
        writeAt[k] = w
    }

    private fun runReverb(out: FloatArray, frames: Int) {
        val cv = reverb ?: return
        tail[1] = cv.tailFrames
        cv.run(inputs[1], out, frames, channels, REVERB_LEVEL)
    }

    private fun runNoise(s: Int, out: FloatArray, frames: Int) {
        val ch = channels
        val st = noiseState[s]
        var x = noiseSeed[s]
        val a = noiseFrom[s] * NOISE_LEVEL
        val b = noiseTo[s] * NOISE_LEVEL
        for (f in 0 until frames) {
            val g = a + (b - a) * (f + 1) / frames
            val base = f * ch
            for (c in 0 until ch) {
                x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
                val n = (x.toFloat() / Int.MAX_VALUE.toFloat()).coerceIn(-1f, 1f)
                out[base + c] += biquad(noiseCoef, st, c, n) * g
            }
        }
        noiseSeed[s] = x
    }

    // ───────── the reverb: a uniformly partitioned convolution ─────────

    /**
     * The 2.4 s hall as Web Audio would play it: L convolved with the impulse's left channel, R with its right (a mono
     * deck: its one channel with the left). Both channels go through one complex FFT (L real, R imaginary).
     */
    internal class Convolver(val sampleRate: Int, impulse: Array<FloatArray>) {
        private val b = BLOCK
        private val n = 2 * b
        private val bins = b + 1
        private val parts = (impulse[0].size + b - 1) / b
        /** The impulse's partitions' spectra: [part][bin] re, im per channel. */
        private val hRe = Array(2) { Array(parts) { FloatArray(bins) } }
        private val hIm = Array(2) { Array(parts) { FloatArray(bins) } }
        /** The input's spectra, newest at [head]; [live]: a partition that wasn't silent. */
        private val xRe = Array(2) { Array(parts) { FloatArray(bins) } }
        private val xIm = Array(2) { Array(parts) { FloatArray(bins) } }
        private val live = BooleanArray(parts)
        private var head = 0
        private val inL = FloatArray(n)
        private val inR = FloatArray(n)
        private val outL = FloatArray(b)
        private val outR = FloatArray(b)
        private var pos = 0
        private var blockFed = false
        private val re = FloatArray(n)
        private val im = FloatArray(n)
        private val accRe = Array(2) { FloatArray(bins) }
        private val accIm = Array(2) { FloatArray(bins) }
        private val fft = Fft(n)

        /** How long it rings after its last input: the impulse, plus the block it is late by and one to spare. */
        val tailFrames = parts * b + 2 * b

        init {
            for (c in 0..1) for (p in 0 until parts) {
                re.fill(0f); im.fill(0f)
                val from = p * b
                val to = min(impulse[c].size, from + b)
                for (i in from until to) re[i - from] = impulse[c][i]
                fft.run(re, im, inverse = false)
                for (k in 0 until bins) { hRe[c][p][k] = re[k]; hIm[c][p][k] = im[k] }
            }
        }

        fun reset() {
            for (c in 0..1) for (p in 0 until parts) { xRe[c][p].fill(0f); xIm[c][p].fill(0f) }
            live.fill(false)
            inL.fill(0f); inR.fill(0f); outL.fill(0f); outR.fill(0f)
            pos = 0
            head = 0
        }

        fun run(input: FloatArray, out: FloatArray, frames: Int, ch: Int, level: Float) {
            for (f in 0 until frames) {
                val base = f * ch
                val l = input[base]
                val r = if (ch > 1) input[base + 1] else 0f
                if (l != 0f || r != 0f) blockFed = true
                inL[b + pos] = l
                inR[b + pos] = r
                out[base] += outL[pos] * level
                if (ch > 1) out[base + 1] += outR[pos] * level
                if (++pos == b) {
                    block(stereo = ch > 1)
                    pos = 0
                }
            }
        }

        /** One block: the last 2B input frames' spectrum into the delay line, the sum over the partitions back (overlap-save). */
        private fun block(stereo: Boolean) {
            head = (head + 1) % parts
            if (blockFed || lastFed) {
                for (i in 0 until n) { re[i] = inL[i]; im[i] = inR[i] }
                fft.run(re, im, inverse = false)
                for (k in 0 until bins) {
                    val j = (n - k) % n
                    // the two real signals' spectra out of the one complex one
                    xRe[0][head][k] = (re[k] + re[j]) * 0.5f
                    xIm[0][head][k] = (im[k] - im[j]) * 0.5f
                    xRe[1][head][k] = (im[k] + im[j]) * 0.5f
                    xIm[1][head][k] = (re[j] - re[k]) * 0.5f
                }
                live[head] = true
            } else if (live[head]) {
                for (c in 0..1) { xRe[c][head].fill(0f); xIm[c][head].fill(0f) }
                live[head] = false
            }
            lastFed = blockFed
            blockFed = false
            // the input slides on: the newer half becomes the older
            System.arraycopy(inL, b, inL, 0, b)
            System.arraycopy(inR, b, inR, 0, b)
            for (c in 0..1) { accRe[c].fill(0f); accIm[c].fill(0f) }
            var any = false
            for (p in 0 until parts) {
                val slot = (head - p + parts) % parts
                if (!live[slot]) continue
                any = true
                for (c in 0 until (if (stereo) 2 else 1)) {
                    val ar = accRe[c]; val ai = accIm[c]
                    val xr = xRe[c][slot]; val xi = xIm[c][slot]
                    val hr = hRe[c][p]; val hi = hIm[c][p]
                    for (k in 0 until bins) {
                        ar[k] += xr[k] * hr[k] - xi[k] * hi[k]
                        ai[k] += xr[k] * hi[k] + xi[k] * hr[k]
                    }
                }
            }
            if (!any) { outL.fill(0f); outR.fill(0f); return }
            // Y = YL + i·YR over the whole spectrum (each half Hermitian), back to time: L real, R imaginary
            for (k in 0 until bins) {
                re[k] = accRe[0][k] - accIm[1][k]
                im[k] = accIm[0][k] + accRe[1][k]
            }
            for (k in bins until n) {
                val j = n - k
                re[k] = accRe[0][j] + accIm[1][j]
                im[k] = -accIm[0][j] + accRe[1][j]
            }
            fft.run(re, im, inverse = true)
            for (i in 0 until b) { outL[i] = re[b + i]; outR[i] = im[b + i] }
        }

        private var lastFed = false
    }

    /** An in-place radix-2 complex FFT of [n] points (the inverse scaled by 1/n). */
    internal class Fft(private val n: Int) {
        private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
        private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
        private val rev = IntArray(n).also { r ->
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
        }

        fun run(re: FloatArray, im: FloatArray, inverse: Boolean) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            val sign = if (inverse) 1f else -1f
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                var start = 0
                while (start < n) {
                    var k = 0
                    for (j in start until start + half) {
                        val wr = cosT[k]
                        val wi = sign * sinT[k]
                        val l = j + half
                        val tr = re[l] * wr - im[l] * wi
                        val ti = re[l] * wi + im[l] * wr
                        re[l] = re[j] - tr
                        im[l] = im[j] - ti
                        re[j] += tr
                        im[j] += ti
                        k += step
                    }
                    start += size
                }
                size *= 2
            }
            if (inverse) {
                val s = 1f / n
                for (i in 0 until n) { re[i] *= s; im[i] *= s }
            }
        }
    }

    companion object {
        /** The extension's DelayNode maxDelayTime. */
        const val MAX_DELAY_SEC = 3.0
        const val ECHO_FEEDBACK = 0.45f
        const val DELAY_FEEDBACK = 0.3f
        const val REVERB_LEVEL = 0.7f
        const val REVERB_SEC = 2.4
        const val NOISE_LEVEL = 0.25f

        /** Repeats until a return is −60 dB or so: 0.45^9 ≈ 0.0008, 0.3^6 ≈ 0.0007 (each under the 3 kHz tone too). */
        private const val ECHO_REPEATS = 9
        private const val DELAY_REPEATS = 6
        private const val TAIL_MARGIN = 2048

        internal const val BLOCK = 1024

        /** Chromium's ConvolverNode normalization: its RMS, −58 dB, at 44.1 kHz (Reverb.cpp CalculateNormalizationScale). */
        private const val GAIN_CALIBRATION = 0.00125f
        private const val GAIN_CALIBRATION_RATE = 44100f
        private const val MIN_POWER = 0.000125f

        private val convolvers = HashMap<Int, Convolver>()

        /**
         * The hall for [sampleRate], made once per rate and kept (each deck's sink and the PC's mixer share the impulse
         * and its spectra, read only; the convolver's running state is its own, so each user gets a copy of that).
         */
        @Synchronized
        internal fun reverbFor(sampleRate: Int): Convolver? {
            if (sampleRate !in 8_000..384_000) return null
            return Convolver(sampleRate, impulse(sampleRate))
        }

        /** Stereo white noise decaying by 60 dB over 2.4 s (crossfade.js hallImpulse), normalized as Web Audio does. */
        internal fun impulse(sampleRate: Int): Array<FloatArray> {
            val n = (sampleRate * REVERB_SEC).roundToInt()
            var x = 0x2545F491
            val ir = Array(2) { FloatArray(n) }
            var power = 0.0
            for (c in 0..1) for (i in 0 until n) {
                x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
                val r = x.toFloat() / Int.MAX_VALUE.toFloat()
                val v = r * 10.0.pow(-3.0 * i / n).toFloat()
                ir[c][i] = v
                power += v.toDouble() * v
            }
            var p = sqrt(power / (2 * n)).toFloat()
            if (!p.isFinite() || p < MIN_POWER) p = MIN_POWER
            val scale = 1f / p * GAIN_CALIBRATION * (GAIN_CALIBRATION_RATE / sampleRate)
            for (c in 0..1) for (i in 0 until n) ir[c][i] *= scale
            return ir
        }

        /** Web Audio's lowpass / highpass Q is in dB: the RBJ Q it stands for. */
        private fun webQ(db: Double) = 10.0.pow(db / 20)

        private fun lowpass(k: FloatArray, hz: Double, q: Double, rate: Int) = rbj(k, hz, q, rate, high = false)

        private fun highpass(k: FloatArray, hz: Double, q: Double, rate: Int) = rbj(k, hz, q, rate, high = true)

        private fun rbj(k: FloatArray, hz: Double, q: Double, rate: Int, high: Boolean) {
            val w0 = 2 * PI * min(hz, rate * 0.45) / rate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            val b0 = if (high) (1 + cw) / 2 else (1 - cw) / 2
            val b1 = if (high) -(1 + cw) else 1 - cw
            k[0] = (b0 / a0).toFloat()
            k[1] = (b1 / a0).toFloat()
            k[2] = (b0 / a0).toFloat()
            k[3] = (-2 * cw / a0).toFloat()
            k[4] = ((1 - alpha) / a0).toFloat()
        }

        private fun biquad(k: FloatArray, z: FloatArray, channel: Int, x: Float): Float {
            val i = channel * 2
            val y = k[0] * x + z[i]
            z[i] = k[1] * x - k[3] * y + z[i + 1]
            z[i + 1] = k[2] * x - k[4] * y
            return y
        }
    }
}
