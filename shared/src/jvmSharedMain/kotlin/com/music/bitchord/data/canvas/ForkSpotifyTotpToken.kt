package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fork: a Spotify web-player token minted by signing `/api/token` the way the web player does, as
 * the FALLBACK when reading the page's own token fails (the phone's offscreen WebView, the PC's
 * JavaFX WebEngine in `ForkSpotifyToken`). Both of those load the whole player and wait up to
 * 20-25 s for it; when the page won't load or never asks for a token, the sign-in was simply lost
 * for that lookup.
 *
 * The method is the owner's YT Music Extension's (Vivimusic Web, `src/spotify.js`,
 * `getSpotifyToken`), which mints this way from a browser extension every day:
 *  1. GET open.spotify.com with the `sp_dc` cookie and read `<script id="appServerConfig">`
 *     (base64 JSON: `clientVersion`, `clientId`), plus the `sp_t` device cookie the page hands out;
 *  2. GET `/api/server-time`: the code is computed for Spotify's clock, not ours (a PC clock a
 *     minute off was enough to be refused);
 *  3. TOTP from Spotify's rotating secret, newest version first, the list from ThetaDev's
 *     spotify-secrets (code.thetadev.de/ThetaDev/spotify-secrets), fetched once per app run and
 *     falling back to the newest secret the extension ships ([FALLBACK_SECRET]);
 *  4. GET `/api/token?reason=transport&productType=web-player&totp…&sTime&cTime&ts` with the
 *     cookie, `App-Platform: WebPlayer`, `Spotify-App-Version` and the web player's Origin/Referer.
 *
 * What upstream's PC signing lacked, and why it stopped minting: one secret version hardcoded
 * (v12) with no way to follow a rotation, `reason=init`, no `sTime`/`cTime`/`ts`, no app version
 * header, and the local clock.
 *
 * The phone's `SpotifyToken` notes that a token signed outside the page has been answered by
 * api.spotify.com/spclient with 429s; that is why this stays the fallback, not the first route.
 * An anonymous token (`isAnonymous`, a cookie Spotify no longer honours) is refused, as the
 * page-harvest bridges refuse it: it can't read a Canvas.
 *
 * A token is cached until shortly before its expiry; a failed mint is not retried for
 * [FAILURE_BACKOFF_MS] for the same cookie, so a lookup per song never turns into a request loop.
 * Blocking (OkHttp, [Http.client]); call it off the main thread.
 */
object ForkSpotifyTotpToken {

    private const val TAG = "ForkSpotifyTotpToken"

    const val HOME_URL = "https://open.spotify.com/"
    const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    const val TOKEN_URL = "https://open.spotify.com/api/token"
    const val SECRETS_URL =
        "https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json"

    /** The extension's fallback when the page carries no version. */
    const val FALLBACK_CLIENT_VERSION = "1.2.87.27.ga2033a72"

    /** The newest secret the YT Music Extension ships (`spotify.js`, `getSecretDict`), used when the list can't be fetched. */
    val FALLBACK_SECRET: Pair<Int, IntArray> =
        12 to intArrayOf(107, 81, 49, 57, 67, 93, 87, 81, 69, 67, 40, 93, 48, 50, 46, 91, 94, 113, 41, 47)

    const val FAILURE_BACKOFF_MS = 10L * 60 * 1000
    private const val DEFAULT_LIFETIME_MS = 3_600_000L

    /** The extension's own browser UA: open.spotify.com serves a different page to unknown agents. */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/141.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** What the existing token code keeps from a mint: the bearer, its expiry, and the client id the client-token step needs. */
    class Token(val accessToken: String, val expiresAtMs: Long, val clientId: String?)

    /** What the web player's page says about itself; [deviceId] is its `sp_t` cookie when it set one. */
    class WebPlayerConfig(val clientVersion: String?, val clientId: String?, val deviceId: String?)

    @Volatile private var cached: Token? = null
    @Volatile private var cachedFor = 0
    @Volatile private var failedFor = 0
    @Volatile private var retryAfterMs = 0L
    @Volatile private var secretForRun: Pair<Int, IntArray>? = null
    @Volatile private var config: WebPlayerConfig? = null

