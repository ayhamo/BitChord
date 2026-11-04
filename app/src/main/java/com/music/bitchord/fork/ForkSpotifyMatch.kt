package com.music.bitchord.fork

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fork: reads Spotify's Pathfinder search answer (`searchV2.tracksV2.items[].item.data`) for the hit
 * that is really the song asked for: `name`, `artists.items[].profile.name`, `albumOfTrack.name`.
 * The phone took the first item whatever it was (see `SpotifyCanvas.searchViaPathfinder`), so a
 * wrong song's Canvas could play. The PC's `forkSearchViaPathfinder` does the same check.
 */
object ForkSpotifyMatch {

    data class Hit(val uri: String, val name: String, val artists: String, val album: String?)

    /**
     * The first item whose title and every requested artist match, or null.
     *
     * A hit named exactly as asked is taken over one that only matches by [core], wherever each
     * sits in the answer: Spotify ranks "Monody (Remix)" above "Monody" for some queries, and
     * [core] strips the brackets off both, so the first pass alone played the remix's Canvas
     * for the original. Only when no hit is named exactly does a [core] match count.
     */
    fun pick(items: JsonArray?, title: String, artist: String): Hit? {
        items ?: return null
        val wantTitle = normal(title)
        val wantCore = core(title)
        val wantArtists = split(artist)
        if (wantArtists.isEmpty()) return null
        var byCore: Hit? = null
        for (entry in items) {
            val data = data(entry) ?: continue
            val uri = text(data, "uri") ?: text(data, "id")?.let { "spotify:track:$it" } ?: continue
            val name = text(data, "name") ?: continue
            val artists = artistsOf(data)
            if (artists.isEmpty()) continue
            val exact = normal(name) == wantTitle
            if (!exact && (byCore != null || core(name) != wantCore)) continue
            val got = artists.flatMap(::split).toSet()
            if (!wantArtists.all { it in got }) continue
            val album = (data["albumOfTrack"] as? JsonObject)?.let { text(it, "name") }
            val hit = Hit(uri, name, artists.joinToString(", "), album)
            if (exact) return hit
            byCore = hit
        }
        return byCore
    }

    /** The song Pathfinder just answered with named hits, none of them it; see [takeNamedMiss]. */
    private val namedMiss = ThreadLocal<String?>()

    fun noteNamedMiss(title: String, artist: String) = namedMiss.set("$title|$artist")

    /** True once, on the same thread, after [noteNamedMiss] for this song: the REST search is not worth asking. */
    fun takeNamedMiss(title: String, artist: String): Boolean {
        val noted = namedMiss.get()
        namedMiss.remove()
        return noted == "$title|$artist"
    }

    /** Whether the answer names its hits at all; one that doesn't is trusted as before. */
    fun carriesNames(items: JsonArray): Boolean = items.any { entry ->
        val data = data(entry) ?: return@any false
        text(data, "name") != null && artistsOf(data).isNotEmpty()
    }

    private fun data(entry: Any?): JsonObject? =
        ((entry as? JsonObject)?.get("item") as? JsonObject)?.get("data") as? JsonObject

    private fun artistsOf(data: JsonObject): List<String> =
        ((data["artists"] as? JsonObject)?.get("items") as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("profile") as? JsonObject)?.let { p -> text(p, "name") } }
            .orEmpty()

    private fun text(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull

    /** The title without brackets, a " - Remastered"-style tail or a "feat." credit: "Song (feat. X)" is "Song". */
    internal fun core(value: String): String = normal(
        value.replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .substringBefore(" - ")
            .replace(Regex("""\s(?:feat|ft)\.?\s.*$""", RegexOption.IGNORE_CASE), " "),
    )

    /** Lower case, accents and punctuation off. */
    internal fun normal(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /**
     * The separate names in an artist credit. `feat`/`ft` must be whole words: as bare prefixes they
     * cut "Feather" in two. "+" separates too, so Spotify's "Florence + The Machine" and
     * a credit reading "Florence and the Machine" come out as the same two names.
     */
    internal fun split(value: String): List<String> =
        value.split(Regex("""\s*(?:,|&|\+|\bx\b|\band\b|\bfeat\b\.?|\bft\b\.?|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
            .map(::normal).filter { it.isNotEmpty() }
}
