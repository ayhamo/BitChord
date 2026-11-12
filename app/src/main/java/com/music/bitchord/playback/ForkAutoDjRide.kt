package com.music.bitchord.playback

import com.music.bitchord.playback.smart.ForkAutoDj
import com.music.bitchord.playback.smart.ForkAutoDjCurves

/**
 * Fork: the phone's side of an Original / Advanced AutoDJ mix (Settings > Automix style; see
 * `ForkAutoDj`). `CrossfadeController` hands its faders and filter ride here when the plan in flight
 * carries [com.music.bitchord.playback.smart.TransitionPlan.forkAutoDj]; the numbers are the shared
 * [ForkAutoDjCurves], so the PC plays the same mix. [ride] gets the controller's track-addressed
 * filters, so each side is aimed at its own sink's lead as the controller's own rides are.
 *
 * Main thread only (the controller's tick), hence the one reused [frame].
 */
internal object ForkAutoDjRide {

    private val frame = ForkAutoDjCurves.Frame()

    fun ride(shape: ForkAutoDj.Shape, progress: Float, tracks: TransitionFilters) {
        ForkAutoDjCurves.at(shape, progress, frame)
        // The cuts are the shelf's 12 dB sections, as the extension's; the 24 dB transition filters stay open.
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        tracks.forkShelves(true, frame.inLowDb, frame.inHighDb, frame.inLowPassHz, frame.inHighPassHz)
        tracks.forkShelves(false, frame.outLowDb, frame.outHighDb, frame.outLowPassHz, frame.outHighPassHz)
    }

    fun rise(shape: ForkAutoDj.Shape, progress: Float): Float {
        ForkAutoDjCurves.at(shape, progress, frame)
        return frame.gainIn
    }

    fun fall(shape: ForkAutoDj.Shape, progress: Float): Float {
        ForkAutoDjCurves.at(shape, progress, frame)
        return frame.gainOut
    }
}
