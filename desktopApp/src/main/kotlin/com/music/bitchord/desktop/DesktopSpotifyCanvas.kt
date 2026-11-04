package com.music.bitchord.desktop

import com.music.bitchord.data.canvas.SpotifyCanvasQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * Spotify's Canvas: the short looping clip shown behind a track in their own app.
 *
 * Needs the listener's `sp_dc` cookie, which is the one thing here that identifies an account —
 * see [DesktopSpotifyToken] for how a bearer is minted from it, and why that is the fragile part
 * on a desktop with no embedded browser.
 */
internal object DesktopSpotifyCanvas {

    private const val SEARCH_URL = "https://api.spotify.com/v1/search"

    /** Fork: the web player's search, and its persisted query id (the phone's `PATHFINDER_SEARCH_HASH`). */
    private const val FORK_PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val FORK_PATHFINDER_SEARCH_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
    private const val ALBUM_TRACKS_URL = "https://api.spotify.com/v1/albums"
    private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"

    /** spclient gates this path to Spotify's own apps by user agent; the web player's is turned
     * away, so this wears a mobile client's instead. */
    private const val SPOTIFY_APP_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val CANVAS_URL_REGEX = Regex("""https://[^"'\s\x00-\x1F]+\.cnvs\.mp4""")

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private data class TrackHit(val uri: String, val title: String, val artist: String, val album: String?)

    fun search(title: String, artist: String, album: String?): DesktopCanvasArtwork? {
        val token = DesktopSpotifyToken.accessToken() ?: return null
        val hit = searchTrack(title, artist, album, token) ?: return null
        val canvasUrl = fetchCanvasUrl(hit.uri, token) ?: return null
        return DesktopCanvasArtwork(
            url = canvasUrl,
            title = hit.title,
            artist = hit.artist,
            album = hit.album,
            source = DesktopCanvasSource.SPOTIFY,
        )
    }

    /** A release's canvas, read off its first track — Spotify hangs Canvas off tracks, not
     * releases, so there is no album-level lookup to make directly. */
    fun searchAlbum(album: String, artist: String): DesktopCanvasArtwork? {
        val token = DesktopSpotifyToken.accessToken() ?: return null
        val items = get(
            url("$SEARCH_URL", listOf("q" to "$album $artist", "type" to "album", "limit" to "10")),
            token,
        )?.get("albums")?.jsonObject?.get("items")?.jsonArray
            ?: return com.music.bitchord.data.canvas.ForkCanvasLookup.failed("Spotify: album search gave no answer") // fork: was `?: return null` on the line above

        for (item in items) {
            val record = item as? JsonObject ?: continue
            val recordTitle = record.text("name") ?: continue
            val artists = record["artists"]?.jsonArray
                ?.mapNotNull { it.jsonObject.text("name") }
                .orEmpty()
            if (!canvasMatches(recordTitle, artists, album, artist)) continue
            val albumId = record.text("id") ?: continue
            val trackUri = firstTrackUri(albumId, token) ?: continue
            val canvasUrl = fetchCanvasUrl(trackUri, token) ?: continue
            return DesktopCanvasArtwork(
                url = canvasUrl,
                title = recordTitle,
                artist = artists.joinToString(", ").ifBlank { null },
                album = recordTitle,
                source = DesktopCanvasSource.SPOTIFY,
            )
        }
        return null
    }

    private fun searchTrack(title: String, artist: String, album: String?, token: String): TrackHit? =
        forkSearchViaPathfinder(title, artist, album, token) ?: searchViaRest(title, artist, album, token) // fork

    // ── fork: Pathfinder search, as on the phone ─────────────────────────

