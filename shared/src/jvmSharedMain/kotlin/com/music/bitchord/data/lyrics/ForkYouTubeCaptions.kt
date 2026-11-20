package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.Innertube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fork: YouTube's captions as lyrics the way the rulebook reads them (lyrics.md §11.2, Metrolist's
 * `YouTubeSubtitleLyricsProvider`): the player response's
 * `captions.playerCaptionsTracklistRenderer.captionTracks`, the track in the app's language first,
 * read with `fmt=json3`, at most [MAX_TRACKS] tracks looked at. Line-synced; each event's
 * `dDurationMs` is the line's end.
 *
 * Asked behind BitChord's own "YouTube captions" source (`get_transcript`), when that has nothing:
 * the two are the same captions by two routes, and the transcript route misses on many videos that
 * carry caption tracks.
 */
object ForkYouTubeCaptions {

    const val MAX_TRACKS = 8

    private val YOUTUBE_ID = Regex("""[A-Za-z0-9_-]{11}""")

    suspend fun lyrics(videoId: String, language: String = Locale.getDefault().language): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            if (!YOUTUBE_ID.matches(videoId)) return@withContext null
            val player = runCatching { Innertube.forkCaptionsPlayer(videoId) }.getOrNull() ?: return@withContext null
            for (url in trackUrls(player, language)) {
                val body = forkLyricsGetAs(json3Url(url), LYRICS_AGENT) ?: continue
                parseJson3(body)?.let { return@withContext it }
            }
            null
        }

    /**
     * The caption tracks' URLs in the order they are tried: the app's language (not
     * auto-generated), any not auto-generated, the app's language auto-generated, then the rest;
     * [MAX_TRACKS] at most.
     */
    fun trackUrls(player: JsonObject, language: String): List<String> {
        val tracks = (((player["captions"] as? JsonObject)
            ?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun JsonObject.asr() = text("kind").equals("asr", ignoreCase = true) || text("vssId").startsWith("a.")
        fun JsonObject.ours() = language.isNotBlank() && text("languageCode").startsWith(language, ignoreCase = true)
        return tracks
            .sortedBy { track ->
                when {
                    track.ours() && !track.asr() -> 0
                    !track.asr() -> 1
                    track.ours() -> 2
                    else -> 3
                }
            }
            .mapNotNull { it.text("baseUrl").takeIf(String::isNotBlank) }
            .take(MAX_TRACKS)
    }

    /**
     * The track's URL on www.youtube.com asking for `json3`, replacing a format it already names.
     * A relative `/api/timedtext` or one on music.youtube.com answers with the app's HTML page
     * (found in the extension's PC live run), so the host is always YouTube's own.
     */
    fun json3Url(baseUrl: String): String {
        val url = baseUrl.replace(Regex("""^(https?:)?(//[^/]*)?(?=/)"""), "https://www.youtube.com")
        return if (Regex("[?&]fmt=").containsMatchIn(url)) {
            url.replace(Regex("([?&])fmt=[^&]*"), "$1fmt=json3")
        } else {
            url + (if ('?' in url) "&" else "?") + "fmt=json3"
        }
    }

    /** A `json3` caption file as lines: `tStartMs`, `dDurationMs`, the `segs`' text; music notes removed. */
    fun parseJson3(body: String): List<LyricLine>? {
        val root = runCatching { lyricsJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val events = root["events"] as? JsonArray ?: return null
        return events.mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            val start = (event["tStartMs"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val duration = (event["dDurationMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val segs = event["segs"] as? JsonArray ?: return@mapNotNull null
            val text = segs.joinToString("") { seg ->
                ((seg as? JsonObject)?.get("utf8") as? JsonPrimitive)?.contentOrNull.orEmpty()
            }.replace(Regex("""[♪♫\s]+"""), " ").trim()
            text.takeIf { it.isNotEmpty() }?.let {
                LyricLine(start, it, sungUntilMs = (start + duration).takeIf { duration > 0 })
            }
        }.sortedBy { it.timeMs }.takeIf { it.isNotEmpty() }
    }
}

private val forkLyricsClient by lazy {
    Http.client.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
}

/** `lyricsGet` with a User-Agent of the caller's: SimpMusic asks for `SimpMusicLyrics/1.0` (lyrics.md §11.1). */
internal fun forkLyricsGetAs(url: String, userAgent: String): String? = runCatching {
    val request = Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
        .build()
    forkLyricsClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrNull()
