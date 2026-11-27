package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What both decks play, on one clock: mix seconds, 0 = the moment our deck takes the outgoing song
 * over from the player. Built from a plan the way the extension's crossfade.js schedules it
 * (rampOutgoing, scheduleDeckBlend/blendShape/djShape, glideToUnity, scheduleLoopMix). Pure
 * Kotlin; a port of the phone's Mix.java.
 */
class ForkV2Mix {
    var style: String? = null
    var outId: String? = null
    var inId: String? = null
    var note: String? = null
    var title: String? = null
    var sub: String? = null
    var v2 = false
    var plan: ForkV2Planner.Plan? = null

    /** The loop mix's plan (stats for nerds), or null for a blend. */
    var loopPlan: ForkV2Planner.LoopPlan? = null

    /** Outgoing song second at mix 0, and where the outgoing song ends (mix seconds at its own speed). */
    var takeoverSong = 0.0
    var outEnd = 0.0

    /** The blend (or loop mix) on the mix clock. */
    var t0 = 0.0
    var D = 0.0

    /** The player moves to the next song; its player takes over from the incoming deck no earlier than [elementAt]. */
    var askAt = 0.0
    var elementAt = 0.0
    val out = Deck()
    val `in` = Deck()

    /**
     * The echo's and the delay's seconds from the window's start (editor.md §12 rule 3, a preset's curves.fxTime), null
     * without an Effects lane: then the returns keep the times they had.
     */
    var fxTime: DoubleArray? = null

    /** Whether any deck sends to the effect returns or plays noise (editor.md §12): the players then run them. */
    val effects: Boolean
        get() = listOf(out, `in`).any { d -> listOf(d.echoSend, d.reverbSend, d.delaySend, d.noise).any { it.moves() } }

    /** The mix's beats on the mix clock (the seek bar pulses on them); null: no beat (a crossfade). */
    var beats: DoubleArray? = null

    /** Where the mix's pulse ends (the decks hand over to the player). */
    fun pulseEnd(): Double = if ("loop" == style) elementAt else t0 + D

    /** One automation lane, like a Web Audio AudioParam's events: set / linear ramp / exponential ramp. */
    class Lane internal constructor(internal val rest: Double) {
        /** {time, value, kind}: 0 set, 1 linear, 2 exponential */
        internal val pts: MutableList<DoubleArray> = ArrayList()

        internal fun set(v: Double, t: Double): Lane {
            pts.add(doubleArrayOf(t, v, 0.0))
            return this
        }

        internal fun lin(v: Double, t: Double): Lane {
            pts.add(doubleArrayOf(t, v, 1.0))
            return this
        }

        internal fun exp(v: Double, t: Double): Lane {
            pts.add(doubleArrayOf(t, v, 2.0))
            return this
        }

        /** setValueCurveAtTime: the values evenly over [t, t + d], linearly between them. */
        internal fun curve(c: FloatArray, t: Double, d: Double): Lane {
            pts.add(doubleArrayOf(t, c[0].toDouble(), 0.0))
            for (i in 1 until c.size) pts.add(doubleArrayOf(t + d * i / (c.size - 1), c[i].toDouble(), 1.0))
            return this
        }

        fun empty(): Boolean = pts.isEmpty()

        /** Whether it ever leaves its rest value. */
        fun moves(): Boolean = pts.any { it[1] != rest }

        fun at(t: Double): Double {
            if (pts.isEmpty()) return rest
            var prevT = Double.NEGATIVE_INFINITY
            var prevV = rest
            for (p in pts) {
                if (t < p[0]) {
                    if (p[2] == 0.0 || prevT == Double.NEGATIVE_INFINITY) return prevV
                    val f = (t - prevT) / (p[0] - prevT)
                    if (p[2] == 2.0 && prevV > 0 && p[1] > 0) return prevV * (p[1] / prevV).pow(f)
                    return prevV + (p[1] - prevV) * f
                }
                prevT = p[0]
                prevV = p[1]
            }
            return prevV
        }
    }

    class Deck {
        /** Beat knots: mix seconds, and the (unwrapped) song second heard then. */
        var knotT = DoubleArray(0)
        var knotP = DoubleArray(0)

        /** A loop [loopA, loopB) song seconds, let go at unwrapped second [release] (then on from loopB). */
        var loopA = 0.0
        var loopB = 0.0
        var release = 1e18

        /**
         * B's late entry (mix-file.md rule 2): before this mix second the deck is silent and its player held, then it
         * starts at its first knot. Without one, never.
         */
        var startAt = Double.NEGATIVE_INFINITY

        /** After the last knot: on at rate 1 (the incoming song), or stop. */
        var runOn = false
        var stopAt = 1e9
        val gain = Lane(0.0)
        val lowDb = Lane(0.0)
        val highDb = Lane(0.0)
        val hpHz = Lane(20.0)
        val lpHz = Lane(20000.0)

