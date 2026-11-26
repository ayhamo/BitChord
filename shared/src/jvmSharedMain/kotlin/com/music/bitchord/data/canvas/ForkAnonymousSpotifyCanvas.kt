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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fork: Spotify's Canvas with no sign-in — the canvas rulebook's §4, Metrolist's
 * `DirectCanvasProvider.getSpotifyCanvas` as the owner's phone extension ports it
 * (`SpotifyCanvas.java`): the anonymous web player's token (spotlib's TOTP, v61) and a client token,
 * Spotify's own top-results search (`searchTopResultsList`), Metrolist's match
 * ([ForkSpotifyCanvasRules.matches]), and the clip from Spotify's own pathfinder `canvas` query
 * (rule 4.5, Metrolist 460a3168a: `data.trackUnion.canvas.url`, [canvasOf]), only ever a `scdn.co`
 * URL over https ([ForkSpotifyCanvasRules.trusted]). Until 2026-11-24 the clip came from the public
 * mirror `spotify-canva.vercel.app`, which now answers every request with HTTP 402.
 *
 * The order (rule 4.0): the listener's own Spotify sign-in first (`SpotifyCanvas` / `SpotifyToken`
 * on the phone, `DesktopSpotifyCanvas` / `DesktopSpotifyToken` on the PC), then this lookup when
 * there is no sign-in or it finds nothing or fails, else no Spotify canvas. The apps' callers
 * (`ForkCanvasSources`, `ForkDesktopCanvasSources`) keep that order.
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
    private const val SEARCH_HASH = "12d940b3f7381506a82ff5382eb59b0ed65118656e6bcdfbe4cebc528f75d29d"
    /** The `canvas` persisted query (rule 4.5); the same hash upstream's signed-in route knows ([SpotifyCanvasQuery.KNOWN_CANVAS_HASH]). */
    private const val CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"
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

    /**
     * Metrolist's `spotifyCanvas`: the first matching Track among the top 10 results that has a canvas.
     * Not synchronized as a whole: up to 12 requests of 16 s each held one lock, so the player's
     * lookup waited behind a Top Picks or album lookup. Only the shared tokens are locked ([tokens]).
     */
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
        val items = searchItems(query("searchTopResultsList", SEARCH_HASH, variables))
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
            val canvas = trackCanvas(id)
            if (ForkSpotifyCanvasRules.trusted(canvas)) return canvas
            Log.d(TAG, "'$name' matches, no canvas")
        }
        return null
    }

    /**
     * Rule 4.5: the canvas URL in a pathfinder `canvas` answer, `data.trackUnion.canvas.url`, when it
     * is a string rule 4.6 trusts; null for no canvas, no track, no data or any other host or scheme
     * (Metrolist's `spotifyTrackCanvas`, the extension's `canvasOf`; case group `spotifyCanvas`).
     */
    fun canvasOf(answer: JsonObject?): String? {
        val canvas = (((answer?.get("data") as? JsonObject)?.get("trackUnion") as? JsonObject)?.get("canvas") as? JsonObject)
            ?: return null
        val url = (canvas["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return url.takeIf { ForkSpotifyCanvasRules.trusted(it) }
    }

    /**
     * A search answer's results, `data.searchV2.topResultsV2.itemsV2`; null when Spotify searched
     * and found nothing. An answer with no `data` object at all (an empty `{}`, a proxy's page that
     * happened to parse) throws: it is a failed lookup, so the caller doesn't save it as a 24 h miss
     * ([ForkCanvasStore.cached] keeps a null answer, never a throw).
     */
    fun searchItems(answer: JsonObject): JsonArray? {
        val data = answer["data"] as? JsonObject ?: throw IOException("searchTopResultsList: no data")
        return ((data["searchV2"] as? JsonObject)?.get("topResultsV2") as? JsonObject)?.get("itemsV2") as? JsonArray
    }

    /**
     * [canvasOf] for an answer to the `canvas` query, where a missing answer is an error, not "no
     * canvas": no `data` object, or no `trackUnion` object in it, throws (asked again later, never
     * saved as a miss). Only a track that is there and has no trusted canvas is null. [canvasOf]
     * itself stays lenient, as the rulebook's `spotifyCanvas` cases read it.
     */
    fun trackCanvasOf(answer: JsonObject): String? {
        val data = answer["data"] as? JsonObject ?: throw IOException("canvas: no data")
        if (data["trackUnion"] !is JsonObject) throw IOException("canvas: no track")
        return canvasOf(answer)
    }

    /** Rule 4.5: the track's canvas, asked of Spotify itself for a 22-character track id. */
    private fun trackCanvas(trackId: String): String? {
        if (!Regex("[A-Za-z0-9]{22}").matches(trackId)) return null
        return trackCanvasOf(query("canvas", CANVAS_HASH, buildJsonObject { put("trackUri", "spotify:track:$trackId") }))
    }

    /**
     * A pathfinder persisted query with the anonymous tokens. An expired session (401) drops both
     * tokens and asks once more; a second 401, a rate limit (429, never retried), GraphQL `errors`
     * or any other HTTP error throws: a failed lookup, never saved as a miss (rule 4.5, §2 rule 1).
     */
    private fun query(operation: String, hash: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", hash) } }
        }.toString()
        repeat(2) { attempt ->
            val (access, clientTok) = tokens()
            val request = Request.Builder().url(PATHFINDER_URL)
                .post(body.toRequestBody("application/json;charset=UTF-8".toMediaType()))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .header("app-platform", "WebPlayer")
                .header("spotify-app-version", APP_VERSION)
                .header("Authorization", "Bearer $access")
                .header("client-token", clientTok)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 && attempt == 0) {
                    dropTokens(access)
                    return@repeat
                }
                checkStatus(operation, response.code)
                val data = json.parseToJsonElement(read(response)) as? JsonObject ?: throw IOException("$operation: not JSON")
                if (hasErrors(data)) throw IOException("$operation: GraphQL errors")
                return data
            }
        }
        throw IOException("$operation: session expired")
    }

    /**
     * Canvas.md §4 rule 5: an answer failed only when its `errors` is a non-empty list. Audit
     * 2026-11-26: `containsKey("errors")` also failed `"errors": []` and `"errors": null`, which
     * some answers carry beside good data, so their canvas was thrown away as a failure.
     */
    internal fun hasErrors(data: JsonObject): Boolean = (data["errors"] as? JsonArray)?.isNotEmpty() == true

    private val tokenLock = Any()

    /** The access and client tokens, minted once for every lookup running at the time ([auth]). */
    private fun tokens(): Pair<String, String> = synchronized(tokenLock) {
        auth()
        (accessToken ?: throw IOException("/api/token: no access token")) to (clientToken ?: throw IOException("clienttoken: no token"))
    }

    /** A 401 on [stale]: both tokens go, unless another lookup has already replaced them. */
    private fun dropTokens(stale: String) = synchronized(tokenLock) {
        if (accessToken == stale) {
            accessToken = null
            clientToken = null
        }
    }

    /**
     * Only a 2xx answer is read. The client follows no redirects (rule 4.7), and a 3xx used to pass a
     * `>= 400` check and fail later as a JSON parse error; it is an HTTP failure like any other.
     */
    internal fun checkStatus(operation: String, code: Int) {
        if (code !in 200..299) throw IOException("$operation: HTTP $code")
    }

    /** spotlib's AuthHttp: the anonymous web-player token (TOTP v61), then the client token. Under [tokenLock]. */
    private fun auth() {
        if (accessToken == null || System.currentTimeMillis() > accessExpiresMs - 60_000) {
            val code = ForkSpotifyCanvasRules.totp(System.currentTimeMillis())
            val request = Request.Builder()
                .url("$TOKEN_URL?reason=init&productType=web-player&totp=$code&totpServer=$code&totpVer=${ForkSpotifyCanvasRules.TOTP_VERSION}")
                .header("User-Agent", USER_AGENT).header("Accept", "*/*").header("Referer", "https://open.spotify.com/")
                .build()
            client.newCall(request).execute().use { response ->
                checkStatus("/api/token", response.code)
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
                checkStatus("clienttoken", response.code)
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
