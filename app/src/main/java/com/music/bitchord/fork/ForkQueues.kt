package com.music.bitchord.fork

import android.content.Context
import androidx.media3.common.Player
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeOffline
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.queueEntryId
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Saved queues (fork-only, a try-out): the queue as it stands (its songs, the
 * one playing and where in it) put aside under a name, and played again later
 * from that same song and second, as Namida keeps several queues.
 *
 * Kept in `files/fork_queues.json`, newest first, at most [MAX]. Songs are
 * stored with what plays them (YouTube id, Navidrome id and stream, file
 * address), not as references, so a queue plays without the page it came
 * from. The player's controller and the app's own "play this list" are handed
 * in by the main screen ([bind]); the rest is here.
 */
object ForkQueues {

    private const val TAG = "BitChordFork"
    private const val MAX = 20

    /** How long a restored queue may take to reach its song before the seek is given up. */
    private const val RESTORE_WAIT_MS = 6_000L

    data class Saved(
        val id: String,
        val name: String,
        val songs: List<Song>,
        val index: Int,
        val positionMs: Long,
        val savedAt: Long,
    ) {
        val current: Song? get() = songs.getOrNull(index)
    }

    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _saved = MutableStateFlow<List<Saved>>(emptyList())
    val saved: StateFlow<List<Saved>> = _saved.asStateFlow()

    private var player: Player? = null
    private var appScope: CoroutineScope? = null
    private var play: ((List<Song>, Int, QueueSource) -> Unit)? = null

    fun init(context: Context) {
        file = File(context.filesDir, "fork_queues.json")
        // Merged, not replaced: a queue saved before the file was read stays.
        scope.launch { val read = read(); _saved.update { (it + read).distinctBy { q -> q.id }.take(MAX) } }
    }

    /** The main screen's controller, its scope and its "play this list from here"; see [ForkQueuesBinding]. */
    internal fun bind(player: Player?, scope: CoroutineScope?, play: ((List<Song>, Int, QueueSource) -> Unit)?) {
        this.player = player
        this.appScope = scope
        this.play = play
    }

    /** What [saveCurrent] would save under no name: the playing song's title. */
    fun suggestedName(): String? = player?.let { p ->
        p.currentMediaItem?.mediaMetadata?.title?.toString()
    }

