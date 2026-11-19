package com.music.bitchord.data.canvas

import kotlin.math.abs

/**
 * Fork: ClipJudge, the canvas rulebook's §7 — Prism's FrameJudge (on the storyboard) and ClipRefine
 * (on the clip's own frames), ported from the owner's phone extension (`ClipJudge.java`) line for
 * line, as plain arithmetic on 32 x 18 grey grids so every rule is run by the JVM tests.
 *
 * Storyboard: black bars, a still upload, every ~8 s window scored by its movement (some, not
 * frantic), brightness (not a black screen) and distance from the intro and the credits; the window
 * near YouTube's "Most replayed" peak is taken first, else near the chorus, else the middle third,
 * else the best; the crop goes where the detail and movement are. Real frames: where the video
 * cuts, the loop ending on its last cut 5 to 9 s in, and the flash guard (WCAG 2.3.1: more than
 * three flashes in a second over a quarter of the picture is never shown).
 *
 * The order of operations and every integer division are the extension's, which are Prism's: the
 * cases pin the edges (a cut of exactly 22, a window at exactly 75 % still), and a "tidier" port that
 * rounded once more or less in the wrong place moved those edges by a cell.
 */
object ForkClipJudge {
    const val W = 32
    const val H = 18
    const val WINDOW_MS = 8_000L
    const val HEAD_MS = 12_000L
    const val TAIL_MS = 15_000L
    const val MIN_LOOP_MS = 5_000L
    const val MAX_LOOP_MS = 9_000L
    private const val BLACK = 24
    private const val LIT = 48
    private const val BAR_SHARE = 0.9
    private const val STEP_MOVE = 3.0
    private const val STILL_SHARE = 0.6
    private const val WINDOW_STILL_SHARE = 0.75
    private const val IDEAL_MOVE = 18.0
    private const val FRANTIC_MOVE = 60.0
    private const val DARK = 16.0
    private const val BRIGHT = 64.0
    private const val CROP_SHARE = 0.32
    private const val CROP_LEAD = 1.15
    private const val PEAK_BEFORE_MS = 4_000L
    private const val PEAK_AFTER_MS = 8_000L
    private const val CHORUS_NEAR_MS = 10_000L
    private const val APART_MS = 15_000L
    private const val RUNNERS_UP = 4
    private const val GOOD_ENOUGH = 0.3
    private const val CUT = 22f
    private const val FLASH_AREA = 0.25f
    private const val FLASH_STEP = 26
    private const val FLASH_CHANGES = 7

    /** One frame's grey values ([luma], [width] wide) inside x0, y0, w, h, averaged to W x H, row by row. */
    fun grid(luma: IntArray, width: Int, x0: Int, y0: Int, w: Int, h: Int): IntArray {
        val out = IntArray(W * H)
        for (cy in 0 until H) for (cx in 0 until W) {
            val ya = y0 + cy * h / H
            val yb = maxOf(ya + 1, y0 + (cy + 1) * h / H)
            val xa = x0 + cx * w / W
            val xb = maxOf(xa + 1, x0 + (cx + 1) * w / W)
            var sum = 0L
            var n = 0
            for (y in ya until yb) for (x in xa until xb) {
                sum += luma[y * width + x]
                n++
            }
            out[cy * W + cx] = if (n > 0) (sum / n).toInt() else 0
        }
        return out
    }

    // ---- bars (rule 7.1) ------------------------------------------------------------------

    /** Black bars baked into the picture: {left, top, right, bottom} as shares of the frame. */
    fun bars(frames: List<IntArray>): FloatArray {
        val lit = frames.filter { f -> f.any { it >= LIT } }
        if (lit.size < 3) return FloatArray(4)
        val need = lit.size * BAR_SHARE
        val col = BooleanArray(W) { x -> lit.count { f -> (0 until H).count { y -> f[y * W + x] <= BLACK } >= H * 9 / 10 } >= need }
        val row = BooleanArray(H) { y -> lit.count { f -> (0 until W).count { x -> f[y * W + x] <= BLACK } >= W * 9 / 10 } >= need }
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        while (left < W && col[left]) left++
        while (right < W - left && col[W - 1 - right]) right++
        while (top < H && row[top]) top++
        while (bottom < H - top && row[H - 1 - bottom]) bottom++
        if (left + right > W / 2) { left = 0; right = 0 }
        if (top + bottom > H / 2) { top = 0; bottom = 0 }
        return floatArrayOf(left / W.toFloat(), top / H.toFloat(), right / W.toFloat(), bottom / H.toFloat())
    }