        /** editor.md §12: the 1 kHz band (dB), the noise (0..1) and the sends to the three returns (0..1); at rest 0. */
        val midDb = Lane(0.0)
        val noise = Lane(0.0)
        val echoSend = Lane(0.0)
        val reverbSend = Lane(0.0)
        val delaySend = Lane(0.0)

        /** Song seconds of decoded audio the deck needs. */
        var needFrom = 0.0
        var needTo = 0.0

        /** The unwrapped song second at mix second [t]. */
        fun unwrappedAt(t: Double): Double {
            val n = knotT.size
            if (t <= knotT[0]) return knotP[0] + (t - knotT[0]) * rate(0)
            if (t >= knotT[n - 1]) return knotP[n - 1] + (if (runOn) t - knotT[n - 1] else 0.0)
            var i = 1
            while (knotT[i] < t) i++
            val f = (t - knotT[i - 1]) / (knotT[i] - knotT[i - 1])
            return knotP[i - 1] + (knotP[i] - knotP[i - 1]) * f
        }

        private fun rate(seg: Int): Double {
            val dt = knotT[seg + 1] - knotT[seg]
            // A zero-length segment (a 0 s hand-over or fallback: the blend's two knots on one time) is 0/0: NaN, which
            // made the cue, the incoming seconds and the hand-over time NaN. It runs at the next segment's rate.
            if (!(dt > 0)) return if (seg + 2 < knotT.size) rate(seg + 1) else 1.0
            return (knotP[seg + 1] - knotP[seg]) / dt
        }

        /** The song second heard at mix second [t]: the loop and its release applied. */
        fun trackAt(t: Double): Double {
            val u = unwrappedAt(t)
            val len = loopB - loopA
            if (len > 0 && u >= loopB) return if (u < release) loopA + (u - loopA) % len else loopB + (u - release)
            return u
        }

        /** Song seconds per mix second at [t]. */
        fun rateAt(t: Double): Double {
            val n = knotT.size
            if (t >= knotT[n - 1]) return if (runOn) 1.0 else 0.0
            if (t < knotT[0]) return rate(0)
            var i = 1
            while (knotT[i] <= t) i++
            return rate(i - 1)
        }

        internal fun knots(k: List<DoubleArray>) {
            knotT = DoubleArray(k.size) { k[it][0] }
            knotP = DoubleArray(k.size) { k[it][1] }
        }
    }

    companion object {
        /** The old song moves to our deck this long before its blend (its speed-up included). */
        const val HAND_LEAD = 4.0

        /** The incoming deck's glide back to x1.0 after a blend: this much speed every [GLIDE_STEP_SEC]. */
        internal const val GLIDE_STEP = 0.0025
        internal const val GLIDE_STEP_SEC = 0.25

        /** The player is asked for the next song this long before the deck needs its player. */
        const val NEXT_LEAD = 2.5
        const val H = 0.025

        internal const val PULSE_REST = 0.1
        internal const val BREATH_SEC = 1.8

        /**
         * The seek bar's brightness at mix second t (crossfade.js stepPulse): full on each beat,
         * (0.5 + 0.5 cos 2πφ)² between, never under PULSE_REST; a slow 1.8 s breath without beats.
         */
        fun pulse(beats: DoubleArray?, t: Double): Double {
            val phase: Double
            if (beats != null && beats.size > 1 && t >= beats[0] && t < beats[beats.size - 1]) {
                var k = binarySearch(beats, t)
                if (k < 0) k = -k - 2
                phase = (t - beats[k]) / (beats[k + 1] - beats[k])
            } else {
                val b = if (beats != null && beats.size > 1) beats[beats.size - 1] - beats[beats.size - 2] else BREATH_SEC
                val from = if (beats != null && beats.isNotEmpty()) beats[beats.size - 1] else 0.0
                phase = (((t - from) % b) + b) % b / b
            }
            val shape = 0.5 + 0.5 * cos(2 * PI * phase)
            return PULSE_REST + (1 - PULSE_REST) * shape * shape
        }

        /** java.util.Arrays.binarySearch(double[], key): the index, or -(insertion point) - 1. */
        private fun binarySearch(a: DoubleArray, key: Double): Int {
            var lo = 0
            var hi = a.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = a[mid].compareTo(key)
                if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
            }
            return -(lo + 1)
        }

        // ---- the blend's shapes ----

        internal const val EP = 65

        /** B's rise at a late entry (ForkMixShelf.LATE_RISE_SEC, crossfade.js LATE_RAMP), for djShape's trim. */
        internal const val LATE_RISE_SEC = 0.01

