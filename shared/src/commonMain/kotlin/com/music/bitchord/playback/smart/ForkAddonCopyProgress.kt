package com.music.bitchord.playback.smart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

/**
 * Fork: where the addon copy of each song stands, for the progress row's last line before the loop
 * search (the extension's spec/automix/editor.md §11 rule 6, the user 2026-11-26; the extension's
 * `addonStage` in `src/automix-overview.js`, efb7d06). [ForkAnalysisProgress.rowFor] shows it for
 * the song now playing only, and only when that song's and the next song's analysis lines have
 * nothing to say; the next song's copy is never shown, and like the rest of the row it is hidden
 * during a live mix.
 *
 * Each app's pipeline sets the stage through one-line hooks (the phone's `ForkAddonCopyPhone`, the
 * PC's `ForkAddonCopyPc`), for any song: the read-ahead of the next song writes here too, and the
 * row's filter on the playing song is what keeps it out, so a race still running when its song
 * becomes the playing one is shown from then on. Every stage is ended by whoever set it (a `finally`
 * on each platform), and on top of that:
 *  - a song that is neither the playing one nor the next is dropped when the planner's pair moves
 *    ([keepOnly], from [ForkAnalysisProgress.notePair]);
 *  - [Stage.SWITCHING], the one stage that ends on a player's state rather than on a call returning,
 *    is not shown past [SWITCH_MAX_MS], so a take-over a hook missed the end of can't sit on the row
 *    for the rest of the song.
 *
 * The app's own source is YouTube on both apps (a Navidrome song has no addon copy to upgrade to).
 */
object ForkAddonCopyProgress {

    enum class Stage {
        /** An addon is opening the song's stream (the race at song start, the upgrade pass, a next row). */
        ASKING,

        /** The copy's file is being fetched (the phone's audition, the PC's window read or HLS/DASH assembly). */
        FETCHING,

        /** The copy is lined up with YouTube's (the window check). */
        LINING_UP,

        /** The take-over, from the copy starting until it is what's heard. */
        SWITCHING,
    }

    /** The longest [Stage.SWITCHING] is shown: the phone's cover lands in about 11 s at worst, the PC's cross in under 10. */
    const val SWITCH_MAX_MS = 20_000L

    private class Entry(val stage: Stage, val lossless: Boolean, val sinceMs: Long)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    private val origin = TimeSource.Monotonic.markNow()

    /** Milliseconds on a monotonic clock; tests move it. */
    internal var clockMs: () -> Long = { origin.elapsedNow().inWholeMilliseconds }

    /**
     * Whether a copy reads as "the lossless copy" (rule 6: FLAC, ALAC, WAV, AIFF or a stated bit
     * depth; the extension's test also takes PCM). [codec] as the source or decoder names it, bare or
     * inside a MIME type (`audio/flac`, `pcm_s24le`).
     */
    fun losslessCopy(codec: String?, bitDepth: Int?): Boolean {
        if (bitDepth != null && bitDepth > 0) return true
        val name = codec?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return LOSSLESS_PREFIXES.any { name.startsWith(it) }
    }

    /**
     * [id]'s copy reached [stage]. [lossless]: what the copy is, when this stage knows; null keeps
     * what an earlier stage said (the take-over is started from where only the song is known).
     */
    fun set(id: String, stage: Stage, lossless: Boolean? = null) {
        if (id.isBlank()) return
        var moved = false
        entries.update { map ->
            val was = map[id]
            val l = lossless ?: was?.lossless ?: false
            if (was != null && was.stage == stage && was.lossless == l) {
                moved = false
                map
            } else {
                moved = true
                map + (id to Entry(stage, l, if (was?.stage == stage) was.sinceMs else clockMs()))
            }
        }
        if (moved) ForkAnalysisProgress.changed()
    }

    /** Ends [id]'s line: the copy is heard, refused or failed, or its pass is over. Only from [only] when given. */
    fun end(id: String, only: Set<Stage>? = null) {
        var moved = false
        entries.update { map ->
            val was = map[id]
            if (was == null || (only != null && was.stage !in only)) {
                moved = false
                map
            } else {
                moved = true
                map - id
            }
        }
        if (moved) ForkAnalysisProgress.changed()
    }

    /** The stage [id]'s copy is at, or null. */
    fun stageOf(id: String?): Stage? = id?.let { entries.value[it]?.stage }

    /** Drops every song but [ids] (the planner's pair moved: the playing song and the next). */
    fun keepOnly(ids: Set<String>) {
        var moved = false
        entries.update { map ->
            val kept = map.filterKeys { it in ids }
            moved = kept.size != map.size
            kept
        }
        if (moved) ForkAnalysisProgress.changed()
    }

    /** The row's line for the playing song [id], or null when its copy has nothing to say. */
    fun lineFor(id: String?): String? {
        val e = id?.let { entries.value[it] } ?: return null
        val copy = if (e.lossless) "the lossless copy" else "the addon's copy"
        return when (e.stage) {
            Stage.ASKING -> "Asking your addons…"
            Stage.FETCHING -> "Getting $copy…"
            Stage.LINING_UP -> "Lining up $copy with YouTube's…"
            Stage.SWITCHING -> if (clockMs() - e.sinceMs > SWITCH_MAX_MS) null else "Switching to $copy…"
        }
    }

    /** Tests: start empty. */
    internal fun clear() {
        entries.value = emptyMap()
    }

    private val LOSSLESS_PREFIXES = listOf("flac", "alac", "wav", "aiff", "pcm")
}
