package com.music.bitchord.fork

import androidx.media3.common.C
import androidx.media3.common.Player
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.navidrome.NavidromeRadio

/**
 * Fork: a station whose server closes the stream cleanly (a restart, a dropped mount, an idle
 * timeout) reads to Media3 as a track that played to its end, so the queue moved on to the next
 * row, or stopped at the end of the queue, as if a song had finished. A live stream has no end:
 * the station is opened again, up to [MAX] times a minute, then let go as before (a server that
 * keeps closing it isn't coming back). The PC does the same (`ForkRadioReconnect`).
 *
 * Called from the service's player listener, on the playback thread.
 */
object ForkRadioReopen {

    private const val TAG = "BitChordNavidrome"
    private const val MAX = 5
    private const val WINDOW_MS = 60_000L

    private val recent = ArrayDeque<Long>()

    /**
     * Every item transition. True when the queue ran on past a station that was playing: the
     * station is reopened and the caller skips its bookkeeping for a song that didn't end.
     *
     * The item left is the one before the new one in play order (shuffle and repeat included),
     * not one remembered from the last transition: a crossfade's handoff moves the session to
     * another player without a transition here, so a remembered station could be one skipped
     * away from long ago, and the next song to end would have jumped back to it.
     */
    fun onTransition(player: Player, reason: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || player.repeatMode == Player.REPEAT_MODE_ONE) return false
        val index = player.previousMediaItemIndex.takeIf { it in 0 until player.mediaItemCount } ?: return false
        val left = player.getMediaItemAt(index).mediaId
        if (!NavidromeRadio.isRadio(left) || !allowed(now)) return false
        Log.d(TAG, "radio: $left closed by its server, opening it again")
        player.seekToDefaultPosition(index)
        return true
    }

    /** The queue ended on a station; true when it is opened again (the caller skips the end-of-queue work). */
    fun onEnded(player: Player, now: Long = System.currentTimeMillis()): Boolean {
        val id = player.currentMediaItem?.mediaId
        if (!NavidromeRadio.isRadio(id) || !player.playWhenReady || !allowed(now)) return false
        Log.d(TAG, "radio: $id closed by its server, opening it again")
        val index = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return false
        player.seekToDefaultPosition(index)
        player.prepare()
        return true
    }

    private fun allowed(now: Long): Boolean {
        while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) recent.removeFirst()
        if (recent.size >= MAX) return false
        recent.addLast(now)
        return true
    }
}
