package com.music.bitchord.desktop

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ForkQueueChips
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: the PC's side of [ForkQueueChips] — a picked queue chip's songs put in
 * place of AutoPlay's rows in the window's live queue.
 *
 * The live queue is the window's own Compose state, so the window installs this
 * with its reader and writer (one hook line in `DesktopApp`, beside the queue's
 * other edits). The edit runs on the window's thread (`Dispatchers.Main`, the
 * Swing one on the desktop), where every other queue edit runs, and is
 * refused, leaving the queue alone, in a party (the server owns that queue and
 * the row isn't drawn there), under repeat-all, or once a newer tap or radio
 * has outdated it.
 *
 * Unlike the phone's top-up, the PC's appends a station without counting the
 * room left when it lands, so a top-up already in flight when a chip is
 * picked can add its few tracks after the chip's; the next top-ups read the
 * chip as their station (`DesktopAutoplay.tracksFor` hook).
 */
internal object ForkQueueChipsDesktop {

    /**
     * [blending] is the engine mid-transition: removing the row being blended into would leave
     * `DesktopQueue.afterHandoffTo` without a match, so the queue would stay on the old song while
     * the new one plays. The tap is refused then.
     */
    fun install(queue: () -> DesktopQueue, setQueue: (DesktopQueue) -> Unit, repeatAll: () -> Boolean, blending: () -> Boolean) {
        ForkQueueChips.install { candidates, isCurrent ->
            withContext(Dispatchers.Main) {
                if (!isCurrent() || blending()) return@withContext false
                if (DesktopListenTogether.state.value.inParty || repeatAll()) return@withContext false
                val now = queue()
                val current = now.current ?: return@withContext false
                val edited = withChipTail(now, candidates) ?: return@withContext false
                ForkQueueChips.anchor(current.videoId)
                setQueue(edited)
                true
            }
        }
    }

    /**
     * [queue] with AutoPlay's rows after the current song replaced by the
     * chip's, built as `DesktopAutoplay.tracksFor` builds AutoPlay rows (the
     * playing song's radio name and source carried over); null when the chip
     * has nothing that isn't already queued or played, so the old tail stays.
     */
    fun withChipTail(queue: DesktopQueue, candidates: List<Song>): DesktopQueue? {
        val current = queue.current ?: return null
        val tail = ForkQueueChips.tailFor(queue.songs, queue.index, candidates)
        if (tail.isEmpty()) return null
        return queue.withoutAutoplay().append(
            tail.map {
                it.copy(
                    radioName = current.radioName,
                    playbackSource = it.playbackSource ?: current.playbackSource,
                    playbackSourceType = if (it.playbackSource != null) it.playbackSourceType else current.playbackSourceType,
                    playbackSourceId = if (it.playbackSource != null) it.playbackSourceId else current.playbackSourceId,
                ).asQueueEntry(QueueTier.AUTOPLAY)
            },
        )
    }
}
