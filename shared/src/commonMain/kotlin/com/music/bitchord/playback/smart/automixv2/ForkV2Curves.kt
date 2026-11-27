package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What each deck does over a transition: spec/automix/plan.schema.json "curves", a port of the
 * extension's automix.js blendCurves and loopCurves (checked against its golden plans by
 * ForkV2SpecFixturesTest). The overview draws these without knowing how the mix was planned.
 * A port of the phone's Curves.java.
 *
 * Each curve is a list of [t, v] points, t in seconds from the transition's start (0: the
 * mix-out, or the outgoing loop's start; negative: the outgoing speed-up before a blend). A value
 * holds before the first point and after the last, runs linear between points (Hz: geometric),
 * and two points at one t are a step. An absent curve stays neutral.
 */
class ForkV2Curves {
    var durationSec = 0.0

    /**
     * Curve name (gain, lowpassHz, highpassHz, bassDb, midDb, highDb, noise, echoSend, reverbSend, delaySend, rate) → its
     * [t, v] points.
     */
    val out: MutableMap<String, Array<DoubleArray>> = LinkedHashMap()
    val `in`: MutableMap<String, Array<DoubleArray>> = LinkedHashMap()

    /** The echo's and the delay's seconds, set at the window's start ([echoSec, delaySec], plan curves.fxTime, editor.md §12); null without an Effects lane. */
    var fxTime: DoubleArray? = null

    /** The Looping lane's length in beats (plan curves.loopBeats, editor.md §13); 0 without one. */
    var loopBeats = 0