    /** The picture inside the bars, in cells: {x0, y0, x1, y1}. */
    fun area(bars: FloatArray): IntArray = intArrayOf(
        Math.round(bars[0] * W), Math.round(bars[1] * H), W - Math.round(bars[2] * W), H - Math.round(bars[3] * H),
    )

    private fun middle(a: IntArray): IntArray {
        val dx = Math.round((a[2] - a[0]) * 0.2).toInt()
        val dy = Math.round((a[3] - a[1]) * 0.2).toInt()
        return intArrayOf(a[0] + dx, a[1] + dy, maxOf(a[0] + dx + 1, a[2] - dx), maxOf(a[1] + dy + 1, a[3] - dy))
    }

    private fun diff(p: IntArray, q: IntArray, a: IntArray): Double {
        var sum = 0L
        for (y in a[1] until a[3]) for (x in a[0] until a[2]) sum += abs(p[y * W + x] - q[y * W + x])
        return sum / ((a[2] - a[0]) * (a[3] - a[1])).toDouble()
    }

    private fun mean(g: IntArray, a: IntArray): Double {
        var sum = 0L
        for (y in a[1] until a[3]) for (x in a[0] until a[2]) sum += g[y * W + x]
        return sum / ((a[2] - a[0]) * (a[3] - a[1])).toDouble()
    }

    // ---- still (rule 7.2) -----------------------------------------------------------------

    /** A still upload: 60 % or more of the steps show no change (under 3) in the middle 60 % of the picture. */
    fun still(frames: List<IntArray>, bars: FloatArray): Boolean {
        if (frames.size < 2) return true
        val m = middle(area(bars))
        var n = 0
        for (i in 1 until frames.size) if (diff(frames[i - 1], frames[i], m) < STEP_MOVE) n++
        return n / (frames.size - 1).toDouble() >= STILL_SHARE
    }

    // ---- windows (rules 7.3, 7.4) ---------------------------------------------------------

    /** A scored window: [startMs] to [endMs], and its [score]. */
    data class Window(val startMs: Long, val endMs: Long, val score: Double)

    /** Every scoring ~8 s window, one per storyboard frame. */
    fun windows(frames: List<IntArray>, intervalMs: Long, lengthMs: Long, bars: FloatArray): List<Window> {
        val out = ArrayList<Window>()
        if (frames.size < 2 || intervalMs <= 0) return out
        val a = area(bars)
        val m = middle(a)
        val steps = maxOf(1, minOf((frames.size - 1).toLong(), Math.round(WINDOW_MS / intervalMs.toDouble())).toInt())
        val move = DoubleArray(frames.size - 1) { diff(frames[it], frames[it + 1], a) }
        val moveMid = DoubleArray(frames.size - 1) { diff(frames[it], frames[it + 1], m) }
        val light = DoubleArray(frames.size) { mean(frames[it], a) }
        val roomy = lengthMs >= HEAD_MS + TAIL_MS + 3 * WINDOW_MS
        for (i in 0..frames.size - 1 - steps) {
            val start = i * intervalMs
            val end = minOf(start + WINDOW_MS, lengthMs)
            val nearEnds = start < HEAD_MS || end > lengthMs - TAIL_MS
            if (nearEnds && roomy) continue
            var still = 0
            var motion = 0.0
            var bright = 0.0
            var dark = 0.0
            for (k in i until i + steps) {
                if (moveMid[k] < STEP_MOVE) still++
                motion += move[k]
            }
            if (still >= steps * WINDOW_STILL_SHARE) continue
            for (k in i..i + steps) {
                bright += light[k]
                if (light[k] < DARK) dark++
            }
            motion /= steps
            bright /= (steps + 1)
            dark /= (steps + 1)
            val moveScore = if (motion <= IDEAL_MOVE) motion / IDEAL_MOVE
            else 1 - 0.5 * minOf(1.0, (motion - IDEAL_MOVE) / (FRANTIC_MOVE - IDEAL_MOVE))
            val brightScore = maxOf(0.0, minOf(1.0, (bright - DARK) / (BRIGHT - DARK)))
            out += Window(start, end, moveScore * brightScore * (1 - dark) * (if (nearEnds) 0.5 else 1.0))
        }
        return out
    }