    /**
     * Fork: the web player's own track search, tried before the REST one, as the phone does
     * (`SpotifyCanvas.searchViaPathfinder`). The REST search turns this kind of token away with
     * 429s in no pattern anyone has pinned down; on the PC it was the only search, so songs rarely
     * found their Canvas, and once the server's backdrops were gone the PC had almost none.
     *
     * Unlike the phone, a hit's name and artists are checked when the answer carries them
     * (`name`, `artists.items[].profile.name`), so a wrong song's Canvas isn't taken; an answer
     * without them is trusted as the phone trusts it.
     */
    private fun forkSearchViaPathfinder(title: String, artist: String, album: String?, token: String): TrackHit? {
        val clientToken = DesktopSpotifyToken.clientToken() ?: return null
        val variables = buildJsonObject {
            put("searchTerm", listOfNotNull(title, artist, album).joinToString(" "))
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", false)
        }.toString()
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", FORK_PATHFINDER_SEARCH_HASH)
            }
        }.toString()
        val address = url(
            FORK_PATHFINDER_URL,
            listOf("operationName" to "searchTracks", "variables" to variables, "extensions" to extensions),
        )
        val response = runCatching {
            http.send(
                HttpRequest.newBuilder(URI.create(address))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer $token")
                    .header("Client-Token", clientToken)
                    .header("App-platform", "WebPlayer")
                    .header("Accept", "application/json")
                    .header("User-Agent", CANVAS_UA)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrElse {
            DesktopTrackLog.log("canvas: Spotify search threw: ${it.message}")
            return null
        }
        if (response.statusCode() !in 200..299) {
            DesktopTrackLog.log("canvas: Spotify search answered HTTP ${response.statusCode()}")
            return null
        }
        val items = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
            ?.get("data")?.jsonObject?.get("searchV2")?.jsonObject?.get("tracksV2")?.jsonObject
            ?.get("items")?.jsonArray ?: return null
        for (entry in items) {
            val data = (entry as? JsonObject)?.get("item")?.jsonObject?.get("data")?.jsonObject ?: continue
            val uri = data.text("uri") ?: data.text("id")?.let { "spotify:track:$it" } ?: continue
            val name = data.text("name")
            val artists = (data["artists"] as? JsonObject)?.get("items")?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("profile")?.jsonObject?.text("name") }
                .orEmpty()
            if (name != null && artists.isNotEmpty() && !canvasMatches(name, artists, title, artist)) continue
            val albumName = (data["albumOfTrack"] as? JsonObject)?.text("name")
            return TrackHit(uri, name ?: title, artists.joinToString(", ").ifBlank { artist }, albumName ?: album)
        }
        return null
    }

    private fun searchViaRest(title: String, artist: String, album: String?, token: String): TrackHit? {
        val query = listOfNotNull(title, artist, album).joinToString(" ")
        val items = get(url(SEARCH_URL, listOf("q" to query, "type" to "track", "limit" to "10")), token)
            ?.get("tracks")?.jsonObject?.get("items")?.jsonArray
            ?: return com.music.bitchord.data.canvas.ForkCanvasLookup.failed("Spotify: search gave no answer") // fork: was `return null`
        for (item in items) {
            val track = item as? JsonObject ?: continue
            val trackTitle = track.text("name") ?: continue
            val artists = track["artists"]?.jsonArray
                ?.mapNotNull { it.jsonObject.text("name") }
                .orEmpty()
            if (!canvasMatches(trackTitle, artists, title, artist)) continue
            val uri = track.text("uri") ?: continue
            return TrackHit(
                uri = uri,
                title = trackTitle,
                artist = artists.joinToString(", ").ifBlank { artist },
                album = track["album"]?.jsonObject?.text("name"),
            )
        }
        return null
    }

    private fun firstTrackUri(albumId: String, token: String): String? =
        get(url("$ALBUM_TRACKS_URL/$albumId/tracks", listOf("limit" to "1")), token)
            ?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.text("uri")

    // ── Pathfinder: the `canvas` GraphQL query ───────────────────────────

    /** The live `canvas` query hash, read off the web player's own scripts. */
    private val queryHashes = SpotifyCanvasQuery.QueryHashes(fetch = { url -> canvasGet(url) })

    /** The track's canvas, asked for the way the current web player does; `canvaz-cache` is the
     * fallback when the query fails or answers with nothing playable. */
    private fun fetchCanvasUrl(trackUri: String, token: String): String? =
        when (val answer = fetchCanvasViaPathfinder(trackUri, token)) {
            is SpotifyCanvasQuery.Answer.Found -> answer.url
            is SpotifyCanvasQuery.Answer.NoCanvas -> {
                DesktopTrackLog.log("canvas: Pathfinder has no playable canvas (${answer.detail}); trying canvaz-cache")
                com.music.bitchord.data.canvas.ForkCanvasLookup.answered { fetchCanvasViaCanvaz(trackUri, token) } // fork: Pathfinder answered, so canvaz-cache failing is no failure
            }
            is SpotifyCanvasQuery.Answer.Failed -> {
                DesktopTrackLog.log("canvas: Spotify's canvas query failed (${answer.reason}); trying canvaz-cache")
                fetchCanvasViaCanvaz(trackUri, token)
            }
        }

    private fun fetchCanvasViaPathfinder(trackUri: String, token: String, isRetry: Boolean = false): SpotifyCanvasQuery.Answer {
        val hash = queryHashes.canvasHash(forceRefresh = isRetry)
        val response = runCatching {
            val builder = HttpRequest.newBuilder(URI.create(SpotifyCanvasQuery.ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Accept-Language", "en")
                .header("App-platform", "WebPlayer")
                .header("User-Agent", CANVAS_UA)
            authHeaders(token).forEach { (name, value) -> builder.header(name, value) }
            http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(SpotifyCanvasQuery.requestBody(trackUri, hash))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrElse { return SpotifyCanvasQuery.Answer.Failed("request threw: ${it.message}") }
        if (response.statusCode() !in 200..299) return SpotifyCanvasQuery.Answer.Failed("http ${response.statusCode()}")

        val answer = SpotifyCanvasQuery.parse(response.body())
        // A rebuilt web player retires the old hash: look it up again once.
        if (answer is SpotifyCanvasQuery.Answer.Failed && answer.staleHash && !isRetry) {
            return fetchCanvasViaPathfinder(trackUri, token, isRetry = true)
        }
        return answer
    }

    // ── canvaz-cache: protobuf in, protobuf out (fallback) ───────────────

    internal data class CanvasHit(val url: String, val trackUri: String?)

    private fun fetchCanvasViaCanvaz(trackUri: String, token: String): String? {
        val bytes = runCatching {
            val builder = HttpRequest.newBuilder(URI.create(CANVAS_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/protobuf")
                .header("Content-Type", "application/protobuf")
                .header("Accept-Language", "en")
                .header("User-Agent", SPOTIFY_APP_UA)
            authHeaders(token).forEach { (name, value) -> builder.header(name, value) }
            val response = http.send(
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(encodeCanvasRequest(trackUri))).build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            if (response.statusCode() in 200..299) response.body() else null
        }.getOrNull() ?: return com.music.bitchord.data.canvas.ForkCanvasLookup.failed("Spotify: canvaz-cache gave no answer") // fork: was `return null`

        // The structured parse first — it can tell this track's own clip apart from another one
        // bundled into the same response. The regex is the fallback, needing only a *.cnvs.mp4 URL
        // to be sitting in the bytes as plain text.
        val hits = decodeCanvasResponse(bytes)
        hits.firstOrNull { it.trackUri == trackUri }?.url?.let { return it }
        hits.firstOrNull()?.url?.let { return it }
        return CANVAS_URL_REGEX.find(String(bytes, Charsets.ISO_8859_1))?.value
    }

    /** `CanvasRequest { repeated Track tracks = 1; Track { string track_uri = 1; } }` */
    internal fun encodeCanvasRequest(trackUri: String): ByteArray {
        val track = ByteArrayOutputStream().apply { writeLengthDelimited(1, trackUri.toByteArray()) }
        return ByteArrayOutputStream().apply { writeLengthDelimited(1, track.toByteArray()) }.toByteArray()
    }

    /**
     * `CanvasResponse { repeated Canvas canvases = 1; }`, `Canvas { id = 1; canvas_url = 2;
     * track_uri = 5; }` — only the fields this needs are read.
     */
    internal fun decodeCanvasResponse(bytes: ByteArray): List<CanvasHit> = runCatching {
        val hits = mutableListOf<CanvasHit>()
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            if (tag shr 3 == 1 && tag and 7 == 2) {
                decodeCanvas(reader.readBytes())?.let(hits::add)
            } else {
                reader.skip(tag)
            }
        }
        hits
    }.getOrElse { emptyList() }

    private fun decodeCanvas(bytes: ByteArray): CanvasHit? = runCatching {
        var url: String? = null
        var trackUri: String? = null
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            when {
                tag shr 3 == 2 && tag and 7 == 2 -> url = String(reader.readBytes(), StandardCharsets.UTF_8)
                tag shr 3 == 5 && tag and 7 == 2 -> trackUri = String(reader.readBytes(), StandardCharsets.UTF_8)
                else -> reader.skip(tag)
            }
        }
        url?.let { CanvasHit(it, trackUri) }
    }.getOrNull()

    // ── Plumbing ─────────────────────────────────────────────────────────

    /** The client token on top of the bearer: both endpoints turn away a bearer-only request with
     * a 429 that reads exactly like rate limiting. Omitted rather than fatal when minting fails. */
    private fun authHeaders(token: String): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        DesktopSpotifyToken.clientToken()?.let { put("Client-Token", it) }
    }

    private fun get(url: String, token: String): JsonObject? =
        canvasGet(url, authHeaders(token))
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun url(base: String, params: List<Pair<String, String>>): String =
        params.joinToString("&", prefix = "$base?") { (name, value) ->
            "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

    /** A string field, or null when Spotify put something other than a string there. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}

// ── A protobuf reader, small enough not to be worth a dependency ────────────

private class ProtoReader(private val bytes: ByteArray) {
    private var at = 0

    fun hasMore(): Boolean = at < bytes.size

    fun readTag(): Int? = if (hasMore()) readVarint().toInt() else null

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        val end = (at + length).coerceAtMost(bytes.size)
        val slice = bytes.copyOfRange(at, end)
        at = end
        return slice
    }

    fun skip(tag: Int) {
        when (tag and 7) {
            0 -> readVarint()
            1 -> at += 8
            2 -> readBytes()
            5 -> at += 4
            else -> at = bytes.size
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (at < bytes.size && shift < 64) {
            val byte = bytes[at++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }
}

private fun ByteArrayOutputStream.writeLengthDelimited(field: Int, payload: ByteArray) {
    writeVarint((field shl 3 or 2).toLong())
    writeVarint(payload.size.toLong())
    write(payload)
}

private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var remaining = value
    while (true) {
        if (remaining and 0x7FL.inv() == 0L) {
            write(remaining.toInt())
            return
        }
        write((remaining and 0x7F or 0x80).toInt())
        remaining = remaining ushr 7
    }
}

/**
 * The bearer Spotify's own web player mints for itself.
 *
 * Android loads the real player in an offscreen WebView and reads the token it mints, because a
 * request signed here is answered with a token the downstream endpoints then refuse. A desktop
 * build has no embedded browser to do that in, so this takes the only route left: the web player's
 * own `/api/token`, signed with the TOTP it derives from a secret published in its bundle. When
 * Spotify declines, the canvas chain simply falls through to its other three sources.
 */
internal object DesktopSpotifyToken {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var accessTokenExpiresAtMs = 0L
    @Volatile private var cachedClientId: String? = null
    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiresAtMs = 0L
    @Volatile private var retryAfterMs = 0L
    @Volatile private var session: Pair<String, String>? = null

    /** The listener's `sp_dc` cookie. Nothing here works without it. */
    fun cookie(): String = DesktopPersistence().string(KEY_SPDC).trim()

    fun setCookie(value: String) {
        DesktopPersistence().saveString(KEY_SPDC, value.trim())
        cachedAccessToken = null
        accessTokenExpiresAtMs = 0L
        retryAfterMs = 0L
        com.music.bitchord.data.canvas.ForkCanvasLookup.clearNotes() // fork: songs held back by the old sign-in's backoff are asked again
    }

    @Synchronized
    fun accessToken(): String? {
        val cookie = cookie().ifBlank { return null }
        val now = System.currentTimeMillis()
        cachedAccessToken?.let { if (now < accessTokenExpiresAtMs - 30_000) return it }
        if (now < retryAfterMs) return com.music.bitchord.data.canvas.ForkCanvasLookup.coolingDown("Spotify: token backing off", retryAfterMs) // fork: was `return null`

        // fork: the page's own token first, as on the phone; signing below is what's left without a browser
        ForkSpotifyToken.mint(cookie)?.let { minted ->
            cachedAccessToken = minted.token
            accessTokenExpiresAtMs = minted.expiresAtMs ?: (now + 3_600_000)
            minted.clientId?.let { cachedClientId = it }
            return minted.token
        }

        // fork: upstream's own signing (one secret, v12, `reason=init`, no server time) stopped minting;
        // the YT Music Extension's route in shared ForkSpotifyTotpToken replaces it, as on the phone
        val signed = com.music.bitchord.data.canvas.ForkSpotifyTotpToken.mint(cookie)
        if (signed == null) {
            // Backed off rather than retried per track: if Spotify has changed the shape, asking on
            // every skip fixes nothing and makes every canvas lookup pay for the round trip.
            retryAfterMs = now + RETRY_MS
            DesktopTrackLog.log("canvas: Spotify would not mint an access token")
            return com.music.bitchord.data.canvas.ForkCanvasLookup.coolingDown("Spotify: no token", retryAfterMs) // fork: was `return null`; backing off until then
        }
        cachedAccessToken = signed.accessToken // fork: the three lines read `signed` (were the parsed answer)
        accessTokenExpiresAtMs = signed.expiresAtMs
        cachedClientId = signed.clientId
        return signed.accessToken
    }

    /**
     * The second header these endpoints demand alongside the bearer. Best-effort: a caller with no
     * client token sends the bearer alone and takes whatever the endpoint does with that.
     */
    @Synchronized
    fun clientToken(): String? {
        val now = System.currentTimeMillis()
        cachedClientToken?.let { if (now < clientTokenExpiresAtMs - 30_000) return it }
        val clientId = cachedClientId ?: return null
        val (clientVersion, deviceId) = session() ?: return null

        val payload = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", clientVersion)
                put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "linux")
                    put("os_version", System.getProperty("os.version").orEmpty())
                    put("device_id", deviceId)
                    put("device_type", "computer")
                }
            }
        }
        val body = runCatching {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val response = client.send(
                HttpRequest.newBuilder(URI.create("https://clienttoken.spotify.com/v1/clienttoken"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    // The bare header, deliberately: clienttoken 400s on a charset suffix.
                    .header("Content-Type", "application/json")
                    .header("User-Agent", CANVAS_UA)
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() in 200..299) response.body() else null
        }.getOrNull() ?: return null

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["response_type"]?.jsonPrimitive?.contentOrNull != "RESPONSE_GRANTED_TOKEN_RESPONSE") return null
        val granted = root["granted_token"]?.jsonObject ?: return null
        val token = granted["token"]?.jsonPrimitive?.contentOrNull ?: return null
        val ttl = granted["expires_after_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        cachedClientToken = token
        clientTokenExpiresAtMs = now + ttl * 1000
        return token
    }

    /** The web player's build version, read off its own page, plus a device id to call ourselves. */
    private fun session(): Pair<String, String>? {
        session?.let { return it }
        // fork: the page's config and its own `sp_t` device id through shared ForkSpotifyTotpToken (the
        // phone's way); upstream parsed the page here and sent a random device id
        val page = com.music.bitchord.data.canvas.ForkSpotifyTotpToken.webPlayerConfig(cookie().ifBlank { null }) ?: return null
        val clientVersion = page.clientVersion ?: return null
        return (clientVersion to (page.deviceId ?: UUID.randomUUID().toString())).also { session = it }
    }

    private const val RETRY_MS = 30L * 60 * 1000

    internal const val KEY_SPDC = "spotify_spdc_token"
}
