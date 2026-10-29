package com.music.bitchord.fork

import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.canvas.CanvasCache
import com.music.bitchord.playback.DynamicLruCacheEvictor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The video clip cache's size and its "clear" buttons (fork-only).
 *
 * Upstream's clip cache ([CanvasCache], `cache/canvas`) is a fixed 150 MB with
 * no way to empty it. Its evictor is this adjustable one instead (the same kind
 * the song cache uses), sized by [ForkSettings.clipCacheMb], and the Navidrome
 * clips can be dropped by kind: backdrops (`.background.mp4`) and cover clips
 * (`.cover.mp4`) are told apart by their URL, which is the cache key.
 */
@UnstableApi
object ForkClipCache {

    val evictor = DynamicLruCacheEvictor(ForkSettings.DEFAULT_CLIP_CACHE_MB * MB, headBytes = 0L, headBudgetBytes = 0L)

    enum class Kind(val suffix: String) { BACKDROPS(".background.mp4"), COVER_CLIPS(".cover.mp4") }

    fun applyLimit(megabytes: Int) {
        evictor.maxBytes = megabytes * MB
        runCatching { CanvasCache.cache }.getOrNull()?.let { evictor.applyNow(it) }
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
    }

    private fun String.forKind(kind: Kind) = substringBefore('?').endsWith(kind.suffix)

    private const val MB = 1024L * 1024
}