    /** Best first: the higher score, on a tie the earlier start (never the windows' order). */
    private fun better(w: Window, than: Window?): Boolean =
        than == null || w.score > than.score || w.score == than.score && w.startMs < than.startMs

    // ---- choose (rule 7.6) ----------------------------------------------------------------

    /**
     * The loop's window: the best near the "Most replayed" [peakMs] (one in the first 12 s is the
     * intro everyone replays: ignored), else near the [chorusMs], else in the middle third, each only
     * from windows scoring above 0 and at least 0.3 of the best; else the best anywhere. Null
     * without windows; [peakMs] and [chorusMs] null when unknown.
     */
    fun choose(windows: List<Window>, lengthMs: Long, peakMs: Long?, chorusMs: Long?): Window? {
        if (windows.isEmpty()) return null
        var top: Window? = null
        for (w in windows) if (better(w, top)) top = w
        val best = top!!
        var w: Window? = null
        if (peakMs != null && peakMs >= HEAD_MS) w = pick(windows, best, peakMs - PEAK_BEFORE_MS, peakMs + PEAK_AFTER_MS)
        if (w == null && chorusMs != null) w = pick(windows, best, chorusMs - CHORUS_NEAR_MS, chorusMs + CHORUS_NEAR_MS)
        if (w == null) w = pick(windows, best, lengthMs / 3, lengthMs * 2 / 3)
        return w ?: best
    }

    private fun pick(windows: List<Window>, top: Window, from: Long, to: Long): Window? {
        var b: Window? = null
        for (w in windows) {
            if (w.startMs in from..to && w.score > 0 && w.score >= top.score * GOOD_ENOUGH && better(w, b)) b = w
        }
        return b
    }

    /** Rule 7.7: up to 4 other good parts' starts, best first, each 15 s from the chosen one and each other. */
    fun runnersUp(windows: List<Window>, chosenMs: Long): List<Long> {
        val scoring = windows.filter { it.score > 0 }
            .sortedWith { x, y -> if (better(x, y)) -1 else if (better(y, x)) 1 else 0 }
        val taken = mutableListOf(chosenMs)
        val out = ArrayList<Long>()
        for (w in scoring) {
            if (out.size >= RUNNERS_UP) break
            if (taken.any { abs(it - w.startMs) < APART_MS }) continue
            taken += w.startMs
            out += w.startMs
        }
        return out
    }

    /** What the storyboard says about a video that is not a still upload. */
    class Loop(
        val startMs: Long,
        val endMs: Long,
        val lengthMs: Long,
        val cropX: Float = 0.5f,
        val bars: FloatArray = FloatArray(4),
        val others: List<Long> = emptyList(),
    )

    /**
     * Prism's FrameJudge.judge: bars, the still check, the windows, the chosen one and its crop.
     * Null for a still upload, also when every window is left out (rule 7.5).
     */
    fun judge(frames: List<IntArray>, intervalMs: Long, lengthMs: Long, peakMs: Long?, chorusMs: Long?): Loop? {
        val length = maxOf(lengthMs, frames.size * intervalMs)
        val b = bars(frames)
        if (still(frames, b)) return null
        val windows = windows(frames, intervalMs, length, b)
        val w = choose(windows, length, peakMs, chorusMs) ?: return null
        val first = (w.startMs / intervalMs).toInt()
        val last = minOf((frames.size - 1).toLong(), (w.endMs + intervalMs - 1) / intervalMs).toInt()
        return Loop(
            startMs = w.startMs,
            endMs = w.endMs,
            lengthMs = length,
            cropX = cropX(frames.subList(first, last + 1), b),
            bars = b,
            others = runnersUp(windows, w.startMs),
        )
    }

    /** Rule 6.9: the middle 8 s of a video with no preview frames: {startMs, endMs}. */
    fun noFrames(lengthMs: Long): LongArray {
        val window = minOf(WINDOW_MS, lengthMs)
        val start = maxOf(0L, (lengthMs - window) / 2)
        return longArrayOf(start, start + window)
    }

    // ---- crop (rule 7.8) ------------------------------------------------------------------

