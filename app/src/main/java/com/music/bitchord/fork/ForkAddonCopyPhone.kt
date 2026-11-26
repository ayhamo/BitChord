package com.music.bitchord.fork

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.smart.ForkAddonCopyProgress
import com.music.bitchord.playback.smart.ForkAddonCopyProgress.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fork: the phone's hooks for the progress row's addon-copy line (editor.md §11 rule 6, through
 * [ForkAddonCopyProgress]). Where each stage starts and ends in the phone's pipeline:
 *  - asking: the race when a song is opened (`PlaybackService`'s resolving data source: a YouTube
 *    song raced against the addons ranked above it, a picked addon row), the upgrade pass's search
 *    (`QualityUpgrade.lookAgain`) and the next row after a refusal ([ForkNextRowPhone.next]); each
 *    ends when its call returns;
 *  - getting the copy: upstream's audition (`PlaybackService.auditionUpgrade`), which fills the
 *    copy's cache entry from the swap point on;
 *  - lining up: the window check ([com.music.bitchord.playback.ForkWindowCheckPhone.passes]);
 *  - switching: from the take-over's start ([com.music.bitchord.playback.ForkUpgradeCover.raise])
 *    until the copy is heard: the cover faded back onto it, or, with no cover, the player playing the
 *    swapped item ([watchCut]).
 * The upgrade pass ending ends whatever it left but a take-over still running ([passEnded]): a copy
 * refused or failed, or the song moved past.
 *
 * Every song writes here, the next one's read-ahead too; the row shows the playing song's only.
 */
object ForkAddonCopyPhone {

    /** Whether the row says "the lossless copy" for [format]: its codec, or a stated bit depth. */
    fun lossless(format: StreamFormat?): Boolean =
        format != null && (format.isLossless == true || ForkAddonCopyProgress.losslessCopy(format.codec, format.bitDepth))

    /** [block] runs while [mediaId] reads "Asking your addons…"; ended when it returns or throws. */
    suspend fun <T> asking(mediaId: String?, block: suspend () -> T): T {
        if (mediaId == null) return block()
        ForkAddonCopyProgress.set(mediaId, Stage.ASKING)
        try {
            return block()
        } finally {
            ForkAddonCopyProgress.end(mediaId, only = ASKING)
        }
    }

    /** The audition is fetching [format]'s file into the cache. */
    fun fetching(mediaId: String, format: StreamFormat?) =
        ForkAddonCopyProgress.set(mediaId, Stage.FETCHING, lossless(format))

    /** The window check is lining the copy up with YouTube's. */
    fun liningUp(mediaId: String, format: StreamFormat?) =
        ForkAddonCopyProgress.set(mediaId, Stage.LINING_UP, lossless(format))

    /** The take-over starts. */
    fun switching(mediaId: String) = ForkAddonCopyProgress.set(mediaId, Stage.SWITCHING)

    /** The copy is what's heard, or the take-over is over without it. */
    fun switched(mediaId: String) = ForkAddonCopyProgress.end(mediaId, only = SWITCHING)

    /** The upgrade pass is over: anything it left but a take-over still running is ended. */
    fun passEnded(mediaId: String) = ForkAddonCopyProgress.end(mediaId, only = PASS)

    /**
     * With no cover, upstream's swap cuts (`replaceMediaItem`, a seek, `prepare`) right after the
     * cover was turned down, on the same main thread. Watched from here, on [scope]'s main thread:
     * the line ends once the player plays an item other than the one it played at the start (the
     * swapped copy is heard), when the song moves on, when the swap didn't happen ([cutDone]), or
     * at [CUT_MAX_MS].
     */
    fun watchCut(scope: CoroutineScope, active: () -> ExoPlayer?, mediaId: String) {
        scope.launch(Dispatchers.Main) {
            val startUri = active()?.currentMediaItem?.localConfiguration?.uri
            val startedAt = SystemClock.elapsedRealtime()
            try {
                while (true) {
                    val p = active()
                    val item = p?.currentMediaItem
                    val done = cutDone(
                        sameSong = item?.mediaId == mediaId,
                        swapped = item?.localConfiguration?.uri != startUri,
                        playing = p?.isPlaying == true,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                    if (done) break
                    delay(TICK_MS)
                }
            } finally {
                switched(mediaId)
            }
        }
    }

    /**
     * Whether a cut's watch is over: the song moved on, the swapped copy plays, the swap didn't come
     * within [NO_SWAP_MS] (upstream's swap returned early: a transition, the queue moved on, the
     * stream dropped), or [CUT_MAX_MS] went by.
     */
    internal fun cutDone(sameSong: Boolean, swapped: Boolean, playing: Boolean, elapsedMs: Long): Boolean = when {
        !sameSong -> true
        swapped && playing -> true
        !swapped && elapsedMs >= NO_SWAP_MS -> true
        else -> elapsedMs >= CUT_MAX_MS
    }

    /** Upstream's swap runs in the same main-thread block that follows the cover being turned down: a second is ample. */
    internal const val NO_SWAP_MS = 1_000L

    /** A copy from the warmed cache plays in about a second; the cover's own wait for it is 10 s. */
    internal const val CUT_MAX_MS = 10_000L

    private const val TICK_MS = 100L

    private val ASKING = setOf(Stage.ASKING)
    private val SWITCHING = setOf(Stage.SWITCHING)
    private val PASS = setOf(Stage.ASKING, Stage.FETCHING, Stage.LINING_UP)
}
