package com.music.bitchord.data.navidrome

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A small Subsonic / OpenSubsonic client for one Navidrome server (fork-only).
 *
 * Every request carries token auth (`t` = md5(password + salt), `s` = salt)
 * rather than the password. The salt is made once per client, so the stream
 * and cover URLs it hands out stay valid for as long as the password does —
 * they end up stored in the queue, and Subsonic tokens don't expire.
 */
class NavidromeClient(
    baseUrl: String,
    private val username: String,
    password: String,
) {
    private val base: HttpUrl = NavidromeSettings.normalizeUrl(baseUrl).toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Not a server address")
    private val salt: String = ByteArray(8).also(SecureRandom()::nextBytes).toHex()
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
        val songs = result["searchResult3"]?.jsonObject?.get("song") ?: return emptyList()
        return (songs as? JsonArray ?: JsonArray(listOf(songs))).mapNotNull { it.toSongRow() }
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
        )
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
