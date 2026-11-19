package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fork: Spotify's Canvas with no sign-in — the canvas rulebook's §4, Metrolist's
 * `DirectCanvasProvider.getSpotifyCanvas` as the owner's phone extension ports it
 * (`SpotifyCanvas.java`): the anonymous web player's token (spotlib's TOTP, v61) and a client token,
 * Spotify's own top-results search (`searchTopResultsList`), Metrolist's match
 * ([ForkSpotifyCanvasRules.matches]), and the clip's URL from the public mirror
 * (`spotify-canva.vercel.app`), only ever a `scdn.co` URL over https ([ForkSpotifyCanvasRules.trusted]).
 *
 * The fork's Spotify source signs in with the listener's `sp_dc` cookie (`SpotifyToken`,
 * `DesktopSpotifyToken`), and without one asked nothing: no Spotify Canvas at all for anyone who
 * hadn't pasted a cookie. This is what the apps ask in that case; with a cookie set the signed-in
 * route stays first, as the owner set it up. The rulebook asks for anonymous only ("never the
 * listener's account"); keeping the cookie route is the fork's recorded difference.
 *
 * Every request: 8 s, no redirects, an answer read up to 256 KB (rule 4.7). Throws on a failed
 * request (the caller reports it, and the song is asked again later, not saved as "no canvas");
 * null is Spotify's own "nothing for this song". Blocking; off the main thread.
 */
object ForkAnonymousSpotifyCanvas {

    private const val TAG = "ForkAnonSpotifyCanvas"
    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val MIRROR_URL = "https://spotify-canva.vercel.app/api/canvas?trackId="
    private const val SEARCH_HASH = "12d940b3f7381506a82ff5382eb59b0ed65118656e6bcdfbe4cebc528f75d29d"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0"
    private const val APP_VERSION = "1.2.96.390.gd46a85db"
    private const val WEB_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"
    private const val TIMEOUT_MS = 8_000L
    private const val MAX_BYTES = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val deviceId = UUID.randomUUID().toString()

    @Volatile private var accessToken: String? = null
    @Volatile private var clientToken: String? = null
    @Volatile private var accessExpiresMs = 0L

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * The song's Canvas as an artwork named as asked (the match is already the rulebook's, so the
     * player's exact-title re-check must not turn a "- Remastered" hit away), or null.
     */
    fun search(title: String, artist: String, album: String?, durationMs: Long?): CanvasArtwork? {
        val url = canvasUrl(title, artist, album, durationMs) ?: return null
        return CanvasArtwork(url = url, title = title, artist = artist, album = album, source = CanvasSource.SPOTIFY)
    }

    /** Metrolist's `spotifyCanvas`: the first matching Track among the top 10 results that has a canvas. */
    @Synchronized
    fun canvasUrl(title: String, artist: String, album: String?, durationMs: Long?): String? {
        if (title.isBlank() || artist.isBlank()) return null
        val variables = buildJsonObject {
            put("query", "$title $artist")
            put("limit", 10)
            put("offset", 0)
            put("numberOfTopResults", 10)
            put("includeArtistHasConcertsField", false)
            put("includeAudiobooks", true)
            put("includeAuthors", false)
            put("includePreReleases", true)
            put("includeAlbumPreReleases", false)
            put("includeEpisodeContentRatingsV2", true)
            putJsonArray("sectionFilters") { add("GENERIC"); add("VIDEO_CONTENT") }
        }
        val items = ((((query("searchTopResultsList", SEARCH_HASH, variables)["data"] as? JsonObject)
            ?.get("searchV2") as? JsonObject)?.get("topResultsV2") as? JsonObject)?.get("itemsV2") as? JsonArray)
            ?: return null.also { Log.d(TAG, "no results for '$title'") }
        for (entry in items.take(10)) {
            val track = ((entry as? JsonObject)?.get("item") as? JsonObject)?.get("data") as? JsonObject ?: continue
            if (track.text("__typename") != "Track") continue
            val id = track.text("id")?.takeIf { it.isNotEmpty() } ?: continue
            val names = ((track["artists"] as? JsonObject)?.get("items") as? JsonArray)
                ?.mapNotNull { ((it as? JsonObject)?.get("profile") as? JsonObject)?.text("name") }
                .orEmpty()
            val name = track.text("name").orEmpty()
            val foundAlbum = (track["albumOfTrack"] as? JsonObject)?.text("name")
            val foundMs = ((track["duration"] as? JsonObject)?.get("totalMilliseconds") as? JsonPrimitive)?.longOrNull ?: 0L
            if (!ForkSpotifyCanvasRules.matchesHit(title, artist, album, durationMs, name, names, foundAlbum, foundMs)) continue
            val canvas = publicCanvas(id)
            if (ForkSpotifyCanvasRules.trusted(canvas)) return canvas
            Log.d(TAG, "'$name' matches, no canvas")
        }
        return null
    }

    /** Metrolist's `publicSpotifyCanvas`: the public mirror's canvas for a 22-character track id (rule 4.5). */
    private fun publicCanvas(trackId: String): String? {
        if (!Regex("[A-Za-z0-9]{22}").matches(trackId)) return null
        val request = Request.Builder().url(MIRROR_URL + URLEncoder.encode(trackId, "UTF-8"))
            .header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null // the mirror has none
            // busy or down (429, 5xx): a failure, so the song is asked again later
            if (response.code != 200) throw IOException("canvas mirror: HTTP ${response.code}")
            val list = (json.parseToJsonElement(read(response)) as? JsonObject)?.get("canvasesList") as? JsonArray ?: return null
            return list.firstNotNullOfOrNull { c ->
                val o = c as? JsonObject ?: return@firstNotNullOfOrNull null
                o.text("canvasUrl")?.takeIf { o.text("trackUri") == "spotify:track:$trackId" && ForkSpotifyCanvasRules.trusted(it) }
            }
        }
    }

    /** A pathfinder persisted query with the anonymous tokens, fetched again once on a 401. */
    private fun query(operation: String, hash: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", hash) } }
        }.toString()
        repeat(2) { attempt ->
            auth()
            val request = Request.Builder().url(PATHFINDER_URL)
                .post(body.toRequestBody("application/json;charset=UTF-8".toMediaType()))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .header("app-platform", "WebPlayer")
                .header("spotify-app-version", APP_VERSION)
                .header("Authorization", "Bearer $accessToken")
                .header("client-token", clientToken.orEmpty())
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 && attempt == 0) {
                    accessToken = null
                    clientToken = null
                    return@repeat
                }
                if (response.code >= 400) throw IOException("$operation: HTTP ${response.code}")
                val data = json.parseToJsonElement(read(response)) as? JsonObject ?: throw IOException("$operation: not JSON")
                if (data.containsKey("errors")) throw IOException("$operation: GraphQL errors")
                return data
            }
        }
        throw IOException("$operation: session expired")
    }

    /** spotlib's AuthHttp: the anonymous web-player token (TOTP v61), then the client token. */
    private fun auth() {
        if (accessToken == null || System.currentTimeMillis() > accessExpiresMs - 60_000) {
            val code = ForkSpotifyCanvasRules.totp(System.currentTimeMillis())
            val request = Request.Builder()
                .url("$TOKEN_URL?reason=init&productType=web-player&totp=$code&totpServer=$code&totpVer=${ForkSpotifyCanvasRules.TOTP_VERSION}")
                .header("User-Agent", USER_AGENT).header("Accept", "*/*").header("Referer", "https://open.spotify.com/")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code >= 400) throw IOException("/api/token: HTTP ${response.code}")
                val t = json.parseToJsonElement(read(response)) as? JsonObject ?: throw IOException("/api/token: not JSON")
                val token = t.text("accessToken")?.takeIf { it.isNotEmpty() } ?: throw IOException("/api/token: no access token")
                accessToken = token
                accessExpiresMs = (t["accessTokenExpirationTimestampMs"] as? JsonPrimitive)?.longOrNull
                    ?: (System.currentTimeMillis() + 30 * 60_000)
                clientToken = null
            }
        }
        if (clientToken == null) {
            val payload = buildJsonObject {
                putJsonObject("client_data") {
                    put("client_version", APP_VERSION)
                    put("client_id", WEB_CLIENT_ID)
                    putJsonObject("js_sdk_data") {
                        put("device_brand", "unknown"); put("device_model", "unknown"); put("os", "android")
                        put("os_version", "unknown"); put("device_id", deviceId); put("device_type", "computer")
                    }
                }
            }.toString()
            val request = Request.Builder().url(CLIENT_TOKEN_URL)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", USER_AGENT).header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com").header("Referer", "https://open.spotify.com/")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code >= 400) throw IOException("clienttoken: HTTP ${response.code}")
                val granted = (json.parseToJsonElement(read(response)) as? JsonObject)?.get("granted_token") as? JsonObject
                clientToken = granted?.text("token")?.takeIf { it.isNotEmpty() } ?: throw IOException("clienttoken: no token")
            }
        }
    }

    /** The answer's body, at most [MAX_BYTES] (rule 4.7). */
    private fun read(response: okhttp3.Response): String {
        val source = response.body?.source() ?: return ""
        if (!source.request(MAX_BYTES + 1L)) return source.buffer.readUtf8()
        throw IOException("answer over ${MAX_BYTES / 1024} KB")
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
