package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.LEVEL_FRAME_RATE
import com.music.bitchord.playback.smart.levelFromRms
import kotlin.math.ceil

/**
 * Fork (from Desktop Revived d77475ba, itself the phone's `TrackAnalyzer` at upstream b690dc14): the
 * pure parts of the PC analyser's head and tail model windows, apart so they can be tested.
 */
internal object ForkAnalysisWindows {

    /**
     * The windows the models run over: 0..[headEnd] and [tailStart]..[duration] (or the whole song
     * when they meet), each tiled with as few [span]-long windows as cover it with at least [overlap]
     * between neighbours, spread evenly.
     */
    fun windows(headEnd: Double, tailStart: Double, duration: Double, span: Double, overlap: Double): List<Pair<Double, Double>> {
        val spans = if (tailStart <= headEnd) listOf(0.0 to duration) else listOf(0.0 to headEnd, tailStart to duration)
        val windows = ArrayList<Pair<Double, Double>>()
        for ((from, to) in spans) {
            val length = to - from
            if (length < 1.0) continue
            if (length <= span) {
                windows += from to to
                continue
            }
            val count = ceil((length - overlap) / (span - overlap) - 1e-6).toInt()
            val stride = (length - span) / (count - 1)
            for (index in 0 until count) {
                val start = from + index * stride
                windows += start to minOf(to, start + span)
            }
        }
        return windows
    }

    /** A window's beat grid and where the window really began and how long it ran. */
    class Graded(val grid: BeatTracker.Grid, val start: Double, val seconds: Double)

    class Beats(val beats: List<Double>, val downbeats: List<Double>, val confidence: Double)

    /** One beat list across the windows: at each overlap, the earlier window's beats before its midpoint, the later's after. */
    fun seam(regions: List<Graded>): Beats {
        val graded = regions.sortedBy { it.start }
        if (graded.isEmpty()) return Beats(emptyList(), emptyList(), 0.0)
        val beats = ArrayList<Double>()
        val downbeats = ArrayList<Double>()
        var weighted = 0.0
        var count = 0
        for ((index, region) in graded.withIndex()) {
            val previous = graded.getOrNull(index - 1)
            val next = graded.getOrNull(index + 1)
            val from = previous?.let { (it.start + it.seconds + region.start) / 2 }
                ?.takeIf { it > region.start } ?: Double.NEGATIVE_INFINITY
            val until = next?.let { (region.start + region.seconds + it.start) / 2 }
                ?.takeIf { it < region.start + region.seconds } ?: Double.POSITIVE_INFINITY
            val kept = region.grid.beats.filter { it >= from && it < until }
            beats += kept
            downbeats += region.grid.downbeats.filter { it >= from && it < until }
            weighted += region.grid.beatConfidence * kept.size
            count += kept.size
        }
        return Beats(beats.sorted(), downbeats.sorted(), if (count > 0) weighted / count else 0.0)
    }

    /** The model's grid over [from]..[to], or null where it found too few beats there. */
    fun side(all: Beats, from: Double, to: Double): BeatSide? {
        val beats = all.beats.filter { it >= from && it <= to }
        val bpm = BeatTracker.tempoFromBeats(beats)
        if (bpm <= 0) return null
        return BeatSide(beats = beats, downbeats = all.downbeats.filter { it >= from && it <= to }, bpm = bpm, confidence = all.confidence)
    }

    fun grid(side: BeatSide) = BeatTracker.Grid(
        beats = side.beats,
        downbeats = side.downbeats,
        bpm = side.bpm,
        beatInterval = 60 / side.bpm,
        firstBeat = side.beats.first(),
        beatConfidence = side.confidence,
    )

    /** The windows' masks over one full-length curve, or null when none ran (the heuristic stays). */
    fun mergeMasks(size: Int, masks: List<DoubleArray>, neutral: Double): List<Double>? {
        if (size <= 0 || masks.isEmpty()) return null
        val merged = DoubleArray(size) { neutral }
        for (source in masks) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != neutral) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /** RMS of the stereo mix in frames of 1 / [LEVEL_FRAME_RATE] s on the [levelFromRms] scale, every fourth sample. */
    fun levelCurve(left: FloatArray, right: FloatArray, sampleRate: Double, start: Double): CurveWindow? {
        val frame = (sampleRate / LEVEL_FRAME_RATE).toInt()
        if (frame <= 0) return null
        val count = minOf(left.size, right.size) / frame
        if (count <= 0) return null
        val values = FloatArray(count)
        for (index in 0 until count) {
            var sum = 0.0
            var n = 0
            var sample = index * frame
            val end = sample + frame
            while (sample < end) {
                val mixed = (left[sample] + right[sample]) * 0.5f
                sum += mixed * mixed
                n++
                sample += 4
            }
            values[index] = levelFromRms(kotlin.math.sqrt(sum / n)).toFloat()
        }
        return CurveWindow(start, LEVEL_FRAME_RATE, values)
    }
}

/**
 * Fork (from Desktop Revived 573478be, `DesktopAutomixWorkQueue`): analysis work limited to the playing
 * song and the next, the playing one first. [focus] drops whatever else was queued; before the first
 * focus everything is let in.
 */
internal class ForkAnalysisQueue<T> {
    @Volatile private var focusIds: List<String>? = null
    private val pending = LinkedHashMap<String, T>()

    @Synchronized
    fun focus(currentId: String?, nextId: String?) {
        val ids = listOfNotNull(currentId?.takeIf { it.isNotBlank() }, nextId?.takeIf { it.isNotBlank() }).distinct()
        focusIds = ids
        pending.keys.retainAll(ids.toSet())
    }

    fun isRelevant(id: String): Boolean = focusIds?.contains(id) ?: true

    @Synchronized
    fun offer(id: String, task: T): Boolean {
        if (!isRelevant(id) || id in pending || pending.size >= 2) return false
        pending[id] = task
        return true
    }

    @Synchronized
    fun poll(): Pair<String, T>? {
        val id = focusIds?.firstOrNull { it in pending } ?: pending.keys.firstOrNull()
        return id?.let { key -> key to pending.remove(key)!! }
    }

    @Synchronized
    fun pendingIds(): List<String> = pending.keys.toList()

    @Synchronized
    fun clear() {
        pending.clear()
        focusIds = emptyList()
    }
}
