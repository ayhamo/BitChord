package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import com.music.bitchord.data.navidrome.NavidromeClips
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork: the video clip cache's size and its "clear" buttons, the phone's `ForkClipCache` over
 * [DesktopCanvasCache]'s files.
 *
 * Those files are named by a hash of the address, which can't be read back into a kind, so a backdrop
 * is now saved as `<hash>.backdrop.mp4`; the kinds are the phone's, told apart by the same address
 * rules. A backdrop saved before this has the plain name: it counts as an animated cover until it
 * is evicted or cleared, and is fetched once more under the new name.
 *
 * Only `.mp4` files directly in [DesktopMediaCache.directory] are clips: the folder also holds
 * translated lyrics and the stream resolver's files. Kept offline clips live in the data folder
 * (`ForkOffline`), so nothing here can reach them; playing a kept clip leaves a copy here, and only
 * that copy goes.
 */
internal object ForkClipCache {

    enum class Kind(val notes: NavidromeClips.Kind) {
        BACKDROPS(NavidromeClips.Kind.BACKDROP),
        COVER_CLIPS(NavidromeClips.Kind.COVER),
    }

    private const val BACKDROP_EXTENSION = "backdrop.mp4"
    private const val MB = 1024L * 1024

    /** Bumped when the cache shrinks, so each row showing a size measures again. */
    val cleared = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The file extension [DesktopCanvasCache] saves this address's clip under. */
    fun extension(url: String): String {
        val path = url.substringBefore('?')
        val backdrop = path.endsWith(".background.mp4") || path.endsWith(".cnvs.mp4") || "scdn.co/" in path
        return if (backdrop) BACKDROP_EXTENSION else "mp4"
    }

    fun isClip(path: Path): Boolean = path.fileName.toString().endsWith(".mp4")

    /** Evicting is disk work, and this is called from a Settings row on the main thread. */
    fun applyLimit() {
        scope.launch {
            trim()
            cleared.value++
        }
    }

    /**
     * Drops the least recently used clips until they fit the limit. The newest is never dropped:
     * it is the one just written, about to be opened, even when it alone is over the limit.
     */
    fun trim() {
        val limit = ForkNavidromeSettings.clipCacheMb.value * MB
        val clips = clips()
        var total = clips.sumOf { sizeOf(it) }
        if (total <= limit) return
        clips
            .sortedBy { path ->
                runCatching {
                    Files.readAttributes(path, BasicFileAttributes::class.java).lastAccessTime().toMillis()
                }.getOrDefault(0L)
            }
            .dropLast(1)
            .forEach { path ->
                if (total <= limit) return
                val size = sizeOf(path)
                if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) total -= size
            }
    }

    /** Bytes the cache holds for [kind]. */
    suspend fun size(kind: Kind): Long = withContext(Dispatchers.IO) { clips(kind).sumOf { sizeOf(it) } }

    /**
     * Drops every [kind] clip; the next play fetches it again. Windows won't delete the file a
     * decoder has open, so the clip on screen stays and the row's size says so.
     */
    suspend fun clear(kind: Kind) = withContext(Dispatchers.IO) {
        clips(kind).forEach { runCatching { Files.deleteIfExists(it) } }
        // And the "no clip" notes, so a clip added on the server since shows now, not in up to a week.
        NavidromeClips.forgetMisses(kind.notes)
        cleared.value++
    }

    /** "Clear song cache" has just emptied the folder, clips included: on the phone it takes the notes too. */
    fun afterSongCacheCleared() {
        scope.launch {
            Kind.entries.forEach { NavidromeClips.forgetMisses(it.notes) }
            cleared.value++
        }
    }

    private fun clips(kind: Kind): List<Path> =
        clips().filter { it.fileName.toString().endsWith(".$BACKDROP_EXTENSION") == (kind == Kind.BACKDROPS) }

    private fun clips(): List<Path> {
        val all: List<Path> = runCatching {
            Files.list(DesktopMediaCache.directory).use { stream ->
                stream.filter(Files::isRegularFile).toList()
            }
        }.getOrDefault(emptyList())
        return all.filter { isClip(it) }
    }

    private fun sizeOf(path: Path): Long = runCatching { Files.size(path) }.getOrDefault(0L)
}

/** After "Clear image cache" in Storage, as on the phone: clips aren't only the server's, so not in the "Navidrome" group. */
@Composable
internal fun ForkClipCacheRows() {
    val mb by ForkNavidromeSettings.clipCacheMb.collectAsState()
    SettingsRow(
        Icons.Rounded.VideoLibrary,
        "Video clip cache",
        "Disk space for cover clips and backdrops (click to change): ${formatCacheSize(mb)}",
    ) {
        val choices = ForkNavidromeSettings.CLIP_CACHE_CHOICES_MB
        ForkNavidromeSettings.setClipCacheMb(choices[(choices.indexOf(mb) + 1) % choices.size])
    }
    ForkClearClipsRow(ForkClipCache.Kind.BACKDROPS, "Clear backdrop videos")
    ForkClearClipsRow(ForkClipCache.Kind.COVER_CLIPS, "Clear animated covers")
}

/** Drops one kind of clip, showing what it holds now. */
@Composable
private fun ForkClearClipsRow(kind: ForkClipCache.Kind, title: String) {
    val scope = rememberCoroutineScope()
    val cleared by ForkClipCache.cleared.collectAsState()
    val bytes by produceState(0L, cleared) { value = ForkClipCache.size(kind) }
    SettingsRow(Icons.Rounded.DeleteSweep, title, "${forkClipSize(bytes)} on this PC") {
        scope.launch { ForkClipCache.clear(kind) }
    }
}

/** Under a megabyte in KB, as the phone's rows do: a short clip would read "0.0 MB". */
private fun forkClipSize(bytes: Long): String =
    if (bytes in 1 until 1048576L) "${(bytes + 1023) / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)
