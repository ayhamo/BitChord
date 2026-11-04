package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.screens.LibraryLink
import com.music.bitchord.data.model.ShelfItem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Fork: the phone's "Cached songs" folder (`AudioCache.cachedSongs`, Library's `local:cache` row,
 * opt-in with Storage's "Show cached songs folder"), on the PC.
 *
 * The phone's list comes from the metadata Media3 keeps beside each cache entry (`describe`: the
 * song's id, title, artist, album, duration, artwork). The PC's song cache ([ForkAudioRangeCache])
 * keeps nothing but windows of a YouTube file named by a hash, so this index is that record: when
 * the engine opens a song ([noteStream]) and the range reader keeps its first window of that stream
 * ([noteKept]), the song and the stream's identity (`id`, `itag`, `lmt`, `clen`, the four values the
 * window key is made of, never the signed address) go into `songs.json` beside the windows.
 *
 * Kept in step with the files, not the other way round: the list is read against the folder each
 * time ([ForkCachedSongIndex.list]), so a song whose windows were evicted (the 256 MiB LRU), expired
 * or removed is dropped from the index there, and "Clear song cache" deletes `songs.json` with the
 * windows ([cleared]).
 *
 * Offline: a song whose windows cover the whole file plays from them when YouTube can't be resolved
 * (no network) or with offline mode switched on ([orKept]): the stream is opened on an address
 * rebuilt from those four values, which the range reader turns into the same keys and serves from
 * disk ([ForkCachedSongIndex.keptUrl]). Only a complete, unexpired copy is offered that way, so no
 * request goes out for a missing window. Like the phone, only YouTube: JioSaavn's files are not kept
 * by the PC's cache at all.
 */
internal object ForkCachedSongs {

    /** The phone's key and default (off). */
    internal const val KEY_SHOWN = "show_cache_folder"

    /** The phone's Library id for the folder. */
    internal const val BROWSE_ID = "local:cache"

    private val _shown = MutableStateFlow(DesktopPersistence().boolean(KEY_SHOWN, false))
    val shown: StateFlow<Boolean> = _shown

    fun setShown(on: Boolean) {
        DesktopPersistence().saveBoolean(KEY_SHOWN, on)
        _shown.value = on
    }

    /** Bumped when the cache's songs change by the listener's hand, so an open page reads again. */
    val version = MutableStateFlow(0)

    private val index by lazy { ForkCachedSongIndex({ DesktopMediaCache.directory.resolve("audio_ranges") }) }

