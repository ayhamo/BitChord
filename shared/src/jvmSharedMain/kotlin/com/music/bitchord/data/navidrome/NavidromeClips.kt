package com.music.bitchord.data.navidrome

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
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
 * Video clips from the owner's server (fork-only), as in the psysonic fork: a
 * song file can have looping clips next to it, a square `<file name>.cover.mp4`
 * (an animated cover) and a `<file name>.background.mp4` (a backdrop), served
 * at `/rest/x-clip/<folder>/<clip name>` behind Navidrome's own login
 * (`X-ND-Authorization: Bearer <token>`).
 *
 * BitChord plays one clip at a time, the canvas. A backdrop (9:16, made as a
 * phone background) is handed over as [CanvasSource.BACKDROP], which the player
 * presents like Spotify's Canvas: full screen, the cover back on top while
 * paused. Without one, the cover clip plays over the sleeve. Each has its own
 * switch (`ForkSettings`).
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

    private lateinit var prefs: NavidromePlatform.Store
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The two clips a song can have, in the order they are tried. */
    enum class Kind(val suffix: String) { COVER(".cover.mp4"), BACKDROP(".background.mp4") }

    /** Clip URLs found this session, by "<song id>|<kind>". */
    private val found = ConcurrentHashMap<String, String>()

    /** Song paths read this session, by song id, so trying a second clip costs no second lookup. */
    private val paths = ConcurrentHashMap<String, String>()

    @Volatile private var token: String? = null
    private val tokenLock = Any()

    fun init(store: NavidromePlatform.Store) {
        prefs = store
        // Kept covers and clips (NavidromeOffline) are answered from the phone before anything goes out.
        Http.forkInterceptor = Interceptor { chain -> NavidromePlatform.kept.serve(chain.request()) ?: interceptor.intercept(chain) }
    }

    /** The settings changed: a different server or account answers differently. */
    fun reset() {
        token = null
        found.clear()
        paths.clear()
        if (::prefs.isInitialized) prefs.clear()
    }

    /** Forgets the "no clip" notes of [kind] (the clip cache's clear button): the server is asked again. */
    fun forgetMisses(kind: Kind) {
        if (!::prefs.isInitialized) return
        val suffix = "|${kind.name}"
        prefs.remove(prefs.keys().filter { it.endsWith(suffix) })
    }

    /** The song's clip, cover first, or null (none, switched off, not a Navidrome song, or the server couldn't be asked). */
    suspend fun canvasFor(song: Song): CanvasArtwork? {
        // Checked before the IO hop: every YouTube song comes through here first.
        if (!NavidromeSettings.isNavidromeId(song.videoId) || !NavidromeSettings.isConfigured()) return null
        return withContext(Dispatchers.IO) { serverClip(song) }
    }

    private fun serverClip(song: Song): CanvasArtwork? {
        val id = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        // A backdrop takes the whole phone (the Canvas presentation, cover back on top while
        // paused); without one, the cover clip plays over the sleeve. One clip plays at a time.
        // The cover clip is also looked up next to a backdrop: where the backdrop can't fill the
        // screen (wide window, landscape) the cover square plays the cover clip, never the backdrop.
        // Looked up whatever the backdrop switch says: the player applies the switch live
        // (ForkCanvasSwitch), falling back to the cover clip, so flipping it needs no new lookup.
        val backdrop = clip(id, Kind.BACKDROP)
        val cover = clip(id, Kind.COVER) // shown or not by "Animated covers", live in the player
        val url = backdrop ?: cover ?: return null
        return CanvasArtwork(
            url = url,
            title = song.title,
            artist = song.artist,
            album = song.albumName,
            source = if (backdrop != null) CanvasSource.BACKDROP else CanvasSource.OTHER,
            sleeveUrl = cover.takeIf { backdrop != null },
        )
    }

    /** The song's clip of [kind], kept or looked up; for "Available offline". */
    fun find(id: String, kind: Kind): String? = clip(id, kind)

    private fun clip(id: String, kind: Kind): String? {
        NavidromePlatform.kept.clipUrl(id, kind == Kind.BACKDROP)?.let { return it }
        val key = "$id|${kind.name}"
        found[key]?.let { return it }
        if (System.currentTimeMillis() - prefs.long(key) < MISS_MS) return null
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
                    prefs.putLong(key, System.currentTimeMillis())
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
    fun clipUrl(base: HttpUrl, songPath: String, kind: Kind): HttpUrl? {
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
        } catch (e: IllegalArgumentException) {
            // Not JSON (a proxy's HTML page): inside an interceptor anything but an IOException
            // escapes OkHttp's handling, so it is a failed login like any other.
            Log.d(TAG, "login: ${e.message}")
            null
        }
        token
    }
}
