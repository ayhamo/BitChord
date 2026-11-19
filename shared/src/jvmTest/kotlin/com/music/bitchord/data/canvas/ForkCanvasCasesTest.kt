package com.music.bitchord.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fork: the shared canvas rulebook's cases (`spec/canvas/canvas-cases.json`, `canvas.md`) run
 * against the fork's code, the way the owner's phone extension runs them (`CanvasCasesTest.java`).
 *
 * The rulebook lives outside this repo (the YT Music Extension project). Its folder comes from
 * `RULEBOOK_SPEC`, else `/spec` (the Docker build mounts it there); a missing file fails the test
 * rather than passing it with nothing run.
 *
 * Every group is run. What the fork has no code for is counted as skipped, with the reason; the
 * groups whose code is upstream Android or Compose code (Apple's scoring, the mesh lift, the badge
 * band) also run in `app` / `sharedUi` tests against the real functions (see FORK.md). The
 * per-group tally is printed and written to `build/canvas-cases.txt`.
 */
class ForkCanvasCasesTest {

    private val w = ForkClipJudge.W
    private val h = ForkClipJudge.H

    private class Tally {
        var pass = 0
        var skip = 0
        val fails = ArrayList<String>()
        val skipWhy = LinkedHashSet<String>()
    }

    private val tallies = LinkedHashMap<String, Tally>()

    private fun tally(group: String) = tallies.getOrPut(group) { Tally() }

    private fun check(group: String, c: JsonObject, ok: Boolean, got: Any?) {
        val t = tally(group)
        if (ok) t.pass++ else t.fails += "$group '${c.str("name")}': got $got, expected ${c["expect"]}"
    }

    private fun skip(group: String, why: String) {
        val t = tally(group)
        t.skip++
        t.skipWhy += why
    }

