package com.music.bitchord.fork

import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.canvas.CanvasCache
import com.music.bitchord.playback.DynamicLruCacheEvictor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The video clip cache's size and its "clear" buttons (fork-only).
 *
 * Upstream's clip cache ([CanvasCache], `cache/canvas`) is a fixed 150 MB with
 * no way to empty it. Its evictor is this adjustable one instead (the same kind
 * the song cache uses), sized by [ForkSettings.clipCacheMb], and it can be
 * emptied by kind, the same two kinds as the player's switches, told apart by
 * the URL (the cache key): "Backdrop videos" are the server's backdrops
 * (`.background.mp4`) and Spotify's Canvas (`.cnvs.mp4` from Spotify's CDN);
 * "Animated covers" are everything else, the server's cover clips
 * (`.cover.mp4`) and the motion covers from Apple Music and the other services.
 */
@UnstableApi
object ForkClipCache {

    val evictor = DynamicLruCacheEvictor(ForkSettings.DEFAULT_CLIP_CACHE_MB * MB, headBytes = 0L, headBudgetBytes = 0L)

    enum class Kind { BACKDROPS, COVER_CLIPS }

    /** Bumped after every clear, so each row showing a size (they appear twice in Settings) measures again. */
    val cleared = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun applyLimit(megabytes: Int) {
        evictor.maxBytes = megabytes * MB
        // Evicting is disk work (called from the settings slider, on the main thread).
        scope.launch { runCatching { CanvasCache.cache }.getOrNull()?.let { evictor.applyNow(it) } }
    }

    /** Bytes the cache holds for [kind]. */
    suspend fun size(kind: Kind): Long = withContext(Dispatchers.IO) {
        val cache = runCatching { CanvasCache.cache }.getOrNull() ?: return@withContext 0L
        cache.keys.filter { it.forKind(kind) }.sumOf { cache.getCachedBytes(it, 0, Long.MAX_VALUE) }
    }

    /** Drops every [kind] clip; the next play fetches it again. */
    suspend fun clear(kind: Kind) = withContext(Dispatchers.IO) {
        val cache = runCatching { CanvasCache.cache }.getOrNull() ?: return@withContext
        cache.keys.filter { it.forKind(kind) }.forEach { key -> runCatching { cache.removeResource(key) } }
        // And the "no clip" notes, so a clip added on the server since shows now, not in up to a week.
        com.music.bitchord.data.navidrome.NavidromeClips.forgetMisses(
            if (kind == Kind.BACKDROPS) com.music.bitchord.data.navidrome.NavidromeClips.Kind.BACKDROP
            else com.music.bitchord.data.navidrome.NavidromeClips.Kind.COVER,
        )
        cleared.value++
    }

    private fun String.forKind(kind: Kind): Boolean {
        val path = substringBefore('?')
        val backdrop = path.endsWith(".background.mp4") || path.endsWith(".cnvs.mp4") || "scdn.co/" in path
        return (kind == Kind.BACKDROPS) == backdrop
    }

    /**
     * "Clear song cache" takes these with it: every clip of both kinds and the
     * lyrics cache, so all four caches start again together. "Available offline"
     * keeps its own copies, untouched.
     */
    fun clearWithSongs() {
        scope.launch {
            Kind.entries.forEach { clear(it) }
            com.music.bitchord.data.navidrome.NavidromeLyrics.clearCache()
            cleared.value++
        }
    }

    private const val MB = 1024L * 1024
}
