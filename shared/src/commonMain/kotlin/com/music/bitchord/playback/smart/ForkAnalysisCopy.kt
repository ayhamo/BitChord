package com.music.bitchord.playback.smart

/**
 * Fork: a second cache entry for Automix's YouTube Opus copy of a song, `<videoId>#automix`, used when the
 * song's own entry is locked by the player.
 *
 * Media3 lets one writer hold an entry, and a read-ahead that finds it held is served from the network and
 * written nowhere (`CacheDataSource` bypasses the cache rather than failing). The player holds the next song's
 * entry as soon as it starts reading it, which is at once when the playing song is already buffered, and its
 * bytes count only once the read closes. So a song moved up with Play next, or a video swapped for its audio
 * version, got its analysis download thrown away. Measured, 2026-11-12: "Shower" and the song after it each
 * fetched their 3.5-4 MB head in under a second and held 0 bytes after ("analysis head ... came up short:
 * held=0"), the read-ahead retries every 5 s held nothing either, and neither song was analysed before it
 * was reached.
 *
 * The copy is the same Opus stream under a key the player never asks for (the cache key factory never
 * makes it), so nothing contends for it. It is tagged as an analysis-only copy like the plain one, and is
 * dropped with them.
 */
object ForkAnalysisCopy {
    const val SUFFIX = "#automix"

    fun keyFor(videoId: String): String = "$videoId$SUFFIX"

    /** Whether [cacheKey] is [videoId]'s Automix copy. */
    fun isCopy(videoId: String, cacheKey: String): Boolean = cacheKey == keyFor(videoId)
}
