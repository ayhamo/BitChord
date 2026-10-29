package com.music.bitchord.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import com.music.bitchord.fork.ForkSettings
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
 * Video clips from the owner's server (fork-only), as in the psysonic fork: a
 * song file can have looping clips next to it, a square `<file name>.cover.mp4`
 * (an animated cover) and a `<file name>.background.mp4` (a backdrop), served
 * at `/rest/x-clip/<folder>/<clip name>` behind Navidrome's own login
 * (`X-ND-Authorization: Bearer <token>`).
 *
 * BitChord has one place for a clip: the canvas, which plays over the sleeve.
 * So the cover clip is tried first (it is made for that square), then the
 * backdrop, each behind its own switch ([ForkSettings][com.music.bitchord.fork.ForkSettings]).
 * The clip is handed to the player as an ordinary canvas ([CanvasArtwork]),
 * tried before the catalogue searches, so it plays through BitChord's canvas
 * player (the still cover first, the clip fading in once it draws, paused in
 * the background) and its disk cache, under the same "Animated cover art" and
 * cellular switches.
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

    /** The two clips a song can have, in the order they are tried. */
    internal enum class Kind(val suffix: String) { COVER(".cover.mp4"), BACKDROP(".background.mp4") }

    /** Clip URLs found this session, by "<song id>|<kind>". */
    private val found = ConcurrentHashMap<String, String>()

    /** Song paths read this session, by song id, so trying a second clip costs no second lookup. */
    private val paths = ConcurrentHashMap<String, String>()

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
        paths.clear()
        if (::prefs.isInitialized) prefs.edit().clear().apply()
    }

    /** The song's clip, cover first, or null (none, switched off, not a Navidrome song, or the server couldn't be asked). */
    suspend fun canvasFor(song: Song): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (!NavidromeSettings.isNavidromeId(song.videoId) || !NavidromeSettings.isConfigured()) return@withContext null
        val id = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        if (!ForkSettings.coverClips.value) return@withContext null
        val url = clip(id, Kind.COVER) ?: return@withContext null
        CanvasArtwork(url = url, title = song.title, artist = song.artist, album = song.albumName)
    }

    private fun clip(id: String, kind: Kind): String? {
        val key = "$id|${kind.name}"
        found[key]?.let { return it }
        if (System.currentTimeMillis() - prefs.getLong(key, 0L) < MISS_MS) return null
        return lookUp(id, kind, key)
    }

    private fun lookUp(id: String, kind: Kind, key: String): String? = try {
        val base = base() ?: return null
        val path = paths[id] ?: songPath(base, id)?.also { paths[id] = it } ?: return null
        val url = clipUrl(base, path, kind)?.toString() ?: return null
        // One byte, to know whether there is a clip before the player mounts it.
        val probe = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
        Http.client.newCall(probe).execute().use { response ->
            when {
                response.isSuccessful -> url.also {
                    found[key] = it
                    Log.d(TAG, "$key: found")
                }
                // Only the clip route's own "no clip" is remembered: it always sends
                // `Cache-Control: private, max-age=604800`. A 404 without it (route missing, proxy
                // reloading), a 5xx or a timeout is a failure, asked again next time.
                response.code == 404 && response.header("Cache-Control")?.contains("max-age") == true -> {
                    prefs.edit().putLong(key, System.currentTimeMillis()).apply()
                    null
                }
                else -> null.also { Log.d(TAG, "$key: HTTP ${response.code}") }
            }
        }
    } catch (e: java.io.IOException) {
        Log.d(TAG, "$key: ${e.message}")
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

    /** `…/Youtube FLAC/001-Song.flac` → `<base>/rest/x-clip/Youtube FLAC/001-Song.cover.mp4` (or `.background.mp4`). */
    internal fun clipUrl(base: HttpUrl, songPath: String, kind: Kind): HttpUrl? {
        val parts = songPath.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val file = parts.last()
        val stem = file.substringBeforeLast('.', file)
        return base.newBuilder()
            .addPathSegment("rest").addPathSegment("x-clip")
            .addPathSegment(parts[parts.size - 2])
            .addPathSegment(stem + kind.suffix)
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
