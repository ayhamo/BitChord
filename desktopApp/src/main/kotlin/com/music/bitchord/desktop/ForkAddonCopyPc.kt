package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.ForkAddonCopyProgress
import com.music.bitchord.playback.smart.ForkAddonCopyProgress.Stage

/**
 * Fork: the PC's hooks for the progress row's addon-copy line (editor.md §11 rule 6, through
 * [ForkAddonCopyProgress]). Where each stage starts and ends in the PC's pipeline:
 *  - asking: every race over the sources ranked above YouTube (`DesktopSourceRegistry.firstSubstitute`:
 *    the race at song start, the patient second look, `upgradeFor`'s follow-ups and next rows), until
 *    it returns;
 *  - getting the copy: an HLS/DASH row put together into one file inside the race
 *    ([ForkNextRowPc.assembles]), and a progressive copy's window read by the window check
 *    ([ForkWindowCheckPc.passes]: the copy's first bytes over the network);
 *  - lining up: the window check's fit;
 *  - switching: from the copy's deck being opened (`DesktopAudioEngine.swapStream`) until it is
 *    what's heard: the engine's crossfaded swap ends ([engineStep]), the cut-over happens, or the copy
 *    is turned down on the way.
 * The upgrade pass ending (`watchForUpgrade`'s `finally`) ends whatever it left but a take-over
 * ([passEnded]): a copy refused or failed, or the song moved past.
 *
 * The audio thread only reads one volatile field per block while nothing is switching, and ends the
 * line with the state flow's own compare-and-set update: no I/O, no wait on another thread's work.
 */
internal object ForkAddonCopyPc {

    private const val YOUTUBE = "youtube"

    /** Whether the row says "the lossless copy" for [format]: its codec, or a stated bit depth. */
    fun lossless(format: DesktopStreamFormat?): Boolean =
        format != null && (format.isLossless || ForkAddonCopyProgress.losslessCopy(format.codec, format.bitDepth))

    /** A race over the addons for [songId] starts. */
    fun asking(songId: String) = ForkAddonCopyProgress.set(songId, Stage.ASKING)

    /** That race returned (a copy, nothing, or cancelled). */
    fun asked(songId: String) = ForkAddonCopyProgress.end(songId, only = ASKING)

    /** [stream]'s file is being fetched: an HLS/DASH row assembled, or a copy's window read. */
    fun fetching(songId: String, stream: DesktopStream) =
        ForkAddonCopyProgress.set(songId, Stage.FETCHING, lossless(stream.format))

    /** An HLS/DASH row is put together (or refused): the race it is part of goes on asking. */
    fun assembled(songId: String) {
        if (ForkAddonCopyProgress.stageOf(songId) == Stage.FETCHING) ForkAddonCopyProgress.set(songId, Stage.ASKING)
    }

    /** The window check fits [stream]'s window against YouTube's. */
    fun liningUp(songId: String, stream: DesktopStream) =
        ForkAddonCopyProgress.set(songId, Stage.LINING_UP, lossless(stream.format))

    /** The upgrade pass is over: anything it left but a take-over still running is ended. */
    fun passEnded(songId: String) = ForkAddonCopyProgress.end(songId, only = PASS)

    // ---- the take-over ----

    /**
     * The take-over being watched: [queuedAtNs] once its deck is handed to the audio thread, [seen]
     * once that thread held it as a pending swap.
     */
    private class Watch(val songId: String, val queuedAtNs: Long? = null, val seen: Boolean = false)

    @Volatile
    private var watch: Watch? = null

    /** `swapStream` opens [stream]'s deck for [songId]; YouTube's own copy (a revert, a reload) is no addon copy. */
    fun switching(songId: String, stream: DesktopStream) {
        if (stream.sourceId == YOUTUBE || stream.sourceId == null) return
        ForkAddonCopyProgress.set(songId, Stage.SWITCHING, lossless(stream.format))
        watch = Watch(songId)
    }

    /** The deck opened and is handed to the audio thread (`Command.ReplaceCurrent`). */
    fun queued(songId: String, nowNs: Long = System.nanoTime()) {
        val w = watch ?: return
        if (w.songId == songId) watch = Watch(songId, queuedAtNs = nowNs)
    }

    /** `swapStream`'s open is over: one that never reached the audio thread (closed, refused, failed) ends the line. */
    fun opened(songId: String) {
        val w = watch ?: return
        if (w.songId != songId || w.queuedAtNs != null) return
        watch = null
        ForkAddonCopyProgress.end(songId, only = SWITCHING)
    }

    /**
     * Audio thread, once per block: the song ids of the engine's crossing, armed and waiting swaps.
     * The line ends once the copy has been one of them and no longer is (crossed in: heard; or given
     * up), or when it never became one within [UNSEEN_MS] of being queued (cut over, kept through a
     * mix, or closed by a transition).
     */
    fun engineStep(crossing: String?, armed: String?, waiting: String?, nowNs: Long = System.nanoTime()) {
        val w = watch ?: return
        val queuedAt = w.queuedAtNs ?: return
        val pending = w.songId == crossing || w.songId == armed || w.songId == waiting
        when {
            pending -> if (!w.seen) watch = Watch(w.songId, queuedAt, seen = true)
            w.seen || nowNs - queuedAt >= UNSEEN_MS * 1_000_000L -> {
                watch = null
                ForkAddonCopyProgress.end(w.songId, only = SWITCHING)
            }
        }
    }

    /** The audio thread drains its commands every block (20 ms while paused): a queued copy not pending by then was not crossed in. */
    internal const val UNSEEN_MS = 1_500L

    /** Tests. */
    internal fun reset() {
        watch = null
    }

    private val ASKING = setOf(Stage.ASKING)
    private val SWITCHING = setOf(Stage.SWITCHING)
    private val PASS = setOf(Stage.ASKING, Stage.FETCHING, Stage.LINING_UP)
}