        internal fun ep(isIn: Boolean): FloatArray {
            val c = FloatArray(EP)
            for (i in 0 until EP) {
                val a = (i / (EP - 1).toDouble()) * PI / 2
                c[i] = (if (isIn) sin(a) else cos(a)).toFloat()
            }
            return c
        }

        internal fun clamp(x: Double, a: Double, b: Double): Double = max(a, min(b, x))

        internal fun glide(a: Double, b: Double, x: Double): Double = a * (b / a).pow(clamp(x, 0.0, 1.0))

        /** crossfade.js djShape: AutoDJ's fader and filter curves (gIn, gOut, inHp, outHp, outLp). */
        internal fun djShape(style: String, clashIn: Double, D: Double, bpm: Double): Array<FloatArray> =
            djShape(style, clashIn, D, bpm, max(EP.toDouble(), min(4000.0, ceil(D * 40))).toInt())

        /** A plan's shape over [n] points: Automix v2's ladder shapes (drop-in, muffle) or djShape at its bass swap. */
        internal fun djShape(m: ForkV2Planner.Plan, D: Double, n: Int): Array<FloatArray> {
            if ("blend" == m.style && ("dropin" == m.shape || "muffle" == m.shape || "echo" == m.shape)) return ladderShape(m, D, n)
            return djShape(m.style, m.clash, D, m.bpm, m.swapAt, n, if (m.inDelaySec > 0 && D > 0) m.inDelaySec else 0.0)
        }

        internal fun djShape(m: ForkV2Planner.Plan, D: Double): Array<FloatArray> =
            djShape(m, D, max(EP.toDouble(), min(4000.0, ceil(D * 40))).toInt())

        /** The same over [n] points (the plan's curves sample it more coarsely than the engine plays it). */
        internal fun djShape(style: String, clashIn: Double, D: Double, bpm: Double, n: Int): Array<FloatArray> =
            djShape(style, clashIn, D, bpm, Double.NaN, n)

        /**
         * Automix v2's fallback ladder shapes (automix.js ladderShape). Drop-in: the next song at full
         * level on its first beat (20 ms), its bass with it; the playing song closes and fades over the
         * two bars. Muffle: the playing song holds its level while its low-pass closes (18 kHz to 300 Hz)
         * over the lead before the bass swap; the next fades in over the same bars, takes the bass on
         * the swap, and the playing song is gone two beats after it.
         */
        internal fun ladderShape(m: ForkV2Planner.Plan, D: Double, n: Int): Array<FloatArray> {
            val S = PI / 2
            val beats = max(1, (if (m.bars > 0) m.bars else 1) * 4)
            val gIn = FloatArray(n)
            val gOut = FloatArray(n)
            val inHp = FloatArray(n)
            val outHp = FloatArray(n)
            val outLp = FloatArray(n)
            val dropin = "dropin" == m.shape
            if ("echo" == m.shape) {
                // echo out: the playing song's dry sound cut on the catch (10 ms), the next song in at full
                // level with it; the echo (the outgoing deck's delay line) rings on over it
                val c = min((m.echo?.cutSec ?: ForkV2Planner.ECHO_CUT_SEC) / D, 0.05)
                for (i in 0 until n) {
                    val p = i / (n - 1).toDouble()
                    gIn[i] = clamp(p / c, 0.0, 1.0).toFloat()
                    gOut[i] = (1 - clamp(p / c, 0.0, 1.0)).toFloat()
                    outLp[i] = 20000f
                    outHp[i] = 20f
                    inHp[i] = 20f
                }
                return arrayOf(gIn, gOut, inHp, outHp, outLp)
            }
            val e = min(0.02 / D, 0.05)
            val at = if (m.swapAt > 0 && m.swapAt < 1) m.swapAt else 0.5
            val from = max(0.0, at - (if (m.muffleLead > 0) m.muffleLead else 0.25))
            val exit = min(1.0, at + ForkV2Planner.MUFFLE_EXIT_BEATS / beats.toDouble())
            val w = 0.5 / beats
            for (i in 0 until n) {
                val p = i / (n - 1).toDouble()
                if (dropin) {
                    gIn[i] = clamp(p / e, 0.0, 1.0).toFloat()
                    gOut[i] = cos(p * S).toFloat()
                    outLp[i] = glide(18000.0, 2000.0, p).toFloat()
                    outHp[i] = glide(20.0, 200.0, clamp(p / e, 0.0, 1.0)).toFloat()
                    inHp[i] = 20f
                } else {
                    val h = clamp((p - at) / w, 0.0, 1.0)
                    gOut[i] = (if (p <= at) 1.0 else cos(clamp((p - at) / (exit - at), 0.0, 1.0) * S)).toFloat()
                    gIn[i] = sin(clamp((p - from) / (at - from), 0.0, 1.0) * S).toFloat()
                    outLp[i] = glide(18000.0, 300.0, (p - from) / (at - from)).toFloat()
                    outHp[i] = glide(20.0, 200.0, h).toFloat()
                    inHp[i] = glide(20.0, 200.0, 1 - h).toFloat()
                }
            }
            return arrayOf(gIn, gOut, inHp, outHp, outLp)
        }

