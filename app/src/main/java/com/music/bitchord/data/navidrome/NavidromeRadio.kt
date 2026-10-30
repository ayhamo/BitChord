package com.music.bitchord.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.Request

/**
 * The server's internet radio stations (fork-only), as a Library page
 * ([PAGE_ID], the "Internet radio" link): each station a row that plays its
 * stream, so Next and Previous move between stations.
 *
 * A station is a [Song] with a `radio:` id and its stream as `localUri`, the
 * way the WebDAV library hands over files. A live stream has no end and is
 * never worth keeping, so its address carries a mark ([isStream]) that keeps
 * it out of the song cache
 * (`AudioCache.playbackFactory`). A `.pls` or `.m3u` address (a playlist file
 * naming the real stream, common for stations) is read for its first stream
 * when the page loads; ExoPlayer plays the stream itself, not the file.
 */
object NavidromeRadio {

    private const val TAG = "BitChordNavidrome"
    const val PAGE_ID = "local:group:radio:navidrome:stations"
    const val ID_PREFIX = "radio:"

    /** Marks a station's address for the cache; a fragment never goes to the server. */
    private const val MARK = "#bitchord-radio"

    fun isRadio(videoId: String?): Boolean = videoId?.startsWith(ID_PREFIX) == true

    /** Whether [url] is a station's stream (not cached). */
    fun isStream(url: String): Boolean = url.endsWith(MARK)

    private lateinit var logos: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        // Plain prefs: station logo addresses (or "none"), nothing secret.
        logos = context.getSharedPreferences("bitchord_radio_logos", Context.MODE_PRIVATE)
    }

    suspend fun pageState(browseId: String, emptyText: () -> String): UiState<List<Song>>? {
        if (browseId != PAGE_ID) return null
        return runCatching {
            val stations = NavidromeRepository.client().radioStations()
            coroutineScope {
                stations.map { station ->
                    async {
                        val stream = resolve(station.streamUrl)
                        Song(
                            videoId = ID_PREFIX + station.id,
                            title = station.name,
                            artist = station.homePageUrl?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
                                ?: "Internet radio",
                            thumbnailUrl = logo(station, stream),
                            localUri = stream + MARK,
                            playbackSource = "Internet radio",
                            playbackSourceType = PlaybackSourceType.BROWSE,
                            playbackSourceId = PAGE_ID,
                        )
                    }
                }.awaitAll()
            }
        }.fold(
            onSuccess = { songs -> if (songs.isEmpty()) UiState.Error(emptyText()) else UiState.Success(songs) },
            onFailure = { if (it is kotlin.coroutines.cancellation.CancellationException) throw it // left the page: not an error
                UiState.Error(it.message ?: emptyText()) },
        )
    }

    /**
     * A logo for [station]: the server's own image for it first ([serverLogo],
     * what psysonic shows and uploads), else radio-browser.info, the open
     * community station directory psysonic takes its logos from: by the stream
     * address first (exact), then by the name, most-voted. Kept per station for [LOGO_MS]
     * ("none" too), so the page doesn't ask again on every open. SVG logos are
     * skipped: the image loader here doesn't draw them.
     */
    private suspend fun logo(station: NavidromeClient.RadioRow, stream: String): String? = withContext(Dispatchers.IO) {
        val key = station.id + "|" + station.streamUrl
        if (::logos.isInitialized) {
            logos.getString(key, null)?.split('\n')?.let { parts ->
                val at = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                if (System.currentTimeMillis() - at < LOGO_MS) return@withContext parts[0].ifEmpty { null }
            }
        }
        val found = runCatching {
            serverLogo(station)
                ?: directory("byurl", "url" to stream)
                ?: directory("byurl", "url" to station.streamUrl)
                ?: directory("byname/" + station.name.substringBefore(" (").trim(), "order" to "votes", "reverse" to "true", "limit" to "10")
        }.onFailure { Log.d(TAG, "radio logo ${station.name}: ${it.message}") }
        // A failed lookup (no network) is not remembered; only a real answer is.
        val url = found.getOrElse { return@withContext null }
        if (::logos.isInitialized) logos.edit().putString(key, "${url.orEmpty()}\n${System.currentTimeMillis()}").apply()
        url
    }

    /**
     * The station's own image on the server (`getCoverArt` id `ra-<station id>`),
     * which psysonic uploads when a station is added from its directory, or
     * null when the server has none (an error, or not an image).
     */
    private suspend fun serverLogo(station: NavidromeClient.RadioRow): String? {
        val client = NavidromeRepository.client()
        val url = client.coverUrl("ra-" + station.id)
        val probe = client.coverUrl("ra-" + station.id, 64)
        Http.client.newCall(Request.Builder().url(probe).get().build()).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            return url.takeIf { response.isSuccessful && type.startsWith("image/") }
        }
    }

    /** The first usable `favicon` among radio-browser.info's stations at [path], or null. */
    private fun directory(path: String, vararg query: Pair<String, String>): String? {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host(DIRECTORY_HOST)
            .addPathSegment("json").addPathSegment("stations")
            .apply { path.split('/').forEach { addPathSegment(it) } }
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", "BitChord").get().build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return json.parseToJsonElement(response.body.string()).jsonArray
                .mapNotNull { (it as? JsonObject)?.get("favicon")?.let { f -> (f as? JsonPrimitive)?.contentOrNull } }
                .firstOrNull { it.startsWith("http") && !it.substringBefore('?').endsWith(".svg", ignoreCase = true) }
        }
    }

    private const val DIRECTORY_HOST = "de1.api.radio-browser.info"
    private const val LOGO_MS = 7L * 24 * 60 * 60 * 1000

    /** The stream a `.pls` / `.m3u` address names, else [url] itself. */
    private suspend fun resolve(url: String): String {
        val path = url.substringBefore('?').lowercase()
        if (!path.endsWith(".pls") && !path.endsWith(".m3u")) return url
        return withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    response.body.string().lineSequence()
                        .map { line -> line.trim().let { if (it.startsWith("http")) it else it.substringAfter('=') } } // pls: "File1=http://…"
                        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                }
            }.onFailure { Log.d(TAG, "radio playlist $url: ${it.message}") }.getOrNull() ?: url
        }
    }
}
