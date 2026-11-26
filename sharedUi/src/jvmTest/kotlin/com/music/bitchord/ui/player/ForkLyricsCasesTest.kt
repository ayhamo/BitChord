package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.EnhancedLrc
import com.music.bitchord.data.lyrics.ForkLyricsRules
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fork: the lyrics rulebook's cases (spec/lyrics/lyrics-cases.json), run as the extension's
 * `scripts/lyrics_cases_tests.mjs` runs them, against BitChord's own functions:
 *
 *  - `currentLine`: [ForkLyricsRules.currentLine];
 *  - `offset`: [ForkLyricsRules.combinedOffset] / `lyricsPosition` / `seekTarget`, and the panel's own
 *    [adjustedLyricsPosition] / [adjustedLyricsSeekTarget] fed the offset in BitChord's stored sign
 *    ([ForkLyricsRules.storedFromRule]), so both agree with the rule;
 *  - `sides`: [ForkLyricsRules.sides], VIEW and CENTER both "center" (the view's alignment is centre in the runner);
 *  - `gaps`: [ForkLyricsRules.gapSpans] over [ForkLyricsRules.timelineStart]s;
 *  - `parse`, `lrcOffset` (spec 0836435): upstream's own parsers, run and reported but not counted.
 *
 * The rulebook's folder: `RULEBOOK_SPEC`, else `/spec`; missing fails the test (skipped on CI).
 */
class ForkLyricsCasesTest {

    private fun long(e: JsonElement?): Long = (e as? JsonPrimitive)?.longOrNull ?: 0L

    /** A parsed line as the cases write it: [start] or [start, [[text, start] ..., [text, start, "bg"] ...]]. */
    private fun shape(line: LyricLine): List<Any> {
        val words = line.words.map { listOf<Any>(it.text, it.startMs) } +
            line.background?.words.orEmpty().map { listOf<Any>(it.text, it.startMs, "bg") }
        return if (words.isEmpty()) listOf(line.timeMs) else listOf(line.timeMs, words)
    }

    @Test
    fun lyricsCasesRunAgainstBitChordsCode() {
        val dir = System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec"
        val file = File(dir, "lyrics/lyrics-cases.json")
        if (!file.isFile) {
            org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true")
            fail("the lyrics rulebook cases are not at $file: set RULEBOOK_SPEC to the spec folder (or mount it at /spec)")
        }
        val all = Json.parseToJsonElement(file.readText()) as JsonObject
        val fails = ArrayList<String>()
        var ok = 0
        var total = 0
        fun check(group: String, c: JsonObject, got: Any?, want: Any?) {
            total++
            if (got == want) ok++ else fails += "$group '${(c["name"] as JsonPrimitive).contentOrNull}': got $got, expected $want"
        }

        for (e in all["currentLine"] as JsonArray) {
            val c = e as JsonObject
            val i = c["input"] as JsonObject
            val got = ForkLyricsRules.currentLine((i["starts"] as JsonArray).map(::long), long(i["position"]))
            check("currentLine", c, got.toLong(), long(c["expect"]))
        }

        for (e in all["offset"] as JsonArray) {
            val c = e as JsonObject
            val i = c["input"] as JsonObject
            val offset = ForkLyricsRules.combinedOffset(long(i["song"]).toInt(), long(i["global"]).toInt())
            val lyrics = ForkLyricsRules.lyricsPosition(long(i["player"]), offset)
            val lyricTime = (i["lyricTime"] as? JsonPrimitive)?.longOrNull ?: lyrics
            val seekTo = ForkLyricsRules.seekTarget(lyricTime, offset)
            val want = c["expect"] as JsonObject
            check("offset", c, listOf(offset.toLong(), lyrics, seekTo), listOf(long(want["offset"]), long(want["lyrics"]), long(want["seekTo"])))
            // The panel's own position and seek, given the stored (BitChord-sign) offset.
            val stored = ForkLyricsRules.storedFromRule(offset)
            check("offset (panel)", c, listOf(adjustedLyricsPosition(long(i["player"]), stored), adjustedLyricsSeekTarget(lyricTime, stored)), listOf(lyrics.coerceAtLeast(0L), seekTo))
        }

        for (e in all["sides"] as JsonArray) {
            val c = e as JsonObject
            val i = c["input"] as JsonObject
            val singers = (i["lines"] as JsonArray).map { if (it is JsonNull) null else (it as JsonPrimitive).content }
            val types = (i["agents"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
            val respect = (i["respect"] as? JsonPrimitive)?.booleanOrNull ?: true
            val got = ForkLyricsRules.sides(singers, types, respect).map {
                when (it) {
                    ForkLyricsRules.Side.LEFT -> "left"
                    ForkLyricsRules.Side.RIGHT -> "right"
                    ForkLyricsRules.Side.CENTER, ForkLyricsRules.Side.VIEW -> "center"
                }
            }
            check("sides", c, got, (c["expect"] as JsonArray).map { (it as JsonPrimitive).content })
        }

        for (e in all["gaps"] as JsonArray) {
            val c = e as JsonObject
            val lines = ((c["input"] as JsonObject)["lines"] as JsonArray).map { it as JsonObject }
            // A line starts at its timelineStart: its tag, or its first timed word when earlier (0836435).
            val starts = lines.map { l ->
                val words = (l["words"] as? JsonArray).orEmpty().map { w ->
                    val word = w as JsonObject
                    LyricWord(long(word["start"]), long(word["end"]), "w")
                }
                ForkLyricsRules.timelineStart(LyricLine(long(l["start"]), "line", words = words))
            }
            val got = ForkLyricsRules.gapSpans(starts, lines.map { long(it["end"]) })
                .map { listOf(it.startMs, it.endMs) }
            check("gaps", c, got, (c["expect"] as JsonArray).map { (it as JsonArray).map(::long) })
        }

        // parse and lrcOffset (spec 0836435, §3.5, §5.6, §11) test upstream BitChord's own LRC
        // parsers (EnhancedLrc, LrcLib.parseLrc), which the fork leaves as they are (owner,
        // 2026-11-24: rules that would change upstream's lyrics logic are upstream's own, not ours).
        // They are run and reported, not counted. A line is its start and, for word lines,
        // [text, start] per word, its background vocal's words marked "bg"; BitChord's own gap lines
        // (blank stamps) are not lines of the file and are left out.
        var upstreamOk = 0
        var upstreamTotal = 0
        for (group in listOf("parse", "lrcOffset")) {
            for (e in (all[group] as? JsonArray).orEmpty()) {
                val c = e as JsonObject
                val i = c["input"] as JsonObject
                val text = (i["text"] as JsonPrimitive).content
                val parsed = if ((i["format"] as JsonPrimitive).content == "enhancedLrc") EnhancedLrc.parse(text) else LrcLib.parseLrc(text)
                val got = parsed.filterNot { it.isGap }.map(::shape)
                val want = (c["expect"] as JsonArray).map { line ->
                    val o = line as JsonObject
                    val words = (o["words"] as? JsonArray)?.map { w ->
                        (w as JsonArray).map { p -> (p as JsonPrimitive).longOrNull ?: p.content }
                    }
                    if (words == null) listOf<Any>(long(o["start"])) else listOf(long(o["start"]), words)
                }
                upstreamTotal++
                if (got == want) upstreamOk++ else println("upstream's own, not ours: $group '${(c["name"] as JsonPrimitive).contentOrNull}': got $got, expected $want")
            }
        }
        println("upstream's parsers: $upstreamOk/$upstreamTotal of the parse and lrcOffset cases (not counted)")

        println("lyrics cases: $ok/$total ok (the offset cases checked twice)")
        assertTrue(fails.isEmpty(), fails.joinToString("\n"))
    }
}