    companion object {
        /** A blend, filter mix or crossfade plan's curves (automix.js blendCurves). */
        internal fun blend(m: ForkV2Planner.Plan): ForkV2Curves {
            val c = ForkV2Curves()
            val D = m.durationSec
            val style = m.style
            c.durationSec = D
            // The songs' own speeds (spotify-mixes.md rule 10): A steady at its speed through a mix of any style, B's glide
            // back ending at its speed, not x1, and B at it throughout a filter mix or crossfade. These curves ignored them
            // (and the planner built them before it set the speeds), so a plan's tempo curves and dumps showed x1 where the
            // decks play the playlist's speed (extension L2; automix.js blendCurves). The players read rate/outRate, not these.
            val sIn = if (m.speedIn > 0) m.speedIn else 1.0
            val sOut = if (m.speedOut > 0) m.speedOut else 1.0
            if (m.v2 && style != "crossfade") {
                val s = ForkV2Mix.djShape(m, D, samples(D))
                val g = trimmed(m, s[0], s[1])
                c.out["gain"] = points(g[1], D)
                c.`in`["gain"] = points(g[0], D)
                c.out["lowpassHz"] = points(s[4], D)
                c.out["highpassHz"] = points(s[3], D)
                c.`in`["highpassHz"] = points(s[2], D)
            } else {
                val g = trimmed(m, equalPower(65, true), equalPower(65, false))
                c.out["gain"] = points(g[1], D)
                c.`in`["gain"] = points(g[0], D)
                if (style != "crossfade") {
                    // the bass swaps within one bar at the midpoint
                    val mid = D / 2
                    val bar = min(if (m.barSec.isNaN()) 0.0 else m.barSec, D / 2)
                    c.`in`["bassDb"] = arrayOf(doubleArrayOf(0.0, m.swapDb), doubleArrayOf(mid - bar / 2, m.swapDb), doubleArrayOf(mid + bar / 2, 0.0))
                    c.out["bassDb"] = arrayOf(doubleArrayOf(mid - bar / 2, 0.0), doubleArrayOf(mid + bar / 2, m.swapDb))
                    if (style == "blend") {
                        c.out["highDb"] = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(D, -8.0))
                        c.`in`["highDb"] = arrayOf(doubleArrayOf(0.0, -8.0), doubleArrayOf(D, 0.0))
                    } else {
                        c.out["highpassHz"] = arrayOf(doubleArrayOf(0.0, 20.0), doubleArrayOf(D, 1800.0))
                        c.`in`["lowpassHz"] = arrayOf(doubleArrayOf(0.0, 350.0), doubleArrayOf(D, 20000.0))
                    }
                }
            }
            // tempo: the playing song speeds up beat by beat before the mix-out (blend only)
            if (style == "blend" && m.outRate > 1.0001 && m.rampBeats > 0) {
                val R = m.outRate
                val b = m.beatSec
                val n = m.rampBeats
                var t = 0.0
                for (k in 1..n) t -= b / (1 + ((R - 1) * k) / n)
                val pts = ArrayList<DoubleArray>()
                pts.add(doubleArrayOf(t, 1.0))
                for (k in 1..n) {
                    val r = 1 + ((R - 1) * k) / n
                    val prev = pts[pts.size - 1][1]
                    pts.add(doubleArrayOf(t, prev))
                    pts.add(doubleArrayOf(t, r))
                    t += b / r
                }
                pts.add(doubleArrayOf(D, R))
                // repeated points dropped (the first step's [t, 1] twice)
                val kept = ArrayList<DoubleArray>()
                for (i in pts.indices) {
                    val p = pts[i]
                    if (i == 0 || p[0] != pts[i - 1][0] || p[1] != pts[i - 1][1]) kept.add(p)
                }
                c.out["rate"] = kept.toTypedArray()
            } else if (m.glide == null && abs((if (style == "blend") m.outRate else sOut) - 1) > 1e-4) {
                // (A at a steady speed through the mix: a blend's outRate, else its playlist speed)
                val R = if (style == "blend") m.outRate else sOut
                c.out["rate"] = arrayOf(doubleArrayOf(0.0, R), doubleArrayOf(D, R))
            }
            val rate = if (style == "blend") m.rate else 1.0
            val glide = m.glide
            if (style == "blend" && glide != null) {
                // Automix v2's glide: both songs step once per beat, the next one ends at x1.0
                val G = ForkV2Planner.glideSteps(glide.q, glide.beats, glide.beatOutSec)
                val o = rateSteps(G.outRate, G.at)
                val i = rateSteps(G.inRate, G.at)
                o.add(doubleArrayOf(D, G.outRate[G.outRate.size - 1]))
                i.add(doubleArrayOf(D, G.inRate[G.inRate.size - 1]))
                i.add(doubleArrayOf(D, sIn))
                c.out["rate"] = o.toTypedArray()
                c.`in`["rate"] = i.toTypedArray()
            } else if (style == "blend" && abs(rate - sIn) > 1e-9) {
                // (also at a rate of x1 when B has a speed of its own: extension 0ab9b80 L2)
                // the glide back to B's speed, timed as the player times it (ForkV2Mix: |rate − to| in GLIDE_STEPs; automix.js
                // glideBack). Written as |1 + rate − sIn − 1| it lost a step to rounding (1 + 1.05 − 1 is 1.0499…98).
                val back = abs(rate - sIn)
                if (back < 0.002) {
                    c.`in`["rate"] = arrayOf(doubleArrayOf(0.0, rate), doubleArrayOf(D, rate), doubleArrayOf(D, sIn))
                } else {
                    val sec = ceil(back / ForkV2Mix.GLIDE_STEP) * ForkV2Mix.GLIDE_STEP_SEC
                    c.`in`["rate"] = arrayOf(doubleArrayOf(0.0, rate), doubleArrayOf(D, rate), doubleArrayOf(D + sec, sIn))
                }
            } else if (abs(sIn - 1) > 1e-4) {
                c.`in`["rate"] = arrayOf(doubleArrayOf(0.0, sIn), doubleArrayOf(D, sIn)) // (B at its own speed throughout)
            }
            return c
        }

        private fun rateSteps(rates: DoubleArray, at: DoubleArray): MutableList<DoubleArray> {
            val pts = ArrayList<DoubleArray>()
            for (k in rates.indices) {
                if (k == 0) {
                    pts.add(doubleArrayOf(0.0, rates[0]))
                } else {
                    pts.add(doubleArrayOf(at[k], rates[k - 1]))
                    pts.add(doubleArrayOf(at[k], rates[k]))
                }
            }
            return pts
        }

