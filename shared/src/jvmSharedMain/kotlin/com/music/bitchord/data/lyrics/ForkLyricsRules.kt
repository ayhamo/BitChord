package com.music.bitchord.data.lyrics

import org.w3c.dom.Element
import kotlin.math.max

/**
 * Fork: the lyrics rulebook (`YT Music Extension/spec/lyrics/lyrics.md`, from Metrolist v14.0.1) as
 * pure functions, for the [view] rules BitChord builds: the early highlight (§2), the two offsets
 * (§3), duet sides (§5), instrumental gaps (§6) and which languages are romanized (§9). The panel
 * (`sharedUi`, `ForkLyricsView.kt`) calls them through one-line hooks; `ForkLyricsCasesTest` runs
 * the rulebook's 17 cases (`lyrics-cases.json`) against them.
 */
object ForkLyricsRules {

    // ---- §2 Early highlight ---------------------------------------------------------------

    /** A line is active this far before its start: Metrolist's `DEFAULT_SUGGESTED_LINE_OFFSET_MS`, −(500 + 160). */
    const val LINE_LEAD_MS = 660L

    /** The shortest lead a line starting under another (a duet) gets. */
    const val OVERLAP_MIN_LEAD_MS = 250L

    /** How much shorter that lead is: word-synced 150 ms, line-synced 250 ms. */
    const val OVERLAP_DELAY_WORD_MS = 150L
    const val OVERLAP_DELAY_LINE_MS = 250L

    /** An overlap shorter than this is timing noise, not two voices: the earlier line is cut at the next start. */
    const val LINE_OVERLAP_MIN_MS = 250L

    /** "Which line is now" outside the view, and a seek by lyrics (§2.4). */
    const val SEEK_LEAD_MS = 100L

    /** The scroll to the next line animates for at most this long (§2.5). */
    const val SCROLL_ANIMATION_MAX_MS = 450L

    // ---- §6 Instrumental gaps -------------------------------------------------------------

    const val GAP_MIN_MS = 7_000L
    const val GAP_START_LAG_MS = 310L
    const val GAP_END_LEAD_MS = 800L

    /** A gap shows while the position plus this is inside it (`HIGHLIGHT_LOOK_AHEAD_MS`). */
    const val GAP_LOOK_AHEAD_MS = 190L

    /** A line nobody gave an end is taken to last this long when the next line is a gap away. */
    const val UNKNOWN_END_MS = 4_000L

    /**
     * §2.4, Metrolist's `findCurrentLineIndex`: line i is current while the next line's start is at
     * or after the position plus [leadMs]; −1 before the first.
     */
    fun currentLine(starts: List<Long>, positionMs: Long, leadMs: Long = SEEK_LEAD_MS): Int {
        for (i in starts.indices) if (starts[i] >= positionMs + leadMs) return i - 1
        return starts.size - 1
    }

    // ---- §3 Offsets -------------------------------------------------------------------------

    /** §3.1: the song's offset and the global one, added. Rule sign: positive shows the lyrics earlier. */
    fun combinedOffset(songMs: Int, globalMs: Int): Int = songMs + globalMs

    /** §3.2: the lyrics position is the player's plus the offset. */
    fun lyricsPosition(playerMs: Long, offsetMs: Int): Long = playerMs + offsetMs

    /** §3.2: a seek by lyrics goes to the lyric's time minus the offset, never below 0. */
    fun seekTarget(lyricMs: Long, offsetMs: Int): Long = max(0L, lyricMs - offsetMs)

    /**
     * BitChord's stored global offset (`AppSettings.lyricsOffsetMs`, the PC's `lyrics_offset_ms`)
     * has the opposite sign: positive delays the lyrics. Turned around on the way in and out, so a
     * saved value keeps its meaning (§3.2).
     */
    fun globalFromStored(storedMs: Int): Int = -storedMs
    fun storedFromRule(ruleMs: Int): Int = -ruleMs

    // ---- §5 Duets ---------------------------------------------------------------------------

    /** Where a line sits. [VIEW] is the view's own alignment (BitChord's: the start edge). */
    enum class Side { LEFT, RIGHT, CENTER, VIEW }

    private const val MOSTLY_RIGHT = 0.85

