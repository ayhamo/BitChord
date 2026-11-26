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
 * - An editor mix's mid band, Looping lane and Effects lane (editor.md §12, §13), on each sink's [ForkMixShelf]: the mid
 *   band and the loop always; with effects or noise ([ForkV2Transition.effects]) also the fader, in the sink
 *   ([ForkMixShelf.aimFader], the players' volumes held at 1, [volumes]), so the sends are taken after it and the
 *   returns ring on after the fader is down. The blend then waits for the outgoing sink's tail ([tailDone]).
 *
 * Main thread only (the controller's tick), hence the one reused [frame].
 */
internal object ForkV2Ride {

    private val frame = ForkV2Transition.Frame()

    /**
     * Aims both sides' shelves and cuts at [progress]; under a glide also the speed of [out] / [into]
     * (null: that side is not aimed in this pass, see the controller's `rideFilters`).
     */
    fun ride(t: ForkV2Transition, progress: Float, tracks: TransitionFilters, out: ExoPlayer?, into: ExoPlayer?) {
        t.frameAt(t.mixTimeForIncoming(progress.toDouble()), frame)
        // the echo out's catch: the outgoing sink reached the blend's start (its pass only)
        t.echo?.let { e ->
            if (out != null && progress >= 0f) {
                tracks.forkEcho(false) { it.echoCatch(e.delaySec, e.beats, e.feedback, e.cutSec) }
                caughtFor = t
            }
        }
        // The cuts are the shelf's 12 dB sections, as the extension's; the 24 dB transition filters stay open.
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        tracks.forkShelves(true, frame.inLowDb, frame.inHighDb, frame.inLowPassHz, frame.inHighPassHz)
        tracks.forkShelves(false, frame.outLowDb, frame.outHighDb, frame.outLowPassHz, frame.outHighPassHz)
        val fx = t.effects
        val times = t.fxTime
        tracks.forkEcho(true) { s ->
            s.aimMid(frame.inMidDb)
            if (fx) {
                s.aimFader(frame.gainIn)
                times?.let { s.fx.times(it[0], it[1]) }
                s.fx.aim(frame.inSends[0], frame.inSends[1], frame.inSends[2], frame.inNoise)
            }
        }
        if (out != null) tracks.forkEcho(false) { s ->
            s.aimMid(frame.outMidDb)
            // the Looping lane: from the outgoing sink's mix-out on, A's first beats of the window repeat (editor.md §13)
            if (loopDue(t, progress)) s.loopStart(t.loopSongSec)
            if (fx) {
                s.aimFader(frame.gainOut)
                times?.let { s.fx.times(it[0], it[1]) }
                s.fx.aim(frame.outSends[0], frame.outSends[1], frame.outSends[2], frame.outNoise)
            }
        }
        if (!t.glides) return
        val listener = AppSettings.playbackSpeed.value
        // Set only when the player's own speed differs: compared with what was last set here, a player whose speed the
        // controller put back (retire, the ease) kept a glide's first step unset when it matched the last glide's end,
        // and the cache held released players for the process's life.
        if (out != null) aimSpeed(out, (listener * frame.outRate).toFloat())
        if (into != null) aimSpeed(into, (listener * frame.inRate).toFloat())
    }

    private fun aimSpeed(player: ExoPlayer, speed: Float) {
        if (needsSpeed(player.playbackParameters.speed, speed)) player.setPlaybackSpeed(speed)
    }

    /** Whether a player at [current] must be set to [wanted] (a glide's step), within [SPEED_EPSILON]. */
    internal fun needsSpeed(current: Float, wanted: Float): Boolean = abs(current - wanted) > SPEED_EPSILON

    /**
     * The blend stopped at [progress]: its move counts once it ran to its end (progress 1), never when called off
     * (versions.md "No effect three times in a row": "each one when the mix reaches its real end"). It counted at the
     * blend's start before, so a skip or a pause mid-blend counted a move nobody heard out.
     */
    fun ended(t: ForkV2Transition, progress: Float) =
        com.music.bitchord.playback.smart.automixv2.ForkV2Moves.ended(t, t.plan, progress >= 1f)

    /**
     * Before the blend: an echo out's outgoing sink starts keeping what it plays once it is
     * [PRIME_LEAD_SEC] from the beat before the catch ([outgoingSinkSec]: the song second that sink is
     * writing), so the beat is there at the catch.
     */
    fun prime(t: ForkV2Transition, outgoingSinkSec: Double, tracks: TransitionFilters) {
        if (!primes(t, outgoingSinkSec)) return
        tracks.forkEcho(false) { it.echoRecord() }
    }

    /**
     * [prime] from the tick before arming, while the playing song's sink is the session's own ([filters] unrouted:
     * `forkEcho(true)` is the active sink). With a sink lead of seconds (float output), arming started with the
     * sink already past the beat before the catch, so nothing was kept and the echo out played its dry cut alone.
     */
    fun primeAhead(t: ForkV2Transition, sessionSinkSec: Double, filters: TransitionFilters) {
        if (!primes(t, sessionSinkSec)) return
        filters.forkEcho(true) { it.echoRecord() }
    }

    /** Whether the outgoing sink, writing song second [sinkSec], is within [PRIME_LEAD_SEC] of the beat before [t]'s catch. */
    internal fun primes(t: ForkV2Transition, sinkSec: Double): Boolean {
        val e = t.echo ?: return false
        return sinkSec >= t.plan.mixOutTrackTime - e.delaySec - PRIME_LEAD_SEC
    }

    /** The outgoing level under an echo out ([ForkV2Transition.echoLevel]), for the controller's headroom trim. */
    fun level(t: ForkV2Transition, progress: Float): Float? = t.echoLevel(t.mixTimeForIncoming(progress.toDouble()))

    /**
     * Whether [t]'s echo out was caught, or [t] has effects (the outgoing fader is in its sink): its tail is the outgoing
     * sink's, which a park would let through dry.
     */
    fun echoed(t: ForkV2Transition?): Boolean = t != null && (caughtFor === t || t.effects)

    private var caughtFor: ForkV2Transition? = null
    private var loopedFor: ForkV2Transition? = null

    /** Whether [t]'s Looping lane starts now ([progress] at its mix-out), once per blend: noted as started. */
    internal fun loopDue(t: ForkV2Transition, progress: Float): Boolean {
        if (!(t.loopSongSec > 0) || !(progress >= 0f) || loopedFor === t) return false
        loopedFor = t
        return true
    }

    /**
     * A blend is over, however it ended (the controller's `finish`): nothing it caught, looped or waited on carries
     * into the next. The marks are by identity, and the v2 latch hands back the very same [ForkV2Transition] when a seek
     * back lands in its window again, so a replayed blend skipped its Looping lane (`loopedFor === t`) and read the
     * aborted attempt's quiet point for its tail (audit 2026-11-26 part 1b #5).
     */
    fun reset() {
        caughtFor = null
        loopedFor = null
        quietAt = null
        tailFor = null
    }

    /**
     * The players' volumes for [t] with effects: both at 1 ([into]'s from its first tick; [out] unless it ran on), the
     * faders being in the sinks ([ride]). Any other blend: left as the controller set them.
     */
    fun volumes(t: ForkV2Transition?, into: ExoPlayer, out: ExoPlayer, ranOn: Boolean) {
        if (t == null || !t.effects) return
        into.volume = 1f
        out.volume = if (ranOn) 0f else 1f
    }

    /**
     * Whether the outgoing sink's effect tails have been heard out, [pastEndMs] after the blend's end: its returns quiet
     * and then its lead played (what it wrote before going quiet), at most [TAIL_MAX_MS]. Any other blend: done.
     */
    fun tailDone(t: ForkV2Transition?, tracks: TransitionFilters, pastEndMs: Float): Boolean {
        if (t == null || !t.effects || pastEndMs >= TAIL_MAX_MS) return true
        var ringing = false
        tracks.forkEcho(false) { ringing = it.fx.ringing }
        if (ringing) {
            quietAt = null
            return false
        }
        val q = quietAt?.takeIf { tailFor === t } ?: pastEndMs.also { quietAt = it; tailFor = t }
        return pastEndMs >= q + tracks.outgoingLeadMs()
    }

    private var quietAt: Float? = null
    private var tailFor: ForkV2Transition? = null

    /** The blend whose move was last counted as heard ([com.music.bitchord.playback.smart.automixv2.ForkV2Moves]). */

    fun rise(t: ForkV2Transition, progress: Float): Float = t.frameAt(t.mixTimeForIncoming(progress.toDouble()), frame).gainIn

    fun fall(t: ForkV2Transition, progress: Float): Float = t.frameAt(t.mixTimeForIncoming(progress.toDouble()), frame).gainOut

    private const val SPEED_EPSILON = 1e-5f
    private const val PRIME_LEAD_SEC = 1.0

    /** The longest the blend waits for an effect tail past its end: a 1-beat echo at 60 BPM rings about 9 s. */
    private const val TAIL_MAX_MS = 10_000f
}