        /** djShape with the bass swap at [swapAtIn] (0..1; else on the bar nearest the middle). */
        internal fun djShape(style: String, clashIn: Double, D: Double, bpm: Double, swapAtIn: Double, n: Int, lateSec: Double = 0.0): Array<FloatArray> {
            val clash = clamp(clashIn, 0.0, 1.0)
            // The equal-power trim is worked out from B's heard rise (mix-file.md rule 2, "A while B is held", extension
            // 43578ed): with a late entry ([lateSec], the plan's inDelaySec) B is silent until then and reaches its curve in
            // 10 ms, so A isn't trimmed while it plays alone and its trim follows B in; the curves' shapes are unchanged
            val heard = { p: Double -> if (!(lateSec > 0)) 1.0 else clamp((p * D - lateSec) / LATE_RISE_SEC, 0.0, 1.0) }
            val beat = if (bpm > 0) 60 / bpm else 0.0
            val grid = if (beat > 0) D / beat else 0.0
            val onBar = { f: Double -> if (grid >= 8) clamp(jround((f * grid) / 4) * 4 / grid, 4 / grid, 1 - 4 / grid) else f }
            val w = if (grid >= 2) 0.5 / grid else 0.2
            val swapAt = if (swapAtIn > 0 && swapAtIn < 1) swapAtIn else onBar(0.5)
            val h = { p: Double -> clamp((p - (swapAt - w)) / w, 0.0, 1.0) }
            val S = PI / 2
            val gIn = FloatArray(n)
            val gOut = FloatArray(n)
            val inHp = FloatArray(n)
            val outHp = FloatArray(n)
            val outLp = FloatArray(n)
            val filter = style == "filter"
            val top = if (filter) glide(1200.0, 2400.0, clash) else glide(520.0, 950.0, clash)
            val openBy = onBar(if (filter) 0.6 + 0.18 * clash else 0.45 + 0.25 * clash)
            val from = onBar(0.3 - 0.18 * clash)
            val floor = glide(2200.0, 1100.0, clash)
            val fallFrom = max(0.0, min(min(swapAt, 0.5), if (grid != 0.0) 1 - 4 / grid else 0.5))
            for (i in 0 until n) {
                val p = i / (n - 1).toDouble()
                val hp = h(p)
                val entry = if (p >= openBy) 20.0 else glide(20.0, top, (1 - p / openBy).pow(0.35))
                outHp[i] = glide(20.0, 200.0, hp).toFloat()
                var r: Double
                var f: Double
                if (filter) {
                    outLp[i] = glide(7000.0, 300.0, p.pow(0.75)).toFloat()
                    inHp[i] = max(entry, glide(20.0, 200.0, 1 - hp)).toFloat()
                    r = sin(p.pow(0.8) * S)
                    f = cos(p.pow(1.6) * S)
                } else {
                    outLp[i] = glide(20000.0, floor, (p - from) / (1 - from)).toFloat()
                    inHp[i] = max(glide(20.0, 200.0, 1 - hp), entry).toFloat()
                    val rp = sin(p * S)
                    val rd = sin(clamp(p / swapAt, 0.0, 1.0) * S)
                    val fp = cos(p * S)
                    val fd = if (p <= fallFrom) 1.0 else cos(((p - fallFrom) / (1 - fallFrom)) * S)
                    r = rp + (rd - rp) * (1 - clash)
                    f = fp + (fd - fp) * (1 - clash)
                }
                r += (sin(p.pow(1.65) * S) - r) * clash
                f += (cos(p.pow(0.65) * S) - f) * clash
                val rh = r * heard(p)
                val trim = if (rh + f <= 1) 1.0 else max(0.5, 1 / kotlin.math.sqrt(rh + f))
                gIn[i] = (r * trim).toFloat()
                gOut[i] = (f * trim).toFloat()
            }
            return arrayOf(gIn, gOut, inHp, outHp, outLp)
        }

        // ---- building ----

