package com.music.bitchord.data.navidrome

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Navidrome albums and playlists kept on the device (fork-only), as psysonic's
 * "make offline": a whole page at a time, never single tracks.
 *
 * Everything a song needs with no server goes to the folder [init] is given,
 * outside every cache, so nothing evicts it:
 * - `<song id>`: the original file (`stream?format=raw`, FLAC stays FLAC);
 * - `covers/<cover id>`: the covers, at the player's size;
 * - `clips/<hash>.mp4`: the song's backdrop and cover clip, with `clips.json`
 *   naming them (a clip's address takes a server lookup to find);
 * - `lyrics/<song id>.lrc`: the timed lyrics, the server's or, without them,
 *   the online sources' (as a download embeds them).
 * A file is shared between the pages that hold it and deleted only when the
 * last one lets it go. [manifest] (`offline.json`) lists the pages and their
 * songs, so they open and play with no server at all.
 *
 * Where the app builds a Navidrome [Song] ([NavidromeRepository.toSong]) a song
 * with a file here plays from it ([localFile]), online or not. Covers and clips
 * keep their server addresses and are answered from here by [serve], in the
 * HTTP client the image loader and the clip player use, so nothing else needs
 * to know they are kept.
 *
 * Downloads run two files at a time in the background and wait for a network
 * downloads may use ([NavidromePlatform.awaitDownload]); the phone shows them
 * under a notification ([NavidromePlatform.onKeepStarted]).
 */
object NavidromeOffline {

    private const val TAG = "BitChordNavidrome"
    private const val PARALLEL = 2
    private const val COVER_PX = 1200

    /** Tries per song file before the page goes on without it; waits [RETRY_MS] times the attempt between them. */
    private const val SONG_ATTEMPTS = 3
    private const val RETRY_MS = 5_000L

    /** One page kept offline: its header and its songs in the page's order. */
    /** One page kept offline: its header and its songs in the page's order; [gone]: a playlist deleted on the server, still kept here. */
    data class Page(val id: String, val title: String, val subtitle: String, val cover: String?, val songs: List<Song>, val gone: Boolean = false)

    /** What the downloads are doing, for the notification. */
    data class Status(val titles: List<String>, val done: Int, val total: Int) {
        val active get() = titles.isNotEmpty()
    }

    private lateinit var dir: File
    private val coverDir get() = File(dir, "covers")
    private val clipDir get() = File(dir, "clips")
    private val lyricsDir get() = File(dir, "lyrics")
    private var rowTitle = "Available offline"
    private var goneLabel = "Deleted on server"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val slots = Semaphore(PARALLEL)

    /** One writer per file: two pages sharing a song wait for each other instead of writing one `.part` twice. */
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    /** Serialises the manifest and clip-list writes (`offline.json.tmp`, `clips.json.tmp`). */
    private val ioLock = Any()

    /** Song ids with a kept file, so building a song asks memory, not the disk. */
    private val onDiskIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Pages being kept right now; their files are not swept, and nothing they need is deleted. */
    private val active = ConcurrentHashMap<String, Page>()

    /** File sizes the server reported, by song id, so a page's size is known before it is kept. */
    private val sizes = ConcurrentHashMap<String, Long>()

    /** Kept clips' addresses, by "<song id>|<kind>". */
    private val clips = ConcurrentHashMap<String, String>()

    private val _pages = MutableStateFlow<Map<String, Page>>(emptyMap())
    val pages: StateFlow<Map<String, Page>> = _pages.asStateFlow()

    /** Download progress (0..1) of the pages being made offline. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Songs done and to do, and the pages' titles, across every page downloading. */
    private val counts = ConcurrentHashMap<String, Triple<String, Int, Int>>()
    private val _status = MutableStateFlow(Status(emptyList(), 0, 0))
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * The manifest was there and couldn't be read: nothing says which files are kept, so nothing
     * is swept this run. Without this one bad write deleted every kept song at the next keep or remove.
     */
    @Volatile private var manifestUnreadable = false

    fun init(directory: File, rowTitle: String, goneLabel: String) {
        dir = directory.apply { mkdirs() }
        coverDir.mkdirs()
        clipDir.mkdirs()
        lyricsDir.mkdirs()
        this.rowTitle = rowTitle
        this.goneLabel = goneLabel
        // Half-written files of a process that died mid-download: nothing is downloading yet.
        listOf(dir, coverDir, clipDir, lyricsDir).forEach { d -> d.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
        // Song files are the only top-level files without a dot (offline.json, clips.json, *.part have one).
        dir.listFiles()?.filter { it.isFile && '.' !in it.name }?.mapTo(onDiskIds) { it.name }
        manifestUnreadable = unreadableManifest.isFile
        _pages.value = readManifest()
        readClips()
        publishRow()
    }

    private val manifest get() = File(dir, "offline.json")
    private val unreadableManifest get() = File(dir, "offline.json.bad")
    private val clipManifest get() = File(dir, "clips.json")

    /** Whether [browseId] is a page this can keep: a Navidrome album or playlist. */
    fun canKeep(browseId: String?): Boolean =
        browseId != null && (browseId.startsWith(NavidromeAlbums.PREFIX) || browseId.startsWith(NavidromePlaylists.PREFIX))

    /** The kept file for a Navidrome song id, or null. */
    fun localFile(songId: String): File? =
        if (::dir.isInitialized && songId in onDiskIds) File(dir, songId) else null

    /**
     * Where a Navidrome song plays from when the address it carried is no good: its kept file,
     * else a stream from the server. A queue saved while offline remembers `file://` addresses,
     * and once the page is removed the player would otherwise take the id for a YouTube one and
     * fail on every song ("URL not accepted"). Null for any other song.
     */
    fun uriFor(videoId: String): String? {
        if (!NavidromeSettings.isNavidromeId(videoId)) return null
        val id = videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        return localFile(id)?.takeIf { it.exists() }?.let(::fileUri)
            ?: runCatching { NavidromeRepository.client().streamUrl(id) }.getOrNull()
    }

    /** The kept lyrics for a Navidrome song id, or null. */
    fun lyricsFile(songId: String): File? =
        if (::dir.isInitialized) File(lyricsDir, "$songId.lrc").takeIf { it.isFile } else null

    /** The song id of a kept file's `file://` address, or null. */
    fun songIdForFile(uri: String): String? {
        if (!::dir.isInitialized || !uri.startsWith("file:")) return null
        val file = runCatching { File(java.net.URI(uri)) }.getOrNull() ?: return null
        return file.name.takeIf { file.parentFile == dir }
    }

    /** A kept file's address, `file:///…`, which is how the phone has always written it. */
    private fun fileUri(file: File): String = file.toURI().toString().replaceFirst("file:/", "file:///")

    /** [NavidromePlatform.Kept], for the app to plug in. */
    object Files : NavidromePlatform.Kept {
        override fun localUri(songId: String): String? = localFile(songId)?.let(::fileUri)
        override fun lyricsFile(songId: String): File? = NavidromeOffline.lyricsFile(songId)
        override fun songIdForFile(uri: String): String? = NavidromeOffline.songIdForFile(uri)
        override fun clipUrl(songId: String, backdrop: Boolean): String? =
            clipUrl(songId, if (backdrop) NavidromeClips.Kind.BACKDROP else NavidromeClips.Kind.COVER)
        override fun keptSongs(): List<Song> = NavidromeOffline.keptSongs()
        override fun noteSize(songId: String, bytes: Long) = NavidromeOffline.noteSize(songId, bytes)
        override fun serve(request: Request): Response? = NavidromeOffline.serve(request)
        override suspend fun pageState(browseId: String): UiState<List<Song>>? = NavidromeOffline.pageState(browseId)
    }

    /** A kept clip's address for the song, or null; see [NavidromeClips]. */
    fun clipUrl(songId: String, kind: NavidromeClips.Kind): String? = clips["$songId|${kind.name}"]

    fun noteSize(songId: String, bytes: Long) {
        sizes[songId] = bytes
    }

    /** What keeping [songs] adds to the phone (their audio not kept yet), or null when the server didn't say. */
    fun estimate(songs: List<Song>): Long? {
        val ids = songs.map { it.videoId.removePrefix(NavidromeSettings.ID_PREFIX) }.filter { localFile(it) == null }
        if (ids.any { !sizes.containsKey(it) }) return null
        return ids.sumOf { sizes.getValue(it) }
    }

    /** What a kept page takes on the phone: its songs' files, lyrics and clips (covers are shared and small). Disk reads: call off the main thread. */
    fun pageBytes(id: String): Long = _pages.value[id]?.songs.orEmpty().sumOf { song ->
        val songId = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        (localFile(songId)?.length() ?: 0L) + (lyricsFile(songId)?.length() ?: 0L) +
            NavidromeClips.Kind.entries.sumOf { kind -> clipUrl(songId, kind)?.let { clipFile(it).length() } ?: 0L }
    }

    /** Every kept song once that has its file, for offline mode's library listing. */
    fun keptSongs(): List<Song> =
        _pages.value.values.flatMap { it.songs }.distinctBy { it.videoId }.filter(::hasFile).map(::onDisk)

    /** A kept page's songs that have their files (the server can't be reached), or null when [browseId] isn't kept. */
    fun pageState(browseId: String): UiState<List<Song>>? =
        _pages.value[browseId]?.songs?.filter(::hasFile)?.map(::onDisk)?.let { UiState.Success(it) }

    /** How many of a kept page's songs have no file yet (they failed to download); 0 when it is whole or not kept. */
    fun missing(id: String): Int = _pages.value[id]?.songs.orEmpty().count { !hasFile(it) }

    private fun hasFile(song: Song) = localFile(song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)) != null

    /**
     * Downloads everything the page's songs need that isn't on the phone yet,
     * then records the page, also when some songs failed ([missing]; keeping it
     * again fetches them). A page that fails or that the user cancels is not
     * recorded, and what it alone had fetched is swept away ([sweep]); one
     * stopped by Android ([cancelAll] with `keepDone`, the service's time
     * budget) is recorded with what it has, so nothing downloaded is lost.
     */
    fun keep(id: String, title: String, subtitle: String, cover: String?, songs: List<Song>) {
        if (jobs[id]?.isActive == true || songs.isEmpty()) return
        val page = Page(id, title, subtitle, cover, songs)
        active[id] = page
        setProgress(id, 0f)
        counts[id] = Triple(title, 0, songs.size)
        publishStatus()
        NavidromePlatform.onKeepStarted()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext.job
            var kept = false
            try {
                val client = NavidromeRepository.client()
                cover?.let {
                    NavidromePlatform.awaitDownload()
                    bestEffort("cover") { keepCover(client, it) }
                }
                coroutineScope {
                    songs.map { song ->
                        async {
                            slots.withPermit { keepSong(client, song) }
                            counts.computeIfPresent(id) { _, (t, done, total) -> Triple(t, done + 1, total) }
                            counts[id]?.let { (_, done, total) -> setProgress(id, done.toFloat() / total) }
                            publishStatus()
                        }
                    }.awaitAll()
                }
                // Nothing at all came down (the server or the network is gone): there is no page to keep.
                if (songs.none(::hasFile)) throw IOException("no song could be downloaded")
                // Removed (or replaced) while its last song finished: don't bring it back.
                if (jobs[id] !== me) throw CancellationException("offline $id was removed")
                val before = _pages.value[id]
                _pages.update { it + (id to page) }
                try {
                    writeManifest()
                } catch (e: IOException) {
                    // A full disk: a page only in memory is gone next launch and its files with it.
                    _pages.update { if (before != null) it + (id to before) else it - id }
                    throw e
                }
                writeClips()
                sweep() // a kept playlist kept again with fewer songs: the dropped ones go
                publishRow()
                kept = true
                NavidromeRepository.invalidateListing() // its songs now play from the files
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "offline $id: ${e.message}")
            } finally {
                // Stopped by Android, not by the user: kept as far as it got. Recorded while the
                // page is still [active], so no other page's sweep can take its files meanwhile.
                val stoppedByAndroid = keepOnStop.remove(id) // whatever else: a finished page must not leave its id for a later Cancel
                val keepPartial = !kept && stoppedByAndroid && jobs[id] === me && songs.any(::hasFile) // nothing down yet: no page
                if (keepPartial) {
                    _pages.update { it + (id to page) }
                    // A full disk here must not skip the clean-up below: the notification would stay up for good.
                    runCatching {
                        writeManifest()
                        writeClips()
                    }.onFailure { Log.d(TAG, "offline $id: ${it.message}") }
                    publishRow()
                    NavidromeRepository.invalidateListing()
                }
                // Only this job's own entries: a cancel and a new keep of the same page may have replaced them.
                if (jobs.remove(id, me)) {
                    active.remove(id)
                    counts.remove(id)
                    setProgress(id, null)
                }
                publishStatus()
                if (!kept && !keepPartial) withContext(NonCancellable) { sweep() }
            }
        }
        jobs[id] = job
        job.start()
    }

    /**
     * One song: its file, then its cover, clips and lyrics (those best-effort: a song without them is still kept).
     *
     * A song file that fails (the network dropped mid-file, a timeout, the song gone from the
     * server) is tried again a few times once a network the settings allow is back, then skipped:
     * one song never throws the rest of the page away. A skipped song still streams online, and
     * keeping the page again fetches it.
     */
    private suspend fun keepSong(client: NavidromeClient, song: Song) {
        val songId = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        if (!safeName(songId)) return
        NavidromePlatform.awaitDownload() // covers and clips too, not only the audio, keep to the mobile-data switch
        var attempt = 0
        while (localFile(songId) == null) {
            try {
                download(client.streamUrl(songId), File(dir, songId))
                onDiskIds += songId
            } catch (e: IOException) {
                if (++attempt >= SONG_ATTEMPTS) {
                    Log.d(TAG, "offline song $songId skipped: ${e.message}")
                    break
                }
                delay(RETRY_MS * attempt)
                NavidromePlatform.awaitDownload()
            }
        }
        song.thumbnailUrl?.let { bestEffort("cover") { keepCover(client, it) } }
        for (kind in NavidromeClips.Kind.entries) {
            val key = "$songId|${kind.name}"
            bestEffort("clip $key") {
                val url = clips[key] ?: NavidromeClips.find(songId, kind)
                if (url != null) {
                    // Recorded before the download, so a page finishing meanwhile doesn't sweep the new file.
                    val had = clips.putIfAbsent(key, url)
                    try {
                        download(url, clipFile(url))
                    } catch (e: Exception) {
                        if (had == null) clips.remove(key, url)
                        throw e
                    }
                }
            }
        }
        if (lyricsFile(songId) == null) {
            bestEffort("lyrics $songId") {
                (NavidromeLyrics.serverLrc(songId) ?: NavidromePlatform.onlineLyrics(song))?.let { File(lyricsDir, "$songId.lrc").writeText(it) }
            }
        }
    }

    /** Runs [block], logging anything but a cancellation instead of failing the page. */
    private inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "offline $what: ${e.message}")
        }
    }

    private suspend fun keepCover(client: NavidromeClient, url: String) {
        val coverId = coverIdOf(url)?.takeIf(::safeName) ?: return
        download(client.coverUrl(coverId, COVER_PX), coverFile(coverId))
    }

    /**
     * Kept playlists follow the server (called when Home loads, online).
     *
     * A playlist can change on the server after it was kept: songs added,
     * removed or moved (a smart playlist changes by itself), renamed, or
     * deleted. Changed, it is kept again as it is now ([keep] fetches what is
     * new, the [sweep] after it drops what left). Deleted, it stays on the
     * phone marked [Page.gone] ("Deleted on server") until it is removed here;
     * the songs are still the user's to play. Albums aren't checked: an album
     * changing on the server is a retag, not something to follow.
     */
    fun syncPlaylists() {
        if (!::dir.isInitialized || NavidromePlatform.offline.value) return
        val kept = _pages.value.values.filter { it.id.startsWith(NavidromePlaylists.PREFIX) }
        if (kept.isEmpty()) return
        scope.launch {
            try {
                val client = NavidromeRepository.client()
                // The user's playlists as the server lists them; a failure (no server) changes nothing.
                val onServer = client.playlists().associateBy { it.id }
                for (page in kept) {
                    if (jobs[page.id]?.isActive == true) continue
                    val row = onServer[page.id.removePrefix(NavidromePlaylists.PREFIX)]
                    if (row == null) {
                        if (!page.gone) {
                            Log.d(TAG, "offline ${page.title}: deleted on the server, kept here")
                            _pages.update { pages -> pages[page.id]?.let { pages + (page.id to it.copy(gone = true)) } ?: pages }
                            writeManifest()
                            publishRow()
                        }
                        continue
                    }
                    val songs = with(NavidromeRepository) { client.playlistSongs(row.id).map { it.toSong(client) } }
                    val same = !page.gone && row.name == page.title && songs.map { it.videoId } == page.songs.map { it.videoId }
                    if (page.id !in _pages.value) continue // removed while this asked the server
                    // Much shorter than what's kept reads as a server mid-rescan, not as an edit: the
                    // sweep after a keep would delete files only a new download can bring back.
                    if (!same && songs.isNotEmpty() && songs.size * 2 >= page.songs.size) {
                        Log.d(TAG, "offline ${page.title}: changed on the server, keeping it again")
                        keep(page.id, row.name, page.subtitle, page.cover, songs)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "offline playlists sync: ${e.message}")
            }
        }
    }

    /** Pages stopped with [cancelAll]`(keepDone = true)`: recorded as far as they got, not swept. */
    private val keepOnStop: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Stops every download. The notification's Cancel: what no kept page uses is
     * swept. [keepDone] (Android ending the service's time budget): each page is
     * kept with what it has, and keeping it again fetches the rest.
     */
    fun cancelAll(keepDone: Boolean = false) {
        if (keepDone) keepOnStop += jobs.keys
        jobs.values.forEach { it.cancel() }
    }

    /**
     * Stops a download. A page already kept (a playlist re-syncing itself) stays as it was;
     * one being kept for the first time goes, as [remove].
     */
    fun stop(id: String) {
        if (id in _pages.value) jobs[id]?.cancel() else remove(id)
    }

    /**
     * Lets a page go, kept or still downloading. Its files are deleted unless
     * another kept page, or one still downloading, holds them. Off the main
     * thread: it can be thousands of files.
     */
    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        active.remove(id)
        counts.remove(id)
        setProgress(id, null)
        publishStatus()
        scope.launch {
            if (id in _pages.value) {
                _pages.update { it - id }
                writeManifest()
                publishRow()
                NavidromeRepository.invalidateListing()
            }
            sweep()
        }
    }

    /** Lets every page go. */
    fun removeAll() {
        _pages.value.keys.toList().forEach(::remove)
    }

    /**
     * Deletes every kept file (song, lyrics, clip, cover) that no kept page and
     * no page still downloading uses: what a cancelled or failed keep, or a
     * removed page, leaves behind. `.part` files belong to downloads in flight
     * and are left to them.
     */
    private fun sweep() = synchronized(ioLock) {
        if (!::dir.isInitialized || manifestUnreadable) return@synchronized
        val pages = _pages.value.values + active.values
        val songIds = pages.flatMap { it.songs }.mapTo(HashSet()) { it.videoId.removePrefix(NavidromeSettings.ID_PREFIX) }
        val coverIds = pages.flatMap { p -> p.songs.map { it.thumbnailUrl } + p.cover }.mapNotNullTo(HashSet()) { url -> url?.let(::coverIdOf)?.let { coverFile(it).name } }
        dir.listFiles()?.filter { it.isFile && '.' !in it.name && it.name !in songIds }?.forEach {
            it.delete()
            onDiskIds -= it.name
        }
        lyricsDir.listFiles()?.filter { it.name.removeSuffix(".lrc") !in songIds && !it.name.endsWith(".part") }?.forEach { it.delete() }
        clips.keys.filter { it.substringBefore('|') !in songIds }.forEach { clips.remove(it) }
        val clipFiles = clips.values.mapTo(HashSet()) { clipFile(it).name }
        clipDir.listFiles()?.filter { it.name.endsWith(".mp4") && it.name !in clipFiles }?.forEach { it.delete() }
        coverDir.listFiles()?.filter { !it.name.endsWith(".part") && it.name !in coverIds }?.forEach { it.delete() }
        writeClipsLocked()
        _swept.value++
    }

    /** Bumped when [sweep] has deleted what it could, so a row measuring what's on the phone measures again. */
    private val _swept = MutableStateFlow(0)
    val swept: StateFlow<Int> = _swept.asStateFlow()

    /** The kept pages as the Library's "Available offline" row, shown with or without the server. */
    private fun publishRow() {
        NavidromePlatform.onKeptRow(shelf(rowTitle))
    }

    /** Bytes everything kept takes. Disk reads: call off the main thread. */
    fun sizeBytes(): Long = if (::dir.isInitialized) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** The kept pages as a Library row (title supplied by the caller). */
    fun shelf(title: String): HomeShelf = HomeShelf(
        title,
        _pages.value.values.sortedBy { it.title.lowercase() }.map { page ->
            ShelfItem(title = page.title, subtitle = if (page.gone) goneLabel else page.subtitle, thumbnailUrl = page.cover, videoId = null, browseId = page.id)
        },
        "Navidrome",
    )

    private fun setProgress(id: String, value: Float?) {
        _progress.update { if (value == null) it - id else it + (id to value) }
    }

    private fun publishStatus() {
        val all = counts.values.toList()
        _status.value = Status(all.map { it.first }, all.sumOf { it.second }, all.sumOf { it.third })
    }

    /**
     * [url] into [target] through `<target>.part`, unless [target] is already
     * there. One writer per file ([fileLocks]); cancellable between chunks, and
     * the `.part` is deleted whatever happens, so nothing half-written is left
     * or ever renamed into place.
     */
    private suspend fun download(url: String, target: File) {
        fileLocks.getOrPut(target.path) { Mutex() }.withLock {
            if (target.isFile) return
            val part = File(target.path + ".part")
            val call = Http.client.newCall(Request.Builder().url(url).get().build())
            // A cancel (the notification's Cancel, the service timing out) aborts a read that is
            // blocked on a stalled connection, instead of waiting out the read timeout.
            val watcher = CoroutineScope(currentCoroutineContext()).launch {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    // Subsonic answers its errors (bad login, song gone) with 200 and a JSON or XML body, and a
                    // login page in front of the server (a proxy's, a captive portal's) with 200 and HTML:
                    // never keep that as a song or a cover. A kept file is never fetched again.
                    if (response.body.contentType()?.let { it.type == "text" || "json" in it.subtype || "xml" in it.subtype } == true) {
                        throw IOException("server error instead of a file")
                    }
                    response.body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!part.renameTo(target)) throw IOException("could not keep ${target.name}")
            } finally {
                watcher.cancel()
                part.delete()
            }
        }
    }

    // ---- Kept covers and clips, answered in the HTTP client ----

    /**
     * A kept cover or clip for [request], or null to let it go to the server.
     *
     * Covers by the `id` of any `getCoverArt` request (whatever size is asked,
     * the kept one is the player's); clips by their exact address. Wired into
     * [Http.client] and the image loader's client (see `NavidromeClips.init`).
     */
    fun serve(request: Request): Response? {
        if (!::dir.isInitialized) return null
        val url = request.url
        val base = NavidromeSettings.url.value.toHttpUrlOrNull() ?: return null
        if (url.host != base.host || url.port != base.port) return null
        val (file, type) = when {
            url.encodedPath.endsWith("/rest/getCoverArt") || url.encodedPath.endsWith("/rest/getCoverArt.view") ->
                (url.queryParameter("id")?.takeIf(::safeName)?.let(::coverFile) ?: return null) to "image/jpeg"
            "/rest/x-clip/" in url.encodedPath -> clipFile(url.toString()) to "video/mp4"
            else -> return null
        }
        if (!file.isFile) return null
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Length", file.length().toString())
            .body(file.source().buffer().asResponseBody(type.toMediaType(), file.length()))
            .build()
    }

    private fun coverIdOf(url: String): String? =
        url.toHttpUrlOrNull()?.takeIf { "getCoverArt" in it.encodedPath }?.queryParameter("id")

    /** Ids come from the server and name files here: none of them may climb out of this folder. */
    private fun safeName(id: String) = '/' !in id && '\\' !in id && ".." !in id

    /**
     * An album's cover id ends in `:<number>`, which Windows takes for a stream of a file with no
     * data: nothing can be kept under that name. The phone's files keep the names they have.
     */
    private fun coverFile(id: String) = File(coverDir, if (File.separatorChar == '\\') id.replace(':', '_') else id)

    /** Over a file already there, which [File.renameTo] refuses on Windows: the manifests stopped updating after their first write. */
    private fun File.moveOver(target: File) {
        if (!renameTo(target)) {
            java.nio.file.Files.move(toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun clipFile(url: String): File {
        // By path: the login rides in a header, so the address names the clip alone.
        val path = url.toHttpUrlOrNull()?.encodedPath ?: url
        val hash = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
        return File(clipDir, "$hash.mp4")
    }

    // ---- Manifests ----

    /** [song] playing from its kept file, when there is one. */
    private fun onDisk(song: Song): Song =
        localFile(song.videoId.removePrefix(NavidromeSettings.ID_PREFIX))
            ?.let { song.copy(localUri = fileUri(it)) } ?: song

    private fun readClips() = runCatching {
        if (!clipManifest.isFile) return@runCatching
        json.parseToJsonElement(clipManifest.readText()).jsonObject.forEach { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { clips[key] = it }
        }
    }.onFailure { Log.d(TAG, "offline clips: ${it.message}") }

    private fun writeClips() = synchronized(ioLock) { writeClipsLocked() }

    /** [writeClips] for a caller already holding [ioLock] (a `synchronized` block is re-entrant, this just reads plainer). */
    private fun writeClipsLocked() {
        val obj = buildJsonObject { clips.forEach { (key, url) -> put(key, url) } }
        val tmp = File(dir, "clips.json.tmp")
        tmp.writeText(obj.toString())
        tmp.moveOver(clipManifest)
    }

    private fun readManifest(): Map<String, Page> = runCatching {
        if (!manifest.isFile) return emptyMap()
        val client = runCatching { NavidromeRepository.client() }.getOrNull() // one for every song, not one each
        json.parseToJsonElement(manifest.readText()).jsonArray.mapNotNull { element ->
            val o = element.jsonObject
            val id = o.str("id") ?: return@mapNotNull null
            Page(
                id = id,
                title = o.str("title").orEmpty(),
                subtitle = o.str("subtitle").orEmpty(),
                cover = NavidromeRepository.live(o.str("cover")),
                gone = (o["gone"] as? JsonPrimitive)?.booleanOrNull ?: false,
                songs = (o["songs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toSong(client) },
            )
        }.associateBy { it.id }
    }.getOrElse {
        Log.d(TAG, "offline manifest: ${it.message}")
        // Set aside, not overwritten by the next keep: with a new manifest that reads fine, the
        // next sweep would delete everything the bad one listed. Nothing is swept while it's here.
        manifest.renameTo(unreadableManifest)
        manifestUnreadable = true
        emptyMap()
    }

    /** One writer at a time, reading the pages inside the lock, so two pages finishing together can't interleave the file. */
    private fun writeManifest() = synchronized(ioLock) {
        val array = buildJsonArray {
            _pages.value.values.forEach { page ->
                add(
                    buildJsonObject {
                        put("id", page.id)
                        put("title", page.title)
                        put("subtitle", page.subtitle)
                        NavidromeRepository.atRest(page.cover)?.let { put("cover", it) }
                        if (page.gone) put("gone", true)
                        put("songs", buildJsonArray { page.songs.forEach { add(it.toJson()) } })
                    },
                )
            }
        }
        val tmp = File(dir, "offline.json.tmp")
        // On disk before the rename: a power cut in between could otherwise leave an empty manifest.
        java.io.FileOutputStream(tmp).use { out ->
            out.write(array.toString().toByteArray())
            out.fd.sync()
        }
        tmp.moveOver(manifest)
    }

    private fun Song.toJson() = buildJsonObject {
        put("videoId", videoId)
        put("title", title)
        put("artist", artist)
        artistId?.let { put("artistId", it) }
        albumId?.let { put("albumId", it) }
        albumName?.let { put("albumName", it) }
        NavidromeRepository.atRest(thumbnailUrl)?.let { put("thumbnailUrl", it) }
        durationText?.let { put("durationText", it) }
        trackNumber?.let { put("trackNumber", it) }
        sourceQuality?.let { put("sourceQuality", it) }
        put("sourceHiRes", sourceHiRes)
    }

    private fun JsonObject.toSong(client: NavidromeClient?): Song? {
        val videoId = str("videoId") ?: return null
        return Song(
            videoId = videoId,
            title = str("title").orEmpty(),
            artist = str("artist").orEmpty(),
            artistId = str("artistId"),
            albumId = str("albumId"),
            thumbnailUrl = NavidromeRepository.live(str("thumbnailUrl")),
            durationText = str("durationText"),
            albumName = str("albumName"),
            // Replaced by the file in [onDisk]; the stream URL only when the file has gone missing.
            localUri = runCatching { client?.streamUrl(videoId.removePrefix(NavidromeSettings.ID_PREFIX)) }.getOrNull(),
            trackNumber = (get("trackNumber") as? JsonPrimitive)?.intOrNull,
            sourceQuality = str("sourceQuality"),
            sourceHiRes = (get("sourceHiRes") as? JsonPrimitive)?.booleanOrNull ?: false,
            playbackSource = "Navidrome",
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = NavidromeSettings.BROWSE_ID,
        )
    }

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
