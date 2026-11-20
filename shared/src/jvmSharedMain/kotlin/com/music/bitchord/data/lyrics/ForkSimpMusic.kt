package com.music.bitchord.data.lyrics

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Fork: reads SimpMusic's reply as the live API sends it (checked 2026-11-20):
 * `{ "type": "success", "data": [{ "durationSeconds", "richSyncLyrics", "syncedLyrics", "plainLyric" }] }`.
 *
 * Upstream's [SimpMusicLyrics.Response] waited for a `success: true` flag and matched on `duration`;
 * the reply carries neither, so every answer was thrown away and SimpMusic never supplied a line.
 * Here success is `type == "success"` (an old `success: true` still counts), the cut is matched on
 * `durationSeconds` (else `duration`), and the text is taken word-synced first, then line-synced,
 * then plain (one line each, stamped zero, as the other unsynced sources give it).
 */
object ForkSimpMusic {

    /** Several cuts of one video can be filed; a cut more than this far from the song isn't it. */
    private const val DURATION_TOLERANCE_SECONDS = 10.0

    fun pick(body: String, durationMs: Long): List<LyricLine>? {
        val reply = runCatching { lyricsJson.decodeFromString<Reply>(body) }.getOrNull() ?: return null
        if (!(reply.type.equals("success", ignoreCase = true) || reply.success == true)) return null
        val seconds = durationMs / 1000.0
        fun lengthOf(t: Track) = t.durationSeconds ?: t.duration
        val track = reply.data.orEmpty()
            .filter { durationMs <= 0 || lengthOf(it)?.let { d -> abs(d - seconds) <= DURATION_TOLERANCE_SECONDS } ?: true }
            .minByOrNull { lengthOf(it)?.let { d -> abs(d - seconds) } ?: Double.MAX_VALUE }
            ?: return null
        return track.richSyncLyrics?.takeIf { it.isNotBlank() }?.let { EnhancedLrc.parse(it) }?.takeIf { it.isNotEmpty() }
            ?: track.syncedLyrics?.takeIf { it.isNotBlank() }?.let { LrcLib.parseLrc(it) }?.takeIf { it.isNotEmpty() }
            ?: (track.plainLyric ?: track.plainLyrics)?.takeIf { it.isNotBlank() }?.let(::plain)?.takeIf { it.isNotEmpty() }
    }

    private fun plain(text: String): List<LyricLine> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { LyricLine(timeMs = 0, text = it) }
        .toList()

    @Serializable
    data class Reply(
        val type: String? = null,
        val success: Boolean? = null,
        val data: List<Track>? = null,
    )

    @Serializable
    data class Track(
        val durationSeconds: Double? = null,
        val duration: Double? = null,
        val richSyncLyrics: String? = null,
        val syncedLyrics: String? = null,
        val plainLyric: String? = null,
        val plainLyrics: String? = null,
    )
}
