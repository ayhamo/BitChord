package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.LyricLine
import java.text.Normalizer

/**
 * Fork: the apps' synced lyrics as the loop mix's sung spans ([ForkLoopSpans], the baseline's reading).
 * Called by the loop mix's own lyrics lookup on both apps (the phone's `CrossfadeController.askLyrics`, the
 * PC's `ForkDesktopLoopMix.askLyrics`) where upstream built [LoopMix.sungSpans].
 */
object ForkLoopLyrics {

    /**
     * Null (the vocal mask decides) unless the lines are line-synced: at least four timed lines, as upstream asks.
     * [title] and [artist] tell the song's credit lines apart ([notSung]).
     */
    fun spans(lines: List<LyricLine>?, title: String = "", artist: String = ""): List<LoopMix.Span>? {
        val synced = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 } ?: return null
        val shapes = synced.mapNotNull { l ->
            val timed = l.words.filter { it.endMs >= it.startMs }
            if (l.text.isBlank() && timed.isEmpty()) return@mapNotNull null
            ForkLoopSpans.Shape(
                startSec = l.timeMs / 1000.0,
                endSec = l.sungUntilMs?.div(1000.0),
                wordEndsSec = timed.map { it.endMs / 1000.0 },
                notSung = notSung(l.text.trim(), l.timeMs / 1000.0, title, artist),
            )
        }
        return ForkLoopSpans.spans(shapes).takeIf { it.isNotEmpty() }
    }

    /**
     * A timed line that isn't sung, the extension's `notSung` (versions.md "The loop mix's baseline" rule 6): a
     * start before 0; no letter or digit (symbols only); the song's [title] or [artist] alone, or a line holding
     * both ("Artist - Title"), compared without case, accents or punctuation; a line opening with a credit word
     * followed by ":", "：", " - " or "by" ([CREDIT_LINE]); or one opening "prod. ". NetEase sends credits as timed
     * lines with no end: SI NO ES CONTIGO's "作曲: …" at 0.00 s, read the baseline way, counted its first 10 s as
     * sung and blocked the phone's loop (2026-11-24).
     */
    fun notSung(text: String, startSec: Double, title: String = "", artist: String = ""): Boolean {
        if (!(startSec >= 0)) return true
        if (text.isEmpty()) return false
        if (text.none { it.isLetterOrDigit() }) return true
        if (CREDIT_LINE.containsMatchIn(text) || PROD.containsMatchIn(text)) return true
        val t = plainOf(text)
        val names = listOf(title, artist).map(::plainOf).filter { it.isNotEmpty() }
        return names.any { t == it } || (names.size == 2 && t.contains(names[0]) && t.contains(names[1]))
    }

    private fun plainOf(x: String): String =
        Normalizer.normalize(x.lowercase(), Normalizer.Form.NFKD).replace(ACCENTS, "").replace(NOT_WORD, " ").trim()

    private val ACCENTS = Regex("[\\u0300-\\u036f]")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val PROD = Regex("^\\s*prod\\.\\s", RegexOption.IGNORE_CASE)
    private val CREDIT_LINE = Regex(
        "^\\s*(?:作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|製作人|监制|監製|混音|母带|母帶|和声|和聲|录音|錄音|出品|发行|發行|letra|m[uú]sica|" +
            "compositor(?:es)?|autor(?:es)?|lyrics?|lyricist|written|writers?|words|music|composed|composer|composition|arranged|" +
            "arrangement|arranger|produced|producers?|prod\\.?|mixed|mixing|mastered|mastering|recorded|engineer(?:ed)?)" +
            "\\s*(?:by\\b|[:：]|\\s-\\s)",
        RegexOption.IGNORE_CASE,
    )
}