    @Test
    fun everyCanvasCase() {
        val dir = System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec"
        val file = File(dir, "canvas/canvas-cases.json")
        if (!file.isFile) { org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true"); fail("the canvas rulebook cases are not at $file: set RULEBOOK_SPEC to the spec folder (or mount it at /spec)") }
        val all = Json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)) as JsonObject
        for ((group, cases) in all) {
            if (group == "notes") continue
            for (c in cases as JsonArray) run(group, c as JsonObject)
        }
        val report = StringBuilder("canvas cases (${file.path}):\n")
        val fails = ArrayList<String>()
        var pass = 0
        var skip = 0
        var total = 0
        for ((group, t) in tallies) {
            val n = t.pass + t.skip + t.fails.size
            pass += t.pass
            skip += t.skip
            total += n
            report.append(String.format(Locale.ROOT, "  %-13s %3d/%-3d pass, %d skipped%s%n", group, t.pass, n, t.skip,
                if (t.skipWhy.isEmpty()) "" else " (" + t.skipWhy.joinToString("; ") + ")"))
            fails += t.fails
        }
        report.append(String.format(Locale.ROOT, "  total %d/%d pass, %d skipped, %d failed%n", pass, total, skip, fails.size))
        for (f in fails) report.append("  FAIL ").append(f).append('\n')
        print(report)
        runCatching { File("build").mkdirs(); File("build/canvas-cases.txt").writeText(report.toString()) }
        assertTrue(fails.isEmpty(), report.toString())
    }

    // ---- reading -----------------------------------------------------------------------------

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject
    private fun JsonObject.arr(key: String): JsonArray = this[key] as JsonArray
    private fun JsonObject.num(key: String): Double = num(this[key])
    private fun JsonObject.lng(key: String): Long = Math.round(num(key))
    private fun JsonObject.orNull(key: String): Long? = this[key]?.takeIf { it !is JsonNull }?.let { Math.round(num(it)) }
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun num(e: JsonElement?): Double = (e as? JsonPrimitive)?.doubleOrNull ?: 0.0
    private fun isNull(e: JsonElement?) = e == null || e is JsonNull
    private fun near(a: Double, b: Double, tol: Double) = abs(a - b) <= tol

    /** A 32 x 18 grid as the notes write it: flat, texture with bars, or columns. */
    private fun frame(o: JsonElement): IntArray {
        val f = o as JsonObject
        val g = IntArray(w * h)
        when {
            f.containsKey("flat") -> g.fill(f.lng("flat").toInt())
            f.containsKey("cols") -> {
                val cols = f.arr("cols")
                for (y in 0 until h) for (x in 0 until w) g[y * w + x] = Math.round(num(cols[x])).toInt()
            }
            else -> {
                val seed = f.lng("texture").toInt()
                val bar = f.lng("barValue").toInt()
                val b = f.arr("bars").map { Math.round(num(it)).toInt() }
                for (y in 0 until h) for (x in 0 until w) {
                    val inBar = x < b[0] || x >= w - b[2] || y < b[1] || y >= h - b[3]
                    g[y * w + x] = if (inBar) bar else 60 + (x * 37 + y * 11 + seed * 53) % 120
                }
            }
        }
        return g
    }

    private fun frames(o: JsonElement?): List<IntArray> = (o as JsonArray).map(::frame)

    /** A timed list: the frames, and their times. */
    private fun timed(o: JsonElement?): Pair<List<IntArray>, LongArray> {
        val m = o as JsonObject
        val fps30 = m.bool("fps30")
        val every = if (fps30) 0 else m.lng("everyMs")
        val out = ArrayList<IntArray>()
        val times = ArrayList<Long>()
        for (run in m.arr("runs")) {
            val r = run as JsonArray
            val f = frame(r[1])
            repeat(Math.round(num(r[0])).toInt()) {
                val i = out.size
                times += if (fps30) Math.floor(i * 1000.0 / 30).toLong() else i * every
                out += f
            }
        }
        return out to times.toLongArray()
    }

    private fun floats(o: JsonElement?): FloatArray = (o as JsonArray).map { num(it).toFloat() }.toFloatArray()

    private fun jwt(header: JsonElement?, payload: JsonElement?): String {
        val e = Base64.getUrlEncoder().withoutPadding()
        return e.encodeToString(header.toString().toByteArray()) + "." + e.encodeToString(payload.toString().toByteArray()) + ".sig"
    }

    /** A w x h ARGB frame with [bar] black rows above and below (-1: all black). */
    private fun letterboxed(width: Int, height: Int, bar: Int): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        if (bar < 0 || y < bar || y >= height - bar) 0xFF000000.toInt() else 0xFF808080.toInt() + ((x * 7 + y * 3) and 0x3F)
    }

    private fun sources(e: JsonElement?): List<ForkCanvasRules.Source> =
        (e as? JsonArray).orEmpty().map { ForkCanvasRules.Source.valueOf((it as JsonPrimitive).content) }

    // ---- the run -----------------------------------------------------------------------------

    private fun run(g: String, c: JsonObject) {
        val want = c["expect"]
        when (g) {
            "sourceOrder" -> {
                val i = c.obj("input")
                val order = ForkCanvasRules.order(
                    custom = sources(i["order"]),
                    enabled = if (i.containsKey("enabled")) sources(i["enabled"]).toSet() else ForkCanvasRules.Source.entries.toSet(),
                    canvasOn = i.bool("canvasEnabled"),
                    coversOn = i.bool("coversEnabled"),
                    spotifyAvailable = i.bool("spotifyAvailable"),
                )
                val found = mapOf(
                    ForkCanvasRules.Source.YOUTUBE_MUSIC_VIDEO to i.str("video"),
                    ForkCanvasRules.Source.SPOTIFY to i.str("spotify"),
                    ForkCanvasRules.Source.APPLE_MUSIC to i.str("apple"),
                )
                val failed = i.arr("failed").map { (it as JsonPrimitive).content }.toSet()
                val got = ForkCanvasRules.select(found, order, failed, sources(i["pending"]).toSet())
                check(g, c, got == (want as? JsonPrimitive)?.contentOrNull, got)
            }
            "cache" -> {
                val i = c.obj("input")
                if (c.str("kind") == "lookup") {
                    val exists = i.bool("fileExists")
                    val got = when (ForkCanvasRules.readSaved(i.str("value"), { exists }, i.lng("now"))) {
                        null -> null
                        ForkCanvasRules.Saved.Miss -> "miss"
                        is ForkCanvasRules.Saved.Clip -> "clip"
                    }
                    check(g, c, got == (want as? JsonPrimitive)?.contentOrNull, got)
                } else {
                    val got = when (i.str("source")) {
                        "video" -> ForkCanvasRules.videoKey(i.str("songId")!!)
                        "spotify" -> ForkCanvasRules.spotifyKey(i.str("title")!!, i.str("artist")!!)
                        "apple" -> ForkCanvasRules.appleKey(i.str("title")!!, i.str("artist")!!, i.bool("tall"))
                        else -> return skip(g, "downloaded-song keys: the fork looks a downloaded song up like a streamed one (phone only)")
                    }
                    check(g, c, got == (want as JsonPrimitive).content, got)
                }
            }
            // The fork's scoring is upstream's AppleMusicCanvas.score (app) and DesktopAppleMusicCanvas.score
            // (desktopApp): ForkAppleCanvasCasesTest (app) runs these against the real function.
            "appleScore", "appleRank" -> skip(g, "run in app ForkAppleCanvasCasesTest against AppleMusicCanvas.score")
            "appleToken" -> {
                val jwts = c.arr("tokens").map { jwt((it as JsonObject)["header"], it["payload"]) }
                val rejected = (c["rejected"] as? JsonArray).orEmpty().map { jwts[Math.round(num(it)).toInt()] }.toSet()
                val got = ForkAppleCanvasRules.pickToken(jwts, c.lng("now"), rejected)
                check(g, c, if (isNull(want)) got == -1 else got.toLong() == Math.round(num(want)), got.takeIf { it >= 0 })
            }
            "appleMotion" -> {
                val video = c.obj("video")
                val m = ForkAppleCanvasRules.motion { key -> ForkAppleCanvasRules.assetUrl(video, key) }
                val ok = if (isNull(want)) m == null else {
                    val ex = want as JsonObject
                    m != null && m.primary == ex.str("primary") && m.alternate == ex.str("alternate") && m.tall == ex.str("tall")
                }
                check(g, c, ok, m)
            }
            "spotifyMatch" -> {
                val i = c.obj("input")
                val got = ForkSpotifyCanvasRules.matches(
                    i.str("title").orEmpty(), i.str("artist").orEmpty(), i.str("album"), i.orNull("durationMs"),
                    i.str("foundTitle").orEmpty(), i.str("foundArtist").orEmpty(), i.str("foundAlbum"), i.orNull("foundDurationMs"),
                    i.str("primary"), i.str("foundPrimary"),
                )
                check(g, c, got == (want as JsonPrimitive).booleanOrNull, got)
            }
            "spotifyMedia" -> {
                val got = ForkSpotifyCanvasRules.trusted(c.str("url"))
                check(g, c, got == (want as JsonPrimitive).booleanOrNull, got)
            }
            "spotifyTotp" -> {
                val got = ForkSpotifyCanvasRules.totp(c.lng("nowMs"))
                check(g, c, got == c.str("expect"), got)
            }
            "videoMatch" -> {
                val song = c.obj("song")
                val names = song.arr("artists").map { (it as JsonObject).str("name").orEmpty() }
                val videos = c.arr("videos").map {
                    val v = it as JsonObject
                    ForkMusicVideoMatch.Video(v.str("id")!!, v.str("title").orEmpty(), (v.arr("artists")[0] as JsonObject).str("name").orEmpty(), v.lng("durationSec"))
                }
                val got = ForkMusicVideoMatch.pick(song.str("title").orEmpty(), ForkMusicVideoMatch.artists(names.joinToString(" & ")), song.lng("durationSec"), videos)?.id
                check(g, c, got == (want as? JsonPrimitive)?.contentOrNull, got)
            }
            "bestPart" -> {
                val v = c.arr("intensity").map { num(it) }.toDoubleArray()
                val got = ForkBestPart.peakMs(v, c.lng("segmentMs"), c.lng("durationMs"))
                check(g, c, if (isNull(want)) got < 0 else got >= 0 && near(got.toDouble(), num(want), 0.5), got.takeIf { it >= 0 })
            }
            "noFrames" -> {
                val got = ForkClipJudge.noFrames(c.lng("lengthMs"))
                val ex = want as JsonArray
                check(g, c, got[0] == Math.round(num(ex[0])) && got[1] == Math.round(num(ex[1])), got.contentToString())
            }
            "bars" -> {
                val got = ForkClipJudge.bars(frames(c["frames"]))
                val ex = want as JsonArray
                check(g, c, (0 until 4).all { near(got[it].toDouble(), num(ex[it]), 1e-6) }, got.contentToString())
            }
            "still" -> {
                val got = ForkClipJudge.still(frames(c["frames"]), floats(c["bars"]))
                check(g, c, got == (want as JsonPrimitive).booleanOrNull, got)
            }
            "windows" -> {
                val bars = if (c.containsKey("bars")) floats(c["bars"]) else FloatArray(4)
                val got = ForkClipJudge.windows(frames(c["frames"]), c.lng("intervalMs"), c.lng("lengthMs"), bars)
                val ex = want as JsonArray
                val ok = got.size == ex.size && got.indices.all { i ->
                    val e = ex[i] as JsonArray
                    near(got[i].startMs.toDouble(), num(e[0]), 1e-9) && near(got[i].endMs.toDouble(), num(e[1]), 1e-9) && near(got[i].score, num(e[2]), 1e-9)
                }
                check(g, c, ok, got)
            }
            "choose" -> {
                val windows = c.arr("windows").map {
                    val e = it as JsonArray
                    ForkClipJudge.Window(Math.round(num(e[0])), Math.round(num(e[1])), num(e[2]))
                }
                val got = ForkClipJudge.choose(windows, c.lng("lengthMs"), c.orNull("peakMs"), c.orNull("chorusMs"))
                check(g, c, if (isNull(want)) got == null else got != null && got.startMs.toDouble() == num(want), got?.startMs)
            }
            "cropX" -> {
                val bars = if (c.containsKey("bars")) floats(c["bars"]) else FloatArray(4)
                val got = ForkClipJudge.cropX(frames(c["frames"]), bars)
                check(g, c, near(got.toDouble(), num(want), 1e-6), got)
            }
            "loopEnd" -> {
                val (fr, times) = timed(c["frames"])
                val got = ForkClipJudge.end(fr, times, c.lng("framesEndMs"))
                val ex = want as JsonObject
                check(g, c, if (ex.bool("flashing")) got == -1L else got == ex.lng("endMs"), got)
            }
            "judge" -> {
                val l = ForkClipJudge.judge(frames(c["frames"]), c.lng("intervalMs"), c.lng("lengthMs"), c.orNull("peakMs"), c.orNull("chorusMs"))
                val ok = if ((want as? JsonPrimitive)?.contentOrNull == "still") l == null
                else l != null && l.startMs == (want as JsonObject).lng("startMs") && l.endMs == want.lng("endMs")
                check(g, c, ok, if (l == null) "still" else "${l.startMs}-${l.endMs}")
            }
            "display" -> {
                val i = c.obj("input")
                when (c.str("kind")) {
                    "tallClip" -> {
                        val got = ForkCanvasRules.tallClip(i.lng("width").toInt(), i.lng("height").toInt())
                        check(g, c, got == (want as JsonPrimitive).booleanOrNull, got)
                    }
                    "clipFade" -> {
                        val got = ForkCanvasRules.clipFade(i.num("heroHeight").toFloat(), i.num("clipBottom").toFloat())
                        check(g, c, near(got.toDouble(), num(want), 1e-5), got)
                    }
                    "letterboxZoom" -> {
                        val wd = i.lng("width").toInt()
                        val ht = i.lng("height").toInt()
                        val got = ForkCanvasRules.letterboxZoom(letterboxed(wd, ht, i.lng("bar").toInt()), wd, ht)
                        check(g, c, near(got.toDouble(), num(want), 1e-5), got)
                    }
                    else -> check(g, c, false, "unknown kind ${c.str("kind")}")
                }
            }
            "backdrop" -> when (c.str("kind")) {
                "meshLift" -> skip(g, "meshLift: run in sharedUi ForkCanvasBackdropCasesTest against ArtworkMeshBackdrop's lifted()")
                else -> skip(g, "${c.str("kind")}: the art bleed (§9-10) is the phone extension's; BitChord's player has no bleed")
            }
            "badge" -> when (c.str("kind")) {
                "bandCentre" -> {
                    val i = c.obj("input")
                    val got = ForkCanvasRules.bandCentre(i.num("progress").toFloat(), i.num("width").toFloat())
                    check(g, c, near(got.toDouble(), num(want), 1e-5), got)
                }
                else -> skip(g, "chromeWhite: the phone extension's additive chrome; BitChord draws its own chrome")
            }
            else -> check(g, c, false, "unknown group")
        }
    }
}
