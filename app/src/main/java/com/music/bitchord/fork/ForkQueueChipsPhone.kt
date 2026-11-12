package com.music.bitchord.fork

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.playback.ForkQueueChips
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.fromAutoplay
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: the phone's side of [ForkQueueChips] — a picked queue chip's songs put
 * in place of AutoPlay's rows, on the playback service's own player.
 *
 * Done on the service's player rather than through the UI's MediaController so
 * that the additions and the removal are one main-thread edit, additions first,
 * so the service's AutoPlay top-up (which re-arms on each timeline change and
 * counts the AutoPlay rows ahead) never sees a short tail. A top-up that does
 * run reads the picked chip as its station (set by
 * [ForkQueueChips.select] before this is called; this anchors the playing song
 * to it), so it can't fill in from the plain radio either.
 *
 * Rows are only AutoPlay's ([fromAutoplay]): what the listener queued and the
 * album's rows are never touched. Refused, leaving the queue as it was, in a
 * party (the server owns that queue; the row isn't drawn there either), under
 * repeat-all (AutoPlay's rows are stashed for its length and put back after,
 * see `PlaybackService.stashAutoplayTracks`), or once a newer tap or radio has
 * outdated this one.
 *
 * New rows are AutoPlay entries built exactly as `loadAutoplayTracks` builds
 * them: the playing song's radio name and playback source carried over, a fresh
 * queue entry id each.
 */
object ForkQueueChipsPhone {

    /**
     * From `PlaybackService.onCreate`; [player] is read at each edit, since crossfade swaps the active player.
     * [busy] is crossfade mid-transition: the standby player holds a copy of the queue taken when the
     * transition armed and only appended rows are carried over at the handoff, so an edit then would come
     * back undone (or doubled) and could remove the very row being blended into. The tap is refused then,
     * as the service holds back its own queue edits (`QueueShuffle.isCrossfadeTransitioning`).
     */
    fun attach(player: () -> ExoPlayer?, busy: () -> Boolean) {
        // The Automix overview's Preview moves the playing song on the same active player (ForkMixPicks).
        com.music.bitchord.playback.smart.ForkMixPicks.seek = { ms -> player()?.seekTo(ms) }
        ForkQueueChips.install { candidates, isCurrent ->
            withContext(Dispatchers.Main) {
                val active = player() ?: return@withContext false
                if (!isCurrent() || busy()) return@withContext false
                replaceTail(active, candidates)
            }
        }
    }

    /** From `PlaybackService.onDestroy`. */
    fun detach() {
        ForkQueueChips.install(null)
        com.music.bitchord.playback.smart.ForkMixPicks.seek = null
    }

    private fun replaceTail(player: Player, candidates: List<com.music.bitchord.data.model.Song>): Boolean {
        if (ListenTogether.state.value.inParty) return false
        if (player.repeatMode == Player.REPEAT_MODE_ALL) return false
        val at = player.currentMediaItemIndex
        if (at < 0 || at >= player.mediaItemCount) return false
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() }
        val current = queue[at]
        val tail = ForkQueueChips.tailFor(queue, at, candidates)
        if (tail.isEmpty()) return false
        ForkQueueChips.anchor(current.videoId)
        val items = tail.map {
            it.copy(
                radioName = current.radioName,
                playbackSource = current.playbackSource,
                playbackSourceType = current.playbackSourceType,
                playbackSourceId = current.playbackSourceId,
            ).asQueueEntry(QueueTier.AUTOPLAY).toMediaItem()
        }
        // The new rows go in first and the old ones come out after: the service's top-up re-arms
        // on every timeline change and counts AutoPlay rows ahead, so removing first showed it an
        // empty tail and it went for ten more at once, found the chip's songs already queued,
        // dropped the station and asked the plain radio, whose answer put "All" back (seen on the
        // emulator, 2026-11-06). Added first, the tail it counts is never short.
        val oldCount = player.mediaItemCount
        player.addMediaItems(items)
        // Bottom up, one run of the old AutoPlay rows at a time (usually the single run at the end),
        // so the indexes ahead still hold and the service hears one removal rather than one per row.
        var end = oldCount
        while (end > at + 1) {
            var start = end
            while (start > at + 1 && player.getMediaItemAt(start - 1).fromAutoplay) start--
            if (start < end) player.removeMediaItems(start, end)
            end = start - 1
        }
        return true
    }
}
