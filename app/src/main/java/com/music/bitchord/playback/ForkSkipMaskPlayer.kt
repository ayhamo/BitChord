package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Fork: the session player as seen while a crossfade skip arms.
 *
 * Media3's controller shows the next song the moment Next is pressed, while the
 * chosen song is still loading on the standby and the session is still on the
 * old player. Answering with the old song flipped the screen back until the
 * handoff, so until then this reports the chosen one, at 0:00
 * ([CrossfadeController.pendingSkipIndex]). The rest of the time it is a plain
 * pass-through to upstream's `SessionPlayer`.
 */
@UnstableApi
private class ForkSkipMaskPlayer(
    player: Player,
    private val crossfade: CrossfadeController,
) : ForwardingPlayer(player) {

    private fun skipIndex(): Int =
        crossfade.pendingSkipIndex().takeIf { it in 0 until wrappedPlayer.mediaItemCount } ?: C.INDEX_UNSET

    private val masking: Boolean get() = skipIndex() != C.INDEX_UNSET

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
