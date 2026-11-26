package com.music.bitchord.playback.smart.automixv2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where a song is sung (the extension's spec/automix/versions.md "Where a song is sung", spec
 * fef76a9; automix.js `lineShapes`, `sungSpansFromLines`, `carveSung`), for every Automix v2 check
 * that asks whether a song is sung somewhere: both singing in a blend, the ladder (drop-in, muffle),
 * the echo out's catch, the bridge, the "where the blend sits" score and the loop mix's bars.
 *
 * Why this replaced upstream's spans ([com.music.bitchord.playback.smart.LoopMix.sungSpans]) under
 * Automix v2: most synced lyrics are plain LRC, a start time per line. Upstream's rule (and the
 * extension's until 2026-11-24) counted a line without an end as sung up to the next line, 10 s at
 * most, padded 0.15 s before and 0.35 s after. Measured on the fixture songs that marked 78-97% of
 * every song as sung, and Automix v2's ladder fell to the hand-over on pairs that have clear
 * breaths. Now a line lasts about as long as its words take, and the vocal mask still hears the
 * gaps inside the lines ([carveSung]).
 *
 * BitChord's own Automix style keeps upstream's spans and its loop search untouched; only the
 * port's planner reads these.
 */
object ForkV2Sung {

    /**
     * A synced line's shape without its text (the fixtures' `lines` in the pair files under `pairs/` hold exactly
     * this): its start and end in seconds ([end] NaN when the provider gives none), its word
     * count, and its words' [start, end] when timed (null: a line-synced line).
     */
    class LineShape(val start: Double, val end: Double, val words: Int, val wordTimes: Array<DoubleArray>?)

    /** Seconds a word takes, for a line without word times. */
    const val LINE_WORD_SEC = 0.35

    /** Added once to a line's words-times-0.35 s (a held last word, a breath). */
    const val LINE_PAD_SEC = 0.5

    /** No line without an end is counted sung for longer than this. */
    const val LINE_MAX_SEC = 10.0

    /** A timed line runs this far past its last word's end (the voice's tail and reverb). */
    const val WORD_TAIL_SEC = 0.3

    /** Inside a sung span, the mask under this for a beat or more is a gap. */
    const val MASK_GAP = 0.3

    /**
     * Each line's sung span, in line order. With word times: the first word's start to the last
     * word's end + [WORD_TAIL_SEC]. Without: the line's start to the earliest of the next line's
     * start, start + words x [LINE_WORD_SEC] + [LINE_PAD_SEC], its own end when known, and start +
     * [LINE_MAX_SEC]. A line with no words is a gap and is left out by the caller ([fromLines]).
     *
     * The shapes are expected sorted by start (as [fromLines] and the extension's `lineShapes`
     * return them): "the next line" is the first later one with a strictly later start.
     */
    /**
     * The loop mix's sung spans, as the baseline reads lines (automix.js loopSpansFromLines, 020f8a1):
     * [com.music.bitchord.playback.smart.ForkLoopSpans] on these shapes. Blends keep [sungSpansFromLines].
     */
    fun loopSpansFromLines(shapes: List<LineShape>): Array<DoubleArray> =
        com.music.bitchord.playback.smart.ForkLoopSpans.spans(
            shapes.map { l ->
                com.music.bitchord.playback.smart.ForkLoopSpans.Shape(
                    startSec = l.start,
                    endSec = l.end.takeIf { it.isFinite() },
                    wordEndsSec = l.wordTimes?.map { it[1] } ?: emptyList(),
                )
            },
        ).map { doubleArrayOf(it.start, it.end) }.toTypedArray()

    fun sungSpansFromLines(shapes: List<LineShape>): Array<DoubleArray> {
        val spans = ArrayList<DoubleArray>()
        for ((i, l) in shapes.withIndex()) {
            val wt = l.wordTimes
            if (wt != null && wt.isNotEmpty()) {
                var lo = Double.POSITIVE_INFINITY
                var hi = Double.NEGATIVE_INFINITY
                for (w in wt) {
                    if (w[0] < lo) lo = w[0]
                    if (w[1] > hi) hi = w[1]
                }
                spans.add(doubleArrayOf(lo, hi + WORD_TAIL_SEC))
                continue
            }
            var next = Double.POSITIVE_INFINITY
            for (j in i + 1 until shapes.size) if (shapes[j].start > l.start) { next = shapes[j].start; break }
            val own = if (l.end.isNaN()) Double.POSITIVE_INFINITY else l.end
            val end = minOf(next, l.start + l.words * LINE_WORD_SEC + LINE_PAD_SEC, own, l.start + LINE_MAX_SEC)
            spans.add(doubleArrayOf(l.start, maxOf(end, l.start)))
        }
        return spans.toTypedArray()
    }