    /**
     * Where the crop sits across the picture inside the bars (0 left, 0.5 centred, 1 right): the
     * band of 0.32 of its width (what a full-height upright crop of a 16:9 picture keeps) holding
     * the most detail and movement, when it leads the centre by 1.15x; ties go nearer the centre.
     */
    fun cropX(frames: List<IntArray>, bars: FloatArray): Float {
        val a = area(bars)
        val aw = a[2] - a[0]
        if (frames.isEmpty() || aw < 4) return 0.5f
        val weight = DoubleArray(aw)
        for (i in frames.indices) {
            val f = frames[i]
            for (y in a[1] until a[3]) for (x in a[0] until a[2]) {
                val k = x - a[0]
                if (x + 1 < a[2]) {
                    val d = abs(f[y * W + x + 1] - f[y * W + x]).toDouble()
                    weight[k] += d / 2
                    weight[k + 1] += d / 2
                }
                if (i > 0) weight[k] += abs(f[y * W + x] - frames[i - 1][y * W + x]).toDouble()
            }
        }
        val band = maxOf(1, minOf(aw, Math.round(aw * CROP_SHARE).toInt()))
        val room = aw - band
        if (room <= 0) return 0.5f
        val centre = sum(weight, room / 2, band)
        var bestAt = room / 2
        var best = centre
        for (s in 0..room) {
            val v = sum(weight, s, band)
            if (v > best || v == best && abs(s * 2 - room) < abs(bestAt * 2 - room)) {
                best = v
                bestAt = s
            }
        }
        if (best <= centre * CROP_LEAD) return 0.5f
        return bestAt / room.toFloat()
    }

    private fun sum(w: DoubleArray, from: Int, n: Int): Double {
        var s = 0.0
        for (i in from until from + n) s += w[i]
        return s
    }

    // ---- real frames (rules 7.9 - 7.11) ---------------------------------------------------

    private fun change(a: IntArray, b: IntArray): Float {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size.toFloat()
    }

    /** Rule 7.9: the times of the first frame after each cut (a lone large change, not movement or a dissolve). */
    fun cuts(frames: List<IntArray>, times: LongArray): List<Long> {
        val out = ArrayList<Long>()
        if (frames.size < 3) return out
        val d = FloatArray(frames.size)
        for (i in 1 until frames.size) d[i] = change(frames[i - 1], frames[i])
        for (i in 1 until frames.size) {
            val before = if (i > 1) d[i - 1] else 0f
            val after = if (i + 1 < frames.size) d[i + 1] else 0f
            if (d[i] >= CUT && d[i] >= 3 * maxOf(maxOf(before, after), 2f)) out += times[i]
        }
        return out
    }

    /** Rule 7.11: 7 changes of direction within a second among flashes over a quarter of the picture (frames before [untilMs]). */
    fun flashing(frames: List<IntArray>, times: LongArray, untilMs: Long): Boolean {
        val at = ArrayList<Long>()
        val up = ArrayList<Boolean>()
        var i = 1
        while (i < frames.size && times[i] < untilMs) {
            val a = frames[i - 1]
            val b = frames[i]
            var cells = 0
            var sum = 0
            for (k in a.indices) {
                val step = b[k] - a[k]
                if (abs(step) >= FLASH_STEP) {
                    cells++
                    sum += step
                }
            }
            if (cells >= a.size * FLASH_AREA && sum != 0) {
                at += times[i]
                up += sum > 0
            }
            i++
        }
        for (s in at.indices) {
            var changes = 1
            var last = up[s]
            var j = s + 1
            while (j < at.size && at[j] - at[s] <= 1000) {
                if (up[j] != last) {
                    changes++
                    last = up[j]
                }
                j++
            }
            if (changes >= FLASH_CHANGES) return true
        }
        return false
    }

    /**
     * Rules 7.10, 7.11: where the loop ends (ms from the clip's start): on the last cut leaving it
     * 5 to 9 s long, else 8 s or where the frames end; moved to earlier cuts (5 s or more) while it
     * flashes. -1 when it still flashes.
     */
    fun end(frames: List<IntArray>, times: LongArray, framesEndMs: Long): Long {
        val cuts = cuts(frames, times)
        var end = -1L
        for (c in cuts) if (c in MIN_LOOP_MS..MAX_LOOP_MS && c <= framesEndMs) end = c
        if (end < 0) end = maxOf(1L, minOf(WINDOW_MS, framesEndMs))
        while (flashing(frames, times, end)) {
            var earlier = -1L
            for (c in cuts) if (c < end && c >= MIN_LOOP_MS) earlier = c
            if (earlier < 0) return -1
            end = earlier
        }
        return end
    }
}
