package com.music.bitchord.data.navidrome

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * A small Subsonic / OpenSubsonic client for one Navidrome server (fork-only).
 *
 * Every request carries token auth (`t` = md5(password + salt), `s` = salt)
 * rather than the password. The library is read with the saved salt
 * ([NavidromeSettings.salt]), so the stream and cover URLs it hands out stay
 * the same across app starts — the image and audio caches are keyed by URL —
 * and valid for as long as the password is; Subsonic tokens don't expire.
 */
class NavidromeClient(
    baseUrl: String,
    private val username: String,
    password: String,
    private val salt: String = NavidromeSettings.newSalt(),
) {
    private val base: HttpUrl = NavidromeSettings.normalizeUrl(baseUrl).toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Not a server address")
    private val token: String = md5(password + salt)

    /** One page of the library's songs, from `search3` with an empty query (Navidrome lists everything). */
    data class SongRow(
        val id: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val albumId: String?,
        val coverArt: String?,
        val track: Int?,
        val disc: Int?,
        val year: Int?,
        val durationSec: Int?,
        val suffix: String?,
        val bitDepth: Int?,
        val samplingRate: Int?,
        /** Starred on the server (Subsonic sends the date it was starred, or nothing). */
        val starred: Boolean = false,
        /** The file's size in bytes, for "Available offline" to say what a page will take. */
        val sizeBytes: Long? = null,
        /** The `comment` tag (OpenSubsonic): yt-dlp's files end it with their YouTube address, see [NavidromeYouTubeCover]. */
        val comment: String? = null,
        /** The server's id for [artist] (Subsonic `artistId`): on a newer Navidrome its first credited artist's. */
        val artistId: String? = null,
        /** Every credited artist as (id, name) (OpenSubsonic `artists`), so an artist page can find its own id. */
        val artists: List<Pair<String, String>> = emptyList(),
        /** The server's ReplayGain in dB (OpenSubsonic `replayGain`, track else album), see [NavidromeReplayGain]. */
        val replayGainDb: Double? = null,
    )

    suspend fun ping() {
        call("ping")
    }

    suspend fun songs(offset: Int, count: Int): List<SongRow> {
        val result = call(
            "search3",
            "query" to "",
            "songCount" to count.toString(),
            "songOffset" to offset.toString(),
            "artistCount" to "0",
            "albumCount" to "0",
        )
        return songRows(result["searchResult3"]?.jsonObject?.get("song"))
    }

    /** The ids of every starred song. */
    suspend fun starredSongIds(): List<String> =
        songRows(call("getStarred2")["starred2"]?.jsonObject?.get("song")).map { it.id }

    /** Songs the server's agents (Last.fm and the like) call similar; empty when it has none configured. */
    suspend fun similarSongs(id: String, count: Int): List<SongRow> {
        val result = call("getSimilarSongs2", "id" to id, "count" to count.toString())
        return songRows(result["similarSongs2"]?.jsonObject?.get("song"))
    }

    /** One artist as the server lists it: [coverArt] is its picture's id for [coverUrl] (`ar-…`). */
    data class ArtistRow(val id: String, val name: String, val coverArt: String?)

    /** Artists whose name matches [query] (`search3`, artists only), for an artist page that has only the name. */
    suspend fun searchArtists(query: String, count: Int = 20): List<ArtistRow> {
        val result = call(
            "search3",
            "query" to query,
            "artistCount" to count.toString(),
            "albumCount" to "0",
            "songCount" to "0",
        )
        return parseArtists(result["searchResult3"]?.jsonObject?.get("artist"))
    }

    /**
     * The artists `getArtistInfo2` calls similar to [id]. Without `includeNotPresent` the server
     * keeps to the artists in this library, which are the only ones a page can be opened for.
     */
    suspend fun similarArtists(id: String): List<ArtistRow> = parseSimilarArtists(call("getArtistInfo2", "id" to id))

    /** Every artist of the library (`getArtists`, one request), for [NavidromeArtistPictures]' pass over them. */
    suspend fun artists(): List<ArtistRow> = parseArtistIndex(call("getArtists"))

    /**
     * The md5 of the picture [coverArt] at [size] px, or null when the server answers with a
     * Subsonic error document instead of an image ("Artwork not found": it has none).
     *
     * For [NavidromeArtistPictures], which needs to know whether an artist's `ar-` picture is a real
     * one or Navidrome's generic placeholder. No field of the Subsonic answers says so: `getArtists`,
     * `search3` and `getArtistInfo2` hand every artist the same kind of `coverArt` id and `/share/img`
     * image links, picture or not. The placeholder is the same file for every artist that has none,
     * so at one size its bytes are identical across artists, and a real photograph's never are. A
     * tiny size keeps this to a few kilobytes per artist, asked once per artist and kept.
     * Throws [IOException] on an HTTP failure (not "no picture": nothing may be remembered then).
     */
    suspend fun coverHash(coverArt: String, size: Int): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint("getCoverArt", "id" to coverArt, "size" to size.toString())).get().build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty().lowercase(java.util.Locale.ROOT)
            if ("json" in type || "xml" in type) return@use null
            val source = response.body.source()
            // A picture this small is a few kilobytes; anything past the cap is not one.
            if (source.request(COVER_HASH_LIMIT + 1L)) throw IOException("cover larger than $COVER_HASH_LIMIT bytes")
            val bytes = source.buffer.readByteArray()
            if (isErrorDocument(bytes)) return@use null
            MessageDigest.getInstance("MD5").digest(bytes).toHex()
        }
    }

    suspend fun randomSongs(count: Int): List<SongRow> {
        val result = call("getRandomSongs", "size" to count.toString())
        return songRows(result["randomSongs"]?.jsonObject?.get("song"))
    }

    /** One album card: [name] is the album tag the library's own album pages group by. */
    data class AlbumRow(val id: String, val name: String, val artist: String?, val coverArt: String?)

    /** Star (the app's heart) or unstar a song. */
    suspend fun setStarred(id: String, starred: Boolean) {
        call(if (starred) "star" else "unstar", "id" to id)
    }

    /** One playlist on the server, for a Home card. */
    data class PlaylistRow(val id: String, val name: String, val songCount: Int?, val coverArt: String?)

    /** An internet radio station the server lists (`getInternetRadioStations`). */
    data class RadioRow(val id: String, val name: String, val streamUrl: String, val homePageUrl: String?)

    suspend fun radioStations(): List<RadioRow> {
        val result = call("getInternetRadioStations")
        return asList(result["internetRadioStations"]?.jsonObject?.get("internetRadioStation")).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            RadioRow(
                id = o.string("id") ?: return@mapNotNull null,
                name = o.string("name") ?: return@mapNotNull null,
                streamUrl = o.string("streamUrl") ?: return@mapNotNull null,
                homePageUrl = o.string("homePageUrl"),
            )
        }
    }

    /**
     * The playlists to show. [ForkHandoff]'s mailbox playlists are left out here, the one call every
     * playlist list of both apps reads (Home and Library rows, the Navidrome page, "Available
     * offline"), so none of them can show it; [handoffPlaylists] is the only way to see them.
     */
    suspend fun playlists(): List<PlaylistRow> = allPlaylists().filterNot { ForkHandoff.isHiddenPlaylist(it.name) }.map { it.row }

    /** A playlist as `getPlaylists` lists it, with what [ForkHandoff] needs: its owner and comment. */
    data class PlaylistEntry(val row: PlaylistRow, val owner: String?, val comment: String?, val public: Boolean?) {
        val name: String get() = row.name
    }

    private suspend fun allPlaylists(): List<PlaylistEntry> {
        val result = call("getPlaylists")
        return asList(result["playlists"]?.jsonObject?.get("playlist")).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            PlaylistEntry(
                row = PlaylistRow(
                    id = o.string("id") ?: return@mapNotNull null,
                    name = o.string("name") ?: return@mapNotNull null,
                    songCount = o.int("songCount"),
                    coverArt = o.string("coverArt"),
                ),
                owner = o.string("owner"),
                comment = (o["comment"] as? JsonPrimitive)?.contentOrNull,
                public = (o["public"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }

    // ── fork: ForkHandoff's mailbox (Subsonic /rest only) ──

    /** This user's own playlists named [name] (others' public ones of that name are not ours). */
    suspend fun handoffPlaylists(name: String): List<PlaylistEntry> =
        allPlaylists().filter { it.name == name && (it.owner == null || it.owner == username) }

    /** `createPlaylist` with no songs; the new playlist's id, or null when the server's answer names none. */
    suspend fun createPlaylist(name: String): String? {
        val result = call("createPlaylist", "name" to name)
        return (result["playlist"] as? JsonObject)?.string("id")
    }

    /**
     * `getPlaylist`'s comment, exactly as kept. Read raw (not [string], which blanks a blank one),
     * so a probe can compare it byte for byte.
     */
    suspend fun playlistComment(id: String): String? =
        ((call("getPlaylist", "id" to id)["playlist"] as? JsonObject)?.get("comment") as? JsonPrimitive)?.contentOrNull

    /**
     * Sets a playlist's comment (and keeps it private). A long comment can't ride in an address, so
     * it goes as a form body ([post]: OpenSubsonic's `formPost`, the login staying in the address);
     * a server that doesn't read the body answers with an error, and then the comment is sent in
     * the address once, when that is short enough ([GET_LIMIT]). Says which way worked.
     *
     * Only the server's refusal ([Refused]) leads to the address: a network failure of the POST is
     * thrown as it is, so one dropped connection doesn't make a caller think the server reads the
     * address only.
     */
    suspend fun setPlaylistComment(id: String, comment: String, allowGet: Boolean = true): String {
        val refused = try {
            postPlaylistComment(id, comment)
            return "post"
        } catch (e: Refused) {
            e
        }
        if (!allowGet || !getPlaylistComment(id, comment)) throw refused
        return "get"
    }

    /** `updatePlaylist` with the comment in a form body. Throws [Refused] when the server answered no, a plain [IOException] when it couldn't be reached. */
    suspend fun postPlaylistComment(id: String, comment: String) {
        post("updatePlaylist", "playlistId" to id, "comment" to comment, "public" to "false")
    }

    /** `updatePlaylist` with the comment in the address; false (nothing sent) when the address would pass [GET_LIMIT]. */
    suspend fun getPlaylistComment(id: String, comment: String): Boolean {
        val params = arrayOf("playlistId" to id, "comment" to comment, "public" to "false")
        if (endpoint("updatePlaylist", *params).toString().length > GET_LIMIT) return false
        call("updatePlaylist", *params)
        return true
    }

    /** The server answered and said no (a 4xx, a Subsonic error, not a Subsonic answer), as opposed to a request that never got an answer or a 5xx. */
    class Refused(message: String) : IOException(message)

    suspend fun deletePlaylist(id: String) {
        call("deletePlaylist", "id" to id)
    }

    /** [call] with [params] in an `application/x-www-form-urlencoded` body instead of the address. */
    private suspend fun post(method: String, vararg params: Pair<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val form = okhttp3.FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder().url(endpoint(method)).post(form).build()
            Http.client.newCall(request).execute().use { response ->
                // A 5xx (a proxy whose server is restarting, an overloaded server) says nothing about
                // the body, so it is a plain failure; a 4xx (405, 413 Payload Too Large) is a refusal.
                if (!response.isSuccessful) throw if (response.code >= 500) IOException("HTTP ${response.code}") else Refused("HTTP ${response.code}")
                val body = response.body.string() // a read failure here stays a plain IOException
                val root = runCatching { json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject }.getOrNull()
                    ?: throw Refused("Not a Subsonic server")
                if (root.string("status") != "ok") throw Refused(root["error"]?.jsonObject?.string("message") ?: "Server refused the request")
                root
            }
        }

    /** A playlist's songs, in its own order. */
    suspend fun playlistSongs(id: String): List<SongRow> {
        val result = call("getPlaylist", "id" to id)
        return songRows(result["playlist"]?.jsonObject?.get("entry"))
    }

    /** `getAlbumList2` of [type] (`newest`, `recent`, `frequent`, `random`, …). */
    suspend fun albums(type: String, size: Int, offset: Int = 0): List<AlbumRow> {
        val result = call("getAlbumList2", "type" to type, "size" to size.toString(), "offset" to offset.toString())
        return asList(result["albumList2"]?.jsonObject?.get("album")).mapNotNull { it.toAlbumRow() }
    }

    /** Songs matching [query] (`search3`, songs only), one page of [count] from [offset]. */
    suspend fun searchSongs(query: String, count: Int, offset: Int = 0): List<SongRow> {
        val result = call(
            "search3",
            "query" to query,
            "songCount" to count.toString(),
            "songOffset" to offset.toString(),
            "albumCount" to "0",
            "artistCount" to "0",
        )
        return songRows(result["searchResult3"]?.jsonObject?.get("song"))
    }

    /** One genre and how many songs it has. */
    data class GenreRow(val name: String, val songCount: Int)

    suspend fun genres(): List<GenreRow> {
        val result = call("getGenres")
        return asList(result["genres"]?.jsonObject?.get("genre")).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            GenreRow(name = o.string("value") ?: return@mapNotNull null, songCount = o.int("songCount") ?: 0)
        }
    }

    /** A genre's songs, one page of [count] from [offset]. */
    suspend fun songsByGenre(genre: String, count: Int, offset: Int = 0): List<SongRow> {
        val result = call("getSongsByGenre", "genre" to genre, "count" to count.toString(), "offset" to offset.toString())
        return songRows(result["songsByGenre"]?.jsonObject?.get("song"))
    }

    /** Albums of a genre (`getAlbumList2` byGenre), for a genre tile's cover. */
    suspend fun albumsByGenre(genre: String, size: Int): List<AlbumRow> {
        val result = call("getAlbumList2", "type" to "byGenre", "genre" to genre, "size" to size.toString())
        return asList(result["albumList2"]?.jsonObject?.get("album")).mapNotNull { it.toAlbumRow() }
    }

    /** Albums whose name matches [query] (`search3`, albums only). */
    suspend fun searchAlbums(query: String, count: Int = 20): List<AlbumRow> {
        val result = call(
            "search3",
            "query" to query,
            "albumCount" to count.toString(),
            "songCount" to "0",
            "artistCount" to "0",
        )
        return asList(result["searchResult3"]?.jsonObject?.get("album")).mapNotNull { it.toAlbumRow() }
    }

    /** One album: its card and its songs in the server's order (disc, then track). */
    suspend fun album(id: String): Pair<AlbumRow?, List<SongRow>> {
        val album = call("getAlbum", "id" to id)["album"]
        return album?.toAlbumRow() to songRows((album as? JsonObject)?.get("song"))
    }

    /** A song's details for Song Info: plays, year, rating, genre. */
    data class SongDetails(
        val playCount: Int?,
        val year: Int?,
        val releaseDate: String?,
        val genre: String?,
        val userRating: Int?,
        val starred: Boolean,
        val bitRate: Int?,
        val suffix: String?,
    )

    /** One song by id (`getSong`), or null when the server has none. */
    suspend fun song(id: String): SongRow? = call("getSong", "id" to id)["song"]?.toSongRow()

    suspend fun songDetails(id: String): SongDetails {
        val o = call("getSong", "id" to id)["song"]?.jsonObject ?: throw IOException("No such song")
        // OpenSubsonic: {"year":2014,"month":11,"day":3}; any part may be missing.
        val date = (o["originalReleaseDate"] as? JsonObject)?.takeIf { it.int("year") != null }
            ?: (o["releaseDate"] as? JsonObject)?.takeIf { it.int("year") != null }
        val releaseDate = date?.let { d ->
            listOfNotNull(d.int("year"), d.int("month"), d.int("day"))
                .mapIndexed { i, part -> if (i == 0) part.toString() else String.format(java.util.Locale.ROOT, "%02d", part) }
                .joinToString("-")
        }
        return SongDetails(
            playCount = o.int("playCount"),
            year = o.int("year"),
            releaseDate = releaseDate,
            genre = o.string("genre"),
            userRating = o.int("userRating"),
            starred = o.string("starred") != null,
            bitRate = o.int("bitRate"),
            suffix = o.string("suffix"),
        )
    }

    private fun JsonElement.toAlbumRow(): AlbumRow? {
        val o = this as? JsonObject ?: return null
        return AlbumRow(
            id = o.string("id") ?: return null,
            name = o.string("name") ?: return null,
            artist = o.string("artist"),
            coverArt = o.string("coverArt"),
        )
    }

    private fun songRows(element: JsonElement?): List<SongRow> = asList(element).mapNotNull { it.toSongRow() }

    /** Subsonic JSON writes a one-item list as the bare object; null or missing is empty. */
    private fun asList(element: JsonElement?): List<JsonElement> = when (element) {
        null -> emptyList()
        is JsonArray -> element
        else -> listOf(element)
    }

    /** One `structuredLyrics` entry: a language's lines, timed or not ([offsetMs] already in the server's sign). */
    data class LyricsRow(val synced: Boolean, val offsetMs: Long, val lines: List<Pair<Long?, String>>)

    /**
     * The song's lyrics as the server read them from the file (tags or a
     * sidecar `.lrc`), via the OpenSubsonic `songLyrics` extension. A server
     * without the extension answers with an error, which surfaces as an
     * [IOException] like any other.
     */
    suspend fun lyricsBySongId(id: String): List<LyricsRow> =
        parseLyrics(call("getLyricsBySongId", "id" to id))

    /**
     * Subsonic `scrobble`: with [submission] false the song shows as playing
     * now (Navidrome's "Now playing" list); true records a finished play
     * started at [timeMs], which is what counts it and fills "recently played".
     */
    suspend fun scrobble(id: String, submission: Boolean, timeMs: Long? = null) {
        call(
            "scrobble",
            *listOfNotNull(
                "id" to id,
                "submission" to submission.toString(),
                timeMs?.let { "time" to it.toString() },
            ).toTypedArray(),
        )
    }

    /** The original file, untouched (`format=raw`): FLAC stays FLAC, Opus stays Opus. */
    fun streamUrl(id: String): String = endpoint("stream", "id" to id, "format" to "raw", login = false).toString()

    /**
     * [url] with this login, when it is this server's address with none; else as it is. A song's
     * addresses carry no login, because a song goes everywhere: the media session any app can read,
     * the player's cache index, logs. The HTTP layer adds it per request ([NavidromeClips]); only
     * what opens an address some other way (FFmpeg, a Cast receiver) calls this itself.
     */
    fun withLogin(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.host != base.host || parsed.port != base.port) return url
        if ("/rest/" !in parsed.encodedPath || "/rest/x-clip/" in parsed.encodedPath || parsed.queryParameter("t") != null) return url
        return parsed.newBuilder()
            .addQueryParameter("u", username)
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            .build()
            .toString()
    }

    /** A cover by its Subsonic id: a song's own (`mf-…`) or an album's (`al-…`). */
    fun coverUrl(coverArt: String, size: Int = 600): String =
        endpoint("getCoverArt", "id" to coverArt, "size" to size.toString(), login = false).toString()

    private fun endpoint(method: String, vararg params: Pair<String, String>, login: Boolean = true): HttpUrl =
        base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(method)
            .apply {
                if (login) {
                    addQueryParameter("u", username)
                    addQueryParameter("t", token)
                    addQueryParameter("s", salt)
                }
            }
            // Subsonic's required version parameter, written as `%76` (= "v"). The player treats any
            // URI with a `v` query parameter as a YouTube watch link (`bitchord://watch?v=…`), so a
            // literal `v=1.16.1` made every stream URL "play" YouTube video "1.16.1". Navidrome
            // decodes the key and still reads it as `v`; Android's Uri.getQueryParameter("v") doesn't.
            .addEncodedQueryParameter("%76", "1.16.1")
            .addQueryParameter("c", "BitChord")
            .addQueryParameter("f", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    /** The `subsonic-response` object, or an [IOException] carrying the server's own error message. */
    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint(method, *params)).get().build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body.string()
                val root = json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject
                    ?: throw IOException("Not a Subsonic server")
                if (root.string("status") != "ok") {
                    val error = root["error"]?.jsonObject
                    throw IOException(error?.string("message") ?: "Server refused the request")
                }
                root
            }
        }

    private fun JsonElement.toSongRow(): SongRow? {
        val o = this as? JsonObject ?: return null
        val id = o.string("id") ?: return null
        return SongRow(
            id = id,
            title = o.string("title") ?: return null,
            artist = o.string("artist"),
            album = o.string("album"),
            albumId = o.string("albumId"),
            coverArt = o.string("coverArt"),
            track = o.int("track"),
            disc = o.int("discNumber"),
            year = o.int("year"),
            durationSec = o.int("duration"),
            suffix = o.string("suffix"),
            bitDepth = o.int("bitDepth"),
            samplingRate = o.int("samplingRate"),
            starred = o.string("starred") != null,
            sizeBytes = (o["size"] as? JsonPrimitive)?.longOrNull,
            comment = o.string("comment"),
            artistId = o.string("artistId"),
            artists = parseArtists(o["artists"]).map { it.id to it.name },
            replayGainDb = NavidromeReplayGain.parse(o["replayGain"]).also { NavidromeReplayGain.remember(id, it) },
        )
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `getArtistInfo2`'s answer (the `subsonic-response` object): its `similarArtist` list, in the server's order. */
        fun parseSimilarArtists(result: JsonObject): List<ArtistRow> =
            parseArtists((result["artistInfo2"] as? JsonObject)?.get("similarArtist"))

        /** `getArtists`' answer (the `subsonic-response` object): every artist of every index letter, in the server's order. */
        fun parseArtistIndex(result: JsonObject): List<ArtistRow> {
            val index = (result["artists"] as? JsonObject)?.get("index") ?: return emptyList()
            return (index as? JsonArray ?: JsonArray(listOf(index))).flatMap { letter -> parseArtists((letter as? JsonObject)?.get("artist")) }
        }

        /** The longest address [setPlaylistComment] falls back to: what a proxy in front of the server commonly allows (nginx's 8 KB line, with room for headers). */
        const val GET_LIMIT = 7_000

        /** Largest picture [coverHash] reads: its size is a few kilobytes. */
        const val COVER_HASH_LIMIT = 2 * 1024 * 1024

        /**
         * Whether [bytes] are a Subsonic error document rather than a picture, for a server that sends
         * no content type: JSON (`{…`) or XML naming `subsonic-response`. Empty counts as no picture.
         */
        fun isErrorDocument(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return true
            val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1).trimStart()
            return head.startsWith("{") || "subsonic-response" in head
        }

        /** A list of artist objects (or the bare one Subsonic JSON writes for one): each with an id and a name. */
        fun parseArtists(element: JsonElement?): List<ArtistRow> = when (element) {
            null -> emptyList()
            is JsonArray -> element
            else -> listOf(element)
        }.mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            fun field(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ArtistRow(id = field("id") ?: return@mapNotNull null, name = field("name") ?: return@mapNotNull null, coverArt = field("coverArt"))
        }

        /** `getLyricsBySongId`'s answer (the `subsonic-response` object); apart from [call] so it can be tested. */
        fun parseLyrics(result: JsonObject): List<LyricsRow> {
            val list = result["lyricsList"]?.jsonObject?.get("structuredLyrics") ?: return emptyList()
            return (list as? JsonArray ?: JsonArray(listOf(list))).mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val lines = when (val raw = o["line"]) {
                    is JsonArray -> raw
                    is JsonObject -> JsonArray(listOf(raw))
                    else -> JsonArray(emptyList())
                }.mapNotNull { line ->
                    val l = line as? JsonObject ?: return@mapNotNull null
                    (l["start"] as? JsonPrimitive)?.longOrNull to
                        (l["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
                LyricsRow(
                    synced = (o["synced"] as? JsonPrimitive)?.booleanOrNull == true,
                    offsetMs = (o["offset"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    lines = lines,
                )
            }
        }

        fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()

        fun ByteArray.toHex(): String = joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }
}