    /**
     * A signed-in token for [cookie] (the `sp_dc` value), or null when Spotify won't mint one, the
     * cookie is blank, or the last attempt with this cookie failed less than [FAILURE_BACKOFF_MS] ago.
     */
    @Synchronized
    fun mint(cookie: String): Token? {
        if (cookie.isBlank()) return null
        val now = System.currentTimeMillis()
        val key = cookie.hashCode()
        cached?.let { if (cachedFor == key && now < it.expiresAtMs - 30_000) return it }
        if (failedFor == key && now < retryAfterMs) return null

        val token = runCatching { request(cookie, now) }
            .onFailure { Log.w(TAG, "signed token request threw: ${it.message}") }
            .getOrNull()
        if (token == null) {
            failedFor = key
            retryAfterMs = now + FAILURE_BACKOFF_MS
            Log.w(TAG, "Spotify would not mint a signed token; not asking again for ${FAILURE_BACKOFF_MS / 60_000} min")
            return null
        }
        cached = token
        cachedFor = key
        failedFor = 0
        Log.d(TAG, "signed token minted, good until ${java.util.Date(token.expiresAtMs)}")
        return token
    }

    /**
     * The web player's own `clientVersion` and a device id, read once per run (neither changes
     * inside a session). For the PC's client-token step, which used to send a random device id;
     * [cookie] is optional: the page's config is the same signed in or not.
     */
    @Synchronized
    fun webPlayerConfig(cookie: String? = null): WebPlayerConfig? {
        config?.let { return it }
        return runCatching { fetchConfig(cookie) }.getOrNull()
            ?.also { if (it.clientVersion != null) config = it } // a page without its config is asked again next time
    }

    private fun request(cookie: String, now: Long): Token? {
        val page = fetchConfig(cookie) ?: return null
        if (page.clientVersion != null) config = page
        val (status, timeBody) = get(SERVER_TIME_URL, cookie, deviceId = page.deviceId)
        if (timeBody == null) {
            Log.w(TAG, "server-time answered http $status")
            return null
        }
        val serverTime = parseServerTime(timeBody) ?: (now / 1000)
        val (version, bytes) = secret()
        val code = totp(secretKey(bytes), serverTime)
        val clientVersion = page.clientVersion ?: FALLBACK_CLIENT_VERSION
        val (tokenStatus, body) = get(
            tokenUrl(code, version, serverTime, now),
            cookie,
            mapOf(
                "App-Platform" to "WebPlayer",
                "Spotify-App-Version" to clientVersion,
                "Accept" to "application/json",
                "Origin" to "https://open.spotify.com",
                "Referer" to "https://open.spotify.com/",
            ),
            deviceId = page.deviceId,
        )
        if (body == null) {
            Log.w(TAG, "api/token answered http $tokenStatus (secret v$version)")
            return null
        }
        return parseToken(body, now)?.let { token ->
            if (token.clientId == null && page.clientId != null) Token(token.accessToken, token.expiresAtMs, page.clientId) else token
        }
    }

