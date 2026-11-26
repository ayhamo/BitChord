package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.data.lyrics.LyricLine

/**
 * Fork: the apps' synced lyrics as Automix v2's sung spans ([ForkV2Sung], versions.md "Where a song
 * is sung"). Called with the same lines the loop mix's lookup got (the phone's
 * `CrossfadeController.askLyrics`, the PC's `ForkDesktopLoopMix.askLyrics`), so a song is asked for
 * its lyrics once and both rules read the one answer.
 *
 * Each line goes in as its shape only: its start, its own end where the provider states one
 * (`sungUntilMs`), its word count from the text, and its words' times when the provider times
 * words. The client's word ends are not measured ends (it ends each word where the next starts),
 * which is why the planner carves the spans by the vocal mask afterwards.
 */
object ForkV2SungLyrics {

    /**
     * Records [id]'s spans from [lines]. As upstream's loop mix reads them, only line-synced lyrics
     * count (at least four timed lines); plain text, or none, records "no lyrics": the mask decides.
     */
    fun remember(id: String, lines: List<LyricLine>?) {
        val synced = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 }
        ForkV2Sung.remember(id, synced?.let { spansOf(it) })
    }

    fun spansOf(lines: List<LyricLine>): Array<DoubleArray>? = ForkV2Sung.spansOf(
        lines.map { l ->
            ForkV2Sung.Line(
                startMs = l.timeMs,
                endMs = l.sungUntilMs,
                text = if (l.isGap) "" else l.text,
                wordsMs = l.words.map { w -> longArrayOf(w.startMs, w.endMs) },
            )
        },
    )
}
