package com.music.bitchord.data.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.fail

/**
 * [ForkWindowFit] against the rulebook (YT Music Extension `spec/addons/offset-map.md`, "Window
 * check" and "Fixtures"):
 * - the synthetic window fixtures of `scripts/addon_offset_fixtures.mjs`, built from the spec's
 *   PRNG and song generator: shifts of +0.7, −2.3, −0.1172, +11 and −9 s at 15 s and 75 s, each
 *   within 1 ms with both scores ≥ 0.8, and another song on the same beat grid refused. Pure, so
 *   they run everywhere, CI included.
 * - `window-cases.json`, the phone's 51 measured windows: the numbers in it are checked as data
 *   (verdicts against the 0.8 bars, the two wrong copies refused throughout, Poker Face at −117.2 ms,
 *   every match within 1 ms of its song's full fit); each window is fitted again only where the
 *   extension's local copies (`.cache/addons/<id>.yt.f32` / `.ad.f32`, git-ignored, never
 *   downloaded here) are on disk, as `scripts/addon_window_cases.mjs` does, else skipped.
 *
 * The full fit and the measured level of the same fixtures file are the extension PC's (offset-map.md
 * "Full fit [PC only]", Level "[PC only]"); BitChord levels by YouTube's loudnessDb and never fits a
 * whole file, so they are not run here.
 */
class ForkWindowFitTest {

    private val r = ForkWindowFit.FIT_RATE

    // ── the spec's signals ────────────────────────────────────────────────

