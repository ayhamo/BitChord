package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.net.URLEncoder

/**
 * Fork: what Apple Music's artist page shows at the top, for the PC's artist page — the phone's
 * `AppleArtistArt` (app/…/data/canvas/AppleArtistArt.kt) and upstream web's
 * `data/apple/artistPage.ts`, readable from shared code. The phone keeps its own copy (org.json,
 * which only Android has), so its behaviour is untouched; the two read the same JSON the same way.
 *
 * The colours are the still photograph's (`bgColor`, `textColor1`), as on the web. The looping
 * clip some artists have (`motionArtistSquare1x1`, which the phone plays over the photo) comes
 * with its own preview colours in [videoBackground] / [videoAccent], so a page that plays the
 * clip can take the clip's colours as the phone does, and one that doesn't keeps the still's.
 */
data class ForkAppleArtistArt(
    val heroUrl: String,
    /** Width over height of the photograph. */
    val heroAspect: Float,
    /** A transparent PNG, null for the many artists without one. */
    val logoUrl: String?,
    /** Width over height, so the logo can be sized before it decodes. */
    val logoAspect: Float,
    /** Apple's `bgColor` for the photograph (ARGB): what the page is tinted with. */
    val background: Int,
    /** Apple's `textColor1` (ARGB). */
    val accent: Int,
    /** The colour Apple fills the artist's Play button with; absent for some artists. */
    val keyColor: Int?,
    /** Fork: the looping clip (an HLS playlist) Apple shows in place of the photo; null for most artists. */
    val videoUrl: String? = null,
    /** Fork: the clip's own `bgColor` (ARGB), for a page showing the clip; null without one. */
    val videoBackground: Int? = null,
    /** Fork: the clip's own `textColor1` (ARGB). */
    val videoAccent: Int? = null,
)

/**
 * Finds [ForkAppleArtistArt] from nothing but an artist's name, as the phone's
 * `AppleArtistArtRepository` does: the iTunes search API turns the name into an Apple Music
 * artist, taken only when the name is the same ([AppleCatalogue.matchKey]) *and* its catalogue
 * shares a song or release title with the page's own ([AppleCatalogue.sharesTitles]); then that
 * artist's music.apple.com page is read for the header in its hydration JSON. Only
 * itunes.apple.com and music.apple.com are ever asked.
 *
 * Answers are kept per [key] (a YouTube browse id or a Navidrome artist page id) in a 64-entry
 * LRU, misses included; a network failure is not remembered, so the next visit asks again.
 */
object ForkAppleArtistArtRepository {

