package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.ForkLyricsRules
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
 * Fork: the lyrics rulebook's 17 cases (spec/lyrics/lyrics-cases.json), run as the extension's
 * `scripts/lyrics_cases_tests.mjs` runs them, against BitChord's own functions:
 *
 *  - `currentLine`: [ForkLyricsRules.currentLine];
 *  - `offset`: [ForkLyricsRules.combinedOffset] / `lyricsPosition` / `seekTarget`, and the panel's own
 *    [adjustedLyricsPosition] / [adjustedLyricsSeekTarget] fed the offset in BitChord's stored sign
 *    ([ForkLyricsRules.storedFromRule]), so both agree with the rule;
 *  - `sides`: [ForkLyricsRules.sides], VIEW and CENTER both "center" (the view's alignment is centre in the runner);
 *  - `gaps`: [ForkLyricsRules.gapSpans].
 *
 * The rulebook's folder: `RULEBOOK_SPEC`, else `/spec`; missing fails the test (skipped on CI).
 */
class ForkLyricsCasesTest {

    private fun long(e: JsonElement?): Long = (e as? JsonPrimitive)?.longOrNull ?: 0L

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
            val got = ForkLyricsRules.gapSpans(lines.map { long(it["start"]) }, lines.map { long(it["end"]) })
                .map { listOf(it.startMs, it.endMs) }
            check("gaps", c, got, (c["expect"] as JsonArray).map { (it as JsonArray).map(::long) })
        }

        println("lyrics cases: $ok/$total ok (17 cases, the offset cases checked twice)")
        assertTrue(fails.isEmpty(), fails.joinToString("\n"))
    }
}