    /** Puts the current queue aside under [name]; false when there is nothing queued. */
    fun saveCurrent(name: String): Boolean {
        val p = player ?: return false
        if (p.mediaItemCount == 0) return false
        val songs = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).toSong() }
        val entry = Saved(
            id = System.currentTimeMillis().toString(),
            name = name.ifBlank { suggestedName().orEmpty() }.ifBlank { "Queue" },
            songs = songs,
            index = p.currentMediaItemIndex.coerceIn(songs.indices),
            positionMs = p.currentPosition.coerceAtLeast(0L),
            savedAt = System.currentTimeMillis(),
        )
        _saved.update { (listOf(entry) + it).take(MAX) }
        persist()
        return true
    }

    /**
     * [ForkHandoffPhone]'s restore: the other device's queue, played through the same path as a
     * saved one (the seek waits for the new queue's entry), but named by its own origin instead of
     * a saved name, so "Playing from …" and AutoPlay carry on as on the other device. False when the
     * main screen isn't bound (nothing to play it with).
     */
    internal fun playHandoff(songs: List<Song>, index: Int, positionMs: Long, source: QueueSource): Boolean {
        if (play == null || songs.isEmpty()) return false
        play(Saved("handoff", source.title, songs, index.coerceIn(songs.indices), positionMs, System.currentTimeMillis()), source)
        return true
    }

    /** Replaces the queue with [queue] and resumes at its song and second; [source] names it (a saved queue: by its name). */
    fun play(queue: Saved, source: QueueSource = QueueSource(queue.name, PlaybackSourceType.QUEUE)) {
        val start = play ?: return
        if (queue.songs.isEmpty()) return
        // Starting only launches the swap. The song may already be playing in the queue being
        // replaced: seeking that one, the new queue then lands at 0:00. Wait to see the swap.
        var swapped = false
        val swap = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) swapped = true
            }
        }
        // The old queue can change before the swap lands too (AutoPlay appending, an edit), which
        // also reads as a playlist change: the swap is only seen once the current song is a new
        // entry. Its entry id is dropped, so the new queue gives it a fresh one (a queue saved this
        // run still carried the ids of the queue it was saved from, the one being replaced).
        val replaced = player?.currentMediaItem?.queueEntryId
        val listening = player?.takeIf { queue.positionMs > 0L }?.also { it.addListener(swap) }
        start(queue.songs.map { it.copy(queueEntryId = null) }, queue.index, source)
        val target = queue.current?.videoId
        if (target == null || listening == null) {
            listening?.removeListener(swap)
            return
        }
        appScope?.launch {
            // The queue is built off the main thread, and the new item is positioned (at 0) as it
            // is prepared: a seek before the song is ready is overwritten. Seek once it is the
            // current song and ready, and check that it took.
            var waited = 0L
            while (waited < RESTORE_WAIT_MS) {
                val p = player ?: return@launch
                if (swapped && isNewEntry(p.currentMediaItem?.queueEntryId, replaced) &&
                    p.currentMediaItem?.mediaId == target && p.playbackState == Player.STATE_READY
                ) {
                    p.seekTo(queue.positionMs)
                    delay(500)
                    if (kotlin.math.abs(p.currentPosition - queue.positionMs) < 5_000) {
                        listening.removeListener(swap)
                        return@launch
                    }
                }
                delay(100)
                waited += 100
            }
            listening.removeListener(swap)
            Log.d(TAG, "saved queue ${queue.name}: never reached ${queue.current?.title}, not seeking")
        }
    }

    /** Whether the playing entry [now] is not the one [replaced] was; with no entry ids to tell by, only the playlist change counts. */
    internal fun isNewEntry(now: String?, replaced: String?): Boolean = now == null || replaced == null || now != replaced

    fun delete(id: String) {
        _saved.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    /** One writer at a time, writing the list as it is then: two quick saves or deletes never land out of order or interleave. */
    private val writeLock = Mutex()

    private fun persist() {
        scope.launch {
            writeLock.withLock {
                runCatching {
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(buildJsonArray { _saved.value.forEach { add(it.toJson()) } }.toString())
                    tmp.renameTo(file)
                }.onFailure { Log.d(TAG, "saved queues: ${it.message}") }
            }
        }
    }

    /** The saved queues as their file holds them, for [ForkBackup]. */
    internal fun exportForBackup(): JsonArray = buildJsonArray { _saved.value.forEach { add(it.toJson()) } }

    /** Replaces the saved queues with [queues] (a backup's), in the file's own shape. */
    internal fun importFromBackup(queues: JsonArray) {
        if (!this::file.isInitialized) return
        // distinctBy: the sheet's list is keyed by id, and a repeated id (an edited file) crashed it.
        _saved.value = parse(queues).distinctBy { it.id }.take(MAX)
        persist()
    }

    private fun read(): List<Saved> = runCatching {
        if (!file.isFile) return emptyList()
        parse(json.parseToJsonElement(file.readText()).jsonArray)
    }.getOrElse {
        Log.d(TAG, "saved queues: ${it.message}")
        emptyList()
    }

    internal fun parse(array: JsonArray): List<Saved> = runCatching {
        array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val songs = (o["songs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toSong() }
            Saved(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name").orEmpty(),
                songs = songs,
                // Within the songs: a backup's index past them (or a song dropped above) started the queue nowhere.
                index = ((o["index"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, (songs.size - 1).coerceAtLeast(0)),
                positionMs = ((o["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L).coerceAtLeast(0L),
                savedAt = (o["savedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }.getOrElse {
        Log.d(TAG, "saved queues: ${it.message}")
        emptyList()
    }

    private fun Saved.toJson() = buildJsonObject {
        put("id", id)
        put("name", name)
        put("index", index)
        put("positionMs", positionMs)
        put("savedAt", savedAt)
        put("songs", buildJsonArray { songs.forEach { add(it.toJson()) } })
    }

    private fun Song.toJson() = buildJsonObject {
        put("videoId", videoId)
        put("title", title)
        put("artist", artist)
        artistId?.let { put("artistId", it) }
        albumId?.let { put("albumId", it) }
        albumName?.let { put("albumName", it) }
        thumbnailUrl?.let { put("thumbnailUrl", it) }
        durationText?.let { put("durationText", it) }
        // A Navidrome song's address carries the login (or points at a kept file that may go): rebuilt on read.
        localUri?.takeUnless { NavidromeSettings.isNavidromeId(videoId) }?.let { put("localUri", it) }
        localPath?.let { put("localPath", it) }
        sourceQuality?.let { put("sourceQuality", it) }
        put("sourceHiRes", sourceHiRes)
        put("isVideo", isVideo)
        setVideoId?.let { put("setVideoId", it) }
        playbackSource?.let { put("playbackSource", it) }
        playbackSourceType?.let { put("playbackSourceType", it.name) }
        playbackSourceId?.let { put("playbackSourceId", it) }
    }

    private fun JsonObject.toSong(): Song? {
        val videoId = str("videoId") ?: return null
        return Song(
        videoId = videoId,
        title = str("title").orEmpty(),
        artist = str("artist").orEmpty(),
        artistId = str("artistId"),
        albumId = str("albumId"),
        albumName = str("albumName"),
        thumbnailUrl = str("thumbnailUrl"),
        durationText = str("durationText"),
        localUri = if (NavidromeSettings.isNavidromeId(videoId)) navidromeUri(videoId) else str("localUri"),
        localPath = str("localPath"),
        sourceQuality = str("sourceQuality"),
        sourceHiRes = (get("sourceHiRes") as? JsonPrimitive)?.booleanOrNull ?: false,
        isVideo = (get("isVideo") as? JsonPrimitive)?.booleanOrNull ?: false,
        setVideoId = str("setVideoId"),
        playbackSource = str("playbackSource"),
        playbackSourceType = str("playbackSourceType")?.let { runCatching { PlaybackSourceType.valueOf(it) }.getOrNull() },
        playbackSourceId = str("playbackSourceId"),
        )
    }

    /** A Navidrome song's address now: its kept file, else a stream with today's login. */
    private fun navidromeUri(videoId: String): String? {
        val id = videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        return NavidromeOffline.localFile(id)?.let { android.net.Uri.fromFile(it).toString() }
            ?: runCatching { NavidromeRepository.client().streamUrl(id) }.getOrNull()
    }

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}

/**
 * Binds [ForkQueues] to the main screen while it is shown, and lets it go with
 * the screen: the playback service outlives the Activity, and an object holding
 * a closed screen's controller, scope and callbacks would keep all of it alive.
 */
@androidx.compose.runtime.Composable
fun ForkQueuesBinding(player: Player?, scope: CoroutineScope, play: (List<Song>, Int, QueueSource) -> Unit) {
    val current = androidx.compose.runtime.rememberUpdatedState(play)
    androidx.compose.runtime.DisposableEffect(player, scope) {
        ForkQueues.bind(player, scope) { songs, index, source -> current.value(songs, index, source) }
        onDispose { ForkQueues.bind(null, null, null) }
    }
}
