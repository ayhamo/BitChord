package com.music.bitchord.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Video backdrops from the owner's server (fork-only), as in the psysonic
 * fork: a song file can have a looping `<file name>.background.mp4` next to
 * it, served at `/rest/x-clip/<folder>/<file name>.background.mp4` behind
 * Navidrome's own login (`X-ND-Authorization: Bearer <token>`).
 *
 * Handed to the player as an ordinary canvas ([CanvasArtwork]), tried before
 * the catalogue searches, so it plays through BitChord's canvas player and its
 * disk cache, under the same "Animated cover art" and cellular switches.
 * The login header can't travel in a URL, so [interceptor] (plugged into
 * [Http.client]) adds it to x-clip and native-API requests for the configured
 * server, and logs in again once on a 401.
 *
 * A song's real file path only comes from Navidrome's native API
 * (`/api/song/<id>`); the Subsonic `path` is made up from the tags.
 */
object NavidromeClips {

    private const val TAG = "BitChordNavidrome"
    private const val MISS_MS = 7L * 24 * 60 * 60 * 1000

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Clip URLs found this session, by song id. */
    private val found = ConcurrentHashMap<String, String>()

    @Volatile private var token: String? = null
    private val tokenLock = Any()

    fun init(context: Context) {
        // Plain prefs: only "no clip for this song" notes, nothing secret.
        prefs = context.getSharedPreferences("bitchord_navidrome_clips", Context.MODE_PRIVATE)
        Http.forkInterceptor = interceptor
    }

    /** The settings changed: a different server or account answers differently. */
    fun reset() {
        token = null
        found.clear()
        if (::prefs.isInitialized) prefs.edit().clear().apply()
    }

    /** The song's backdrop clip, or null (none, not a Navidrome song, or the server couldn't be asked). */
    suspend fun canvasFor(song: Song): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (!NavidromeSettings.isNavidromeId(song.videoId) || !NavidromeSettings.isConfigured()) return@withContext null
        val id = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        val url = found[id] ?: run {
            if (System.currentTimeMillis() - prefs.getLong(id, 0L) < MISS_MS) return@withContext null
            lookUp(id) ?: return@withContext null
        }
        CanvasArtwork(url = url, title = song.title, artist = song.artist, album = song.albumName)
    }

    private fun lookUp(id: String): String? = try {
        val base = base() ?: return null
        val path = songPath(base, id) ?: return null
        val url = clipUrl(base, path)?.toString() ?: return null
        // One byte, to know whether there is a clip before the player mounts it.
        val probe = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
        Http.client.newCall(probe).execute().use { response ->
            when {
                response.isSuccessful -> url.also { found[id] = it }
                // Only the clip route's own "no clip" is remembered (it sends Cache-Control);
                // a 404 from a server without the route, or any other failure, is asked again later.
                response.code == 404 && response.header("Cache-Control")?.contains("max-age") == true -> {
                    prefs.edit().putLong(id, System.currentTimeMillis()).apply()
                    null
                }
                else -> null.also { Log.d(TAG, "clip for $id: HTTP ${response.code}") }
            }
        }
    } catch (e: java.io.IOException) {
        Log.d(TAG, "clip for $id: ${e.message}")
        null
    }

    /** The file's path as stored on the server, from the native API. */
    private fun songPath(base: HttpUrl, id: String): String? {
        val url = base.newBuilder().addPathSegment("api").addPathSegment("song").addPathSegment(id).build()
        Http.client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "song $id: HTTP ${response.code}")
                return null
            }
            val song = runCatching { json.parseToJsonElement(response.body.string()) }.getOrNull() as? JsonObject
            return (song?.get("path") as? JsonPrimitive)?.contentOrNull
                .also { if (it == null) Log.d(TAG, "song $id: no path in the answer") }
        }
    }

    /** `…/Youtube FLAC/001-Song.flac` → `<base>/rest/x-clip/Youtube FLAC/001-Song.background.mp4`. */
    internal fun clipUrl(base: HttpUrl, songPath: String): HttpUrl? {
        val parts = songPath.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val file = parts.last()
        val stem = file.substringBeforeLast('.', file)
        return base.newBuilder()
            .addPathSegment("rest").addPathSegment("x-clip")
            .addPathSegment(parts[parts.size - 2])
            .addPathSegment("$stem.background.mp4")
            .build()
    }

    private fun base(): HttpUrl? = NavidromeSettings.url.value.toHttpUrlOrNull()

    /** Requests that need Navidrome's login: its native API and the x-clip route, on the configured server. */
    private fun needsLogin(url: HttpUrl): Boolean {
        val base = base() ?: return false
        if (url.host != base.host || url.port != base.port) return false
        val path = url.encodedPath
        return "/rest/x-clip/" in path || "/api/" in path
    }

    private val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!needsLogin(request.url)) return@Interceptor chain.proceed(request)
        val first = chain.proceed(request.withToken(token(fresh = false)))
        if (first.code != 401) return@Interceptor first
        // Expired login: once more with a new one.
        first.close()
        chain.proceed(request.withToken(token(fresh = true)))
    }

    private fun Request.withToken(token: String?): Request =
        if (token == null) this else newBuilder().header("X-ND-Authorization", "Bearer $token").build()

    /** Navidrome's login token, logging in when there is none (or [fresh] asks for a new one). */
    private fun token(fresh: Boolean): String? = synchronized(tokenLock) {
        if (!fresh) token?.let { return it }
        val base = base() ?: return null
        val body = buildJsonObject {
            put("username", NavidromeSettings.username.value)
            put("password", NavidromeSettings.password.value)
        }.toString().toRequestBody("application/json".toMediaType())
        val url = base.newBuilder().addPathSegment("auth").addPathSegment("login").build()
        token = try {
            Http.client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "login: HTTP ${response.code}")
                    null
                } else {
                    (json.parseToJsonElement(response.body.string()).jsonObject["token"] as? JsonPrimitive)?.contentOrNull
                }
            }
        } catch (e: java.io.IOException) {
            Log.d(TAG, "login: ${e.message}")
            null
        }
        token
    }
}