    /**
     * Stream address -> the song it was opened for, newest last. Memory only, as
     * [ForkSongCache]'s: the addresses are signed. Bounded the same way.
     */
    private val pending = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Song>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>) = size > 64
    })

    /** From the engine as it opens [song] on [url] (through [ForkSongCache.noteStream]). */
    fun noteStream(song: Song, url: String) {
        if (ForkCachedSongIndex.listable(song) && url.contains(".googlevideo.com")) pending[url] = song
    }

    /** From the range reader as it keeps a window of [url]; the index is written only for a new stream. */
    fun noteKept(url: String) {
        val song = pending[url] ?: return
        index.record(song, url)
    }

    /** The folder's rows, most recently played first. Reads the disk: off the UI thread. */
    fun songs(): List<ForkCachedSongIndex.Listed> = index.list()

    fun holds(videoId: String): Boolean = index.holds(videoId)

    /**
     * Takes the song's windows off disk and its row out of the folder; the bytes freed. The page
     * reads again whenever the song had a row, even when [ForkSongCache] had deleted the windows
     * already (it is called from there, after its own pass).
     */
    fun remove(videoId: String): Long {
        val freed = index.remove(videoId) ?: return 0L
        version.value++
        return freed
    }

    /** "Clear song cache" deleted the windows and `songs.json`: the memory copy goes too. */
    fun cleared() {
        index.forget()
        version.value++
    }

    /**
     * [resolved] (YouTube's answer for [song]), or the kept copy in its place when it failed (no
     * network) or when offline mode is on, which never asks YouTube for a song kept whole.
     */
    suspend fun orKept(song: Song, resolved: suspend () -> Result<DesktopStream>): Result<DesktopStream> {
        if (ForkOffline.switched.value) kept(song)?.let { return Result.success(it) }
        val answer = resolved()
        if (answer.isSuccess) return answer
        return kept(song)?.let { Result.success(it) } ?: answer
    }

    private suspend fun kept(song: Song): DesktopStream? = withContext(Dispatchers.IO) {
        val url = index.keptUrl(song.videoId) { key, start -> ForkAudioRangeCache.shared.fresh(key, start) } ?: return@withContext null
        DesktopTrackLog.log("  youtube: '${song.title}' plays from the song cache (kept whole)")
        DesktopStream(
            url = url.first,
            format = DesktopStreamFormat(codec = url.second),
            sourceId = "youtube",
            durationSec = song.durationText?.let(::forkDurationSeconds),
            // The same windows the range reader kept, so the same reads: anything else would make
            // FFmpeg ask the address itself.
            windowedReads = true,
        )
    }

    /** The Library row, while Storage's switch shows it (the phone's `takeIf { showCacheFolder }`). */
    fun link(shown: Boolean): LibraryLink? = LibraryLink(
        item = ShelfItem(
            title = DesktopStrings["cached_songs", "Cached songs"],
            subtitle = DesktopStrings["cached_songs_subtitle", "Songs from YouTube and JioSaavn in the song cache"],
            thumbnailUrl = null,
            videoId = null,
            browseId = BROWSE_ID,
        ),
        icon = Icons.Rounded.Storage,
    ).takeIf { shown }

    val title: String get() = DesktopStrings["cached_songs", "Cached songs"]
    val subtitle: String get() = DesktopStrings["cached_songs_subtitle", "Songs from YouTube and JioSaavn in the song cache"]
    val empty: String
        get() = DesktopStrings[
            "cached_songs_empty",
            "No cached songs yet. Songs you play from YouTube or JioSaavn will show up here.",
        ]
}

/** "3:25" or "1:02:03" in seconds; null when it isn't one. */
internal fun forkDurationSeconds(text: String): Int? {
    val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3) return null
    return parts.fold(0) { total, part -> total * 60 + part }
}

/**
 * The page: the cached songs, read off the UI thread and again whenever one is removed; [content]
 * draws them (the window passes its Local Music list, as for Downloads). Nothing is drawn until the
 * first read is done, so an empty cache's message isn't flashed in front of a full one.
 */
@Composable
internal fun ForkCachedSongsPage(content: @Composable (List<Song>) -> Unit) {
    val version by ForkCachedSongs.version.collectAsState()
    val songs by produceState<List<Song>?>(null, version) {
        value = withContext(Dispatchers.IO) { ForkCachedSongs.songs().map { it.song } }
    }
    songs?.let { content(it) }
}

/** Storage: the phone's "Show cached songs folder", under the PC's song cache folder row. */
@Composable
internal fun ForkShowCachedSongsRow() {
    val shown by ForkCachedSongs.shown.collectAsState()
    SettingsToggle(
        DesktopStrings["show_cache_folder", "Show cached songs folder"],
        DesktopStrings[
            "show_cache_folder_subtitle",
            "Adds a Cached songs folder to Library with the YouTube and JioSaavn songs kept in the song cache",
        ],
        shown,
        ForkCachedSongs::setShown,
    )
}

/**
 * The index itself, over [directory] (the song cache's window folder), with the window size the
 * range reader keeps ([DesktopRangeStream]'s 512 KiB, which is also [ForkAudioRangeCache.MAX_SPAN]).
 * A class so the tests can point it at a folder of their own.
 */
