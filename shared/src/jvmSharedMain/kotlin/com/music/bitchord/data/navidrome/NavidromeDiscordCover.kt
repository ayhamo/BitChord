package com.music.bitchord.data.navidrome

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.splitArtists
import com.music.bitchord.data.canvas.titlesMatch
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: the cover Discord is given for what is playing, on the phone (`DiscordRPC`) and the PC
 * (`DesktopDiscordRpc`) alike.
 *
 * Discord never loads a presence's picture from the listener's device: its own servers fetch the
 * URL (the gateway mirrors it onto Discord's CDN, the desktop client's media proxy does the same
 * for the local socket). A Navidrome song's own cover is `…/rest/getCoverArt?id=…` on the owner's
 * server, which sits on a LAN or Tailscale address Discord can't reach, and whose login (`u`, `t`,
 * `s`, the HTTP layer adds them) must never leave the app. Upstream sent any `http…` cover, so a
 * server song showed the app icon at best and named the server to Discord at worst; the phone then
 * dropped `getCoverArt` covers, the PC did not even do that.
 *
 * So two jobs:
 * 1. [isPublic], the guard every cover passes before either app hands it to Discord, for every
 *    song (a WebDAV / SMB / module cover can be a LAN address with a login just as well).
 * 2. For a server song whose own cover is private, a public picture of the same song, looked up
 *    once and kept ([coverFor]): first the YouTube video its file came from (the owner's files are
 *    yt-dlp's, whose `comment` tag ends with the video's address; its thumbnail is the picture
 *    yt-dlp embedded, see [NavidromeYouTubeCover]), then the iTunes Search API by title and artist
 *    (the same Apple catalogue the canvas and artist art already ask, no key, no login). A song
 *    with no file art already carries its public YouTube thumbnail and needs no lookup.
 *
 * The lookup never holds a presence back for long: the caller waits at most [WAIT_MS] (the
 * presence then goes without a cover, the BitChord icon, as before), while the lookup itself runs
 * on here for up to [LOOKUP_MS] and keeps its answer for the next push of that song. A definite
 * "nothing found" is kept too; a failed request (offline, iTunes' rate limit) is not, so the song
 * is asked about again later instead of being stuck without a cover until a restart.
 */
object NavidromeDiscordCover {

    /** How long a presence push waits for a lookup it doesn't have yet. */
    const val WAIT_MS = 3_000L

    /** How long a lookup may take in all before it gives up (not remembered then). */
    const val LOOKUP_MS = 10_000L

    /** Songs remembered, oldest dropped first: a day's listening is well under this. */
    private const val KEPT = 500

    /** A public cover for a server song, null for none; throws when it couldn't tell (network). */
    fun interface Source {
        suspend fun find(song: Song, px: Int): String?
    }

    /** What [now] can say without waiting. */
    sealed interface Answer {
        /** Send this ([url] null: no cover, Discord shows the app's icon). */
        data class Ready(val url: String?) : Answer

        /** A server song not looked up yet: [coverFor] would wait for it. */
        data object Pending : Answer
    }

    /** In order; the first public answer wins. Tests swap them. */
    @Volatile
    internal var sources: List<Source> = listOf(
        Source { song, px -> fromServerComment(song, px) },
        Source { song, px -> fromItunes(song, px) },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Per song and size: the cover found, or "" for none. Access order, so the oldest goes first. */
    private val kept = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > KEPT
    }

    /** Lookups under way, so a second push of the same song (quality line, resume) waits on the first. */
    private val pending = ConcurrentHashMap<String, Deferred<String?>>()

    /**
     * The cover to send for [song] at [px] right now, or [Answer.Pending] when it is a server song
     * whose public cover hasn't been looked up yet. Never waits and never asks the network.
     */
    fun now(song: Song, px: Int): Answer {
        val own = song.artworkAt(px)
        // A WebDAV or SMB song's own cover is never sent, even when it passes [isPublic]: it is the
        // file's address on the owner's share (a Nextcloud host, the user name and the folders
        // in its path), with the login in a header where the guard can't see it. Such a song gets
        // the public lookup as a server song does.
        val privateLibrary = isPrivateLibraryId(song.videoId)
        if (!privateLibrary && (!NavidromeSettings.isNavidromeId(song.videoId) || isPublic(own))) {
            return Answer.Ready(own?.takeIf { isPublic(it) })
        }
        val found = synchronized(kept) { kept[key(song, px)] } ?: return Answer.Pending
        return Answer.Ready(found.ifEmpty { null })
    }

    /**
     * The cover to send for [song]: its own when Discord may have it, else (a server song) the
     * public one found for it, waiting at most [waitMs] for a lookup; null for none. Cancelling
     * the caller (the next track's push) leaves the lookup running for the next time.
     */
    suspend fun coverFor(song: Song, px: Int, waitMs: Long = WAIT_MS): String? {
        when (val answer = now(song, px)) {
            is Answer.Ready -> return answer.url
            Answer.Pending -> Unit
        }
        val key = key(song, px)
        val lookup = pending.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) { lookUp(song, px, key) }
                .also { started -> started.invokeOnCompletion { pending.remove(key, started) } }
        }
        lookup.start()
        return withTimeoutOrNull(waitMs) { lookup.await() }
    }

    /** Runs [sources] in order and keeps a definite answer; a failure or a timeout keeps nothing. */
    private suspend fun lookUp(song: Song, px: Int, key: String): String? {
        var failed = false
        var finished = false
        val found = withTimeoutOrNull(LOOKUP_MS) {
            var hit: String? = null
            for (source in sources) {
                val url = try {
                    source.find(song, px)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    Log.d(TAG, "Discord cover: ${song.title}: ${e.message}")
                    null
                }
                // A source's answer passes the same guard as any cover: one that named a private
                // address would otherwise be kept and sent.
                if (isPublic(url)) {
                    hit = url
                    break
                }
            }
            finished = true
            hit
        }
        if (found != null || (finished && !failed)) synchronized(kept) { kept[key] = found.orEmpty() }
        return found
    }

    private fun key(song: Song, px: Int) = "${song.videoId}@$px"

    /** Ids of songs read off the owner's own shares (the app's `WebDavConfig` / `SmbConfig` prefixes). */
    internal fun isPrivateLibraryId(id: String): Boolean = id.startsWith("webdav:") || id.startsWith("smb:")

    // ---- The guard ---------------------------------------------------------------------------

    /** Query names that carry a login on a Subsonic server (or any other), never sent anywhere. */
    private val LOGIN_PARAMS = setOf(
        "u", "t", "s", "p", "apikey", "api_key", "token", "access_token", "jwt", "password", "auth",
        "key", "sig", "signature", "x-plex-token", "x-emby-token", "x-mediabrowser-token", "session", "sid",
    )

    /**
     * The "Listen on YouTube Music" button's address. A YouTube id opens its own watch page, as
     * upstream does. Any other id (`navidrome:`, `webdav:`, `smb:`, a source's `key:id`) has no
     * YouTube page: upstream put it into `watch?v=` anyway, which is a dead link that also shows
     * the server's song id to whoever opens the card. Those open a YouTube Music search for the
     * song's title and artist instead. A YouTube id never holds a colon.
     */
    fun listenUrl(song: Song): String =
        if (':' !in song.videoId) {
            "https://music.youtube.com/watch?v=${song.videoId}"
        } else {
            val query = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")
            "https://music.youtube.com/search?q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8)
        }

    /**
     * Whether [url] may be handed to Discord: a plain http(s) address on a public host name, at the
     * scheme's own port, with no login in it. Refused: anything on the Navidrome server's host or
     * under a `/rest/` (Subsonic) path on any host; a `getCoverArt` address however written; a
     * user:password part; a login-like query parameter ([LOGIN_PARAMS], `%75` decoded as `u` and
     * in any case); an IP address of any kind (a public one is most likely the owner's own line);
     * a LAN or Tailscale name ([NavidromeRadio.isPublicStream]: `.local`, `.lan`, `.ts.net`, a
     * single label…); a port of its own (a home server's, not a CDN's). Names are not resolved:
     * that would ask a DNS server about every cover, and a public name is as public as its owner
     * made it.
     */
    fun isPublic(url: String?, serverHost: String? = currentServerHost()): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) return false
        if ("getcoverart" in url.lowercase() || parsed.pathSegments.any { "getcoverart" in it.lowercase() }) return false
        if (parsed.pathSegments.any { it.equals("rest", ignoreCase = true) }) return false
        if (parsed.queryParameterNames.any { it.lowercase() in LOGIN_PARAMS }) return false
        val host = parsed.host.lowercase().trimEnd('.')
        if (':' in host || host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() != null } }) return false
        return NavidromeRadio.isPublicStream(parsed.toString(), serverHost)
    }

    /** The configured server's host name, also when it was typed without a scheme. */
    private fun currentServerHost(): String? = runCatching {
        val typed = NavidromeSettings.url.value.trim()
        if (typed.isEmpty()) return null
        (typed.toHttpUrlOrNull() ?: "http://$typed".toHttpUrlOrNull())?.host
    }.getOrNull()

    // ---- Sources -----------------------------------------------------------------------------

    /**
     * The song's YouTube video, from the `comment` yt-dlp writes (asked of the server, which is
     * the app's own and already trusted with everything): its thumbnail is the picture the file
     * has. `hqdefault` because every video has one.
     */
    private suspend fun fromServerComment(song: Song, @Suppress("UNUSED_PARAMETER") px: Int): String? {
        if (!NavidromeSettings.isNavidromeId(song.videoId) || !NavidromeSettings.isConfigured()) return null
        val row = NavidromeRepository.client().song(song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)) ?: return null
        return NavidromeYouTubeCover.youtubeId(row.comment)?.let { "https://${NavidromeYouTubeCover.HOST}/vi/$it/hqdefault.jpg" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The iTunes Search API's songs for the title and lead artist; picked by [pickItunes]. */
    private suspend fun fromItunes(song: Song, px: Int): String? = withContext(Dispatchers.IO) {
        val lead = song.artist.split(',', '&').first().trim()
        val url = HttpUrl.Builder().scheme("https").host(ITUNES_HOST).addPathSegment("search")
            .addQueryParameter("term", "${song.title} $lead".trim())
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "15")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("iTunes search: http ${response.code}")
            response.body.string()
        }
        pickItunes(body, song, px)
    }

    /**
     * The cover of the first iTunes song that is [song]: the same title (folded, with the
     * bracketed or dashed version note dropped on both sides: "(Remastered)", "- Single") and an
     * artist in common, preferring one whose length is within 10 s of the song's. Apple's
     * `artworkUrl100` is asked at [px] (its CDN serves any `NxNbb` size). Null for no match.
     */
    internal fun pickItunes(body: String, song: Song, px: Int): String? {
        val rows = runCatching { json.parseToJsonElement(body).jsonObject["results"]?.jsonArray }.getOrNull() ?: return null
        val wantTitle = baseTitle(song.title)
        val wantArtists = artistKeys(song.artist)
        val wantSeconds = seconds(song.durationText)
        val matches = rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val title = o["trackName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artist = o["artistName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val art = o["artworkUrl100"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!titlesMatch(baseTitle(title), wantTitle)) return@mapNotNull null
            if (artistKeys(artist).none { it in wantArtists }) return@mapNotNull null
            val ms = o["trackTimeMillis"]?.jsonPrimitive?.longOrNull
            art to ms
        }
        val chosen = matches.firstOrNull { (_, ms) -> wantSeconds != null && ms != null && kotlin.math.abs(ms / 1000 - wantSeconds) <= 10 }
            ?: matches.firstOrNull()
            ?: return null
        return ITUNES_SIZE.replace(chosen.first, "/${px}x${px}bb.jpg")
    }

    private fun baseTitle(title: String): String =
        title.substringBefore(" (").substringBefore(" [").substringBefore(" - ").trim().ifEmpty { title }

    /** Each credited artist, folded; a name the Latin folding empties (CJK, Arabic) is kept whole, lowercased. */
    private fun artistKeys(raw: String): Set<String> =
        splitArtists(raw).toSet().ifEmpty { setOfNotNull(raw.trim().lowercase().ifEmpty { null }) }

    private fun seconds(text: String?): Long? = text?.split(':')?.mapNotNull { it.toLongOrNull() }
        ?.takeIf { it.isNotEmpty() }?.fold(0L) { total, part -> total * 60 + part }

    /** Clears what is kept, for tests. */
    internal fun forget() {
        synchronized(kept) { kept.clear() }
        pending.clear()
    }

    private const val TAG = "BitChordNavidrome"
    private const val ITUNES_HOST = "itunes.apple.com"
    private val ITUNES_SIZE = Regex("""/\d+x\d+bb\.(?:jpg|png|webp)$""")
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"
}