    private fun fetchConfig(cookie: String?): WebPlayerConfig? {
        val request = Request.Builder().url(HOME_URL).header("User-Agent", UA)
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", "sp_dc=$cookie") }
            .build()
        return cookieClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "web player page answered http ${response.code}")
                return@use null
            }
            val html = response.body?.string() ?: return@use null
            val deviceId = response.headers("Set-Cookie").firstNotNullOfOrNull { header ->
                SP_T.find(header)?.groupValues?.get(1)
            }
            val parsed = parseAppServerConfig(html)
            WebPlayerConfig(parsed?.clientVersion, parsed?.clientId, deviceId)
        }
    }

    /** The newest secret from ThetaDev's list, fetched once per run; the shipped one when that fails. */
    private fun secret(): Pair<Int, IntArray> {
        secretForRun?.let { return it }
        // A list that failed to load is asked again an hour later, not only at the next start: one
        // dropped request shouldn't pin the run to a shipped secret Spotify may have retired.
        if (System.currentTimeMillis() < secretRetryAfterMs) return FALLBACK_SECRET
        val fetched = runCatching {
            val request = Request.Builder().url(SECRETS_URL).header("User-Agent", UA).build()
            Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.let(::parseSecretDict) else null
            }
        }.getOrNull()
        if (fetched == null) {
            Log.w(TAG, "couldn't read the secret list; using v${FALLBACK_SECRET.first}, asking again in an hour")
            secretRetryAfterMs = System.currentTimeMillis() + SECRET_RETRY_MS
            return FALLBACK_SECRET
        }
        // A list that loaded is kept for the run: one request per run, as the extension does.
        return fetched.also { secretForRun = it }
    }

    @Volatile private var secretRetryAfterMs = 0L
    private const val SECRET_RETRY_MS = 60L * 60 * 1000

    /**
     * The page's cookies go back with the next requests, as the extension's `credentials: 'include'`
     * sends them: `sp_dc`, and the `sp_t` device id the page handed out.
     */
    private fun get(
        url: String,
        cookie: String,
        headers: Map<String, String> = emptyMap(),
        deviceId: String? = null,
    ): Pair<Int, String?> {
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", if (deviceId.isNullOrBlank()) "sp_dc=$cookie" else "sp_dc=$cookie; sp_t=$deviceId")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return cookieClient.newCall(request).execute().use { response ->
            response.code to if (response.isSuccessful) response.body?.string() else null
        }
    }

    /**
     * [Http.client] with the `Cookie` header kept to open.spotify.com. OkHttp drops only
     * `Authorization` when a redirect leaves the host, and the cookie is set here by hand (the client
     * has no cookie jar), so a redirect to a login or consent page elsewhere would otherwise carry
     * the listener's sign-in with it. A network interceptor sees every hop of a redirect chain.
     */
    private val cookieClient by lazy {
        Http.client.newBuilder()
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == COOKIE_HOST || request.header("Cookie") == null) chain.proceed(request)
                else chain.proceed(request.newBuilder().removeHeader("Cookie").build())
            }
            .build()
    }

    private const val COOKIE_HOST = "open.spotify.com"

    // ---- Pure parts, tested without a network ---------------------------------------------

    /** `/api/token` with the extension's parameters. [nowMs] is `ts`, the request's own clock. */
    internal fun tokenUrl(code: String, version: Int, serverTimeSeconds: Long, nowMs: Long): String =
        TOKEN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("reason", "transport")
            .addQueryParameter("productType", "web-player")
            .addQueryParameter("totp", code)
            .addQueryParameter("totpVer", version.toString())
            .addQueryParameter("totpServer", code)
            .addQueryParameter("sTime", serverTimeSeconds.toString())
            .addQueryParameter("cTime", serverTimeSeconds.toString())
            .addQueryParameter("ts", nowMs.toString())
            .build()
            .toString()

    /**
     * The HMAC key from one of Spotify's obfuscated secrets: each byte XORed with `(i % 33) + 9`,
     * the results written out as decimal numbers one after another, and the ASCII of that string
     * is the key. (The extension base32-encodes and decodes it in between, which changes nothing.)
     */
    internal fun secretKey(secret: IntArray): ByteArray =
        secret.mapIndexed { index, value -> value xor (index % 33 + 9) }
            .joinToString("")
            .toByteArray(Charsets.US_ASCII)

    /** RFC 6238 TOTP: HMAC-SHA1, 30 s steps, 6 digits. */
    internal fun totp(key: ByteArray, epochSeconds: Long): String {
        val counter = epochSeconds / 30
        val message = ByteArray(8) { index -> (counter ushr (56 - index * 8)).toByte() }
        val hash = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(message)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    /** The newest version in ThetaDev's `secretDict.json` (`{"<version>": [bytes…], …}`). */
    internal fun parseSecretDict(body: String): Pair<Int, IntArray>? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        root.entries.mapNotNull { (version, value) ->
            val number = version.trim().toIntOrNull() ?: return@mapNotNull null
            val bytes = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null }
                ?: return@mapNotNull null
            if (bytes.isEmpty()) null else number to bytes.toIntArray()
        }.maxByOrNull { it.first }
    }.getOrNull()

    /** `clientVersion` and `clientId` out of the page's `<script id="appServerConfig">` (base64 JSON). */
    internal fun parseAppServerConfig(html: String): WebPlayerConfig? = runCatching {
        val encoded = APP_SERVER_CONFIG.find(html)?.groupValues?.get(1)?.trim() ?: return null
        val root = json.parseToJsonElement(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)) as? JsonObject
            ?: return null
        WebPlayerConfig(root.text("clientVersion"), root.text("clientId"), null)
    }.getOrNull()

    /** `{"serverTime": <seconds>}`. */
    internal fun parseServerTime(body: String): Long? = runCatching {
        ((json.parseToJsonElement(body) as? JsonObject)?.get("serverTime") as? JsonPrimitive)?.let {
            it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong()
        }
    }.getOrNull()?.takeIf { it > 0 }

    /** The token answer; null for none, a blank one, or an anonymous one. */
    internal fun parseToken(body: String, nowMs: Long): Token? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val token = root.text("accessToken")?.takeIf { it.isNotBlank() } ?: return null
        if (root.text("isAnonymous")?.toBooleanStrictOrNull() == true) return null
        val expiresAt = root.text("accessTokenExpirationTimestampMs")?.toLongOrNull()?.takeIf { it > nowMs }
            ?: (nowMs + DEFAULT_LIFETIME_MS)
        Token(token, expiresAt, root.text("clientId"))
    }.getOrNull()

    private val APP_SERVER_CONFIG = Regex("""<script[^>]*\bid="appServerConfig"[^>]*>([^<]+)</script>""")
    private val SP_T = Regex("""sp_t=([^;]+)""")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}