        /**
         * A blend, filter mix or crossfade (not a fallback onto the player's own crossfade).
         * [outDuration]: the playing song's length as the player reports it.
         */
        fun blend(p: ForkV2Planner.Plan, outId: String?, inId: String?, outDuration: Double): ForkV2Mix {
            val m = ForkV2Mix()
            m.plan = p
            m.style = p.style
            m.v2 = p.v2 && p.style != "crossfade"
            m.outId = outId
            m.inId = inId
            val R = if (p.outRate > 0) p.outRate else 1.0
            val b = p.beatSec
            val mixOut = p.mixOutTrackTime
            val rate = p.rate
            val n = if (p.style == "blend") p.rampBeats else 0
            val rampSong = n * (if (b > 0) b else 0.0)
            m.takeoverSong = mixOut - HAND_LEAD - rampSong
            // outgoing: rate 1 to the ramp, one 0.75% step per beat up to R (rampOutgoing), R through the blend
            val ok = ArrayList<DoubleArray>()
            ok.add(doubleArrayOf(0.0, m.takeoverSong))
            var t = HAND_LEAD
            var pos = mixOut - rampSong
            ok.add(doubleArrayOf(t, pos))
            for (k in 1..n) {
                val r = 1 + ((R - 1) * k) / n
                t += b / r
                pos += b
                ok.add(doubleArrayOf(t, pos))
            }
            m.t0 = t
            m.D = p.durationSec
            val pg = p.glide
            val G = if (p.style == "blend" && pg != null) ForkV2Planner.glideSteps(pg.q, pg.beats, pg.beatOutSec) else null
            if (G != null && pg != null) {
                // Automix v2's glide (crossfade.js glideOutgoing): from the mix-out, one rate per beat,
                // the last held after the blend while the playing song fades
                val B = G.outRate.size
                val gb = pg.beatOutSec
                val last = G.outRate[B - 1]
                val beats = DoubleArray(B + 1)
                for (k in 0 until B) {
                    if (k > 0) ok.add(doubleArrayOf(m.t0 + G.at[k], mixOut + k * gb)) // (k = 0: the mix-out, added above)
                    beats[k] = m.t0 + G.at[k]
                }
                beats[B] = m.t0 + G.total
                m.beats = beats
                ok.add(doubleArrayOf(m.t0 + G.total, mixOut + B * gb))
                ok.add(doubleArrayOf(m.t0 + G.total + 1, mixOut + B * gb + last))
                m.out.knots(ok)
                m.out.stopAt = m.t0 + m.D + 0.2
                m.out.needFrom = m.takeoverSong - 1
                m.out.needTo = mixOut + B * gb + last + 1
                m.outEnd = m.t0 + G.total + max(0.0, outDuration - (mixOut + B * gb)) / last
                // the next song (deckGlideIn): one rate per beat, ending at x1.0 with the blend
                val ik = ArrayList<DoubleArray>()
                val mixIn = p.mixInTrackTime
                for (k in 0 until B) ik.add(doubleArrayOf(m.t0 + G.at[k], mixIn + k * G.beatInSec))
                ik.add(doubleArrayOf(m.t0 + G.total, mixIn + B * G.beatInSec))
                ik.add(doubleArrayOf(m.t0 + G.total + 0.5, mixIn + B * G.beatInSec + 0.5))
                m.`in`.knots(ik)
                m.`in`.runOn = true
                m.elementAt = m.t0 + G.total + 1
                m.`in`.needFrom = mixIn - 1
                m.`in`.needTo = m.`in`.trackAt(m.elementAt) + 30
                m.askAt = min(min(m.t0 + m.D / 2, m.t0 + m.D - 1.5 - NEXT_LEAD), m.outEnd - 1.5)
                shape(m, p, rate)
                return m
            }
            if (b > 0) {
                // the ramp's beats as laid out above, then the blend's at the outgoing song's speed
                val bt = ArrayList<Double>()
                for (k in 1 until ok.size) bt.add(ok[k][0])
                var x = m.t0 + b / R
                while (x <= m.t0 + m.D + 1e-6) {
                    bt.add(x)
                    x += b / R
                }
                m.beats = DoubleArray(bt.size) { bt[it] }
            }
            ok.add(doubleArrayOf(m.t0 + m.D + 1, mixOut + (m.D + 1) * R))
            m.out.knots(ok)
            m.out.stopAt = m.t0 + m.D + 0.2
            m.out.needFrom = m.takeoverSong - 1
            m.out.needTo = mixOut + (m.D + 1) * R + 1
            m.outEnd = m.t0 + (outDuration - mixOut) / R

            // incoming: [rate] through the blend, then back to its own tempo on our deck, 0.25% every
            // 0.25 s (the extension's deckGlideBack, and the plan's curves): a smooth glide, not steps
            val mixIn = p.mixInTrackTime
            val ik = ArrayList<DoubleArray>()
            // (B's late entry, mix-file.md rule 2: the deck's first point is that long into the mix, B silent and
            // held until then; the curves below still run from t0)
            val late = p.inDelaySec
            if (late > 0) {
                ik.add(doubleArrayOf(m.t0 + late, mixIn))
                m.`in`.startAt = m.t0 + late
            } else {
                ik.add(doubleArrayOf(m.t0, mixIn))
            }
            var it = m.t0 + m.D
            var ip = if (late > 0) mixIn + (m.D - late) * rate else mixIn + m.D * rate
            ik.add(doubleArrayOf(it, ip))
            // (a song at its playlist speed glides back to it and stays there, not x1: spotify-mixes.md rule 10)
            val to = if (ForkSongSpeed.off1(p.speedIn)) p.speedIn else 1.0
            if (kotlin.math.abs(rate - to) < 0.002) {
                ik.add(doubleArrayOf(it + 0.5, ip + 0.5 * rate))
                it += 0.5
            } else {
                val steps = ceil(kotlin.math.abs(rate - to) / GLIDE_STEP).toInt()
                var r = rate
                for (i in 1..steps) {
                    it += GLIDE_STEP_SEC
                    ip += GLIDE_STEP_SEC * r
                    ik.add(doubleArrayOf(it, ip))
                    r = if (i == steps) to else rate + (to - rate) * i / steps.toDouble()
                }
                // ...and on at its own speed (x1.0, or the song's) to the takeover
                ik.add(doubleArrayOf(it + 0.5, ip + 0.5 * to))
                it += 0.5
            }
            m.`in`.knots(ik)
            m.`in`.runOn = true
            m.elementAt = it + 0.5
            m.`in`.needFrom = mixIn - 1
            m.`in`.needTo = m.`in`.trackAt(m.elementAt) + 30
            m.askAt = min(min(m.t0 + m.D / 2, m.t0 + m.D - 1.5 - NEXT_LEAD), m.outEnd - 1.5)
            shape(m, p, rate)
            return m
        }

