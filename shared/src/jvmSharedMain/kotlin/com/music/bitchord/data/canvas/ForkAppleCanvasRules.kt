package com.music.bitchord.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Fork: the canvas rulebook's Apple rules (§3) that both apps share: which scraped token to use,
 * how hits are ranked, and which motion URLs an `editorialVideo` gives.
 *
 * BitChord's own `AppleMusicCanvas` is the rulebook's original for Apple, so the phone follows it
 * by construction (its scorer is run against the cases directly). The PC's copy had drifted on the
 * token: it took a token as the web player's when its payload named `root_https_origin`
 * music.apple.com, which every token the bundle ships does, so the first unexpired JWT won whatever
 * it was. [isWebPlayerToken] is the phone's `kid`/`iss` check, which the PC now calls.
 */
object ForkAppleCanvasRules {

    /** A hit under this score ends the search (rule 3.6). */
    const val MIN_SCORE = 12

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun part(jwt: String, index: Int): JsonObject? = runCatching {
        val encoded = jwt.split('.').getOrNull(index) ?: return null
        val bytes = Base64.getUrlDecoder().decode(encoded.trimEnd('=').let { it.padEnd((it.length + 3) / 4 * 4, '=') })
        json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
    }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Rule 3.1: the web player's own token says so in its header `kid` (`WebPlayKid`) or payload `iss` (`AMPWebPlay`). */
    fun isWebPlayerToken(jwt: String): Boolean =
        part(jwt, 0)?.text("kid")?.contains("WebPlayKid") == true ||
            part(jwt, 1)?.text("iss")?.contains("AMPWebPlay") == true

    /** A JWT's `exp` in ms, or null when it can't be read. */
    fun expiryMs(jwt: String): Long? =
        (part(jwt, 1)?.get("exp") as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
            ?.times(1000)

    /**
     * Rule 3.1: the index in [jwts] of the token to use at [nowMs]: never one in [rejected] (a 401),
     * never an expired or unreadable one; the first web player token, else the first left. -1 for none.
     */
    fun pickToken(jwts: List<String>, nowMs: Long, rejected: Set<String> = emptySet()): Int {
        val usable = jwts.indices.filter { i ->
            val jwt = jwts[i]
            jwt !in rejected && (expiryMs(jwt) ?: return@filter false) > nowMs
        }
        return usable.firstOrNull { isWebPlayerToken(jwts[it]) } ?: usable.firstOrNull() ?: -1
    }

    /**
     * Rule 3.6: the order hits are tried in, as indices into [scores]: by score, ties in Apple's
     * order; a rejected hit (null) never, and the list stops at the first under [MIN_SCORE].
     */
    fun rank(scores: List<Int?>): List<Int> =
        scores.indices.filter { scores[it] != null }
            .sortedByDescending { scores[it]!! } // stable: ties keep Apple's order
            .takeWhile { scores[it]!! >= MIN_SCORE }

    /** The motion URLs of an `editorialVideo` (rule 3.8). */
    data class Motion(val primary: String, val alternate: String?, val tall: String?)

    /**
     * Rule 3.8: [link] gives an asset's URL by key (its `video`, else `videoUrl`, `hlsUrl`, `url`).
     * Primary: the square clip, else the raw one, else the tall one; the alternate: the next of
     * those that differs; tall: the 3:4 clip. A still only is no motion art (null).
     */
    fun motion(link: (String) -> String?): Motion? {
        val square = link("motionDetailSquare") ?: link("motionSquareVideo1x1")
        val raw = link("motionDetailRaw")
        val tall = link("motionDetailTall") ?: link("motionTallVideo3x4")
        val primary = square ?: raw ?: tall ?: return null
        val alternate = listOfNotNull(square, raw, tall).firstOrNull { it != primary }
        return Motion(primary, alternate, tall)
    }

    /** An asset's URL out of `editorialVideo[key]`, in the rule's field order; blank is none. */
    fun assetUrl(video: JsonObject, key: String): String? {
        val asset = video[key] as? JsonObject ?: return null
        return listOf("video", "videoUrl", "hlsUrl", "url").firstNotNullOfOrNull { asset.text(it) }?.takeIf { it.isNotBlank() }
    }
}
