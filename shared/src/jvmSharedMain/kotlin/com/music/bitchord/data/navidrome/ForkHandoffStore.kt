package com.music.bitchord.data.navidrome

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException

/**
 * Fork: where [ForkHandoff]'s note is kept: the comment of one private, empty playlist of the
 * logged-in user on the Navidrome server ([ForkHandoff.PLAYLIST_NAME]), through Subsonic `/rest/…`
 * only. Every function is one attempt; the caller ([ForkHandoff.Saver], the offer check) decides
 * what happens after a failure. No error message carries the login: [NavidromeClient] reports the
 * server's own message or an HTTP code.
 *
 * The playlist's id is remembered for the run (and forgotten when the server or login changes, via
 * [forget]), so a save is one request once it is known; reading is one `getPlaylists`, whose
 * entries already carry the comment (a `getPlaylist` only when the listing leaves it out).
 */
object ForkHandoffStore {

    private val lock = Mutex()
    @Volatile private var playlistId: String? = null
    @Volatile private var forUser: String? = null

    /** Whether the last write had to go in the address ([NavidromeClient.setPlaylistComment]'s "get"): later notes then keep to [ForkHandoff.GET_BUDGET]. */
    @Volatile private var addressOnly = false

    fun forget() {
        playlistId = null
        forUser = null
        addressOnly = false
    }

    private fun identity() = NavidromeSettings.url.value + "\u0000" + NavidromeSettings.username.value

    /** The note on the server, or null when there is none (or it isn't one). Throws [IOException] when the server can't be read. */
    suspend fun read(): ForkHandoff.Note? {
        if (!NavidromeSettings.isConfigured()) return null
        val client = NavidromeRepository.client()
        val found = client.handoffPlaylists(ForkHandoff.PLAYLIST_NAME)
        // Two (a race between two first saves): the newest note wins, the others are left alone.
        val notes = found.map { entry ->
            val text = entry.comment ?: client.playlistComment(entry.row.id)
            entry.row.id to ForkHandoff.decode(text)
        }
        val best = notes.filter { it.second != null }.maxByOrNull { it.second!!.savedAt }
        (best?.first ?: found.firstOrNull()?.row?.id)?.let { remember(it) }
        return best?.second
    }

    /** Writes [note]; false when it doesn't fit even alone or the server refused it. */
    suspend fun write(note: ForkHandoff.Note, log: (String) -> Unit = {}): Boolean = lock.withLock {
        if (!NavidromeSettings.isConfigured()) return@withLock false
        val client = NavidromeRepository.client()
        val text = ForkHandoff.encode(note, if (addressOnly) ForkHandoff.GET_BUDGET else ForkHandoff.DEFAULT_BUDGET)
            ?: return@withLock false.also { log("handoff: the note doesn't fit even with the playing song alone") }
        val id = playlistId(client) ?: return@withLock false.also { log("handoff: no mailbox playlist") }
        val way = try {
            client.setPlaylistComment(id, text)
        } catch (e: IOException) {
            // A playlist deleted by hand since: found again (or made) on the next save, not now.
            playlistId = null
            throw e
        }
        if (way == "get" && !addressOnly) {
            addressOnly = true
            log("handoff: this server reads the comment from the address only; notes kept under ${ForkHandoff.GET_BUDGET / 1024} KB")
        }
        true
    }

    private fun remember(id: String) {
        playlistId = id
        forUser = identity()
    }

    /** The mailbox's id: remembered, else found, else made (one `createPlaylist`). */
    private suspend fun playlistId(client: NavidromeClient): String? {
        playlistId?.takeIf { forUser == identity() }?.let { return it }
        client.handoffPlaylists(ForkHandoff.PLAYLIST_NAME).firstOrNull()?.let { remember(it.row.id); return it.row.id }
        val made = client.createPlaylist(ForkHandoff.PLAYLIST_NAME)
            ?: client.handoffPlaylists(ForkHandoff.PLAYLIST_NAME).firstOrNull()?.row?.id // an older server answers without the playlist
        made?.let { remember(it) }
        return made
    }

    /**
     * How long a comment this server keeps, measured through the app's own client (the PC's
     * `/handoff-probe`): a separate playlist ([ForkHandoff.PROBE_PLAYLIST_NAME]) gets comments of
     * each size in [sizes] (bytes of UTF-8, ASCII with some accented and CJK letters so an encoding
     * fault shows), each read back and compared, and is deleted at the end whatever happened. One
     * write and one read per size, no retries; it stops at the first size that fails both ways,
     * since a bigger one won't pass. The answer is JSON for the bridge, with no login in it.
     */
    suspend fun probe(sizes: List<Int> = listOf(4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024)): String {
        if (!NavidromeSettings.isConfigured()) return """{"error":"Navidrome is not configured"}"""
        val client = NavidromeRepository.client()
        val results = mutableListOf<Triple<Int, String, String?>>() // size, outcome, transport
        var largest = 0
        var probeId: String? = null
        var error: String? = null
        try {
            probeId = client.handoffPlaylists(ForkHandoff.PROBE_PLAYLIST_NAME).firstOrNull()?.row?.id
                ?: client.createPlaylist(ForkHandoff.PROBE_PLAYLIST_NAME)
                ?: client.handoffPlaylists(ForkHandoff.PROBE_PLAYLIST_NAME).firstOrNull()?.row?.id
            val id = probeId ?: throw IOException("could not make the probe playlist")
            for (size in sizes) {
                val text = filler(size)
                val way = try {
                    client.setPlaylistComment(id, text, allowGet = true)
                } catch (e: IOException) {
                    results += Triple(size, "write failed: ${e.message}", null)
                    break
                }
                val back = try {
                    client.playlistComment(id)
                } catch (e: IOException) {
                    results += Triple(size, "read failed: ${e.message}", way)
                    break
                }
                val outcome = when {
                    back == text -> "intact".also { largest = size }
                    back == null -> "read back empty"
                    back.length < text.length && text.startsWith(back) -> "cut to ${back.toByteArray(Charsets.UTF_8).size} bytes"
                    else -> "changed (${back.toByteArray(Charsets.UTF_8).size} bytes back)"
                }
                results += Triple(size, outcome, way)
                if (outcome != "intact") break
            }
        } catch (e: IOException) {
            error = e.message
        } finally {
            probeId?.let { id -> runCatching { client.deletePlaylist(id) }.onFailure { error = (error?.let { "$it; " } ?: "") + "delete failed: ${it.message}" } }
        }
        return buildJsonObject {
            put("largestIntactBytes", largest)
            putJsonArray("sizes") {
                results.forEach { (size, outcome, way) ->
                    addJsonObject {
                        put("bytes", size)
                        put("result", outcome)
                        way?.let { put("transport", it) }
                    }
                }
            }
            error?.let { put("error", it) }
            put("probePlaylistDeleted", probeId == null || error?.contains("delete failed") != true)
        }.toString()
    }

    /** [size] bytes of UTF-8 text: a repeating line with ASCII, accented letters and CJK, padded with ASCII to the byte. */
    internal fun filler(size: Int): String {
        val unit = "BitChord handoff probe – é ü 日本 0123456789 {\"k\":[1,2]} & = + % ? / # ; "
        val out = StringBuilder()
        var bytes = 0
        val unitBytes = unit.toByteArray(Charsets.UTF_8).size
        while (bytes + unitBytes <= size) {
            out.append(unit)
            bytes += unitBytes
        }
        while (bytes < size) {
            out.append('x')
            bytes++
        }
        return out.toString()
    }
}
