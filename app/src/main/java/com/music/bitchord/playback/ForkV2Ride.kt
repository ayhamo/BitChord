package com.music.bitchord.playback

import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.automixv2.ForkV2Transition
import kotlin.math.abs

/**
 * Fork: the phone's side of an Automix v2 blend ([com.music.bitchord.playback.smart.TransitionPlan.forkV2]).
 * `CrossfadeController` hands its faders, its filter ride and (under a glide) the two players'
 * speeds here; everything else about the blend (arming, the cue, the outgoing speed-up ramp, the
 * handoff, finishing) stays the controller's own, fed by the plan's fields
 * ([com.music.bitchord.playback.smart.automixv2.ForkV2Automix.toTransitionPlan]).
 *
 * What the phone plays from the plan, and how exactly:
 * - Faders: the incoming and outgoing gain lanes of the plan's deck schedule, read at the mix time
 *   the controller's progress stands for ([ForkV2Transition.mixTimeForIncoming]: progress is the
 *   share of the incoming song played since its cue, the one clock that stops on a pause). Set as
 *   player volumes once per controller tick (about 50 ms during a blend), so the curve is followed
 *   in steps, not sample by sample.
 * - EQ and cuts: the 250 Hz / 4 kHz shelves and the 12 dB/oct low- and high-pass of each side, in
 *   the transition processor's [ForkMixShelf] (the 24 dB transition filters held open). Each side is
 *   aimed at its own sink's lead, as the controller's own rides are ([rideFilters] passes the
 *   progress its sink is writing), and the shelf glides to each aim (0.5 dB, or its Hz glide, per
 *   64 frames) so a step never clicks.
 * - Rates: a constant stretch is the controller's own (the standby set at
 *   [com.music.bitchord.playback.smart.TransitionPlan.incomingPlaybackRate], the outgoing ramp a
 *   beat at a time up to the plan's speed-up). A glide's per-beat rates come from here: each
 *   player's speed is set when the progress its sink is processing crosses a beat of the glide. Set
 *   times the listener's own speed, as the controller does.
 *
 * Main thread only (the controller's tick), hence the one reused [frame].
 */
internal object ForkV2Ride {

    private val frame = ForkV2Transition.Frame()

    /** The speed each player was last set to here, so a speed is only set when the glide moves on. */
    private var outSpeed = Float.NaN
    private var inSpeed = Float.NaN
    private var outPlayer: ExoPlayer? = null
    private var inPlayer: ExoPlayer? = null

    /**
     * Aims both sides' shelves and cuts at [progress]; under a glide also the speed of [out] / [into]
     * (null: that side is not aimed in this pass, see the controller's `rideFilters`).
     */
    fun ride(t: ForkV2Transition, progress: Float, tracks: TransitionFilters, out: ExoPlayer?, into: ExoPlayer?) {
        t.frameAt(t.mixTimeForIncoming(progress.toDouble()), frame)
        // The cuts are the shelf's 12 dB sections, as the extension's; the 24 dB transition filters stay open.
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        tracks.forkShelves(true, frame.inLowDb, frame.inHighDb, frame.inLowPassHz, frame.inHighPassHz)
        tracks.forkShelves(false, frame.outLowDb, frame.outHighDb, frame.outLowPassHz, frame.outHighPassHz)
        if (!t.glides) return
        val listener = AppSettings.playbackSpeed.value
        if (out != null) {
            val speed = (listener * frame.outRate).toFloat()
            if (outPlayer !== out || abs(outSpeed - speed) > SPEED_EPSILON) {
                out.setPlaybackSpeed(speed)
                outSpeed = speed
                outPlayer = out
            }
        }
        if (into != null) {
            val speed = (listener * frame.inRate).toFloat()
            if (inPlayer !== into || abs(inSpeed - speed) > SPEED_EPSILON) {
                into.setPlaybackSpeed(speed)
                inSpeed = speed
                inPlayer = into
            }
        }
    }

    fun rise(t: ForkV2Transition, progress: Float): Float = t.frameAt(t.mixTimeForIncoming(progress.toDouble()), frame).gainIn

    fun fall(t: ForkV2Transition, progress: Float): Float = t.frameAt(t.mixTimeForIncoming(progress.toDouble()), frame).gainOut

    private const val SPEED_EPSILON = 1e-5f
}
