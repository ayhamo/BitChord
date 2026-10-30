package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Fork: saved queues, the phone's `ForkQueues` on the desktop. The queue as it stands (its songs,
 * the one playing and where in it) put aside under a name, and played again later from that same
 * song and second.
 *
 * Kept in `bitchord/fork_queues.json` in the app's data folder, newest first, at most [MAX]. Unlike
 * the phone, a Navidrome row keeps no address at all, only its cover's id: both of its addresses
 * carry the login, so they would go stale with a new password and put the login in a plain file.
 * They are built with today's login when the queue is played ([live]), a kept file first.
 */
internal object ForkQueues {

    const val TITLE = "Saved queues"
    private const val MAX = 20

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

    /** Set by the window, which owns the queue: what it holds now and the second it is at. */
    var currentQueue: () -> DesktopQueue = { DesktopQueue() }
    var playingAtMs: () -> Long = { 0L }

    /** Set by the window: replaces its queue with these songs and plays one from a given second. */
    var restore: (List<Song>, Int, Long) -> Unit = { _, _, _ -> }

    private val file: File by lazy {
        // The app's data folder rather than a cache, as in [ForkOffline].
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.local/share"
        File(base, "bitchord/fork_queues.json")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _saved = MutableStateFlow<List<Saved>>(emptyList())
    val saved: StateFlow<List<Saved>> = _saved.asStateFlow()

    /** One writer at a time, writing the list as it is then: two quick saves or deletes never land out of order or interleave. */
    private val writeLock = Mutex()

    init {
        // Merged, not replaced: a queue saved before the file was read stays.
        scope.launch {
            val read = read()
            _saved.update { (it + read).distinctBy { q -> q.id }.take(MAX) }
        }
    }

    /** What [saveCurrent] would save under no name: the playing song's title. */
    fun suggestedName(): String? = currentQueue().current?.title

    /** Puts the current queue aside under [name]; false when there is nothing queued. */
    fun saveCurrent(name: String): Boolean {
        val queue = currentQueue()
        if (queue.songs.isEmpty()) return false
        val songs = queue.songs.map { it.atRest() }
        val entry = Saved(
            id = System.currentTimeMillis().toString(),
            name = name.ifBlank { suggestedName().orEmpty() }.ifBlank { "Queue" },
            songs = songs,
            index = queue.index.coerceIn(songs.indices),
            positionMs = playingAtMs().coerceAtLeast(0L),
            savedAt = System.currentTimeMillis(),
        )
        _saved.update { (listOf(entry) + it).take(MAX) }
        persist()
        return true
    }

    /** Replaces the queue with [queue] and resumes at its song and second. */
    fun play(queue: Saved) {
        if (queue.songs.isEmpty()) return
        restore(queue.songs.live(queue.name), queue.index, queue.positionMs)
    }

    fun delete(id: String) {
        _saved.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    /** A row as it is kept: for a Navidrome song no address, and its cover by id. */
    private fun Song.atRest(): Song = if (!ForkNavidrome.owns(this)) this else copy(
        localUri = null,
        thumbnailUrl = thumbnailUrl?.toHttpUrlOrNull()?.takeIf { "getCoverArt" in it.encodedPath }?.queryParameter("id"),
    )

    /**
     * Rows ready for the queue, as the phone's `buildContextQueue` makes them: from this saved
     * queue, in the album's section, each its own queue entry. A Navidrome row needs its address
     * back even though [ForkNavidrome.stream] answers at play time: a row with none reads as a
     * YouTube track to the song menu and to a party.
     */
    private fun List<Song>.live(name: String): List<Song> {
        val client = runCatching { NavidromeRepository.client() }.getOrNull() // none without a login
        return map { song ->
            val addressed = if (!ForkNavidrome.owns(song)) song else {
                val id = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
                song.copy(
                    localUri = NavidromePlatform.kept.localUri(id) ?: client?.streamUrl(id),
                    thumbnailUrl = song.thumbnailUrl?.let { cover -> client?.coverUrl(cover) },
                )
            }
            addressed
                .copy(playbackSource = name, playbackSourceType = PlaybackSourceType.QUEUE, playbackSourceId = null)
                .asQueueEntry(QueueTier.CONTEXT)
        }
    }

    private fun persist() {
        scope.launch {
            writeLock.withLock {
                runCatching {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(buildJsonArray { _saved.value.forEach { add(it.toJson()) } }.toString())
                    // Not File.renameTo, as on the phone: on Windows it won't replace a file that exists.
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }.onFailure { DesktopTrackLog.log("saved queues: ${it.message}") }
            }
        }
    }

    private fun read(): List<Saved> = runCatching {
        if (!file.isFile) return emptyList()
        json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            Saved(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name").orEmpty(),
                songs = (o["songs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toSong() },
                index = (o["index"] as? JsonPrimitive)?.intOrNull ?: 0,
                positionMs = (o["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
                savedAt = (o["savedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }.getOrElse {
        DesktopTrackLog.log("saved queues: ${it.message}")
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
        localUri?.let { put("localUri", it) }
        localPath?.let { put("localPath", it) }
        sourceQuality?.let { put("sourceQuality", it) }
        put("sourceHiRes", sourceHiRes)
        put("isVideo", isVideo)
        put("isVideoOrigin", isVideoOrigin)
        setVideoId?.let { put("setVideoId", it) }
    }

    private fun JsonObject.toSong(): Song? {
        val videoId = str("videoId") ?: return null
        val isVideo = (get("isVideo") as? JsonPrimitive)?.booleanOrNull ?: false
        return Song(
            videoId = videoId,
            title = str("title").orEmpty(),
            artist = str("artist").orEmpty(),
            artistId = str("artistId"),
            albumId = str("albumId"),
            albumName = str("albumName"),
            thumbnailUrl = str("thumbnailUrl"),
            durationText = str("durationText"),
            localUri = str("localUri"),
            localPath = str("localPath"),
            sourceQuality = str("sourceQuality"),
            sourceHiRes = (get("sourceHiRes") as? JsonPrimitive)?.booleanOrNull ?: false,
            isVideo = isVideo,
            isVideoOrigin = (get("isVideoOrigin") as? JsonPrimitive)?.booleanOrNull ?: isVideo,
            setVideoId = str("setVideoId"),
        )
    }

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}

/** Fork: "Saved queues" in the player's song menu, the phone's `ForkSavedQueuesRow`. */
@Composable
internal fun ForkSavedQueuesRow(onOpen: () -> Unit) {
    val saved by ForkQueues.saved.collectAsState()
    DesktopMenuRow(
        icon = Icons.AutoMirrored.Rounded.QueueMusic,
        label = ForkQueues.TITLE,
        value = saved.size.takeIf { it > 0 }?.toString().orEmpty(),
        onClick = onOpen,
    )
}

/**
 * The phone's saved-queues dialog as a drawer of the song menu, as the menu's lyrics search is: a
 * dialog raised from inside its popup would go with the popup at the first click outside it.
 */
@Composable
internal fun ForkSavedQueuesDrawer(onClose: () -> Unit) {
    val saved by ForkQueues.saved.collectAsState()
    var name by remember { mutableStateOf(ForkQueues.suggestedName().orEmpty()) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { if (ForkQueues.saveCurrent(name)) name = "" }) { Text("Save this queue") }
        }
        if (saved.isEmpty()) {
            Text(
                "No saved queues. Save this one to come back to it later, from the same song and second.",
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
            )
        }
        saved.forEach { queue ->
            key(queue.id) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            queue.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entryLine(queue),
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = {
                        ForkQueues.play(queue)
                        onClose()
                    }) { Text("Play") }
                    TextButton(onClick = { ForkQueues.delete(queue.id) }) { Text("Delete") }
                }
            }
        }
    }
}

/** "12 songs · The song · 1:23", the phone's `fork_queues_entry`. */
private fun entryLine(queue: ForkQueues.Saved): String {
    val count = queue.songs.size
    val songs = if (count == 1) "1 song" else "$count songs"
    val at = String.format(Locale.ROOT, "%d:%02d", queue.positionMs / 60_000, queue.positionMs / 1_000 % 60)
    return "$songs · ${queue.current?.title.orEmpty()} · $at"
}
