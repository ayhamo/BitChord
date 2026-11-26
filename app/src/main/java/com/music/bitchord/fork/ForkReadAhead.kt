package com.music.bitchord.fork

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.ForkReadAheadWindow
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.MusicOnlyPlayback
import com.music.bitchord.playback.StreamChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: the rulebook's read-ahead (protocol.md 8.1.3; the owner's decision 2026-11-20, no longer a kept
 * difference) in BitChord's normal mode. The next song's addon `/stream` call and its audio start only
 * [ForkReadAheadWindow.LEAD_MS] before the transition (the planned crossfade or Automix start, else the
 * song's end), so a skip never spends an addon's `/stream` allowance on a song that isn't heard.
 *
 * Upstream's normal mode raced the addons whenever ExoPlayer prepared the next item, which can be
 * minutes ahead. Direct Source Stream already held its opens to this window ([DirectSourceGate]); this
 * holds normal mode's the same way, only where an addon would be asked:
 *  - a YouTube song about to be raced (`resolveWithModulePriority`), with an addon ranked above
 *    YouTube; a song served from the cache, from a remembered choice ([StreamChoice]) or pinned to
 *    YouTube never gets that far, so it is never held;
 *  - a picked addon row (`bitchord://source…`), which only an addon can serve.
 * The playing song (and the one blending in) is never held, and the next one is released once the
 * window opens, ticked from `PlaybackService.driveDirectWindow` every 500 ms like Direct mode's.
 *
 * BitChord has no search-only look-ahead: the next song's search runs with its `/stream`, inside the
 * window (the rulebook allows the search earlier; it doesn't ask for it).
 *
 * Also the end of "Upgrade quality" (8.1.7, [ForkUpgradeByHand]): a song the listener asked to
 * upgrade has its cache entries and remembered choice dropped once it stops playing.
 */
object ForkReadAhead {

    private const val TAG = "BitChord"

    private val open = MutableStateFlow(ForkReadAheadWindow.Open())

    /** Whether normal mode holds anything: Direct Source Stream holds its own, and with no addon nothing is asked. */
    private fun gates(row: Boolean): Boolean {
        if (DirectSourceGate.isActive()) return false
        return if (row) {
            SourceRegistry.activeForPlayback().any { it.kind == SourceKind.ADDON }
        } else {
            SourceResolver.directAddons().isNotEmpty()
        }
    }

    /**
     * Holds [mediaId]'s open until it plays or its window opens. [row]: a picked addon row, held while
     * any addon is on (the ranking against YouTube doesn't apply to it).
     */
    suspend fun awaitOpen(mediaId: String, row: Boolean = false) {
        if (open.value.admits(mediaId) || !gates(row)) return
        if (!row && StreamChoice.substitutesRefused(mediaId)) return
        TrackLog.d(TAG, "holding $mediaId's addon lookup until it is within ${ForkReadAheadWindow.LEAD_MS / 1000}s of playing", about = mediaId)
        combine(open, AppSettings.directSourceStream) { state, _ -> state.admits(mediaId) || !gates(row) }
            .first { it }
        TrackLog.d(TAG, "released $mediaId's addon lookup", about = mediaId)
    }

    /**
     * From `PlaybackService.driveDirectWindow`, on the main thread every tick: what is audible, and
     * whether the next song's window has opened. [boundaryMs]: the planned transition, null for none.
     * [settled]: the playhead has run on its own since the last seek, so a scrub to the end of a song
     * opens nothing.
     */
    fun drive(player: ExoPlayer, audible: Set<String>, boundaryMs: Long?, settled: Boolean) {
        ForkAddonFloorPhone.seenYouTube(player.currentMediaItem?.mediaId)
        ForkUpgradeByHand.onAudible(audible)
        val now = open.value
        var next = if (now.playing == audible) now else now.playing(audible)
        if (player.isPlaying && settled) {
            val index = player.nextMediaItemIndex
            val duration = player.duration
            if (index != C.INDEX_UNSET && duration != C.TIME_UNSET && duration > 0L) {
                val item = player.getMediaItemAt(index)
                val remaining = ForkReadAheadWindow.remainingMs(
                    boundaryMs ?: duration,
                    player.currentPosition,
                    player.playbackParameters.speed,
                )
                if (!MusicOnlyPlayback.wants(item) && ForkReadAheadWindow.due(remaining) && !next.admits(item.mediaId)) {
                    next = next.opening(item.mediaId)
                    if (gates(row = false) || gates(row = true)) {
                        TrackLog.d(TAG, "read-ahead window open: ${item.mediaId} with ${remaining}ms to the transition", about = item.mediaId)
                    }
                }
            }
        }
        if (next != now) open.value = next
    }
}

/**
 * Fork: "Upgrade quality" (protocol.md 8.1.7, 37ee7b4) forgets the song's files and its ticket too, not
 * only its search and `/stream` answers (`SourceRegistry.clearCompletedAddonTrackCalls`, upstream's),
 * or a replay brings the same file straight back off the disk cache.
 *
 * On the phone the action is offered while YouTube's own copy plays (or after a revert), and that copy
 * is cached under the song's `#alt` entry while an addon ranks above YouTube: replayed, it is read off
 * disk without a race, and the upgrade has to be found and swapped in again mid-song. The entries can't
 * go while the song plays (the player reads them, and a better copy's audition and cover read the old
 * one), so they go once it stops being audible: every cache entry of the song ([AudioCache.discard]; a
 * download is a file of its own and stays) and the choice that pinned it ([StreamChoice.forget]).
 * The next play races afresh, against addons whose answers were cleared.
 */
object ForkUpgradeByHand {

    private val asked = ConcurrentHashMap<String, Uri>()
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** From `PlaybackService.upgradeQualityNow`: the listener asked for [mediaId], playing [uri]. */
    fun asked(mediaId: String, uri: Uri?) {
        if (uri?.getQueryParameter("v") == null) return
        ForkNextRowPhone.forgetSong(mediaId, uri) // its refused rows can be opened again (8.1.7 drops the ticket)
        asked[mediaId] = uri
        TrackLog.d("BitChord", "upgrade by hand: $mediaId's cached copy and choice go once it stops playing", about = mediaId)
    }

    internal fun onAudible(audible: Set<String>) {
        if (asked.isEmpty()) return
        for ((mediaId, uri) in asked.entries.toList()) {
            if (mediaId in audible) continue
            if (!asked.remove(mediaId, uri)) continue
            io.launch {
                StreamChoice.forget(uri.getQueryParameter("v") ?: mediaId)
                AudioCache.discard(uri)
            }
        }
    }
}
