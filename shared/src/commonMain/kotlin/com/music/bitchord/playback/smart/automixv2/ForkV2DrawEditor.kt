package com.music.bitchord.playback.smart.automixv2

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The drawing editor's rules (spec/automix/editor.md §10 rule 6; the extension's
 * automix-overview.js startDraw, saveDrawing, undoDraw, pointAt, pointStart/Move, pointDelete and
 * repeat), kept out of the Compose editor (sharedUi ForkTransitionEditor) so they're testable: which
 * beat a drawing counts in, a lane's shape turned into points, a point added, moved or deleted, Repeat,
 * the length a drawing edit pins and Undo.
 */
object ForkV2DrawEditor {

    const val UNDO_MAX = 50
    const val VOLUME_KEEPS_ONE = "A volume line keeps at least one point"

    /** What a drawing's x counts in on this plan (automix.js plan, §10 rule 4). */
    class Ctx(val beatSec: Double, val glide: ForkV2Planner.Glide?, val unit: String) {
        fun sec(x: Double, unit: String = this.unit): Double = ForkV2Drawing.drawSec(x, unit, beatSec, glide)
        fun x(t: Double, unit: String = this.unit): Double = ForkV2Drawing.drawX(t, unit, beatSec, glide)
    }

    /** A beat-matched plan counts the mix's beat of A (or the glide's steps); else A's own beat, and the editor draws in seconds. */
    fun ctx(plan: ForkV2Planner.Plan, gridOut: ForkV2EditorGeometry.EditorGrid?): Ctx {
        val beat = plan.style != "crossfade" && plan.barSec > 0
        return Ctx(
            if (beat) plan.barSec / 4 else gridOut?.beatSec?.takeIf { it > 0 } ?: 0.5,
            if (beat) plan.glide else null,
            if (plan.style == "crossfade") "sec" else "beat",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // a curve's height in its half of the canvas (0 at the bottom, 1 at the top), as the decks draw it

    private val LOG1K = ln(1000.0)

    fun norm(key: String, v: Double): Double = when (key) {
        "gain", "noise", "echoSend", "reverbSend", "delaySend" -> v // (editor.md §12 rule 6: 0 at the bottom, 1 at the top)
        "bassDb", "midDb", "highDb" -> 1 + v / 18
        "lowpassHz" -> ln(v / 20) / LOG1K
        else -> 1 - ln(v / 20) / LOG1K
    }

    fun value(key: String, n: Double): Double = when (key) {
        "gain", "noise", "echoSend", "reverbSend", "delaySend" -> n
        "bassDb", "midDb", "highDb" -> 18 * n - 18
        "lowpassHz" -> 20 * 1000.0.pow(n)
        else -> 20 * 1000.0.pow(1 - n)
    }

    /** A pointer's place as a point: x snapped to a quarter beat or 10 ms ([free], Shift: to 0.001), v rounded (Hz to 1 Hz). */
    fun pointAt(key: String, x: Double, n: Double, unit: String, free: Boolean): DoubleArray {
        val x0 = max(0.0, x)
        val sx = if (free) jround(x0 * 1000) / 1000.0 else if (unit == "sec") jround(x0 * 100) / 100.0 else jround(x0 * 4) / 4.0
        val v = value(key, max(0.0, min(1.0, n)))
        return doubleArrayOf(sx, if (key.endsWith("Hz")) jround(v).toDouble() else jround(v * 1000) / 1000.0)
    }

    // ---------------------------------------------------------------------------------------------
    // Draw: the lane's shape as points

    /**
     * Draw picked in a lane's dropdown: the lane as it plays now, as points (its drawing, or its shape
     * read back, [ForkV2Drawing.shapeDrawing]). Every curve of the lane the shape didn't have starts
     * flat and open, so each can be drawn.
     */
    fun startDrawing(lane: String, ov: ForkV2MixOverride?, D: Double, c: Ctx): ForkV2Drawing {
        ov?.takeIf { it.preset == "custom" }?.drawing(lane)?.let { return it }
        val shape = ForkV2EditorGeometry.lanesNow(ov)[ForkV2MixOverride.LANES.indexOf(lane)]
        var d = (ForkV2Drawing.shapeDrawing(lane, shape, D, c.beatSec, c.glide, c.unit) as? ForkV2Drawing)
            ?: ForkV2Drawing(lane, c.unit, emptyMap(), emptyMap())
        for (key in ForkV2Drawing.KEYS[lane]!!) {
            if (key == "gain") continue
            for (out in listOf(true, false)) if (d.points(key, out).isEmpty()) d = d.with(key, out, arrayOf(doubleArrayOf(0.0, ForkV2Drawing.OPEN[key]!!)))
        }
        if (lane == "volume") {
            // a volume deck is never empty
            if (d.points("gain", true).isEmpty()) d = d.with("gain", true, arrayOf(doubleArrayOf(0.0, 1.0)))
            if (d.points("gain", false).isEmpty()) d = d.with("gain", false, arrayOf(doubleArrayOf(0.0, 0.0)))
        }
        return d
    }

    // ---------------------------------------------------------------------------------------------
    // a point added, moved, deleted

    /** [p] added to [pts] in x order; null when a third point would share its x. Returns the points and the new one's index. */
    fun add(pts: Array<DoubleArray>, p: DoubleArray): Pair<Array<DoubleArray>, Int>? {
        if (pts.count { it[0] == p[0] } >= 2) return null
        var i = pts.indexOfFirst { it[0] > p[0] }
        if (i < 0) i = pts.size
        val l = pts.map { it.copyOf() }.toMutableList()
        l.add(i, p)
        return l.toTypedArray() to i
    }

    /** Point [i] moved to [p], held between its neighbours: onto one makes a step, never a third point at one x. */
    fun move(pts: Array<DoubleArray>, i: Int, p: DoubleArray): Array<DoubleArray> {
        val l = Array(pts.size) { pts[it].copyOf() }
        val prev = l.getOrNull(i - 1)
        val next = l.getOrNull(i + 1)
        var lo = prev?.get(0) ?: 0.0
        var hi = next?.get(0) ?: Double.POSITIVE_INFINITY
        if (prev != null && i >= 2 && l[i - 2][0] == prev[0]) lo += 0.001
        if (next != null && i + 2 < l.size && l[i + 2][0] == next[0]) hi -= 0.001
        l[i] = doubleArrayOf(max(lo, min(hi, p[0])), p[1])
        return l
    }

    /** Point [i] deleted; null for a volume deck's last point. */
    fun delete(key: String, pts: Array<DoubleArray>, i: Int): Array<DoubleArray>? {
        if (key == "gain" && pts.size <= 1) return null
        return pts.filterIndexed { j, _ -> j != i }.toTypedArray()
    }

    /**
     * Repeat: the chosen curve's points in [from, from + len), both decks, copied every [len] along the
     * window (to x [endX], the window's end); each copy replaces the points in its span. Null past 128
     * points on a deck (refused, nothing changed).
     */
    fun repeat(d: ForkV2Drawing, key: String, from: Double, len: Double, endX: Double): ForkV2Drawing? {
        if (!(len > 0) || !(from >= 0)) return d
        var r = d
        for (out in listOf(true, false)) {
            val pts = d.points(key, out)
            val part = pts.filter { it[0] >= from && it[0] < from + len }
            if (part.isEmpty()) continue
            var res = pts.filter { it[0] < from + len }.map { it.copyOf() }
            var a = from + len
            while (a < endX - 1e-9) {
                val start = a
                res = res.filter { it[0] < start } + part.map { doubleArrayOf(jround((it[0] - from + start) * 1000) / 1000.0, it[1]) }
                // Checked as it grows, not only at the end: each copy keeps every point before it, so
                // the count only rises, and a tiny length (0.0001 over 32 s, 320 000 copies of a growing
                // list) froze the UI thread before the refusal below was reached. Same answer, at once.
                if (res.size > ForkV2Drawing.MAX_POINTS) return null
                a += len
            }
            val lastX = res.last()[0]
            res = res + pts.filter { it[0] >= endX - 1e-9 && it[0] > lastX }.map { it.copyOf() }
            if (res.size > ForkV2Drawing.MAX_POINTS) return null
            r = r.with(key, out, res.toTypedArray())
        }
        return r
    }

    // ---------------------------------------------------------------------------------------------
    // saving and Undo

    /** The length a drawing edit pins (rule 6): the one asked for, else the plan's when it is one the editor offers. */
    fun pinnedBars(ov: ForkV2MixOverride?, plan: ForkV2Planner.Plan): Int {
        if (ov != null && ov.bars > 0) return ov.bars
        return if (plan.style != "crossfade" && plan.bars in ForkV2MixOverride.BARS) plan.bars else 0
    }

    /**
     * One Undo step: the pair's preset and lanes before a drawing edit, its length then and the one the edit set.
     * [whole]: a shape or preset that replaced a drawing (editor.md §10 "Replacing a drawing"): Undo puts the
     * whole override from before it back.
     */
    class Step(val pair: String, val before: ForkV2MixOverride?, val barsBefore: Int, val barsAfter: Int, val whole: Boolean = false)

    /** The drawn lanes a choice would replace: [lane]'s for a shape in its dropdown, every drawn one for a preset (null). */
    fun replacedDrawings(ov: ForkV2MixOverride?, lane: String?): List<String> =
        if (ov?.preset != "custom") emptyList() else ForkV2MixOverride.LANES.filter { ov.drawing(it) != null && (lane == null || it == lane) }

    /** The question before [n] drawings are replaced. */
    fun replaceQuestion(n: Int): String = if (n > 1) "Replace the drawings?" else "Replace the drawing?"

    /** A drawing edit on [cur]: Custom with [lane] drawn as [d] (the other lanes as they play), at [bars] when not 0. */
    fun withDrawing(cur: ForkV2MixOverride?, lane: String, d: ForkV2Drawing, bars: Int): ForkV2MixOverride? {
        val lanes = ForkV2EditorGeometry.lanesNow(cur)
        return ForkV2EditorGeometry.edited(cur, true) {
            if (preset != "custom") {
                drawings.clear()
                volume = lanes[0]
                eq = lanes[1]
                effect = lanes[2]
                setLane("fx", lanes[3])
                setLane("looping", lanes[4])
            }
            preset = "custom"
            setLane(lane, null, d)
            if (bars > 0) this.bars = bars
        }
    }

    /**
     * [now] with [step] undone: the preset and lanes back; the length back only if this edit set it (a
     * length picked by hand since stays).
     */
    fun undone(now: ForkV2MixOverride?, step: Step): ForkV2MixOverride? {
        if (step.whole) return step.before?.copy()
        val b = step.before
        val nowBars = now?.bars ?: 0
        return ForkV2EditorGeometry.edited(now, true) {
            drawings.clear()
            if (b?.preset == "custom") {
                preset = "custom"
                for (k in ForkV2MixOverride.LANES) setLane(k, b.laneName(k), b.drawing(k))
            } else {
                preset = b?.preset
                volume = null
                eq = null
                effect = null
                fx = null
                looping = null
            }
            bars = if (nowBars == step.barsAfter) step.barsBefore else nowBars
        }
    }
}
