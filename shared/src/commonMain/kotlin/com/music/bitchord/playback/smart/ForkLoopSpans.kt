package com.music.bitchord.playback.smart

import kotlin.math.max
import kotlin.math.min

/**
 * Fork: where a song is sung, for the loop mix only, as the owner's baseline reads its synced lyrics (the
 * extension's `loopSpansFromLines` at 020f8a1, d26dc16's BitChord `sungSpansFromLines`; versions.md "The loop
 * mix's baseline" rule 1). Each line is padded [LoopMix.LYRIC_LEAD_SEC] before and [LoopMix.LYRIC_TAIL_SEC]
 * after; a line with word times ends at its last word, one without at its own end, else at the next line,
 * [LoopMix.LYRIC_OPEN_LINE_SEC] at most. Every span is certain to its end, so a loop it touches (or the 0.3 s
 * before it) is refused outright and the vocal mask is never asked, as in the extension's `firstClean`.
 * Blends keep "Where a song is sung" (`ForkV2Sung`).
 *
 * A line that isn't sung ([Shape.notSung]: a credit, the title or artist alone, symbols only, or a start before
 * 0; versions.md "The loop mix's baseline" rule 6) counts for nothing, and doesn't end the line before it.
 */
object ForkLoopSpans {

    /**
     * A line as the extension's `lineShapes` keeps it: gap lines (no text, no word times) left out.
     * [notSung]: marked from its text by the app's lyrics side (`ForkLoopLyrics.notSung`), the text then dropped.
     */
    class Shape(val startSec: Double, val endSec: Double?, val wordEndsSec: List<Double>, val notSung: Boolean = false)

    fun spans(shapes: List<Shape>): List<LoopMix.Span> {
        val sorted = shapes.filter { it.startSec.isFinite() && it.startSec >= 0 && !it.notSung }.sortedBy { it.startSec }
        return sorted.mapIndexed { i, l ->
            val next = sorted.subList(i + 1, sorted.size).firstOrNull { it.startSec > l.startSec }?.startSec
            val open = l.startSec + LoopMix.LYRIC_OPEN_LINE_SEC
            val end = if (l.wordEndsSec.isNotEmpty()) l.wordEndsSec.max()
            else min(open, l.endSec?.takeIf { it > l.startSec } ?: next ?: open)
            LoopMix.Span(l.startSec - LoopMix.LYRIC_LEAD_SEC, max(end, l.startSec) + LoopMix.LYRIC_TAIL_SEC)
        }
    }
}
