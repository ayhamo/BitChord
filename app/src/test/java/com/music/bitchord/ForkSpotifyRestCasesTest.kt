package com.music.bitchord

import com.music.bitchord.data.canvas.SpotifyCanvas
import com.music.bitchord.fork.ForkSpotifyMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Fork: the canvas rulebook's `spotifyMatch` cases (§4.4) on the phone's REST search path,
 * `SpotifyCanvas.searchViaRest`, the search after Pathfinder.
 *
 * Each case's found song is written as a Web API search track (`name`, `artists[].name` with the
 * found primary first, `album.name`, `duration_ms`) and judged by [ForkSpotifyMatch.restMatches], the
 * function the path calls; `searchViaRest` is pinned to that call, so an upstream merge that brings
 * back its own check fails here. Upstream's `isMatch` is run on the same tracks for the record (not
 * asserted): the report prints how many it got.
 *
 * The rulebook's folder: `RULEBOOK_SPEC`, else `/spec`; missing fails the test (skipped on CI).
 */
class ForkSpotifyRestCasesTest {

    private val isMatch = SpotifyCanvas::class.java.getDeclaredMethod(
        "isMatch", String::class.java, List::class.java, String::class.java, String::class.java,
    ).apply { isAccessible = true }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.ms(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull?.takeIf { it > 0 }

    /** The found artists in credit order: the found primary first when the case names one. */
    private fun artists(found: String, primary: String?): List<String> =
        if (primary != null && found.startsWith(primary) && found != primary) {
            listOf(primary) + found.removePrefix(primary).removePrefix(",").trim().takeIf { it.isNotEmpty() }.let(::listOfNotNull)
        } else listOf(found)

    @Test
    fun spotifyMatchCasesOnTheRestPath() {
        val dir = System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec"
        val file = File(dir, "canvas/canvas-cases.json")
        if (!file.isFile) { org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true"); fail("the canvas rulebook cases are not at $file: set RULEBOOK_SPEC to the spec folder (or mount it at /spec)") }
        val all = Json.parseToJsonElement(file.readText()) as JsonObject
        val fails = ArrayList<String>()
        var pass = 0
        var upstreamPass = 0
        val cases = all["spotifyMatch"] as JsonArray
        for (e in cases) {
            val c = e as JsonObject
            val i = c["input"] as JsonObject
            val want = (c["expect"] as JsonPrimitive).booleanOrNull
            val credited = artists(i.str("foundArtist").orEmpty(), i.str("foundPrimary"))
            val track = buildJsonObject {
                put("name", i.str("foundTitle"))
                putJsonArray("artists") { credited.forEach { a -> addJsonObject { put("name", a) } } }
                i.str("foundAlbum")?.let { a -> putJsonObject("album") { put("name", a) } }
                i.ms("foundDurationMs")?.let { put("duration_ms", it) }
            }
            val title = i.str("title").orEmpty()
            val artist = i.str("artist").orEmpty()
            val got = ForkSpotifyMatch.restMatches(track, title, artist, i.str("album"), i.ms("durationMs"), i.str("primary"))
            if (got == want) pass++ else fails += "'${c.str("name")}': got $got, expected $want"
            if (isMatch.invoke(SpotifyCanvas, i.str("foundTitle").orEmpty(), credited, title, artist) == want) upstreamPass++
        }
        // the REST search still calls it
        val rel = "src/main/java/com/music/bitchord/data/canvas/SpotifyCanvas.kt"
        val src = listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()
        val rest = src.substringAfter("private fun searchViaRest(").substringBefore("suspend fun searchAlbum(")
        if (!rest.contains("ForkSpotifyMatch.restMatches(track, title, artist, album)") || rest.contains("if (!isMatch(")) {
            fails += "SpotifyCanvas.searchViaRest no longer judges its hits by ForkSpotifyMatch.restMatches (rule 4.4)"
        }
        val report = "spotifyMatch on the REST path: $pass/${cases.size} pass (upstream's isMatch on the same tracks: $upstreamPass/${cases.size})" +
            fails.joinToString("") { "\n  FAIL $it" }
        println(report)
        assertTrue(report, fails.isEmpty())
    }
}
