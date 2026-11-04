package com.music.bitchord.desktop

import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeOffline
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * Fork: "Pre-load next songs" on the PC, the phone's `ForkPreload`. The next N Navidrome songs in
 * the queue are read whole into a folder of their own while one plays; [ForkNavidrome.stream] (the
 * source resolve path, the next song's read-ahead deck included) then hands the engine the file, so
 * they start at once and keep playing through a dropped connection.
 *
 * As on the phone: it waits a few seconds into a track (the playing song's own stream first), and a
 * new queue position replaces the run; nothing goes out offline; kept files, radio stations and songs
 * already read are skipped; a pause or a stop cancels it. One `/rest/stream` per song through the
 * shared client (whose interceptor adds the login), no retries: a song that failed is tried again
 * only when the queue moves. The folder is held to the song cache's limit, oldest first, never the
 * songs wanted now.
 */
internal object ForkPreload {

    private const val DELAY_MS = 8_000L
    private const val BUFFER_BYTES = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dir: File by lazy { DesktopMediaCache.directory.resolve("navidrome_preload").toFile().also { it.mkdirs() } }

    private data class Input(val current: Song?, val upcoming: List<Song>, val playing: Boolean)

    @Volatile private var clears = 0

    private val input = MutableStateFlow(Input(null, emptyList(), false))

    /** What runs: the server's ids to read, and those to keep in the folder (the playing one too). */
    internal data class Plan(val read: List<String>, val keep: Set<String>)

    fun install() {
        scope.launch {
            // Leftovers of a run the app was closed in the middle of.
            dir.listFiles { file -> file.name.endsWith(".part") }?.forEach { it.delete() }
            combine(input, ForkNavidromeSettings.preloadSongs, ForkOffline.offline, NavidromeSettings.saved) { now, count, offline, _ ->
                if (!now.playing || offline || count <= 0 || !NavidromeSettings.isConfigured()) {
                    Plan(emptyList(), emptySet())
                } else {
                    plan(now.current, now.upcoming, count, ::eligible)
                }
            }.distinctUntilChanged().collectLatest { plan -> run(plan) }
        }
    }

    /** The queue as it is now; called by the app whenever it or the playing state changes. */
    fun around(current: Song?, upcoming: List<Song>, playing: Boolean) {
        input.value = Input(current, upcoming.take(MAX_LOOKAHEAD), playing)
    }

    /** The whole file read ahead for a server song's id (no prefix), or null. */
    fun file(id: String): File? {
        val file = File(dir, fileName(id))
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis()) // played: the newest, so trimming takes it last
        return file
    }

    /**
     * A server song read whole once into a temporary file, for Automix's analysis, which opens its
     * source six times (the full pass, then two head and three tail model windows): straight from
     * the server that was six `/rest/stream` requests per song (2026-11-04 audit). Null when the
     * song is already a file (kept or pre-loaded: the analyser reads that), offline, or on any
     * failure (the analysis then streams as before). The caller deletes the file.
     */
    fun analysisCopy(song: Song): File? {
        if (!NavidromeSettings.isNavidromeId(song.videoId) || NavidromeRadio.isRadio(song.videoId)) return null
        if (ForkOffline.offline.value || !NavidromeSettings.isConfigured()) return null
        val id = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        if (NavidromeOffline.localFile(id) != null || file(id) != null) return null
        val target = File.createTempFile("bitchord-analysis-", ".audio")
        return runCatching { kotlinx.coroutines.runBlocking { download(id, target, counted = false) }; target }
            .onFailure { target.delete() }
            .getOrNull()
            ?.takeIf { it.isFile && it.length() > 0 }
    }

    /** Whether a server song's id (no prefix) is read ahead here, for the song menu's "Remove from cache". */
    fun holds(id: String): Boolean = File(dir, fileName(id)).isFile

    /** That one song's file thrown away; the bytes freed. */
    fun remove(id: String): Long {
        val file = File(dir, fileName(id))
        val size = file.length()
        return if (file.isFile && file.delete()) size else 0L
    }

    /** Thrown away with "Clear song cache". Returns the bytes freed. */
    fun clear(): Long {
        clears++ // a read finishing after this is not kept (see download)
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
        return files.sumOf { file -> file.length().takeIf { file.delete() } ?: 0L }
    }

    /**
     * The next [count] songs of [upcoming] that are the server's, by id. Read: those of them not
     * already kept or read ([eligible]); keep: all of them and [current].
     */
    internal fun plan(current: Song?, upcoming: List<Song>, count: Int, eligible: (Song) -> Boolean): Plan {
        val next = upcoming.take(count).filter { NavidromeSettings.isNavidromeId(it.videoId) && !NavidromeRadio.isRadio(it.videoId) }
        val ids = next.map { it.videoId.removePrefix(NavidromeSettings.ID_PREFIX) }
        val keep = ids.toSet() + listOfNotNull(current?.videoId?.takeIf(NavidromeSettings::isNavidromeId)?.removePrefix(NavidromeSettings.ID_PREFIX))
        return Plan(next.filter(eligible).map { it.videoId.removePrefix(NavidromeSettings.ID_PREFIX) }, keep)
    }

    /** Not a kept ("Available offline") song: that file plays already. */
    private fun eligible(song: Song): Boolean =
        NavidromeOffline.localFile(song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)) == null

    private suspend fun run(plan: Plan) {
        if (plan.read.isEmpty()) return
        delay(DELAY_MS)
        for (id in plan.read) {
            val target = File(dir, fileName(id))
            if (target.isFile) continue
            try {
                download(id, target)
                DesktopTrackLog.log("navidrome: pre-loaded $id (${target.length() / 1024} KB)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DesktopTrackLog.log("navidrome: pre-load $id: ${e.message}")
            }
            trim(plan.keep)
        }
    }

    /** `/rest/stream` into [target] through a `.part` file, cancellable mid-read. */
    private suspend fun download(id: String, target: File, counted: Boolean = true) {
        val epoch = clears
        val url = NavidromeRepository.client().streamUrl(id)
        val part = File(target.path + ".part")
        val call = Http.client.newCall(Request.Builder().url(url).get().build())
        // A cancel (the queue moved, a pause) aborts a read blocked on a stalled connection.
        val watcher = CoroutineScope(currentCoroutineContext()).launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                // Subsonic answers its errors with 200 and a JSON or XML body: never keep that as a song.
                if (response.body.contentType()?.let { it.type == "text" || "json" in it.subtype || "xml" in it.subtype } == true) {
                    throw IOException("server error instead of a file")
                }
                response.body.byteStream().use { stream ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = stream.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!part.renameTo(target)) throw IOException("could not keep ${target.name}")
            // "Clear song cache" ran while this was read: it goes with the rest.
            if (counted && epoch != clears) target.delete()
        } finally {
            watcher.cancel()
            part.delete()
        }
    }

    /** Oldest first until the folder fits the song cache's limit, sparing [keep]. */
    private fun trim(keep: Set<String>) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
        val doomed = evictions(
            files.map { Entry(it.name, it.length(), it.lastModified()) },
            keep.mapTo(HashSet(), ::fileName),
            // One budget with the song cache, which doesn't count this folder (it lists its own files only).
            (DesktopMediaCache.limitBytes() - DesktopMediaCache.sizeBytes()).coerceAtLeast(0L),
        )
        files.filter { it.name in doomed }.forEach { it.delete() }
    }

    internal data class Entry(val name: String, val bytes: Long, val usedAt: Long)

    /** The names to delete so [files] fit in [cap] bytes: the least recently used, never one in [keep]. */
    internal fun evictions(files: List<Entry>, keep: Set<String>, cap: Long): Set<String> {
        var total = files.sumOf { it.bytes }
        val out = HashSet<String>()
        for (entry in files.filter { it.name !in keep }.sortedBy { it.usedAt }) {
            if (total <= cap) break
            out += entry.name
            total -= entry.bytes
        }
        return out
    }

    /** A server id as a file name: ids are the server's own, so anything odd in one is replaced. */
    internal fun fileName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** More than the largest count offered, so a change of the setting needs no new queue. */
    private const val MAX_LOOKAHEAD = 8
}