        /** The blend's gains, EQ and filters, and its names (scheduleDeckBlend, blendShape). */
        private fun shape(m: ForkV2Mix, p: ForkV2Planner.Plan, rate: Double) {
            if (p.preset != null && p.curves != null) {
                preset(m, p)
            } else {
                planned(m, p)
            }
            val name = if (p.v3) "Automix v2" else if (m.v2) "Automix Classic" else "Automix"
            val kind = if ("dropin" == p.shape) " · drop-in" else if ("muffle" == p.shape) " · muffle" else if ("echo" == p.shape) " · echo out" else if (p.glide != null) " · glide" else ""
            m.title = if (p.style == "blend") name + kind else if (p.style == "filter") "$name · filter mix"
            else if (p.handoff) "Automix v2 · hand-over" else if (p.bridge) "Automix v2 · beatless bridge" else "Automix · crossfade"
            val g = p.glide
            m.sub = if (p.style == "crossfade") forkV2Format("%.1f s%s", m.D, if (p.reason != null) " · " + p.reason else "")
            else forkV2Format(
                "%d bars · %.1f s · %d BPM%s", p.bars, m.D, jround(p.bpm),
                if (g != null) forkV2Format(" · glide ×%.3f", g.q) else if (p.style == "blend" && kotlin.math.abs(rate - 1) > 0.002) forkV2Format(" · ×%.3f", rate) else "",
            )
            m.note = p.toString() + (if (p.kick != null) "; " + p.kick else "")
        }

        /** The planner's own fader, EQ and filters (scheduleDeckBlend, blendShape). */
        private fun planned(m: ForkV2Mix, p: ForkV2Planner.Plan) {
            // gains: AutoDJ's fader for a version 2 blend or filter mix, else equal power; Automix v2's loudness trim on either
            val dj = if (m.v2) djShape(p, m.D) else null
            val g = ForkV2Curves.trimmed(p, dj?.get(0) ?: ep(true), dj?.get(1) ?: ep(false))
            val gIn = g[0]
            val gOut = g[1]
            m.out.gain.set(0.0, -H).lin(1.0, 0.0).set(1.0, m.t0).curve(gOut, m.t0, m.D).set(0.0, m.t0 + m.D)
            m.`in`.gain.set(0.0, 0.0).curve(gIn, m.t0, m.D).set(1.0, m.t0 + m.D)

            // the EQ and filters (blendShape)
            val mid = m.t0 + m.D / 2
            val bar = min(p.barSec, m.D / 2)
            if (dj != null) {
                m.`in`.hpHz.set(dj[2][0].toDouble(), 0.0).curve(dj[2], m.t0, m.D)
                val ease = max(0.01, m.t0 - 0.03)
                m.out.lpHz.set(20000.0, ease).lin(dj[4][0].toDouble(), m.t0 - 0.001).curve(dj[4], m.t0, m.D)
                m.out.hpHz.set(20.0, ease).lin(dj[3][0].toDouble(), m.t0 - 0.001).curve(dj[3], m.t0, m.D)
            } else if (p.style == "blend" || p.style == "filter") {
                m.`in`.lowDb.set(p.swapDb, 0.0).set(p.swapDb, mid - bar / 2).lin(0.0, mid + bar / 2)
                m.out.lowDb.set(0.0, mid - bar / 2).lin(p.swapDb, mid + bar / 2)
                if (p.style == "blend") {
                    m.out.highDb.set(0.0, m.t0).lin(-8.0, m.t0 + m.D)
                    m.`in`.highDb.set(-8.0, 0.0).set(-8.0, m.t0).lin(0.0, m.t0 + m.D)
                } else {
                    m.out.hpHz.set(20.0, m.t0).exp(1800.0, m.t0 + m.D)
                    m.`in`.lpHz.set(350.0, 0.0).set(350.0, m.t0).exp(20000.0, m.t0 + m.D)
                }
            }
        }