    /**
     * A song's line shapes from the app's synced lines (ms), as the extension's `lineShapes`: lines
     * without a start, or with neither text nor timed words, left out; sorted by start. [Line] keeps
     * this file free of either app's lyrics types.
     */
    class Line(val startMs: Long, val endMs: Long?, val text: String, val wordsMs: List<LongArray>)

    fun fromLines(lines: List<Line>): List<LineShape> {
        val shapes = ArrayList<LineShape>()
        for (l in lines) {
            if (l.startMs < 0) continue
            val timed = l.wordsMs.filter { it.size >= 2 && it[1] >= it[0] }
            val text = l.text.trim()
            if (text.isEmpty() && timed.isEmpty()) continue // a gap line
            shapes.add(
                LineShape(
                    start = l.startMs / 1000.0,
                    end = if (l.endMs != null && l.endMs > l.startMs) l.endMs / 1000.0 else Double.NaN,
                    words = if (text.isNotEmpty()) text.split(Regex("\\s+")).size else timed.size,
                    wordTimes = if (timed.isEmpty()) null else Array(timed.size) { doubleArrayOf(timed[it][0] / 1000.0, timed[it][1] / 1000.0) },
                ),
            )
        }
        shapes.sortBy { it.start }
        return shapes
    }

    /** [fromLines] then [sungSpansFromLines]; null when nothing is sung (the vocal mask decides). */
    fun spansOf(lines: List<Line>): Array<DoubleArray>? = sungSpansFromLines(fromLines(lines)).takeIf { it.isNotEmpty() }

    /**
     * [spans] with the gaps the vocal mask hears cut out (automix.js `carveSung`). Inside a sung
     * span, a run of one beat or more ([a]'s beat; 0.5 s without a grid) with the mask under
     * [MASK_GAP] isn't sung. Spans that touch or overlap are joined first, so a breath across a
     * line change is one gap: half a beat at the end of one line and half at the start of the next
     * is a beat.
     *
     * Why the carve is needed even with word times: enhanced LRC stamps each word's start only,
     * and the lyrics client ends each word where the next starts and the last at the next line's
     * start, so the spans run back to back (Poker Face's 59 lines, 488 word pairs, all contiguous).
     * Only the carve frees its breaths, the catch beats the echo out needs at 226.0 and 230.0 s.
     *
     * Without a mask on [a] the lyrics stand as they are; null stays null.
     */
    fun carveSung(spans: Array<DoubleArray>?, a: ForkV2Analysis?): Array<DoubleArray>? {
        if (spans == null || a == null || a.vocals.isEmpty()) return spans
        val beat = if (a.beatIntervalSec > 0) a.beatIntervalSec else 0.5
        // (the walk is 50 steps a second over every sung second; the planner re-plans each second of
        // the song on a copy of the analysis, so the answer is kept on what it is made of, never the copy)
        carved.value.firstOrNull { it.spans === spans && it.beat == beat && sameMask(it.curves, a.vocals) }
            ?.let { return copyOf(it.out) }
        val out = walk(spans, a, beat)
        carved.update { (listOf(Carved(spans, a.vocals.map { w -> w.curve }, beat, out)) + it).take(CARVED_KEEP) }
        return copyOf(out)
    }

    /**
     * The last carves, newest first: [carveSung]'s answer for the same spans, the same vocal mask
     * (each window's curve, the very arrays every copy the planner makes of an analysis shares) and
     * the same beat.
     * The extension's PC (c42bb5f) keyed its cache on the analysis object, which the planner copies
     * on every plan, so it never hit and the mask walk took 77 % of its main thread; here each pair's
     * re-plan (once a second, `ForkV2Automix.planTransition`'s memo) carved both songs again.
     */
    private class Carved(
        val spans: Array<DoubleArray>,
        val curves: List<FloatArray>,
        val beat: Double,
        val out: Array<DoubleArray>,
    )

    private const val CARVED_KEEP = 8
    private val carved = MutableStateFlow<List<Carved>>(emptyList())

    /** Carves made since start (tests: a copy of an analysis carves nothing new). */
    internal var carves = 0
        private set

    /** Whether [vocals] are the windows whose curves [curves] holds (by identity: the arrays are never changed in place). */
    private fun sameMask(curves: List<FloatArray>, vocals: List<ForkV2Analysis.VocalWindow>): Boolean =
        curves.size == vocals.size && curves.indices.all { curves[it] === vocals[it].curve }

    /** A copy the caller may change without touching the kept answer. */
    private fun copyOf(spans: Array<DoubleArray>): Array<DoubleArray> = Array(spans.size) { spans[it].copyOf() }

