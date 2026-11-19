package com.music.bitchord.data.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Fork: BestPart, the canvas rulebook's §6 — Prism's `BestPart.peakMs` as the owner's phone
 * extension ports it (`VideoCanvas.peakMs` / `partStart` / `heatmapOf`): where YouTube's "Most
 * replayed" curve rises to its peak, which the music-video canvas loops (through [ForkClipJudge.choose]).
 *
 * The curve is `MARKER_TYPE_HEATMAP` in the watch page's `next` answer (`frameworkUpdates`), ten
 * markers or more; signed out it is there for most music videos with any views.
 */
object ForkBestPart {
    private const val PART_HEAD_MS = 5_000L
    private const val PART_TAIL_MS = 30_000L
    private const val MIN_SONG_MS = 60_000L
    private const val MIN_RISE = 0.08
    private const val MIN_LIFT = 0.03
    private const val TREND_HALF_MS = 45_000.0
    private const val TREND_FLOOR = 0.05

    /** One "Most replayed" marker: where it starts and how long it is (ms), and its height 0..1. */
    data class Marker(val startMs: Long, val lengthMs: Long, val intensity: Double)

    /** The heat markers in a `next` answer, or null when it has no curve of ten markers or more. */
    fun heatmapOf(next: JsonObject): List<Marker>? {
        val mutations = ((next["frameworkUpdates"] as? JsonObject)?.get("entityBatchUpdate") as? JsonObject)
            ?.get("mutations") as? JsonArray ?: return null
        for (mutation in mutations) {
            val list = (((mutation as? JsonObject)?.get("payload") as? JsonObject)
                ?.get("macroMarkersListEntity") as? JsonObject)?.get("markersList") as? JsonObject ?: continue
            if ((list["markerType"] as? JsonPrimitive)?.contentOrNull != "MARKER_TYPE_HEATMAP") continue
            val markers = list["markers"] as? JsonArray ?: continue
            if (markers.size < 10) continue
            return markers.map { m ->
                val o = m as? JsonObject
                fun num(key: String): Double = (o?.get(key) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: 0.0
                Marker(num("startMillis").toLong(), num("durationMillis").toLong(), num("intensityScoreNormalized"))
            }
        }
        return null
    }

    /**
     * Where the most replayed stretch begins (ms) on [markers], or -1: [peakMs] with the segment the
     * median marker length. [durationMs] the song's length (0: the curve's own).
     */
    fun partStart(markers: List<Marker>?, durationMs: Long): Long {
        if (markers == null || markers.size < 10) return -1
        val sorted = markers.sortedBy { it.startMs }
        val lengths = sorted.map { it.lengthMs }.sorted()
        val segment = lengths[sorted.size / 2]
        if (segment <= 0) return -1
        return peakMs(DoubleArray(sorted.size) { sorted[it].intensity.coerceIn(0.0, 1.0) }, segment, durationMs)
    }

    /**
     * Prism's BestPart.peakMs (rules 6.2 - 6.8): each moment of a smoothed curve against the median
     * of the curve about 45 s either side (segment 0 left out); the biggest rise as a share over that
     * trend (at least 0.05) wins, ties to the earlier, between max(5 s, 2 segments) and 30 s before the
     * end. None under a rise of 0.08 or a lift of 0.03, nor in a song under 60 s or a curve under 10
     * segments. Pulled back over the segments rising at least half as much, then forward to the first
     * raw segment that does. -1 when none.
     */
    fun peakMs(intensity: DoubleArray, segmentMs: Long, durationMs: Long): Long {
        val n = intensity.size
        val duration = if (durationMs > 0) durationMs else segmentMs * n
        if (duration < MIN_SONG_MS || n < 10 || segmentMs <= 0) return -1
        val half = maxOf(1, Math.round(4_500.0 / segmentMs).toInt())
        val smooth = DoubleArray(n)
        val trend = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            var c = 0
            for (j in maxOf(0, i - half)..minOf(n - 1, i + half)) {
                sum += intensity[j]
                c++
            }
            smooth[i] = sum / c
        }
        val wide = maxOf(3, Math.round(TREND_HALF_MS / segmentMs).toInt())
        for (i in 0 until n) {
            val w = smooth.copyOfRange(maxOf(1, i - wide), minOf(n - 1, i + wide) + 1)
            w.sort()
            trend[i] = w[w.size / 2]
        }
        val head = maxOf(PART_HEAD_MS, 2 * segmentMs)
        fun candidate(i: Int) = i >= 0 && i * segmentMs >= head && i * segmentMs <= duration - PART_TAIL_MS
        fun rise(v: Double, t: Double) = v / maxOf(t, TREND_FLOOR) - 1
        var peak = -1
        for (i in 0 until n) {
            if (candidate(i) && (peak < 0 || rise(smooth[i], trend[i]) > rise(smooth[peak], trend[peak]))) peak = i
        }
        if (peak < 0) return -1
        val top = rise(smooth[peak], trend[peak])
        if (top < MIN_RISE || smooth[peak] - trend[peak] < MIN_LIFT) return -1
        val floor = top * 0.5
        var start = peak
        while (candidate(start - 1) && rise(smooth[start - 1], trend[start - 1]) >= floor) start--
        var jump = start
        for (i in start..peak) {
            if (rise(intensity[i], trend[i]) >= floor) {
                jump = i
                break
            }
        }
        return jump * segmentMs
    }
}