        /** A blend's fader with its loudness trim: the playing song from 1 to its trim, the next from its trim to 1 (automix.js trimmed). */
        internal fun trimmed(m: ForkV2Planner.Plan, gIn: FloatArray, gOut: FloatArray): Array<FloatArray> {
            val t = m.trim
            if (t == null || (t.outDb == 0.0 && t.inDb == 0.0)) return arrayOf(gIn, gOut)
            val gi = 10.0.pow(t.inDb / 20)
            val go = 10.0.pow(t.outDb / 20)
            val inn = FloatArray(gIn.size)
            val out = FloatArray(gOut.size)
            for (i in inn.indices) {
                val x = if (inn.size > 1) i / (inn.size - 1).toDouble() else 1.0
                val ss = x * x * (3 - 2 * x)
                inn[i] = (gIn[i] * (gi + (1 - gi) * ss)).toFloat()
            }
            for (i in out.indices) {
                val x = if (out.size > 1) i / (out.size - 1).toDouble() else 1.0
                val ss = x * x * (3 - 2 * x)
                out[i] = (gOut[i] * (1 + (go - 1) * ss)).toFloat()
            }
            return arrayOf(inn, out)
        }

        /** A loop mix's curves, from its schedule (automix.js loopCurves). */
        internal fun loop(plan: ForkV2Planner.LoopPlan): ForkV2Curves {
            val x = ForkV2Planner.loopSchedule(plan, 0.0)
            val half = x.bar / 2
            val c = ForkV2Curves()
            c.durationSec = x.releaseAt
            // the baseline's (versions.md "The loop mix's baseline", 020f8a1): no trim
            c.out["gain"] = arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(x.enterEnd, 1.0), doubleArrayOf(x.rampEnd, 0.6), doubleArrayOf(x.releaseAt, 0.0))
            c.out["bassDb"] = arrayOf(doubleArrayOf(x.mid - half, 0.0), doubleArrayOf(x.mid + half, ForkV2Planner.BASS_CUT_DB))
            c.out["highpassHz"] = arrayOf(doubleArrayOf(x.rampEnd, 20.0), doubleArrayOf(x.releaseAt, 1500.0))
            val active = ArrayList<ForkV2Planner.Change>()
            for (ch in x.outChanges) if (ch.active) active.add(ch)
            c.out["rate"] = steps(active, x.releaseAt)
            c.`in`["gain"] = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(x.enterEnd, 0.55), doubleArrayOf(x.rampEnd, 1.0))
            c.`in`["bassDb"] = arrayOf(doubleArrayOf(0.0, ForkV2Planner.BASS_CUT_DB), doubleArrayOf(x.mid - half, ForkV2Planner.BASS_CUT_DB), doubleArrayOf(x.mid + half, 0.0))
            c.`in`["rate"] = steps(x.inChanges, x.releaseAt)
            return c
        }

        /** A deck's speed changes as steps, up to [until]. */
        private fun steps(changes: List<ForkV2Planner.Change>, until: Double): Array<DoubleArray> {
            val pts = ArrayList<DoubleArray>()
            for (ch in changes) {
                if (ch.output > until) break
                if (pts.isEmpty()) {
                    pts.add(doubleArrayOf(ch.output, ch.rate))
                } else {
                    val prev = pts[pts.size - 1][1]
                    if (abs(prev - ch.rate) > 1e-9) {
                        pts.add(doubleArrayOf(ch.output, prev))
                        pts.add(doubleArrayOf(ch.output, ch.rate))
                    }
                }
            }
            return pts.toTypedArray()
        }

        /** A sampled shape as points over 0..D. */
        private fun points(v: FloatArray, D: Double): Array<DoubleArray> =
            Array(v.size) { i -> doubleArrayOf(D * i / (v.size - 1), v[i].toDouble()) }

        /** About 8 points a second, at least 65. */
        internal fun samples(D: Double): Int = max(65.0, ceil(D * 8) + 1).toInt()

        private fun equalPower(n: Int, rising: Boolean): FloatArray {
            val c = FloatArray(n)
            for (i in 0 until n) {
                val a = (i / (n - 1).toDouble()) * PI / 2
                c[i] = (if (rising) sin(a) else cos(a)).toFloat()
            }
            return c
        }
    }
}