    private fun walk(spans: Array<DoubleArray>, a: ForkV2Analysis, beat: Double): Array<DoubleArray> {
        carves++
        val runs = ArrayList<DoubleArray>()
        for (s in spans.sortedBy { it[0] }) {
            val last = runs.lastOrNull()
            if (last != null && s[0] <= last[1]) last[1] = maxOf(last[1], s[1]) else runs.add(doubleArrayOf(s[0], s[1]))
        }
        val out = ArrayList<DoubleArray>()
        for (r in runs) {
            val x = r[0]
            val y = r[1]
            var from = x
            var gapAt = Double.NaN
            var t = x
            while (t < y) {
                val m = maskAt(a, t)
                if (!m.isNaN() && m < MASK_GAP) {
                    if (gapAt.isNaN()) gapAt = t
                } else {
                    if (!gapAt.isNaN() && t - gapAt >= beat) {
                        if (gapAt > from) out.add(doubleArrayOf(from, gapAt))
                        from = t
                    }
                    gapAt = Double.NaN
                }
                t += 0.02
            }
            if (!gapAt.isNaN() && y - gapAt >= beat) {
                if (gapAt > from) out.add(doubleArrayOf(from, gapAt))
            } else if (y > from) {
                out.add(doubleArrayOf(from, y))
            }
        }
        return out.toTypedArray()
    }

    /** The vocal mask at song second [t] (the first window that covers it), NaN where none does. */
    private fun maskAt(a: ForkV2Analysis, t: Double): Double {
        for (w in a.vocals) {
            val i = jround((t - w.startSec) * w.fps)
            if (i >= 0 && i < w.curve.size) return w.curve[i.toInt()].toDouble()
        }
        return Double.NaN
    }

    /**
     * The spans the planner reads for [a] (automix.js `lyricSpans`): none for a stub (not
     * analysed), a music video (its audio rarely runs on the song's lyric times) or a song
     * without a key; else [spans] carved by [a]'s mask.
     */
    internal fun forPlanner(spans: Array<DoubleArray>?, a: ForkV2Analysis): Array<DoubleArray>? =
        if (a.stub || a.musicVideo || a.fileKey == null) null else carveSung(spans, a)

    // ---- the songs' spans in use (both apps) ----

    /** A song's spans once its lyrics were asked: null inside when it has none. */
    private class Known(val spans: Array<DoubleArray>?)

    /** Songs remembered, as the extension's sungCache: the newest [KEEP]. */
    private const val KEEP = 24

    /**
     * Replaced whole on each write (never changed in place), so the planner on the playback thread
     * reads one consistent map while a lyrics lookup lands on another. Written by compare-and-set:
     * two lookups landing at once (this song's and the next's) each copied the map and the later
     * store dropped the other's song, which then planned as "not asked" with upstream's spans.
     */
    private val known = MutableStateFlow<Map<String, Known>>(emptyMap())

    /**
     * A song's sung spans from its synced lines, recorded where each app asks for the lyrics the loop
     * mix reads (the phone's `CrossfadeController.askLyrics`, the PC's `ForkDesktopLoopMix.askLyrics`):
     * null when it has none, which the planner reads as "the vocal mask decides".
     */
    fun remember(id: String, spans: Array<DoubleArray>?) {
        known.update { was ->
            val m = LinkedHashMap(was)
            m.remove(id)
            m[id] = Known(spans)
            while (m.size > KEEP) m.remove(m.keys.first())
            m
        }
    }

    /** Whether [id]'s lyrics were asked and answered. */
    fun known(id: String?): Boolean = id != null && known.value.containsKey(id)

    /** [id]'s spans under this rule, or null: none, or not asked yet. */
    fun spansFor(id: String?): Array<DoubleArray>? = id?.let { known.value[it]?.spans }

    /**
     * The spans Automix v2 plans the pair with (the blend, the ladder, the echo out, the bridge and
     * the loop mix: versions.md "Where a song is sung" rule 3), uncarved: the planner carves them by
     * each song's mask. For a song not in [known] yet, [fallbackOut] / [fallbackIn] (upstream's spans,
     * which the loop mix was handed) stand in.
     */
    fun liveSung(
        outId: String?,
        inId: String?,
        fallbackOut: Array<DoubleArray>? = null,
        fallbackIn: Array<DoubleArray>? = null,
    ): ForkV2Planner.Sung {
        val k = known.value // one map for both songs
        val s = ForkV2Planner.Sung()
        val o = outId?.let { k[it] }
        val i = inId?.let { k[it] }
        s.out = if (o != null) o.spans else fallbackOut
        s.`in` = if (i != null) i.spans else fallbackIn
        return s
    }

    /** Tests: start empty. */
    fun clear() {
        known.value = emptyMap()
        carved.value = emptyList()
    }
}