    /** mulberry32, uniform in [0, 1). Int arithmetic wraps as JS's `>>> 0` and `Math.imul` do. */
    private fun mulberry32(seed: Int): () -> Double {
        var a = seed
        return {
            a += 0x6D2B79F5
            var t = a
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    /** offset-map.md "Song": 120 BPM kick, off-beat hat, a 3-tone chord per bar, scaled to [peakDb]. */
    private fun song(seed: Int, seconds: Double, peakDb: Double = -12.0): FloatArray {
        val rnd = mulberry32(seed)
        val x = FloatArray(Math.round(seconds * r).toInt())
        val beats = ceil(seconds * 2).toInt()
        for (b in 0 until beats) {
            val kick = 0.3 + 0.7 * rnd()
            val hat = 0.1 * rnd()
            val s0 = Math.round(b * 0.5 * r).toInt()
            val chord = if (b % 4 == 0) List(3) { (110 + 770 * rnd()) to (0.05 + 0.1 * rnd()) } else null
            var i = 0
            while (i < 0.5 * r && s0 + i < x.size) {
                val t = i.toDouble() / r
                x[s0 + i] = (x[s0 + i] + kick * exp(-8 * t) * sin(2 * PI * 60 * t)).toFloat()
                if (t >= 0.25) x[s0 + i] = (x[s0 + i] + hat * exp(-30 * (t - 0.25)) * (2 * rnd() - 1)).toFloat()
                i++
            }
            if (chord != null) {
                i = 0
                while (i < 2 * r && s0 + i < x.size) {
                    val t = (s0 + i).toDouble() / r
                    for ((f, g) in chord) x[s0 + i] = (x[s0 + i] + g * sin(2 * PI * f * t)).toFloat()
                    i++
                }
            }
        }
        var peak = 0.0
        for (v in x) peak = max(peak, abs(v.toDouble()))
        val g = 10.0.pow(peakDb / 20) / peak
        for (i in x.indices) x[i] = (x[i] * g).toFloat()
        return x
    }

    private fun silence(sec: Double) = FloatArray(Math.round(sec * r).toInt())
    private fun cat(a: FloatArray, b: FloatArray) = a + b
    private fun cut(x: FloatArray, fromSec: Double) = x.copyOfRange(Math.round(fromSec * r).toInt(), x.size)
    private fun win(ad: FloatArray, pos: Double) =
        ad.copyOfRange(Math.round(pos * r).toInt(), minOf(ad.size, Math.round((pos + 12) * r).toInt()))

    @Test
    fun syntheticWindowFixtures() {
        val yt = song(1, 120.0)
        val failures = mutableListOf<String>()
        var n = 0
        for (off in listOf(0.7, -2.3, -0.1172, 11.0, -9.0)) {
            val ad = if (off > 0) cat(silence(off), yt) else cut(yt, -off)
            for (pos in listOf(15.0, 75.0)) {
                n++
                val got = ForkWindowFit.fitWindow(yt, win(ad, pos), pos)
                val ok = got.offsetSec != null && abs(got.offsetSec!! - off) <= 0.001 &&
                    (got.score ?: 0.0) >= 0.8 && (got.coarseScore ?: 0.0) >= 0.8
                if (!ok) failures += "window at $pos s, shift $off s: ${got.describe()}"
            }
        }
        n++
        val other = ForkWindowFit.fitWindow(yt, win(song(2, 120.0), 45.0), 45.0)
        if (other.match) failures += "window of another song on the same beat grid: ${other.describe()}"
        println("window fixtures: ${n - failures.size}/$n")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** The app decodes only YouTube's span around the window; the answer must not move for that. */
    @Test
    fun youtubeSpanGivesTheSameAnswer() {
        val yt = song(1, 120.0)
        val ad = cat(silence(0.7), yt)
        for (pos in listOf(15.0, 75.4)) {
            val whole = ForkWindowFit.fitWindow(yt, win(ad, pos), pos)
            val (from, to) = ForkWindowFit.youtubeSpan(pos)
            val part = yt.copyOfRange(Math.round(from * r).toInt(), minOf(yt.size, Math.round(to * r).toInt()))
            val spanned = ForkWindowFit.fitWindow(part, win(ad, pos), pos - from)
            // equal but for floating-point noise: the span's times are the song's less a whole number of seconds
            val same = whole.match == spanned.match &&
                abs((whole.offsetSec ?: 0.0) - (spanned.offsetSec ?: 0.0)) < 1e-6 &&
                abs((whole.score ?: 0.0) - (spanned.score ?: 0.0)) < 1e-6
            if (!same) fail("at $pos s: whole song ${whole.describe()}, span ${spanned.describe()}")
        }
    }

    // ── window-cases.json ─────────────────────────────────────────────────

    private val spec: File by lazy {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.ifBlank { null } ?: "/spec")
        if (!File(dir, "addons/window-cases.json").isFile) {
            org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true")
            fail("rulebook not found: set RULEBOOK_SPEC to the extension's spec directory (looked in ${dir.absolutePath})")
        }
        dir
    }

    /** The extension's local copies: `WINDOW_CASES_AUDIO`, else `.cache/addons` beside the spec. */
    private val audio: File
        get() = File(System.getenv("WINDOW_CASES_AUDIO")?.ifBlank { null } ?: File(spec.absoluteFile.parentFile, ".cache/addons").path)

    @Test
    fun windowCases() {
        val doc = Json.parseToJsonElement(File(spec, "addons/window-cases.json").readText()).jsonObject
        val songs = doc.getValue("songs").jsonArray.map { it.jsonObject }
        val failures = mutableListOf<String>()
        var windows = 0; var matches = 0; var refusals = 0
        var ran = 0; var skipped = 0
        val wrongCopies = setOf("A7Zlgi2FMUE", "exHJb5t-6e8")

        for (s in songs) {
            val id = s.str("id")
            val fullFitMs = s.getValue("fullFit").jsonObject["offsetMs"]?.num()
            val ws = s.getValue("windows").jsonArray.map { it.jsonObject }
            // pure numbers: what the file states must hold together
            for (w in ws) {
                windows++
                val at = "$id at ${w.num("posSec")} s"
                val coarse = w["coarse"]?.num()
                val fine = w["fine"]?.num()
                when (w.str("expect")) {
                    "match" -> {
                        matches++
                        val off = w["offsetMs"]?.num()
                        if (coarse == null || fine == null || coarse < 0.8 || fine < 0.8) failures += "$at: a match under the 0.8 bars"
                        if (off == null) failures += "$at: a match without an offset"
                        if (off != null && fullFitMs != null && abs(off - fullFitMs) > 1) failures += "$at: $off ms against the full fit's $fullFitMs ms"
                        if (id in wrongCopies) failures += "$at: a window of a wrong copy matches"
                        if (id == "oG-4Uvhm4lI" && (off == null || abs(off + 117.2) > 1)) failures += "$at: Poker Face at $off ms"
                    }
                    "refuse" -> {
                        refusals++
                        if ((coarse ?: 0.0) >= 0.8 && (fine ?: 0.0) >= 0.8) failures += "$at: refused over both bars"
                    }
                    else -> failures += "$at: expect ${w.str("expect")}"
                }
            }
            // the audio, where the extension's local copies exist
            val ytFile = File(audio, "$id.yt.f32")
            val adFile = File(audio, "$id.ad.f32")
            if (!ytFile.isFile || !adFile.isFile) {
                skipped += ws.size
                continue
            }
            val yt = floats(ytFile)
            val ad = floats(adFile)
            for (w in ws) {
                ran++
                val pos = w.num("posSec")
                val got = ForkWindowFit.fitWindow(
                    yt,
                    ad.copyOfRange(Math.round(pos * r).toInt(), minOf(ad.size, Math.round((pos + 12) * r).toInt())),
                    pos,
                )
                val want = w.str("expect")
                val good = (if (got.match) "match" else "refuse") == want &&
                    (!got.match || abs(got.offsetSec!! * 1000 - w.num("offsetMs")) <= 1)
                if (!good) failures += "$id at $pos s: ${got.describe()}, measured $want ${w["offsetMs"]?.num() ?: ""}"
            }
        }
        if (windows != doc.num("windowCount").toInt()) failures += "windowCount ${doc.num("windowCount")} but $windows windows"
        println("window cases: $windows windows ($matches match, $refusals refuse) checked as numbers; audio: $ran run, $skipped skipped (no local copies in ${audio.path})")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun floats(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
    private fun JsonObject.num(key: String): Double = getValue(key).jsonPrimitive.content.toDouble()
    private fun kotlinx.serialization.json.JsonElement.num(): Double? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull()

}
