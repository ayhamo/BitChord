package com.music.bitchord.data.sources

/**
 * Fork (rulebook protocol.md §7 "Mashups", 2026-11-24; not in upstream's matcher): a mashup's title
 * holds whole song titles, so one of them found inside it is another song. [TrackMatcher]'s "one core
 * contains the other" branch and its credit override (a row within 2 s with nobody credited in
 * common) both let one in: YouTube `6Edmp3yHv1s`, "LET ME LOVE YOU x GATA ONLY" by Moonshine, took
 * Tidal's "Let Me Love You" by Bonnie X Clyde, refused by the fit only after the download. For a
 * mashup both are off: the cores must match another way and the row must share an artist. A wrong-song
 * bug, hence a marked patch on upstream's matcher (two `// fork:` conditions).
 *
 * A mashup: a `mashup`, `mash-up` or `medley` word anywhere in the title, or, in one part of it
 * (bracketed asides and ` - ` parts apart, a part that is just the credited artist skipped), two or
 * more pieces joined by ` x `, ` × `, ` vs `, ` vs. ` or ` / `, none of them the credited artist. A
 * piece that is the credited artist makes it a credit ("Sigala x Ella Eyre - Came Here For Love"). A
 * title taken for a mashup by mistake only makes its match stricter, never lets a wrong row in.
 *
 * The rulebook reads the YouTube title only; the matcher also reads the row's (audit 2026-11-24,
 * 2B #3: "Let Me Love You x Gata Only" by Moonshine, a row, took "Let Me Love You" by DJ Snake by
 * containment and the credit override). That side only turns the same two ways off, so it can
 * only make a match stricter, which the rulebook allows.
 */
object ForkMashup {

    private val WORD = Regex("""(?:^|[^\p{L}\p{N}])(?:mash[\s-]?ups?|medleys?)(?:$|[^\p{L}\p{N}])""")
    private val JOIN = Regex("""\s+(?:x|×|vs\.?|/)\s+""")
    private val BRACKETED = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    private val DASH = Regex("""\s+[-–—|]+\s+""")
    private val WORD_SPLIT = Regex("""[\s.·~〜]+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]""")

    fun isMashup(title: String?, artist: String?): Boolean {
        val text = TrackMatcher.normalize(title.orEmpty())
        val credit = artist.orEmpty()
        if (WORD.containsMatchIn(text)) return true
        // innermost brackets first, again until none are left: "(a (b) c)" goes whole
        var plain = text
        while (BRACKETED.containsMatchIn(plain)) plain = BRACKETED.replace(plain, " ")
        // an unbalanced bracket (a title cut mid-aside) takes the rest with it, as the matcher's parseTitle does
        plain.indexOfFirst { it == '(' || it == '[' }.takeIf { it >= 0 }?.let { plain = plain.substring(0, it) }
        return plain
            .split(DASH)
            .filter { !isArtistName(it, credit) }
            .any { part ->
                val sides = part.split(JOIN).map { it.trim() }.filter { wordsOf(it).isNotEmpty() }
                sides.size >= 2 && sides.none { isArtistName(it, credit) }
            }
    }

    private fun wordsOf(text: String) =
        text.split(WORD_SPLIT).map { it.replace(NON_ALNUM, "") }.filter { it.isNotEmpty() }

    /**
     * The matcher's own test: [text] is nothing but words of [artist], Latin accents folded on both
     * sides as the matcher's `artistNames` folds them ("Tiesto x Martin Garrix" by "Tiësto").
     */
    private fun isArtistName(text: String, artist: String): Boolean {
        if (artist.isBlank()) return false
        val words = wordsOf(TrackMatcher.foldLatin(text))
        if (words.isEmpty()) return false
        val credited = wordsOf(TrackMatcher.foldLatin(TrackMatcher.normalize(artist))).toSet()
        return words.all { it in credited }
    }
}
