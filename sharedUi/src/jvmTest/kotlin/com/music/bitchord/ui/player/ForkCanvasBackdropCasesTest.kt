package com.music.bitchord.ui.player

import com.music.bitchord.data.canvas.ForkCanvasRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fork: the canvas rulebook's cases whose code is the player's own, upstream's (canvas.md §8.2, §9.2,
 * §11.1), against that code:
 *
 *  - `backdrop` meshLift: ArtworkMeshBackdrop's private `lifted()`, by reflection;
 *  - `display` clipFade and `badge` bandCentre: `ForkCanvasRules.clipFade` / `bandCentre` run the
 *    cases, and the player's own lines (NowPlayingScreen's `clipFadeFraction`, PlayerControls'
 *    ShimmerText band) are pinned to the same arithmetic, so an upstream change that moves them away
 *    from the rulebook fails here rather than passing silently. They are composable bodies, not
 *    functions a test can call.
 *
 * The rulebook's folder: `RULEBOOK_SPEC`, else `/spec`; missing fails the test.
 */
class ForkCanvasBackdropCasesTest {

    private val lifted = Class.forName("com.music.bitchord.ui.player.ArtworkMeshBackdropKt")
        .getDeclaredMethod("lifted", Int::class.javaPrimitiveType!!).apply { isAccessible = true }

    private fun num(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull ?: 0.0

    /** The player's source, from the module folder (the test's working directory) or the repo root. */
    private fun source(name: String): String {
        val rel = "src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/$name"
        return listOf(File(rel), File("sharedUi/$rel")).first { it.isFile }.readText()
    }

    @Test
    fun playerCasesRunAgainstThePlayersCode() {
        val dir = System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec"
        val file = File(dir, "canvas/canvas-cases.json")
        if (!file.isFile) { org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true"); fail("the canvas rulebook cases are not at $file: set RULEBOOK_SPEC to the spec folder (or mount it at /spec)") }
        val all = Json.parseToJsonElement(file.readText()) as JsonObject
        val fails = ArrayList<String>()
        val counts = LinkedHashMap<String, IntArray>()
        fun check(group: String, c: JsonObject, ok: Boolean, got: Any?) {
            val n = counts.getOrPut(group) { IntArray(2) }
            n[1]++
            if (!ok) fails += "$group '${(c["name"] as JsonPrimitive).contentOrNull}': got $got, expected ${c["expect"]}"
            else n[0]++
        }
        for (e in all["backdrop"] as JsonArray) {
            val c = e as JsonObject
            if ((c["kind"] as JsonPrimitive).content != "meshLift") continue
            val rgb = ((c["input"] as JsonObject)["rgb"] as JsonArray).map { num(it).toInt() }
            val p = lifted.invoke(null, (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]) as Int
            val got = listOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            val want = (c["expect"] as JsonArray).map { num(it).toInt() }
            check("backdrop meshLift", c, got == want, got)
        }
        for (e in all["display"] as JsonArray) {
            val c = e as JsonObject
            if ((c["kind"] as JsonPrimitive).content != "clipFade") continue
            val i = c["input"] as JsonObject
            val got = ForkCanvasRules.clipFade(num(i["heroHeight"]).toFloat(), num(i["clipBottom"]).toFloat())
            check("display clipFade", c, abs(got - num(c["expect"])) <= 1e-5, got)
        }
        for (e in all["badge"] as JsonArray) {
            val c = e as JsonObject
            if ((c["kind"] as JsonPrimitive).content != "bandCentre") continue
            val i = c["input"] as JsonObject
            val got = ForkCanvasRules.bandCentre(num(i["progress"]).toFloat(), num(i["width"]).toFloat())
            check("badge bandCentre", c, abs(got - num(c["expect"])) <= 1e-5, got)
        }
        // the player's own lines still do that arithmetic
        val np = source("NowPlayingScreen.kt")
        if (!np.contains("private const val HERO_FADE_FRACTION = 0.42f") ||
            !np.contains("(heroHeight.value * HERO_FADE_FRACTION / clipBottom.value).coerceIn(0f, 1f)")
        ) fails += "NowPlayingScreen's clip fade no longer reads banner x 0.42 over the clip's bottom (rule 8.2)"
        val pc = source("PlayerControls.kt")
        if (!pc.contains("val band = width * 0.6f") || !pc.contains("val center = -band + progress.value * (width + 2 * band)")) {
            fails += "PlayerControls' shimmer band is no longer 0.6 x width swept from -band to width + band (rule 11.1)"
        }
        val report = counts.entries.joinToString("\n") { (g, n) -> "  $g ${n[0]}/${n[1]} pass" } + fails.joinToString("") { "\n  FAIL $it" }
        println("canvas cases against the player:\n$report")
        assertTrue(fails.isEmpty(), report)
    }
}