internal class ForkCachedSongIndex(
    private val directory: () -> Path,
    private val window: Int = WINDOW,
) {
    /** One stream of a song: the four values its windows are keyed on, raw as the address had them. */
    @Serializable
    data class Stream(val id: String, val itag: String, val lmt: String, val clen: String, val mime: String? = null)

    /** What the phone's `describe` writes, plus the streams the windows came from (newest first). */
    @Serializable
    data class Entry(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val albumId: String? = null,
        val artistId: String? = null,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val streams: List<Stream> = emptyList(),
    )

    /** A row of the folder: the song, its bytes on disk, when it was last read, and whether it is whole. */
    class Listed(val song: Song, val bytes: Long, val lastUsedMs: Long, val complete: Boolean)

    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false

    private val file get() = directory().resolve(FILE)

    /** Records [song] as played from [url]; false when the address isn't a kept YouTube stream or nothing changed. */
    @Synchronized
    fun record(song: Song, url: String): Boolean {
        if (!listable(song)) return false
        val stream = streamOf(url) ?: return false
        load()
        val before = entries[song.videoId]
        val streams = (listOf(stream) + before?.streams.orEmpty().filterNot { it.sameFile(stream) }).take(MAX_STREAMS)
        val entry = Entry(
            videoId = song.videoId,
            title = song.title.ifBlank { before?.title.orEmpty() },
            artist = song.artist.ifBlank { before?.artist.orEmpty() },
            album = song.albumName?.takeIf(String::isNotBlank) ?: before?.album,
            albumId = song.albumId ?: before?.albumId,
            artistId = song.artistId ?: before?.artistId,
            thumbnailUrl = song.thumbnailUrl?.takeIf(String::isNotBlank) ?: before?.thumbnailUrl,
            durationText = song.durationText?.takeIf(String::isNotBlank) ?: before?.durationText,
            streams = streams,
        )
        if (entry == before) return false
        entries[song.videoId] = entry
        save()
        return true
    }

    /**
     * Every song with windows still on disk, most recently read first; songs with none left are
     * dropped from the index here (evicted, expired, removed, or the folder cleared).
     */
    @Synchronized
    fun list(): List<Listed> {
        load()
        val spans = spanFiles()
        val gone = mutableListOf<String>()
        val rows = entries.values.mapNotNull { entry ->
            val files = entry.streams.flatMap { spans[keyOf(it)].orEmpty() }
            if (files.isEmpty()) {
                gone += entry.videoId
                return@mapNotNull null
            }
            val bytes = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
            val touched = files.maxOf { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            val complete = entry.streams.any { whole(it, spans[keyOf(it)].orEmpty()) }
            Listed(entry.song(), bytes, touched, complete)
        }
        if (gone.isNotEmpty()) {
            gone.forEach(entries::remove)
            save()
        }
        return rows.sortedByDescending { it.lastUsedMs }
    }

    @Synchronized
    fun holds(videoId: String): Boolean {
        load()
        val entry = entries[videoId] ?: return false
        val spans = spanFiles()
        return entry.streams.any { spans[keyOf(it)].orEmpty().isNotEmpty() }
    }

    /** Deletes every window of [videoId]'s streams and its entry; the bytes freed, null when it had no entry. */
    @Synchronized
    fun remove(videoId: String): Long? {
        load()
        val entry = entries.remove(videoId) ?: return null
        val spans = spanFiles()
        val freed = entry.streams.flatMap { spans[keyOf(it)].orEmpty() }.sumOf { path ->
            runCatching { Files.size(path).also { Files.deleteIfExists(path) } }.getOrDefault(0L)
        }
        save()
        return freed
    }

    /**
     * The address and container of [videoId]'s newest stream kept whole with every window [fresh],
     * or null. The address is rebuilt from the four key values on a googlevideo host, so the range
     * reader's `keyFor` makes the same keys from it ([ForkAudioRangeCache.keyFor]).
     */
    @Synchronized
    fun keptUrl(videoId: String, fresh: (key: String, start: Long) -> Boolean): Pair<String, String?>? {
        load()
        val entry = entries[videoId] ?: return null
        val spans = spanFiles()
        val stream = entry.streams.firstOrNull { stream ->
            val key = keyOf(stream)
            whole(stream, spans[key].orEmpty()) && starts(stream).all { fresh(key, it) }
        } ?: return null
        val url = "https://$HOST/videoplayback?id=${stream.id}&itag=${stream.itag}&lmt=${stream.lmt}&clen=${stream.clen}" +
            (stream.mime?.let { "&mime=$it" } ?: "")
        val container = stream.mime?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.substringAfterLast('/')?.lowercase()
        return url to container
    }

    @Synchronized
    fun forget() {
        entries.clear()
        loaded = false
    }

    /** The window key, made exactly as [ForkAudioRangeCache.keyFor] makes it. */
    internal fun keyOf(stream: Stream): String =
        ForkAudioRangeCache.identity("gv|${stream.id}|${stream.itag}|${stream.lmt}|${stream.clen}|$window")

    /** Every window offset of [stream]'s file. */
    private fun starts(stream: Stream): List<Long> {
        val length = stream.clen.toLongOrNull()?.takeIf { it > 0 } ?: return emptyList()
        return (0 until (length + window - 1) / window).map { it * window.toLong() }
    }

    private fun whole(stream: Stream, files: List<Path>): Boolean {
        val needed = starts(stream)
        if (needed.isEmpty()) return false
        val held = files.mapNotNull { it.fileName.toString().removeSuffix(".span").substringAfterLast('-').toLongOrNull() }.toSet()
        return needed.all { it in held }
    }

    /** The folder's windows by key (`<key>-<start>.span`). */
    private fun spanFiles(): Map<String, List<Path>> = runCatching {
        Files.list(directory()).use { files ->
            files.filter { it.fileName.toString().endsWith(".span") }.toList()
        }.groupBy { it.fileName.toString().substringBefore('-') }
    }.getOrDefault(emptyMap())

    private fun Entry.song(): Song = Song(
        videoId = videoId,
        title = title.ifBlank { videoId },
        artist = artist,
        thumbnailUrl = thumbnailUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = album,
    )

    private fun Stream.sameFile(other: Stream) = id == other.id && itag == other.itag && lmt == other.lmt && clen == other.clen

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val text = Files.readString(file)
            json.decodeFromString(ListSerializer(Entry.serializer()), text).forEach { entries[it.videoId] = it }
        }
    }

    /** Written whole through a temporary file, so a crash mid-write leaves the old index. */
    private fun save() {
        runCatching {
            val dir = directory()
            Files.createDirectories(dir)
            val temporary = Files.createTempFile(dir, ".songs-", ".tmp")
            try {
                Files.writeString(temporary, json.encodeToString(ListSerializer(Entry.serializer()), entries.values.toList()))
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }.onFailure { DesktopTrackLog.log("song cache index not written: ${it.message}") }
    }

    companion object {
        const val FILE = "songs.json"
        const val WINDOW = 512 * 1024
        private const val MAX_STREAMS = 3

        /** Never contacted for a whole copy; any googlevideo host makes the same keys. */
        private const val HOST = "kept.googlevideo.com"

        private val json = Json { ignoreUnknownKeys = true }
        private val QUERY = Regex("""(?:^|&)(id|itag|lmt|clen|mime)=([^&]+)""")

        /** The phone's two kinds are YouTube and JioSaavn; the PC keeps YouTube's only, under a plain id. */
        fun listable(song: Song): Boolean =
            song.videoId.isNotBlank() && song.localUri == null && song.localPath == null &&
                !song.videoId.contains(':') && !NavidromeSettings.isNavidromeId(song.videoId)

        /** The stream's identity off a googlevideo address, or null when its windows aren't kept. */
        fun streamOf(url: String): Stream? {
            val uri = runCatching { java.net.URI.create(url) }.getOrNull() ?: return null
            if (!uri.host.orEmpty().endsWith(".googlevideo.com")) return null
            val query = QUERY.findAll(uri.rawQuery.orEmpty()).associate { it.groupValues[1] to it.groupValues[2] }
            return Stream(
                id = query["id"] ?: return null,
                itag = query["itag"] ?: return null,
                lmt = query["lmt"] ?: return null,
                clen = query["clen"] ?: return null,
                mime = query["mime"],
            )
        }
    }
}