        /**
         * The editor's preset (editor.md §9): the plan's own curves play as points (linear; Hz
         * exponential; two points at one t a step), the loudness trim on the faders (crossfade.js
         * presetGains, presetShape). A curve the preset leaves out stays neutral.
         */
        private fun preset(m: ForkV2Mix, p: ForkV2Planner.Plan) {
            val c = p.curves!!
            val t = p.trim
            val trim = t != null && (t.inDb != 0.0 || t.outDb != 0.0)
            val gi = if (trim) 10.0.pow(t!!.inDb / 20) else 1.0
            val go = if (trim) 10.0.pow(t!!.outDb / 20) else 1.0
            // the curves are over their own window (P); the times fit to the seconds the mix has (m.D)
            val P = if (c.durationSec > 0) c.durationSec else m.D
            m.out.gain.set(0.0, -H).lin(1.0, 0.0).set(1.0, m.t0)
            play(m.out.gain, c.out["gain"], m.t0, P, m.D, false, if (trim) { x -> 1 + (go - 1) * x * x * (3 - 2 * x) } else null)
            m.out.gain.set(0.0, m.t0 + m.D)
            m.`in`.gain.set(0.0, 0.0)
            play(m.`in`.gain, c.`in`["gain"], m.t0, P, m.D, false, if (trim) { x -> gi + (1 - gi) * x * x * (3 - 2 * x) } else null)
            m.`in`.gain.set(1.0, m.t0 + m.D)
            play(m.out.lowDb, c.out["bassDb"], m.t0, P, m.D, false, null)
            play(m.`in`.lowDb, c.`in`["bassDb"], m.t0, P, m.D, false, null)
            play(m.out.highDb, c.out["highDb"], m.t0, P, m.D, false, null)
            play(m.`in`.highDb, c.`in`["highDb"], m.t0, P, m.D, false, null)
            play(m.out.hpHz, c.out["highpassHz"], m.t0, P, m.D, true, null)
            play(m.`in`.hpHz, c.`in`["highpassHz"], m.t0, P, m.D, true, null)
            play(m.out.lpHz, c.out["lowpassHz"], m.t0, P, m.D, true, null)
            play(m.`in`.lpHz, c.`in`["lowpassHz"], m.t0, P, m.D, true, null)
            // editor.md §12: the mid band, and the noise and the sends, which are 0 again at the window's end
            // (crossfade.js presetShape: the returns ring on by themselves)
            play(m.out.midDb, c.out["midDb"], m.t0, P, m.D, false, null)
            play(m.`in`.midDb, c.`in`["midDb"], m.t0, P, m.D, false, null)
            for (d in listOf(true, false)) {
                val deck = if (d) m.out else m.`in`
                val side = if (d) c.out else c.`in`
                for ((lane, key) in listOf(deck.noise to "noise", deck.echoSend to "echoSend", deck.reverbSend to "reverbSend", deck.delaySend to "delaySend")) {
                    val pts = side[key] ?: continue
                    if (pts.none { it[1] > 0 }) continue
                    play(lane, pts, m.t0, P, m.D, false, null)
                    lane.set(0.0, m.t0 + m.D + 0.002)
                }
            }
            m.fxTime = c.fxTime
        }