    /**
     * §5.2, Metrolist's `assignLineAlignments`: an agent's type is its declared one; without one
     * `v1000` and ids starting with `g` are a group, `v2000` and ids starting with `o` are "other",
     * the rest a person. A group is centred; the first person or other goes left (right for
     * "other"); after that the side changes each time the singer changes; no singer keeps the view's
     * alignment; 85% or more on the right swaps every side. [respect] is the sides setting: off,
     * every line takes the view's alignment, a group too.
     */
    fun sides(singers: List<String?>, types: Map<String, String> = emptyMap(), respect: Boolean = true): List<Side> {
        var left = true
        var last: String? = null
        var right = 0
        var total = 0
        val out = singers.map { id ->
            val side = when {
                id.isNullOrEmpty() -> Side.VIEW
                !respect -> Side.VIEW
                typeOf(id, types) == "group" -> Side.CENTER
                else -> {
                    if (last == null) left = typeOf(id, types) != "other" else if (id != last) left = !left
                    last = id
                    if (left) Side.LEFT else Side.RIGHT
                }
            }
            if (side == Side.LEFT || side == Side.RIGHT) {
                total++
                if (side == Side.RIGHT) right++
            }
            side
        }
        if (!respect || total == 0 || right.toDouble() / total < MOSTLY_RIGHT) return out
        return out.map { if (it == Side.LEFT) Side.RIGHT else if (it == Side.RIGHT) Side.LEFT else it }
    }

    private fun typeOf(id: String, types: Map<String, String>): String = when (types[id]) {
        "group" -> "group"
        "other" -> "other"
        null -> when {
            id == "v1000" || id.startsWith("g") -> "group"
            id == "v2000" || id.startsWith("o") -> "other"
            else -> "person"
        }
        else -> "person"
    }

    /**
     * [sides] as BitChord's line alignment, for the parsers (TTML, LyricsPlus): left and the view's
     * alignment are [LyricAlignment.Start], a group [LyricAlignment.Center]. The sides setting is
     * applied by the panel (`forkSidesOn`), so the parsed song keeps its sides.
     */
    fun alignments(singers: List<String?>, types: Map<String, String>): List<LyricAlignment> =
        sides(singers, types).map {
            when (it) {
                Side.RIGHT -> LyricAlignment.End
                Side.CENTER -> LyricAlignment.Center
                else -> LyricAlignment.Start
            }
        }

    /**
     * §5.1: a TTML line's singer when the `p` names none, inherited from the nearest `div` (or
     * `body`) that does. BitChord's parser read the `p` only.
     */
    fun inheritedAgent(paragraph: Element): String? {
        var node = paragraph.parentNode
        while (node is Element) {
            node.agentAttribute()?.let { return it }
            node = node.parentNode
        }
        return null
    }

