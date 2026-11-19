package com.music.bitchord

import com.music.bitchord.data.canvas.AppleMusicCanvas
import com.music.bitchord.data.canvas.ForkAppleCanvasRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Fork: the canvas rulebook's Apple cases (§3: `appleScore`, `appleRank`, `appleMotion`,
 * `appleToken` in `spec/canvas/canvas-cases.json`) against the phone's own code, upstream's
 * `AppleMusicCanvas`: its private `score`, `motionUrls`, `tallUrl`, `isWebPlayerToken` and `expiry`,
 * called by reflection so upstream's file stays as it is. The ranking is the search's own
 * (`sortedByDescending`, stop under 12), which `ForkAppleCanvasRules.rank` states; the token pick is
 * the scrape's (`firstOrNull { isWebPlayerToken } ?: first()` over the unexpired, unrejected ones).
 *
 * The rulebook's folder: `RULEBOOK_SPEC`, else `/spec`; missing fails the test.
 */
class ForkAppleCanvasCasesTest {

    private val canvas = AppleMusicCanvas
    private fun method(name: String, vararg types: Class<*>) =
        AppleMusicCanvas::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }

    private val scoreFn = method("score", JsonObject::class.java, String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!)
    private val motionFn = method("motionUrls", JsonObject::class.java)
    private val tallFn = method("tallUrl", JsonObject::class.java)
    private val webPlayerFn = method("isWebPlayerToken", String::class.java)
    private val expiryFn = method("expiry", String::class.java)

    private fun score(hit: JsonObject, want: JsonObject): Int? = scoreFn.invoke(
        canvas,
        buildJsonObject { put("attributes", hit) },
        want.str("title").orEmpty(), want.str("artist").orEmpty(), want.str("album"), false,
    ) as Int?

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun num(e: JsonElement?): Double = (e as? JsonPrimitive)?.doubleOrNull ?: 0.0
    private fun isNull(e: JsonElement?) = e == null || e is JsonNull

    private fun jwt(header: JsonElement?, payload: JsonElement?): String {
        val e = Base64.getUrlEncoder().withoutPadding()
        return e.encodeToString(header.toString().toByteArray()) + "." + e.encodeToString(payload.toString().toByteArray()) + ".sig"
    }

    @Test
    fun appleCasesRunAgainstAppleMusicCanvas() {
        val dir = System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec"
        val file = File(dir, "canvas/canvas-cases.json")
        if (!file.isFile) { org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true"); fail("the canvas rulebook cases are not at $file: set RULEBOOK_SPEC to the spec folder (or mount it at /spec)") }
        val all = Json.parseToJsonElement(file.readText()) as JsonObject
        val fails = ArrayList<String>()
        val counts = LinkedHashMap<String, IntArray>()
        fun check(group: String, c: JsonObject, ok: Boolean, got: Any?) {
            val n = counts.getOrPut(group) { IntArray(2) }
            n[1]++
            if (ok) n[0]++ else fails += "$group '${c.str("name")}': got $got, expected ${c["expect"]}"
        }
        for (e in all["appleScore"] as JsonArray) {
            val c = e as JsonObject
            val s = score(c["hit"] as JsonObject, c["want"] as JsonObject)
            val taken = ForkAppleCanvasRules.rank(listOf(s)).size == 1
            val ex = c["expect"] as JsonObject
            val ok = (if (isNull(ex["score"])) s == null else s != null && s.toDouble() == num(ex["score"])) &&
                taken == (ex["taken"] as JsonPrimitive).booleanOrNull
            check("appleScore", c, ok, "{score $s, taken $taken}")
        }
        for (e in all["appleRank"] as JsonArray) {
            val c = e as JsonObject
            val want = c["want"] as JsonObject
            val hits = (c["hits"] as JsonArray).map { it as JsonObject }
            // the search's own ranking: by score (stable), stop under 12; the first with inline motion art
            val ranked = hits.mapIndexedNotNull { i, h -> score(h, want)?.let { it to i } }.sortedByDescending { it.first }
            var got: String? = null
            for ((s, i) in ranked) {
                if (s < 12) break
                if ((hits[i]["motion"] as? JsonPrimitive)?.booleanOrNull == true) {
                    got = hits[i].str("id")
                    break
                }
            }
            check("appleRank", c, got == (c["expect"] as? JsonPrimitive)?.contentOrNull, got)
        }
        for (e in all["appleMotion"] as JsonArray) {
            val c = e as JsonObject
            val video = c["video"] as JsonObject
            @Suppress("UNCHECKED_CAST")
            val m = motionFn.invoke(canvas, video) as Pair<String, String?>?
            val tall = tallFn.invoke(canvas, video) as String?
            val want = c["expect"]
            val ok = if (isNull(want)) m == null else {
                val ex = want as JsonObject
                m != null && m.first == ex.str("primary") && m.second == ex.str("alternate") && tall == ex.str("tall")
            }
            check("appleMotion", c, ok, m?.let { "$it, tall $tall" })
        }
        for (e in all["appleToken"] as JsonArray) {
            val c = e as JsonObject
            val jwts = (c["tokens"] as JsonArray).map { jwt((it as JsonObject)["header"], it["payload"]) }
            val rejected = (c["rejected"] as? JsonArray).orEmpty().map { jwts[num(it).toInt()] }.toSet()
            val now = num(c["now"]).toLong()
            // the scrape's pick (AppleMusicCanvas.token): unrejected, unexpired, the web player's first, else the first
            val usable = jwts.indices.filter { i ->
                jwts[i] !in rejected && ((expiryFn.invoke(canvas, jwts[i]) as Long?) ?: return@filter false) > now
            }
            val got = usable.firstOrNull { webPlayerFn.invoke(canvas, jwts[it]) as Boolean } ?: usable.firstOrNull() ?: -1
            val want = c["expect"]
            check("appleToken", c, if (isNull(want)) got == -1 else got.toDouble() == num(want), got)
        }
        val report = counts.entries.joinToString("\n") { (g, n) -> "  $g ${n[0]}/${n[1]} pass" } +
            fails.joinToString("") { "\n  FAIL $it" }
        println("canvas cases against AppleMusicCanvas:\n$report")
        runCatching { File("build").mkdirs(); File("build/canvas-cases-apple.txt").writeText(report) }
        assertTrue(report, fails.isEmpty())
    }
}