        /**
         * A curve's points onto a lane from t0, [mul] scaling them by the window's fraction (sampled in 32
         * steps too). [D]: the curves' window; [Dplay]: the seconds the mix really has, the times fit to it
         * (crossfade.js curveEvents).
         */
        internal fun play(lane: Lane, pts: Array<DoubleArray>?, t0: Double, D: Double, Dplay: Double, geo: Boolean, mul: ((Double) -> Double)?) {
            if (pts == null || pts.isEmpty()) return
            val ts = HashSet<Double>()
            for (q in pts) ts.add(jround(q[0] * 1e6) / 1e6)
            if (mul != null) for (k in 0..32) ts.add(jround(D * k / 32 * 1e6) / 1e6)
            // editor.md §3: a curve holds its first value before its first point (overviewValue answers pts[0] there).
            // Without this a drawing starting past 0 played the lane's rest (flat, open) until it, then stepped.
            ts.add(0.0)
            val fit = if (Dplay > 0 && D > 0) Dplay / D else 1.0
            var first = true
            for (tt in ts.sorted()) {
                val k = if (mul != null) mul(clamp(tt / D, 0.0, 1.0)) else 1.0
                var n = 0
                var v0 = 0.0
                var v1 = 0.0
                for (q in pts) {
                    if (kotlin.math.abs(q[0] - tt) < 1e-6) {
                        if (n == 0) v0 = q[1]
                        v1 = q[1]
                        n++
                    }
                }
                if (n == 0) {
                    v0 = overviewValue(pts, tt, 0.0)
                    v1 = v0
                }
                if (first) {
                    lane.set(v0 * k, t0 + tt * fit)
                    first = false
                } else if (geo && v0 > 0) {
                    lane.exp(v0 * k, t0 + tt * fit)
                } else {
                    lane.lin(v0 * k, t0 + tt * fit)
                }
                if (n > 1) lane.set(v1 * k, t0 + tt * fit)
            }
        }

        /** A curve's value at t, linear between its points (the phone's Overview.value, which [play] reads). */
        internal fun overviewValue(pts: Array<DoubleArray>?, t: Double, neutral: Double): Double {
            if (pts == null || pts.isEmpty()) return neutral
            if (t <= pts[0][0]) return pts[0][1]
            for (i in 1 until pts.size) {
                if (t <= pts[i][0]) {
                    val a = pts[i - 1][0]
                    val b = pts[i][0]
                    return pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * ((t - a) / max(1e-6, b - a))
                }
            }
            return pts[pts.size - 1][1]
        }

        /** The loop mix, its clock from the outgoing loop's start (scheduleLoopMix). */
        fun loop(lp: ForkV2Planner.LoopPlan, outId: String?, inId: String?, outDuration: Double): ForkV2Mix {
            val x = ForkV2Planner.loopSchedule(lp, 0.0)
            val lo = lp.out!!
            val li = lp.`in`!!
            val m = ForkV2Mix()
            m.style = "loop"
            m.loopPlan = lp
            m.outId = outId
            m.inId = inId
            m.takeoverSong = lo.loopStart
            m.t0 = 0.0
            m.D = x.elementAt
            val beats = x.beats
            val ok = ArrayList<DoubleArray>()
            val ik = ArrayList<DoubleArray>()
            for (k in 0..beats) {
                ok.add(doubleArrayOf(x.beatTimes[k], lo.loopStart + k * lo.beatSec))
                ik.add(doubleArrayOf(x.beatTimes[k], li.loopStart + k * lp.fold * li.beatSec))
            }
            m.out.knots(ok)
            m.out.loopA = lo.loopStart
            m.out.loopB = lo.loopEnd
            m.out.stopAt = x.releaseAt + 0.05
            m.out.needFrom = lo.loopStart - 1
            m.out.needTo = lo.loopEnd + 1
            m.`in`.knots(ik)
            m.beats = x.beatTimes.copyOf() // the ramp and the settle bars, up to the handover
            m.`in`.loopA = li.loopStart
            m.`in`.loopB = li.loopEnd
            m.`in`.release = ik[beats][1]
            m.`in`.runOn = true
            m.`in`.needFrom = li.loopStart - 1
            m.`in`.needTo = x.elementTrackTime + 30
            m.elementAt = x.elementAt
            m.outEnd = outDuration - lo.loopStart
            m.askAt = min(min(x.handoffAt, x.elementAt - 1.5 - NEXT_LEAD), m.outEnd - 1.5)

            val cut = ForkV2Planner.BASS_CUT_DB
            m.out.gain.set(0.0, -H).lin(1.0, 0.0).set(1.0, x.enterEnd).lin(0.6, x.rampEnd).lin(0.0, x.releaseAt)
            m.`in`.gain.set(0.0, 0.0).lin(0.55, x.enterEnd).lin(1.0, x.rampEnd)
            m.`in`.lowDb.set(cut, 0.0).set(cut, x.mid - x.bar / 2).lin(0.0, x.mid + x.bar / 2)
            m.out.lowDb.set(0.0, x.mid - x.bar / 2).lin(cut, x.mid + x.bar / 2)
            m.out.hpHz.set(20.0, x.rampEnd).exp(1500.0, x.releaseAt)

            m.title = "Automix · loop mix"
            m.sub = forkV2Format("%d-bar loop · %d→%d BPM · %.1f s", lo.bars, jround(lo.bpm), jround(li.bpm), m.D)
            m.note = lp.toString()
            return m
        }
    }
}
