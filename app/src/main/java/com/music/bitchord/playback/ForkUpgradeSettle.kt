package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.delay

/**
 * Fork: the phone's swap onto a better copy waits for the player to stand still and be ready (phone audit 2026-11-27
 * P1-4, `switchWaitsForPlay`: play, `!moving && ready`). A swap made while a seek or a stall is buffering lands its
 * cut at a position the player is still moving to, and the window check's offset is measured against a position that
 * isn't heard yet. A paused player is still: its cut isn't heard ([ForkUpgradeCover], "paused, so the cut is not heard").
 */
internal object ForkUpgradeSettle {
    private const val POLL_MS = 100L
    /** Bounded, so a stall that never ends can't hold the upgrade forever; the swap's own check then shelves it. */
    private const val WAIT_MS = 10_000L

    /** Seeking or stalled: buffering, or not yet prepared. */
    fun moving(state: Int?): Boolean = state == Player.STATE_BUFFERING

    suspend fun await(mediaId: String, state: suspend () -> Int?) {
        var waited = 0L
        while (moving(state()) && waited < WAIT_MS) {
            if (waited == 0L) TrackLog.d("BitChord", "upgrade for $mediaId waits for the player to settle")
            delay(POLL_MS)
            waited += POLL_MS
        }
    }
}