    /** `ttm:agent` on either DOM (the JVM keeps the prefix, Android's Expat files it under `agent`). */
    private fun Element.agentAttribute(): String? {
        getAttribute("ttm:agent").takeIf { it.isNotEmpty() }?.let { return it }
        val found = attributes ?: return null
        for (i in 0 until found.length) {
            val attribute = found.item(i) ?: continue
            if (attribute.nodeName == "ttm:agent" || attribute.nodeName == "agent" || attribute.localName == "agent") {
                return attribute.nodeValue?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    // ---- §6 Instrumental gaps -----------------------------------------------------------------

    /**
     * §6.1, Metrolist's `buildLyricsListItems`: the intro is a gap when the first line starts at
     * [GAP_MIN_MS] or later, from 0 to the first start − 800; between lines a gap runs from the
     * latest end so far + 310 to the next start − 800 when the silence is [GAP_MIN_MS] or more.
     * Each gap is returned with the index of the line it comes before.
     */
    fun gapSpans(starts: List<Long>, ends: List<Long>): List<Gap> {
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<Gap>()
        if (starts[0] >= GAP_MIN_MS) out += Gap(0L, starts[0] - GAP_END_LEAD_MS, 0)
        var lastEnd: Long? = null
        for (k in starts.indices) {
            val previous = lastEnd
            if (previous != null && starts[k] - previous >= GAP_MIN_MS) {
                out += Gap(previous + GAP_START_LAG_MS, starts[k] - GAP_END_LEAD_MS, k)
            }
            lastEnd = max(previous ?: 0L, ends[k])
        }
        return out
    }

    data class Gap(val startMs: Long, val endMs: Long, val beforeLine: Int)

    /**
     * A sheet with BitChord's gap lines (an LRC bare stamp, or the 4 s measured breaks of
     * `withInstrumentalGaps`) replaced by the rule's (§6.1): each gap a blank line stamped at its
     * start with its end as [LyricLine.sungUntilMs]. A bare stamp still counts as where the line
     * before it stopped. A plain sheet (no stamps) is returned as it is.
     */
    fun withSpecGaps(lines: List<LyricLine>): List<LyricLine> {
        if (lines.none { it.timeMs > 0L }) return lines
        val sung = ArrayList<LyricLine>(lines.size)
        val ends = ArrayList<Long>(lines.size)
        lines.forEachIndexed { index, line ->
            if (line.isGap) return@forEachIndexed
            sung += line
            val marker = lines.getOrNull(index + 1)?.takeIf { it.isGap && it.timeMs > line.timeMs }?.timeMs
            val nextStart = (index + 1 until lines.size).firstOrNull { !lines[it].isGap }?.let { lines[it].timeMs }
            ends += when {
                line.hasKnownEnd -> line.endMs
                marker != null -> marker
                else -> unknownEnd(line.timeMs, nextStart)
            }
        }
        if (sung.isEmpty()) return lines
        val gaps = gapSpans(sung.map { it.timeMs }, ends)
        val out = ArrayList<LyricLine>(sung.size + gaps.size)
        var g = 0
        sung.forEachIndexed { k, line ->
            while (g < gaps.size && gaps[g].beforeLine == k) {
                out += LyricLine(gaps[g].startMs, "", sungUntilMs = gaps[g].endMs)
                g++
            }
            out += line
        }
        return out
    }

    /** Metrolist's end for a line with none: the next start, or [UNKNOWN_END_MS] when that is a gap away. */
    private fun unknownEnd(start: Long, nextStart: Long?): Long = when {
        nextStart != null && nextStart > start -> if (nextStart - start >= GAP_MIN_MS) start + UNKNOWN_END_MS else nextStart
        else -> start + UNKNOWN_END_MS
    }

    // ---- §2 Which rows are active in the view -------------------------------------------------

    /**
     * §2.1-2.2 and §6.2, Metrolist's `updateByLookAhead` without its state: a line becomes active
     * when the position + [LINE_LEAD_MS] reaches its start, or + max(250, 660 − 150/250) when it
     * starts under a line still active (a duet; both are active then). It stops at its end, or
     * when the next line is active by the same rule; a line overlapping the next stops on the
     * shorter lead. A gap line is active while the position + [GAP_LOOK_AHEAD_MS] is inside it.
     *
     * When nothing is active (a silence under [GAP_MIN_MS], the end of the song) the last line
     * started by the lead stays: BitChord's panel measures every row's dimming from an active row,
     * and a song with none lit the whole stack (`activeLyricRows` did the same).
     */
    fun activeRows(lines: List<LyricLine>, positionMs: Long): List<Int> {
        if (lines.isEmpty()) return emptyList()
        val wordByWord = lines.any { it.isWordSynced }
        val predictive = positionMs + LINE_LEAD_MS
        val overlapTime = positionMs + max(
            OVERLAP_MIN_LEAD_MS,
            LINE_LEAD_MS - if (wordByWord) OVERLAP_DELAY_WORD_MS else OVERLAP_DELAY_LINE_MS,
        )
        val out = ArrayList<Int>(2)
        var lineActive = false
        var maxActiveEnd = Long.MIN_VALUE
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isGap) {
                val end = line.sungUntilMs ?: lines.getOrNull(i + 1)?.timeMs ?: continue
                val at = positionMs + GAP_LOOK_AHEAD_MS
                if (line.timeMs <= at && at < end) out += i
                continue
            }
            if (line.timeMs > predictive) break
            val end = lineEnd(lines, i)
            val startCheck = if (lineActive && line.timeMs < maxActiveEnd) overlapTime else predictive
            val next = lines.getOrNull(i + 1)?.takeUnless { it.isGap }
            val endCheck = when {
                next != null && next.timeMs < end -> overlapTime
                next != null && predictive >= next.timeMs -> predictive
                else -> positionMs
            }
            if (startCheck >= line.timeMs && endCheck < end) {
                out += i
                lineActive = true
                maxActiveEnd = max(maxActiveEnd, end)
            }
        }
        if (out.isEmpty()) {
            val latest = lines.indexOfLast { it.timeMs <= predictive }
            if (latest >= 0) out += latest
        }
        return out
    }

    /** A line's end for [activeRows]: its own, or Metrolist's guess; a hair over the next start is cut there. */
    internal fun lineEnd(lines: List<LyricLine>, index: Int): Long {
        val line = lines[index]
        val nextIndex = (index + 1 until lines.size).firstOrNull { !lines[it].isGap }
        val nextStart = nextIndex?.let { lines[it].timeMs }
        var end = if (line.hasKnownEnd) line.endMs else unknownEnd(line.timeMs, nextStart)
        // The gap after it starts 310 ms after the singing stopped; the line stops there.
        lines.getOrNull(index + 1)?.takeIf { it.isGap && it.timeMs > line.timeMs }?.let {
            end = minOf(end, max(line.timeMs, it.timeMs - GAP_START_LAG_MS))
        }
        if (nextStart != null && end > nextStart && end - nextStart < LINE_OVERLAP_MIN_MS) end = nextStart
        return end
    }

    // ---- §9 Romanization ----------------------------------------------------------------------

    /** §9.1's languages, each with its own switch (all on by default). */
    enum class RomanLanguage(val label: String) {
        JAPANESE("Japanese"),
        KOREAN("Korean"),
        CHINESE("Chinese"),
        ARABIC("Arabic"),
        HINDI("Hindi"),
        PUNJABI("Punjabi"),
        THAI("Thai"),
        RUSSIAN("Russian"),
        UKRAINIAN("Ukrainian"),
        SERBIAN("Serbian"),
        BULGARIAN("Bulgarian"),
        BELARUSIAN("Belarusian"),
        KYRGYZ("Kyrgyz"),
        MACEDONIAN("Macedonian"),
    }

    /**
     * Which of [RomanLanguage] a line is in, from its script; null for Latin text and for scripts
     * the list does not name. Han without kana is Chinese unless the [sheet] has kana; Cyrillic is
     * told apart by the letters only one language uses, read over the whole [sheet].
     */
    fun romanLanguage(text: String, sheet: String = text): RomanLanguage? {
        var han = false
        var cyrillic = false
        for (ch in text) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HANGUL -> return RomanLanguage.KOREAN
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return RomanLanguage.JAPANESE
                Character.UnicodeScript.HAN -> han = true
                Character.UnicodeScript.ARABIC -> return RomanLanguage.ARABIC
                Character.UnicodeScript.DEVANAGARI -> return RomanLanguage.HINDI
                Character.UnicodeScript.GURMUKHI -> return RomanLanguage.PUNJABI
                Character.UnicodeScript.THAI -> return RomanLanguage.THAI
                Character.UnicodeScript.CYRILLIC -> cyrillic = true
                else -> Unit
            }
        }
        if (han) {
            val kana = sheet.any {
                val script = Character.UnicodeScript.of(it.code)
                script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
            }
            return if (kana) RomanLanguage.JAPANESE else RomanLanguage.CHINESE
        }
        if (cyrillic) return cyrillicLanguage(sheet.lowercase())
        return null
    }

    private fun cyrillicLanguage(sheet: String): RomanLanguage = when {
        sheet.any { it == 'ў' } -> RomanLanguage.BELARUSIAN
        sheet.any { it in "їєґ" } -> RomanLanguage.UKRAINIAN
        sheet.any { it in "ѓќѕ" } -> RomanLanguage.MACEDONIAN
        sheet.any { it in "ђћ" } -> RomanLanguage.SERBIAN
        sheet.any { it in "өүң" } -> RomanLanguage.KYRGYZ
        sheet.any { it == 'і' } -> RomanLanguage.UKRAINIAN
        sheet.any { it in "љњџј" } -> RomanLanguage.SERBIAN
        sheet.any { it in "ыэё" } -> RomanLanguage.RUSSIAN
        sheet.any { it == 'ъ' } -> RomanLanguage.BULGARIAN
        else -> RomanLanguage.RUSSIAN
    }

    /**
     * §9.1: a romanized sheet with the lines in a switched-off language put back to the original, so
     * nothing is drawn under them (the panel skips a sub-line equal to its line). Scripts outside
     * the list (Greek, Hebrew…) keep Google's romanization, as BitChord had it.
     */
    fun romanizedFor(originals: List<LyricLine>, romanized: List<LyricLine>, enabled: Set<RomanLanguage>): List<LyricLine> {
        if (enabled.size == RomanLanguage.entries.size) return romanized
        val sheet = originals.joinToString("\n") { it.text }
        return romanized.mapIndexed { index, line ->
            val original = originals.getOrNull(index) ?: return@mapIndexed line
            val language = romanLanguage(original.text, sheet)
            val background = original.background?.let { source ->
                val sourceLanguage = romanLanguage(source.text, sheet)
                if (sourceLanguage != null && sourceLanguage !in enabled) source else line.background
            } ?: line.background
            if (language != null && language !in enabled) original.copy(background = background) else line.copy(background = background)
        }
    }
}
