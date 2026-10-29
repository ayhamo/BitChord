package com.music.bitchord.data.navidrome

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * A small Subsonic / OpenSubsonic client for one Navidrome server (fork-only).
 *
 * Every request carries token auth (`t` = md5(password + salt), `s` = salt)
 * rather than the password. The library is read with the saved salt
 * ([NavidromeSettings.salt]), so the stream and cover URLs it hands out stay
 * the same across app starts — the image and audio caches are keyed by URL —
 * and valid for as long as the password is; Subsonic tokens don't expire.
 */
class NavidromeClient(
    baseUrl: String,
    private val username: String,
    password: String,
    private val salt: String = NavidromeSettings.newSalt(),
) {
    private val base: HttpUrl = NavidromeSettings.normalizeUrl(baseUrl).toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Not a server address")
    private val token: String = md5(password + salt)

    /** One page of the library's songs, from `search3` with an empty query (Navidrome lists everything). */
    data class SongRow(
        val id: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val albumId: String?,
        val coverArt: String?,
        val track: Int?,
        val disc: Int?,
        val year: Int?,
        val durationSec: Int?,
        val suffix: String?,
        val bitDepth: Int?,
        val samplingRate: Int?,
        /** Starred on the server (Subsonic sends the date it was starred, or nothing). */
        val starred: Boolean = false,
    )

    suspend fun ping() {
        call("ping")
    }

    suspend fun songs(offset: Int, count: Int): List<SongRow> {
        val result = call(
            "search3",
            "query" to "",
            "songCount" to count.toString(),
            "songOffset" to offset.toString(),
            "artistCount" to "0",
            "albumCount" to "0",
        )
        return songRows(result["searchResult3"]?.jsonObject?.get("song"))
    }

    /** Songs the server's agents (Last.fm and the like) call similar; empty when it has none configured. */
    suspend fun similarSongs(id: String, count: Int): List<SongRow> {
        val result = call("getSimilarSongs2", "id" to id, "count" to count.toString())
        return songRows(result["similarSongs2"]?.jsonObject?.get("song"))
    }

    suspend fun randomSongs(count: Int): List<SongRow> {
        val result = call("getRandomSongs", "size" to count.toString())
        return songRows(result["randomSongs"]?.jsonObject?.get("song"))
    }

    /** One album card: [name] is the album tag the library's own album pages group by. */
    data class AlbumRow(val id: String, val name: String, val artist: String?, val coverArt: String?)

    /** Star (the app's heart) or unstar a song. */
    suspend fun setStarred(id: String, starred: Boolean) {
        call(if (starred) "star" else "unstar", "id" to id)
    }

    /** One playlist on the server, for a Home card. */
    data class PlaylistRow(val id: String, val name: String, val songCount: Int?, val coverArt: String?)

    suspend fun playlists(): List<PlaylistRow> {
        val result = call("getPlaylists")
        return asList(result["playlists"]?.jsonObject?.get("playlist")).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            PlaylistRow(
                id = o.string("id") ?: return@mapNotNull null,
                name = o.string("name") ?: return@mapNotNull null,
                songCount = o.int("songCount"),
                coverArt = o.string("coverArt"),
            )
        }
    }

    /** A playlist's songs, in its own order. */
    suspend fun playlistSongs(id: String): List<SongRow> {
        val result = call("getPlaylist", "id" to id)
        return songRows(result["playlist"]?.jsonObject?.get("entry"))
    }

    /** `getAlbumList2` of [type] (`newest`, `recent`, `frequent`, `random`, …). */
    suspend fun albums(type: String, size: Int): List<AlbumRow> {
        val result = call("getAlbumList2", "type" to type, "size" to size.toString())
        return asList(result["albumList2"]?.jsonObject?.get("album")).mapNotNull { it.toAlbumRow() }
    }

    /** Albums whose name matches [query] (`search3`, albums only). */
    suspend fun searchAlbums(query: String, count: Int = 20): List<AlbumRow> {
        val result = call(
            "search3",
            "query" to query,
            "albumCount" to count.toString(),
            "songCount" to "0",
            "artistCount" to "0",
        )
        return asList(result["searchResult3"]?.jsonObject?.get("album")).mapNotNull { it.toAlbumRow() }
    }

    /** One album: its card and its songs in the server's order (disc, then track). */
    suspend fun album(id: String): Pair<AlbumRow?, List<SongRow>> {
        val album = call("getAlbum", "id" to id)["album"]
        return album?.toAlbumRow() to songRows((album as? JsonObject)?.get("song"))
    }

    /** A song's details for Song Info: plays, year, rating, genre. */
    data class SongDetails(
        val playCount: Int?,
        val year: Int?,
        val releaseDate: String?,
        val genre: String?,
        val userRating: Int?,
        val starred: Boolean,
        val bitRate: Int?,
        val suffix: String?,
    )

    suspend fun songDetails(id: String): SongDetails {
        val o = call("getSong", "id" to id)["song"]?.jsonObject ?: throw IOException("No such song")
        // OpenSubsonic: {"year":2014,"month":11,"day":3}; any part may be missing.
        val date = (o["originalReleaseDate"] as? JsonObject)?.takeIf { it.int("year") != null }
            ?: (o["releaseDate"] as? JsonObject)?.takeIf { it.int("year") != null }
        val releaseDate = date?.let { d ->
            listOfNotNull(d.int("year"), d.int("month"), d.int("day"))
                .mapIndexed { i, part -> if (i == 0) part.toString() else "%02d".format(part) }
                .joinToString("-")
        }
        return SongDetails(
            playCount = o.int("playCount"),
            year = o.int("year"),
            releaseDate = releaseDate,
            genre = o.string("genre"),
            userRating = o.int("userRating"),
            starred = o.string("starred") != null,
            bitRate = o.int("bitRate"),
            suffix = o.string("suffix"),
        )
    }

    private fun JsonElement.toAlbumRow(): AlbumRow? {
        val o = this as? JsonObject ?: return null
        return AlbumRow(
            id = o.string("id") ?: return null,
            name = o.string("name") ?: return null,
            artist = o.string("artist"),
            coverArt = o.string("coverArt"),
        )
    }

    private fun songRows(element: JsonElement?): List<SongRow> = asList(element).mapNotNull { it.toSongRow() }

    /** Subsonic JSON writes a one-item list as the bare object; null or missing is empty. */
    private fun asList(element: JsonElement?): List<JsonElement> = when (element) {
        null -> emptyList()
        is JsonArray -> element
        else -> listOf(element)
    }

    /** One `structuredLyrics` entry: a language's lines, timed or not ([offsetMs] already in the server's sign). */
    data class LyricsRow(val synced: Boolean, val offsetMs: Long, val lines: List<Pair<Long?, String>>)

    /**
     * The song's lyrics as the server read them from the file (tags or a
     * sidecar `.lrc`), via the OpenSubsonic `songLyrics` extension. A server
     * without the extension answers with an error, which surfaces as an
     * [IOException] like any other.
     */
    suspend fun lyricsBySongId(id: String): List<LyricsRow> =
        parseLyrics(call("getLyricsBySongId", "id" to id))

    /**
     * Subsonic `scrobble`: with [submission] false the song shows as playing
     * now (Navidrome's "Now playing" list); true records a finished play
     * started at [timeMs], which is what counts it and fills "recently played".
     */
    suspend fun scrobble(id: String, submission: Boolean, timeMs: Long? = null) {
        call(
            "scrobble",
            *listOfNotNull(
                "id" to id,
                "submission" to submission.toString(),
                timeMs?.let { "time" to it.toString() },
            ).toTypedArray(),
        )
    }

    /** The original file, untouched (`format=raw`): FLAC stays FLAC, Opus stays Opus. */
    fun streamUrl(id: String): String = endpoint("stream", "id" to id, "format" to "raw").toString()

    /** A cover by its Subsonic id: a song's own (`mf-…`) or an album's (`al-…`). */
    fun coverUrl(coverArt: String, size: Int = 600): String =
        endpoint("getCoverArt", "id" to coverArt, "size" to size.toString()).toString()

    private fun endpoint(method: String, vararg params: Pair<String, String>): HttpUrl =
        base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(method)
            .addQueryParameter("u", username)
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            // Subsonic's required version parameter, written as `%76` (= "v"). The player treats any
            // URI with a `v` query parameter as a YouTube watch link (`bitchord://watch?v=…`), so a
            // literal `v=1.16.1` made every stream URL "play" YouTube video "1.16.1". Navidrome
            // decodes the key and still reads it as `v`; Android's Uri.getQueryParameter("v") doesn't.
            .addEncodedQueryParameter("%76", "1.16.1")
            .addQueryParameter("c", "BitChord")
            .addQueryParameter("f", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    /** The `subsonic-response` object, or an [IOException] carrying the server's own error message. */
    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint(method, *params)).get().build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body.string()
                val root = json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject
                    ?: throw IOException("Not a Subsonic server")
                if (root.string("status") != "ok") {
                    val error = root["error"]?.jsonObject
                    throw IOException(error?.string("message") ?: "Server refused the request")
                }
                root
            }
        }

    private fun JsonElement.toSongRow(): SongRow? {
        val o = this as? JsonObject ?: return null
        val id = o.string("id") ?: return null
        return SongRow(
            id = id,
            title = o.string("title") ?: return null,
            artist = o.string("artist"),
            album = o.string("album"),
            albumId = o.string("albumId"),
            coverArt = o.string("coverArt"),
            track = o.int("track"),
            disc = o.int("discNumber"),
            year = o.int("year"),
            durationSec = o.int("duration"),
            suffix = o.string("suffix"),
            bitDepth = o.int("bitDepth"),
            samplingRate = o.int("samplingRate"),
            starred = o.string("starred") != null,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `getLyricsBySongId`'s answer (the `subsonic-response` object); apart from [call] so it can be tested. */
        internal fun parseLyrics(result: JsonObject): List<LyricsRow> {
            val list = result["lyricsList"]?.jsonObject?.get("structuredLyrics") ?: return emptyList()
            return (list as? JsonArray ?: JsonArray(listOf(list))).mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val lines = when (val raw = o["line"]) {
                    is JsonArray -> raw
                    is JsonObject -> JsonArray(listOf(raw))
                    else -> JsonArray(emptyList())
                }.mapNotNull { line ->
                    val l = line as? JsonObject ?: return@mapNotNull null
                    (l["start"] as? JsonPrimitive)?.longOrNull to
                        (l["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
                LyricsRow(
                    synced = (o["synced"] as? JsonPrimitive)?.booleanOrNull == true,
                    offsetMs = (o["offset"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    lines = lines,
                )
            }
        }

        fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