    private const val TAG = "ForkAppleArtistArt"
    private const val CACHE_SIZE = 64
    private const val HERO_WIDTH = 1200
    private const val LOGO_WIDTH = 1000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private class Entry(val art: ForkAppleArtistArt?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _updates = MutableStateFlow(0)

    /** Ticks whenever a lookup settles. */
    val updates: StateFlow<Int> = _updates

    /** What is already known for [key], without a request; null for never asked and for "nothing". */
    fun cached(key: String): ForkAppleArtistArt? = synchronized(cache) { cache[key] }?.art

    /**
     * The header art for the artist [artistName] on page [key], or null when Apple has none that
     * provably is them. [catalogue] is the page's own song and release titles, asked only on a
     * cache miss; with none, nothing can be proved and nothing is asked. Never throws.
     */
    suspend fun artFor(
        key: String,
        artistName: String,
        catalogue: suspend () -> Collection<String>,
    ): ForkAppleArtistArt? {
        val name = AppleCatalogue.matchKey(artistName)
        if (name.isEmpty() || key.isBlank()) return null
        synchronized(cache) { cache[key] }?.let { return it.art }
        val titles = AppleCatalogue.keys(runCatching { catalogue() }.getOrDefault(emptyList()))
        if (titles.isEmpty()) return null
        val entry = withContext(Dispatchers.IO) {
            runCatching { lookup(artistName, name, titles) }
                .onFailure { Log.d(TAG, "lookup failed for $artistName: $it") }
                .getOrNull()
        } ?: return null
        synchronized(cache) { cache[key] = entry }
        _updates.value++
        return entry.art
    }

    private fun lookup(artistName: String, name: String, titles: Set<String>): Entry? {
        val search = get(
            "https://itunes.apple.com/search?term=${URLEncoder.encode(artistName, "UTF-8")}&entity=musicArtist&limit=5",
        ) ?: return null
        // Every same-named candidate is checked, not just the first: the one Apple ranks highest
        // is often the wrong one of two.
        val page = candidates(search, name).firstOrNull { (id, _) ->
            AppleCatalogue.sharesTitles(id, titles).also { shares ->
                if (!shares) Log.d(TAG, "$artistName: Apple artist $id shares no titles, skipped")
            }
        }?.second ?: return Entry(null)
        val html = get(page) ?: return null
        return Entry(parseArtistPage(html))
    }

    /**
     * The iTunes search answers named exactly [nameKey] (an [AppleCatalogue.matchKey]), in
     * Apple's order, as (artist id, page address). The page is the link Apple gives (its slug
     * spares a redirect), or the bare id's page; only music.apple.com addresses are kept.
     */
    fun candidates(searchJson: String, nameKey: String): List<Pair<Long, String>> {
        val results = runCatching { json.parseToJsonElement(searchJson) }.getOrNull()
            ?.obj()?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val r = element.obj() ?: return@mapNotNull null
            if (AppleCatalogue.matchKey(r.str("artistName").orEmpty()) != nameKey) return@mapNotNull null
            val id = (r["artistId"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val link = r.str("artistLinkUrl")?.substringBefore('?')?.takeIf { it.isNotBlank() }
                ?: "https://music.apple.com/us/artist/$id"
            if (!link.startsWith("https://music.apple.com/")) return@mapNotNull null
            id to link
        }
    }

    /** The header art in an artist page's hydration JSON; null when it has none. Never throws. */
    fun parseArtistPage(html: String): ForkAppleArtistArt? = runCatching { parse(html) }.getOrNull()

    private fun parse(html: String): ForkAppleArtistArt? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        val header = findHeader(json.parseToJsonElement(html.substring(start, end))) ?: return null

        // Absent for most artists: the key is there and null.
        val logo = header.obj("artistLogo")?.obj("dictionary")
        val hero = (header.obj("artwork") ?: header.obj("colorBackdropArtwork"))?.obj("dictionary") ?: return null
        val heroW = hero.int("width")?.takeIf { it > 0 } ?: return null
        val heroH = hero.int("height")?.takeIf { it > 0 } ?: return null
        val heroUrl = hero.str("url").fill(HERO_WIDTH, (HERO_WIDTH.toLong() * heroH / heroW).toInt(), "jpg") ?: return null
        val logoW = logo?.int("width") ?: 0
        val logoH = logo?.int("height") ?: 0
        val logoUrl = if (logo != null && logoW > 0 && logoH > 0) {
            logo.str("url").fill(LOGO_WIDTH, (LOGO_WIDTH.toLong() * logoH / logoW).toInt(), "png")
        } else {
            null
        }
        val background = hero.str("bgColor").toArgb() ?: return null
        // The phone's read (AppleArtistArt.parse): the clip and the colours of its preview frame.
        val clip = header.obj("videoArtwork")?.obj("dictionary")?.obj("motionArtistSquare1x1")
        val video = clip?.str("video")?.takeIf { it.startsWith("https://") }
        val preview = clip?.obj("previewFrame")?.takeIf { video != null }
        val videoBackground = preview?.str("bgColor").toArgb()
        return ForkAppleArtistArt(
            heroUrl = heroUrl,
            heroAspect = heroW.toFloat() / heroH,
            logoUrl = logoUrl,
            logoAspect = if (logoUrl != null) logoW.toFloat() / logoH else 1f,
            background = background,
            accent = hero.str("textColor1").toArgb() ?: background,
            keyColor = header.str("keyColor").toArgb(),
            videoUrl = video,
            videoBackground = videoBackground,
            videoAccent = preview?.str("textColor1").toArgb() ?: videoBackground,
        )
    }

    /** The first object anywhere in [node] that has an `artistLogo` key: the page's header. */
    private fun findHeader(node: JsonElement?): JsonObject? {
        when (node) {
            is JsonObject -> {
                if ("artistLogo" in node) return node
                node.values.forEach { child -> findHeader(child)?.let { return it } }
            }
            is JsonArray -> node.forEach { child -> findHeader(child)?.let { return it } }
            else -> Unit
        }
        return null
    }

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /** Apple's colours are six hex digits with no prefix. */
    private fun String?.toArgb(): Int? =
        this?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }

    /** Fills Apple's image address template (`{w}x{h}{c}.{f}`). */
    private fun String?.fill(w: Int, h: Int, format: String): String? {
        if (this.isNullOrBlank() || !contains("{w}")) return null
        return replace("{w}", w.toString()).replace("{h}", h.toString()).replace("{c}", "bb").replace("{f}", format)
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string()
        }
    }
}
