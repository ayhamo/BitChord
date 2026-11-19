package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.fail

/**
 * The shared addon rulebook (YT Music Extension `spec/addons/`), run against
 * this fork's own code: `matcher-cases.json` against [TrackMatcher], and
 * `pick-cases.json`'s `rowsToOpen` against [ForkAddonPick]. The extension's
 * `scripts/addon_matcher_fixtures.mjs` and `addon_pick_tests.mjs` and the
 * phone's `PickCasesTest` read the same files, so a rule changed there shows
 * up here as a failing case rather than as a port quietly drifting.
 *
 * The spec directory is `RULEBOOK_SPEC`, else `/spec` (the Docker build mounts
 * it there). Missing, the test fails rather than passing on nothing.
 *
 * The pick-cases' other groups (tier, kbps, probe, parameters, stream answers,
 * base URLs) read the phone's addon code and are in the app's
 * `ForkRulebookPickTest`.
 */
class ForkRulebookTest {

    private val spec: File by lazy {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.ifBlank { null } ?: "/spec")
        if (!File(dir, "addons/matcher-cases.json").isFile) {
            fail("rulebook not found: set RULEBOOK_SPEC to the extension's spec directory (looked in ${dir.absolutePath})")
        }
        dir
    }

    private fun read(name: String): JsonObject =
        Json.parseToJsonElement(File(spec, "addons/$name").readText()).jsonObject

    @Test
    fun matcherCases() {
        val doc = read("matcher-cases.json")
        val failures = mutableListOf<String>()
        var n = 0
        for (c in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
            n++
            val target = targetOf(c.getValue("target").jsonObject)
            val rows = c.getValue("candidates").jsonArray.map { it.jsonObject }
            val songs = rows.map { songOf(it) }
            val scores = songs.mapNotNull { s -> TrackMatcher.score(s, target)?.let { s.videoId to it } }.toMap()
            val ranked = TrackMatcher.ranked(songs, target).map { it.videoId }
            val got = mapOf(
                "pick" to ranked.firstOrNull(),
                "ranked" to ranked,
                "scores" to scores,
                "rejected" to songs.map { it.videoId }.filter { it !in scores },
            )
            val e = c.getValue("expect").jsonObject
            val want = mapOf(
                "pick" to e["pick"]?.textOrNull(),
                "ranked" to e.getValue("ranked").jsonArray.map { it.jsonPrimitive.content },
                "scores" to e.getValue("scores").jsonObject.mapValues { it.value.jsonPrimitive.content.toDouble().toInt() },
                "rejected" to e.getValue("rejected").jsonArray.map { it.jsonPrimitive.content },
            )
            if (got != want) failures += "matcher: ${c.str("name")}\n   got  $got\n   want $want"
        }
        for (q in doc["queries"]?.jsonArray.orEmpty().map { it.jsonObject }) {
            n++
            val got = TrackMatcher.queries(targetOf(q.getValue("target").jsonObject))
            val want = q.getValue("expect").jsonArray.map { it.jsonPrimitive.content }
            if (got != want) failures += "queries: ${q.str("name")}\n   got  $got\n   want $want"
        }
        report("matcher-cases", n, failures)
    }

    @Test
    fun rowsToOpenCases() {
        val failures = mutableListOf<String>()
        val cases = read("pick-cases.json").getValue("rowsToOpen").jsonArray.map { it.jsonObject }
        for (c in cases) {
            val t = c.getValue("target").jsonObject
            val opt = c["opt"]?.jsonObject ?: JsonObject(emptyMap())
            val picked = ForkAddonPick.rowsToOpen(
                c.getValue("rows").jsonArray.map { it.jsonObject },
                targetOf(t),
                ForkAddonPick.Opt(
                    slackSec = opt["slackSec"]?.numOrNull() ?: ForkAddonPick.DEFAULT_SLACK_SEC,
                    forDownload = opt["forDownload"]?.flagOrNull() ?: false,
                ),
                isrc = t["isrc"]?.textOrNull(),
            ) { rowOf(it) }
            val got = picked.open.map { it.str("id") } to picked.refused.map { it.id to it.why }
            val e = c.getValue("expect").jsonObject
            val want = e.getValue("open").jsonArray.map { it.jsonPrimitive.content } to
                e.getValue("refused").jsonArray.map { it.jsonObject.str("id") to it.jsonObject.str("why") }
            if (got != want) failures += "rowsToOpen: ${c.str("name")}\n   got  $got\n   want $want"
        }
        report("pick-cases rowsToOpen", cases.size, failures)
    }

    /** paramsFor's atmos value (both apps send it when Atmos is not wanted): an "off" option, else false. */
    @Test
    fun atmosOffValue() {
        val cases = listOf(
            Triple(emptyList<String>(), "true", "false"),
            Triple(listOf("auto", "only", "off"), "auto", "off"),
            Triple(listOf("Stereo", "Atmos"), "Atmos", "Stereo"),
            Triple(listOf("1", "0"), "1", "0"),
            Triple(listOf("auto", "strict"), "strict", "false"),
        )
        val failures = cases.mapNotNull { (options, default, want) ->
            val got = ForkAddonPick.atmosOff(options, default)
            if (got == want) null else "$options (default $default): got $got, want $want"
        }
        report("atmosOff", cases.size, failures)
    }

    private fun report(what: String, n: Int, failures: List<String>) {
        println("$what: ${n - failures.size}/$n pass")
        if (failures.isNotEmpty()) fail("$what: ${failures.size} of $n differ\n" + failures.joinToString("\n"))
    }

    // ── Reading the cases ─────────────────────────────────────────────────

    /** A rulebook target: whole seconds, as a queue row carries them; 0 is passed through (the pick reads it as unknown). */
    private fun targetOf(t: JsonObject) = TrackMatcher.Target(
        title = t.str("title"),
        artist = t.str("artist"),
        durationSec = t["durationSec"]?.numOrNull()?.toInt(),
        album = t["album"]?.textOrNull(),
        isExplicit = t["isExplicit"]?.flagOrNull(),
        isVideo = t["isVideo"]?.flagOrNull() ?: false,
    )

    /**
     * A matcher candidate as an addon row reaches the matcher in this app: its
     * runtime cut to whole seconds (`AddonTrack.durationSec`), as `m:ss`.
     */
    private fun songOf(r: JsonObject) = Song(
        videoId = r.str("id"),
        title = r.str("title"),
        artist = r.str("artist"),
        thumbnailUrl = null,
        albumName = r["album"]?.textOrNull(),
        durationText = r["durationSec"]?.numOrNull()?.takeIf { it > 0 }?.toInt()
            ?.let { "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}" },
        isExplicit = r["explicit"]?.flagOrNull(),
    )

    /**
     * An addon's raw row as the rulebook reads it (`pick.js` `rowOf`). The
     * phone's own reading of the same JSON (`AddonTrack`) is checked by the
     * app's `ForkRulebookPickTest`, end to end through `AddonSource.search`.
     */
    private fun rowOf(r: JsonObject): ForkAddonPick.Row {
        val flags = listOf("atmos", "isAtmos", "isAtmosOnly").any { r[it]?.flagOrNull() == true }
        val modes = listOfNotNull(r["audioMode"], r["format"], r["audioQuality"]).map { it.textOrNull().orEmpty() } +
            (r["audioModes"] as? JsonArray).orEmpty().map { it.textOrNull().orEmpty() }
        return ForkAddonPick.Row(
            id = r.str("id"),
            title = r.str("title"),
            artist = r.str("artist"),
            album = r["album"]?.textOrNull(),
            durationSec = r["duration"]?.numOrNull(),
            explicit = r["explicit"]?.flagOrNull(),
            isrc = ForkAddonPick.isrcOf(r["isrc"]?.textOrNull()),
            atmos = flags || modes.any { ATMOS.containsMatchIn(it) },
        )
    }

    private fun JsonObject.str(key: String) = this[key]?.textOrNull().orEmpty()

    private fun JsonElement.textOrNull(): String? =
        if (this is JsonNull) null else (this as? JsonPrimitive)?.content

    private fun JsonElement.numOrNull(): Double? = textOrNull()?.toDoubleOrNull()

    private fun JsonElement.flagOrNull(): Boolean? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.booleanOrNull?.let { return it }
        return when (p.content.trim().lowercase(Locale.ROOT)) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> p.content.toDoubleOrNull()?.let { it != 0.0 }
        }
    }

    private val ATMOS = Regex("atmos|dolby|eac3[_-]?joc|ec-?3", RegexOption.IGNORE_CASE)
}
