package com.music.bitchord.data.navidrome

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

/**
 * Video clips from the owner's server (fork-only), as in the psysonic fork: a song file could have
 * looping clips next to it, a square `<file name>.cover.mp4` and a `<file name>.background.mp4`,
 * served at `/rest/x-clip/<folder>/<clip name>` behind Navidrome's own login.
 *
 * Since 2026-11 the server has no clips, so nothing here asks it for one: only clips kept on the
 * device by "Available offline" before then still play ([clip]), handed to the player as an
 * ordinary canvas ([CanvasArtwork]) ahead of the catalogue searches. Every other server song gets
 * its animated cover and backdrop from the online services, like a YouTube song.
 *
 * What stays in use: [init] plugs the HTTP layer's fork interceptor in, which answers kept covers
 * and clips from the device and adds the Subsonic login to this server's `/rest/` requests
 * ([withSubsonicLogin]). The Navidrome login token ([token]) is only for its native API, which
 * nothing calls now.
 */
object NavidromeClips {

    private const val TAG = "BitChordNavidrome"

    private lateinit var prefs: NavidromePlatform.Store
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The two clips a song can have, in the order they are tried. */
    enum class Kind(val suffix: String) { COVER(".cover.mp4"), BACKDROP(".background.mp4") }

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
        if (::prefs.isInitialized) prefs.clear()
    }

    /** Forgets the "no clip" notes of [kind] (the clip cache's clear button): the server is asked again. */
    fun forgetMisses(kind: Kind) {
        if (!::prefs.isInitialized) return
        val suffix = "|${kind.name}"
        prefs.remove(prefs.keys().filter { it.endsWith(suffix) })
    }

    /** The song's kept clip, backdrop first, or null (none kept, or not a Navidrome song): then the online services are asked. */
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

    /** The song's kept clip of [kind], or null; for "Available offline", which no longer gets new ones. */
    fun find(id: String, kind: Kind): String? = clip(id, kind)

    /**
     * Only a clip already kept on the device. The server's x-clip route is gone (2026-11, the
     * clips left the server), so nothing is asked of it any more: a server song's animated cover
     * and backdrop come from the online services in [com.music.bitchord.data.canvas] like any
     * YouTube song's, and asking first only cost two requests and a miss on every play.
     */
    private fun clip(id: String, kind: Kind): String? = NavidromePlatform.kept.clipUrl(id, kind == Kind.BACKDROP)

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
        // Not x-clip any more: a kept clip whose file is gone would otherwise log in to ask a server that has none.
        return "/api/" in path
    }

    private val interceptor = Interceptor { chain ->
        val request = chain.request().withSubsonicLogin()
        if (!needsLogin(request.url)) return@Interceptor chain.proceed(request)
        val first = chain.proceed(request.withToken(token(fresh = false)))
        if (first.code != 401) return@Interceptor first
        // Expired login: once more with a new one.
        first.close()
        chain.proceed(request.withToken(token(fresh = true)))
    }

    /** The login a song's address leaves out (see [NavidromeClient.withLogin]), for this server only. */
    private fun Request.withSubsonicLogin(): Request {
        if ("/rest/" !in url.encodedPath || url.queryParameter("t") != null || !NavidromeSettings.isConfigured()) return this
        val plain = url.toString()
        val signed = NavidromeRepository.withLogin(plain)
        return if (signed == plain) this else newBuilder().url(signed).build()
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
