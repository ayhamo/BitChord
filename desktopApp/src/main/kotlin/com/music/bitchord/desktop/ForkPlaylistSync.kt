package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Fork: "Keep playlists updated", the phone's `PlaylistSync` on the desktop. Keeps the YouTube
 * playlists downloaded whole ("Download all") in step with their online copies while the setting
 * is on.
 *
 * Copied from the phone rule by rule:
 * - Only ever adds. A track the listener deleted is still named by its playlist's record, so it
 *   does not come back; a track removed online keeps its file, and only leaves the record (so it
 *   would be fetched again if it were added back).
 * - What is new is worked out fresh on every check and recorded only once it has been dealt with,
 *   so a check cut short (the app closed, a page failing) leaves the rest new for the next one.
 * - Only YouTube playlists: not albums, not Spotify's (no YouTube copy to compare) and not the
 *   server's, whose kept playlists already follow the server by themselves
 *   (`NavidromeOffline.syncPlaylists`, on every Home load).
 * - More than [ASK_ABOVE] new tracks in one playlist are asked about first.
 * - The schedule: twice a day, a check when the switch goes on, and on launch a catch-up when the
 *   last complete check is over [CATCH_UP_MILLIS] old.
 *
 * The phone's WorkManager job, Wi-Fi and battery rules and notifications have no PC counterpart:
 * the schedule is a loop in this process, the ask is a card over the window ([AskHost]) and the
 * "Added to …" message is a [ForkNotice]. Offline mode, switched on, holds the check back.
 *
 * The record (`bitchord/fork_playlist_sync.json` in the data folder) is this file's own: the PC's
 * downloads list keeps loose tracks, and only the 200 newest. A record is dropped once every file
 * downloaded for it is gone (the phone's `forgetCollection`, for "a record whose files are
 * already gone"): files deleted one by one, or outside the app, can empty a playlist without
 * anybody asking for it to be forgotten, and a playlist whose files were all deleted is not one to
 * keep refilling. A page's or card's "Delete download" forgets its record at once ([forget]).
 */
internal object ForkPlaylistSync {

    private const val KEY_ENABLED = "sync_downloaded_playlists"
    private const val KEY_LAST_CHECK = "playlist_sync_last_complete_check"

    /** The phone's period, without Android's flex. */
    private const val PERIOD_MILLIS = 12L * 60 * 60 * 1000

    /** How stale the last whole check may be when the app starts. */
    private const val CATCH_UP_MILLIS = 6L * 60 * 60 * 1000

    /** How often the loop looks at the clock; the checks themselves are [PERIOD_MILLIS] apart. */
    private const val TICK_MILLIS = 15L * 60 * 1000

    /** After launch, before the first look: the window's startup check of the downloads goes first. */
    private const val FIRST_LOOK_MILLIS = 90_000L

    /** The most one playlist may fetch without being asked; about 1.7 GB of lossless. */
    internal const val ASK_ABOVE = 50

    /** Between requests for pages and for playlists: none of this is in a hurry. */
    private const val PAUSE_MILLIS = 400L

    data class Record(
        /** The id YouTube answers to, with its `VL`. */
        val id: String,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        /** Every track the record names, in the playlist's order as last seen. */
        val videoIds: List<String>,
        /** The files downloaded for it, by track, so "every file is gone" can be told. */
        val files: Map<String, String> = emptyMap(),
    )

    private val persistence = DesktopPersistence()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val file: File by lazy {
        // The app's data folder rather than a cache, as [ForkQueues] and [ForkOffline] keep theirs.
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.local/share"
        File(base, "bitchord/fork_playlist_sync.json")
    }

    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val records = MutableStateFlow<Map<String, Record>>(emptyMap())
    @Volatile private var loaded = false

    /** Playlists waiting for a yes, with how many new tracks each has. */
    private val _asks = MutableStateFlow<Map<String, Pair<String, Int>>>(emptyMap())

    /** One check at a time, however many of the triggers fire together. */
    private val running = Mutex()
    private val writeLock = Mutex()
    @Volatile private var lastAttempt = 0L

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        persistence.saveBoolean(KEY_ENABLED, on)
        if (!on) _asks.value = emptyMap()
    }

    /**
     * Starts the schedule. Off, it only waits on the switch: a setting that defaults to off
     * costs a launch nothing.
     */
    fun install() {
        // Files that finish for a recorded track are noted, for the "every file is gone" rule.
        scope.launch {
            DesktopDownloadQueue.finished.collect { saved ->
                val path = saved.localPath ?: return@collect
                // Loaded first: a download carried over from the last session can finish before
                // anything else has read the record, and would otherwise go unnoted.
                ensureLoaded()
                var changed = false
                records.update { current ->
                    current.mapValues { (_, record) ->
                        if (saved.videoId in record.videoIds && record.files[saved.videoId] != path) {
                            changed = true
                            record.copy(files = record.files + (saved.videoId to path))
                        } else {
                            record
                        }
                    }
                }
                if (changed) persist()
            }
        }
        // Switched on: a check now, as the phone queues one.
        scope.launch {
            enabled.drop(1).collect { on -> if (on) launch { runCheck(approved = false) } }
        }
        scope.launch {
            delay(FIRST_LOOK_MILLIS)
            var first = true
            while (true) {
                if (enabled.value) {
                    val now = System.currentTimeMillis()
                    val sinceComplete = now - (persistence.string(KEY_LAST_CHECK, "0").toLongOrNull() ?: 0L)
                    // A check that failed partway is due again after a period, not on every tick:
                    // the next launch's catch-up is the phone's early retry.
                    val due = if (first) sinceComplete >= CATCH_UP_MILLIS else sinceComplete >= PERIOD_MILLIS && now - lastAttempt >= PERIOD_MILLIS
                    if (due) runCheck(approved = false)
                }
                first = false
                delay(TICK_MILLIS)
            }
        }
    }

    /**
     * Records a playlist downloaded whole, the phone's `Downloads.rememberCollection`: [songs] are
     * what was asked for, merged with what was asked before (a playlist fetched in pages can be
     * downloaded twice from two depths of it), the new order leading. Anything but a YouTube
     * playlist is left alone.
     */
    fun remember(browseId: String, type: BrowseType, title: String, subtitle: String, thumbnailUrl: String?, songs: List<Song>) {
        if (type != BrowseType.PLAYLIST) return
        val id = syncableBrowseId(browseId) ?: return
        val ids = songs.filterNot { it.videoId.isBlank() }.map { it.videoId }.distinct()
        if (ids.isEmpty()) return
        scope.launch {
            ensureLoaded()
            records.update { current ->
                val existing = current[id]
                current + (
                    id to Record(
                        id = id,
                        title = title.ifBlank { existing?.title.orEmpty() },
                        subtitle = subtitle,
                        thumbnailUrl = thumbnailUrl ?: existing?.thumbnailUrl,
                        videoIds = (ids + existing?.videoIds.orEmpty()).distinct(),
                        files = existing?.files.orEmpty(),
                    )
                )
            }
            persist()
        }
    }

    /** The tracks the record for [browseId] names; empty when it is not a followed playlist. */
    suspend fun tracksOf(browseId: String): Set<String> {
        val id = syncableBrowseId(browseId) ?: return emptySet()
        ensureLoaded()
        return records.value[id]?.videoIds?.toSet().orEmpty()
    }

    /**
     * What every record but [browseId]'s names, by track and by file: a release's "Delete download"
     * leaves those, as the phone's `Downloads.tracksOnlyIn` does (one file can answer to two ids).
     */
    suspend fun keptByOthers(browseId: String): Pair<Set<String>, Set<String>> {
        ensureLoaded()
        val own = syncableBrowseId(browseId)
        val others = records.value.values.filter { it.id != own }
        return others.flatMapTo(HashSet()) { it.videoIds } to others.flatMapTo(HashSet()) { it.files.values }
    }

    /**
     * Stops following [browseId], the phone's `forgetCollection` after "Delete download": a playlist
     * the listener has just removed from the computer is not one to fill again.
     */
    suspend fun forget(browseId: String) {
        val id = syncableBrowseId(browseId) ?: return
        ensureLoaded()
        if (records.value[id] == null) return
        records.update { it - id }
        _asks.update { it - id }
        persist()
    }

    /**
     * The id a check looks at, under the id YouTube answers to (`VL…`), or null for anything
     * without a YouTube playlist behind it: an album (`MP…`), a Spotify playlist and every
     * `local:` page (the server's, the computer's own) carry a `:` or the album prefix.
     */
    internal fun syncableBrowseId(id: String): String? = when {
        id.isBlank() || ':' in id || id.startsWith(SPOTIFY_PAGE_PREFIX) || id.startsWith("MP") || id.startsWith("OLAK5uy_") -> null
        id.startsWith("VL") -> id
        else -> "VL$id"
    }

    // ---- A check ------------------------------------------------------------

    /**
     * Goes through every recorded playlist in turn. Only a check that read every playlist to its
     * end is stamped as complete. [approved] also fetches the batches over [ASK_ABOVE], and waits
     * for a check already running rather than being dropped behind it.
     */
    private suspend fun runCheck(approved: Boolean) {
        if (approved) running.lock() else if (!running.tryLock()) return
        try {
            lastAttempt = System.currentTimeMillis()
            ensureLoaded()
            if (ForkOffline.switched.value) {
                DesktopTrackLog.log("playlist sync: offline mode, not checked")
                return
            }
            var whole = true
            for (id in records.value.keys.toList()) {
                if (!enabled.value) return
                val record = records.value[id] ?: continue
                if (filesAllGone(record)) {
                    DesktopTrackLog.log("playlist sync: every file of '${record.title}' is gone; no longer following it")
                    records.update { it - id }
                    persist()
                    continue
                }
                val listing = readWholePlaylist(id)
                if (!listing.complete) {
                    whole = false
                    DesktopTrackLog.log("playlist sync: '${record.title}' was not read to its end; the rest waits for the next check")
                }
                update(record, listing, approved)
                delay(PAUSE_MILLIS)
            }
            if (whole) persistence.saveString(KEY_LAST_CHECK, System.currentTimeMillis().toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never a retry loop: the next period (or launch) is the next try.
            DesktopTrackLog.log("playlist sync: check failed: ${e.message}")
        } finally {
            running.unlock()
        }
    }

    private suspend fun update(record: Record, listing: Listing, approved: Boolean) {
        val known = record.videoIds.toHashSet()
        val fresh = listing.songs.filter { it.videoId !in known }
        if (fresh.isEmpty()) {
            // Nothing written, as on the phone: the record only changes alongside new tracks.
            _asks.update { it - record.id }
            return
        }
        if (fresh.size > ASK_ABOVE && !approved) {
            _asks.update { it + (record.id to (record.title to fresh.size)) }
            DesktopTrackLog.log("playlist sync: ${fresh.size} new tracks in '${record.title}', waiting for a yes")
            return
        }
        _asks.update { it - record.id }
        // Already on this computer for some other reason: joins the playlist without being fetched again.
        val saved = DesktopAppUiHost.downloaded.value
        val (onDisk, missing) = fresh.partition { it.videoId in saved }
        val cover = listing.cover ?: record.thumbnailUrl?.takeIf { it.startsWith("http") }
        val fetch = missing.map { song ->
            if (song.thumbnailUrl.isNullOrBlank() && cover != null) song.copy(thumbnailUrl = cover) else song
        }
        val arrived = downloadNow(fetch)
        val handled = onDisk.mapTo(HashSet()) { it.videoId } + arrived.handled
        writeRecord(record, listing, handled)
        DesktopTrackLog.log("playlist sync: '${record.title}': ${fresh.size} new, ${arrived.saved} downloaded, ${onDisk.size} already here")
        if (arrived.saved > 0) {
            ForkNotice.show(DesktopStrings.format("playlist_sync_done_title", record.title, arrived.saved, fallback = "Added to %1\$s: %2\$d"))
        }
    }

    /**
     * The listing as the record keeps it ([listing] without what is still to be dealt with, in
     * the playlist's own order: a track left out stays new, so the next check tries it again).
     * Read again first: the record may have gone meanwhile, and writing it back would bring it
     * back from the dead.
     */
    private fun writeRecord(record: Record, listing: Listing, handled: Set<String>) {
        var changed = false
        records.update { current ->
            val now = current[record.id] ?: return@update current
            val known = now.videoIds.toHashSet()
            val seen = listing.songs.filter { it.videoId in known || it.videoId in handled }.map { it.videoId }
            // A listing cut short says nothing about what comes after it, so there the rest of the
            // record stays (the phone drops it, and finds those tracks again as new ones already
            // on the device; here the downloads list may have let them fall out of its 200).
            val order = if (listing.complete) seen else (seen + now.videoIds).distinct()
            if (order == now.videoIds) return@update current
            changed = true
            current + (record.id to now.copy(videoIds = order))
        }
        if (changed) persist()
    }

    private class Batch(val handled: Set<String>, val saved: Int)

    /**
     * Fetches [songs] through the download queue and waits until each has settled, the phone's
     * `Downloads.downloadNow`. Handled are the ones nobody needs to come back to: saved, called
     * off by the listener, or already queued from a click. A track that failed is not among them,
     * so the next check tries it again.
     */
    private suspend fun downloadNow(songs: List<Song>): Batch {
        if (songs.isEmpty()) return Batch(emptySet(), 0)
        val before = DesktopDownloadQueue.active.value
        val alreadyGoing = songs.filter { before[it.videoId]?.state.let { s -> s == DesktopDownloadState.Queued || s is DesktopDownloadState.Running } }
            .mapTo(HashSet()) { it.videoId }
        val ours = songs.filter { it.videoId !in alreadyGoing }
        val savedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // Undispatched, so it is subscribed before the first enqueue: [DesktopDownloadQueue.finished]
        // replays nothing, and a track already in the media cache can finish almost at once.
        val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            DesktopDownloadQueue.finished.collect { if (ours.any { s -> s.videoId == it.videoId }) savedIds += it.videoId }
        }
        try {
            DesktopDownloadQueue.enqueueAll(ours)
            DesktopDownloadQueue.active.first { active ->
                ours.all { song ->
                    val state = active[song.videoId]?.state
                    state == null || state is DesktopDownloadState.Failed
                }
            }
            // The last finished emission can land a moment after its row leaves [active].
            delay(500)
        } finally {
            watcher.cancel()
        }
        val active = DesktopDownloadQueue.active.value
        val handled = alreadyGoing + ours.filter { active[it.videoId]?.state !is DesktopDownloadState.Failed }.map { it.videoId }
        return Batch(handled, savedIds.size)
    }

    private fun filesAllGone(record: Record): Boolean =
        record.files.isNotEmpty() && record.files.values.none { File(it).isFile }

    // ---- Reading a playlist -------------------------------------------------

    /**
     * @property complete whether this reached the end of the playlist. False when a request
     *   failed; [songs] is then whatever came before it, still worth acting on since a check only
     *   ever adds.
     */
    private class Listing(val songs: List<Song>, val complete: Boolean, val cover: String? = null)

    /**
     * Every track in a playlist, however many pages it runs to; the phone's own walk rather than
     * `YtMusicRepository.allSongs`, which stops at a page limit and turns a failed page into a
     * shorter list without saying so. Only the rows a page lists as its own, so YouTube's
     * suggestions are never taken for part of it.
     */
    private suspend fun readWholePlaylist(browseId: String): Listing {
        val opening = YtMusicRepository.browseSongs(browseId).getOrNull() ?: return Listing(emptyList(), complete = false)
        val cover = opening.header?.thumbnailUrl
        val songs = LinkedHashMap<String, Song>()
        opening.songs.forEach { songs.putIfAbsent(it.videoId, it) }
        var next = opening.continuation
        while (next != null) {
            delay(PAUSE_MILLIS)
            val page = YtMusicRepository.moreSongs(next).getOrNull() ?: return Listing(songs.values.toList(), complete = false, cover)
            val before = songs.size
            page.songs.forEach { songs.putIfAbsent(it.videoId, it) }
            // A page with nothing new is a feed looped back on itself with a token still attached.
            if (songs.size == before) break
            next = page.continuation
        }
        return Listing(songs.values.toList(), complete = true, cover)
    }

    // ---- The ask --------------------------------------------------------------

    /**
     * The phone's "New songs in X: N · Tap to download" notification, as a card over the window
     * until it is answered. "Download" runs a whole check with the yes, as the phone's tap does.
     */
    @Composable
    fun AskHost() {
        val asks by _asks.collectAsState()
        val (id, ask) = asks.entries.firstOrNull()?.toPair() ?: return
        val (title, count) = ask
        Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -180)) {
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier.widthIn(max = 520.dp).clip(shape).desktopCard(shape).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    DesktopStrings.format("playlist_sync_ask_title", title, count, fallback = "New songs in %1\$s: %2\$d"),
                    Modifier.weight(1f, fill = false),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = {
                    _asks.value = emptyMap()
                    scope.launch { runCheck(approved = true) }
                }) { Text(DesktopStrings["download", "Download"], color = DesktopAccent) }
                TextButton(onClick = { _asks.update { it - id } }) {
                    Text(DesktopStrings["not_now", "Not now"], color = DesktopSecondary)
                }
            }
        }
    }

    // ---- The record on disk ---------------------------------------------------

    private suspend fun ensureLoaded() {
        if (loaded) return
        writeLock.withLock {
            if (loaded) return
            val read = runCatching {
                if (!file.isFile) emptyMap() else parse(json.parseToJsonElement(file.readText()).jsonObject["playlists"]?.jsonArray)
            }.getOrElse {
                DesktopTrackLog.log("playlist sync: record unreadable: ${it.message}")
                emptyMap()
            }
            // Merged: a playlist recorded before the file was read stays.
            records.update { read + it }
            loaded = true
        }
    }

    private fun persist() {
        scope.launch {
            writeLock.withLock {
                runCatching {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(
                        buildJsonObject {
                            put("playlists", buildJsonArray { records.value.values.forEach { add(it.toJson()) } })
                        }.toString(),
                    )
                    // Not File.renameTo: on Windows it won't replace a file that exists.
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }.onFailure { DesktopTrackLog.log("playlist sync: record not saved: ${it.message}") }
            }
        }
    }

    private fun Record.toJson() = buildJsonObject {
        put("id", id)
        put("title", title)
        put("subtitle", subtitle)
        thumbnailUrl?.let { put("thumbnailUrl", it) }
        put("videoIds", buildJsonArray { videoIds.forEach { add(JsonPrimitive(it)) } })
        put("files", buildJsonObject { files.forEach { (k, v) -> put(k, v) } })
    }

    private fun parse(array: JsonArray?): Map<String, Record> = array.orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        Record(
            id = id,
            title = o.str("title").orEmpty(),
            subtitle = o.str("subtitle").orEmpty(),
            thumbnailUrl = o.str("thumbnailUrl"),
            videoIds = (o["videoIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            files = (o["files"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
        )
    }.associateBy { it.id }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** The phone's row, where the phone has it: in Downloads, after the download settings. */
@Composable
internal fun ForkPlaylistSyncToggle() {
    val on by ForkPlaylistSync.enabled.collectAsState()
    SettingsToggle(
        title = DesktopStrings["sync_downloaded_playlists", "Keep playlists updated"],
        subtitle = DesktopStrings["sync_downloaded_playlists_subtitle", "Checks twice a day and downloads songs newly added to your downloaded playlists"],
        checked = on,
        onCheckedChange = ForkPlaylistSync::setEnabled,
    )
}
