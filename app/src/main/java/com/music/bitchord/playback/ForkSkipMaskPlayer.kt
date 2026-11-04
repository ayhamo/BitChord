package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Fork: the session player as seen while a crossfade skip arms.
 *
 * Media3's controller shows the next song the moment Next is pressed, while the
 * chosen song is still loading on the standby and the session is still on the
 * old player. Answering with the old song flipped the screen back until the
 * handoff, so until then this reports the chosen one, at 0:00
 * ([ForkCrossfadeSkip.pendingSkipIndex]). The rest of the time it is a plain
 * pass-through to upstream's `SessionPlayer`.
 *
 * Two things follow from reporting a song the player isn't on yet (L10):
 * - A seek in the song shown (the seek bar, seek back / forward) means that
 *   song. Passed through, it went to the old song still playing underneath,
 *   whose jump then dropped the skip: Next, drag the bar, and back in the
 *   previous song. While masking it carries the skip out plainly at that
 *   position instead ([ForkCrossfadeSkip.carryOut]).
 * - The mask switching on or off is no event of the wrapped player's, so the
 *   session's other controllers (the notification, Android Auto, the widget)
 *   kept the old song until some unrelated event or the handoff. The session's
 *   listeners are told the item changed ([ForkCrossfadeSkip.onMaskChanged]).
 */
@UnstableApi
private class ForkSkipMaskPlayer(
    player: Player,
    private val crossfade: CrossfadeController,
) : ForwardingPlayer(player) {

    /** The session's own listeners (Media3's), told when the mask switches; see the class doc. */
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    init {
        crossfade.forkSkip.onMaskChanged = ::announce
    }

    private fun skipIndex(): Int =
        crossfade.forkSkip.pendingSkipIndex().takeIf { it in 0 until wrappedPlayer.mediaItemCount } ?: C.INDEX_UNSET

    private val masking: Boolean get() = skipIndex() != C.INDEX_UNSET

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    /** The current item and its metadata, as this player now reports them, to every listener. */
    private fun announce() {
        val item = currentMediaItem
        val metadata = mediaMetadata
        listeners.forEach {
            it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            it.onMediaMetadataChanged(metadata)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!crossfade.forkSkip.carryOut(positionMs)) super.seekTo(positionMs)
    }

    override fun seekToDefaultPosition() {
        if (!crossfade.forkSkip.carryOut(0L)) super.seekToDefaultPosition()
    }

    // The song shown stands at 0:00: back stays at its start, forward goes one increment in.
    override fun seekBack() {
        if (!crossfade.forkSkip.carryOut(0L)) super.seekBack()
    }

    override fun seekForward() {
        if (!masking || !crossfade.forkSkip.carryOut(seekForwardIncrement)) super.seekForward()
    }

    override fun getCurrentPosition(): Long = if (masking) 0L else super.getCurrentPosition()

    override fun getContentPosition(): Long = if (masking) 0L else super.getContentPosition()

    override fun getBufferedPosition(): Long = if (masking) 0L else super.getBufferedPosition()

    override fun getDuration(): Long = if (masking) C.TIME_UNSET else super.getDuration()

    override fun getContentDuration(): Long = if (masking) C.TIME_UNSET else super.getContentDuration()

    override fun getCurrentMediaItemIndex(): Int =
        skipIndex().takeIf { it != C.INDEX_UNSET } ?: super.getCurrentMediaItemIndex()

    override fun getCurrentPeriodIndex(): Int =
        skipIndex().takeIf { it != C.INDEX_UNSET } ?: super.getCurrentPeriodIndex()

    override fun getCurrentMediaItem(): MediaItem? =
        skipIndex().takeIf { it != C.INDEX_UNSET }?.let { wrappedPlayer.getMediaItemAt(it) } ?: super.getCurrentMediaItem()

    override fun getMediaMetadata(): MediaMetadata =
        skipIndex().takeIf { it != C.INDEX_UNSET }?.let { wrappedPlayer.getMediaItemAt(it).mediaMetadata } ?: super.getMediaMetadata()
}

/** See [ForkSkipMaskPlayer]. */
@UnstableApi
internal fun Player.forkMasked(crossfade: CrossfadeController): Player = ForkSkipMaskPlayer(this, crossfade)

/** The item Previous goes to, or unset when it only restarts this one (Media3's 3 s rule). */
internal fun Player.forkPreviousTarget(): Int =
    if (currentPosition <= maxSeekToPreviousPosition) previousMediaItemIndex else C.INDEX_UNSET
