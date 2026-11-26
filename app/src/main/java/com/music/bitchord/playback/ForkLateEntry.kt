package com.music.bitchord.playback

import com.music.bitchord.playback.smart.TransitionPlan

/**
 * Fork: B's late entry on the phone (mix-file.md rule 2 [all], `window.in.delaySec`; the PC's crossfade.js
 * `inStartOf`). B stays silent and its player held [com.music.bitchord.playback.smart.automixv2.ForkV2Transition.incomingHoldSeconds]
 * into the blend, then starts at its mix-in; the curves still run from the blend's start.
 *
 * [CrossfadeController.driveFade] runs a blend off the incoming player's position (so a pause parks it), and a held
 * player's position doesn't move. So while B is held the blend runs on the outgoing player's clock: A's media time
 * since the blend started, times incoming over outgoing rate, is the incoming media time the blend has run (the
 * listener's speed is on both and cancels). Once that reaches the hold B is started, and from then on the blend is
 * B's own position from its cue plus what the hold ran: the axis [com.music.bitchord.playback.smart.automixv2.ForkV2Transition.incomingSongSeconds]
 * counts, B's song time from the mix's start as if it had played from before its mix-in.
 */
internal class ForkLateEntry {

    /** Incoming media ms the hold lasts; 0 without one. */
    private var holdMs = 0.0
    private var outAtStart = 0.0

    /** Incoming media ms the blend had run when B started. */
    private var ranMs = 0.0

    /** Incoming media ms per output ms (B's rate), for B's 10 ms rise in [heardRise]. */
    private var inRate = 1.0

    /** B was started ([release]): [heardRise] follows its rise from [ranMs]. */
    private var released = false

    /** B is held: its player stays paused (the controller's pause mirror leaves it). */
    var holding = false
        private set

    /** The hold ended at the last [elapsed]: the controller starts B's player ([takeStart]). */
    private var startDue = false

    /** At the blend's start, A at [outPosMs]: whether B may play now (false: held, B's late entry). */
    fun begin(plan: TransitionPlan?, outPosMs: Double): Boolean =
        begin(plan?.forkV2?.incomingHoldSeconds ?: 0.0, plan?.incomingPlaybackRate ?: 1.0, outPosMs)

    /** [begin] for a hold of [holdSec] output seconds, B at [inRate]. */
    fun begin(holdSec: Double, inRate: Double, outPosMs: Double): Boolean {
        reset()
        if (!(holdSec > 0)) return true
        this.inRate = inRate.coerceAtLeast(0.01)
        holdMs = holdSec * 1000.0 * this.inRate
        outAtStart = outPosMs
        holding = true
        return false
    }

    /**
     * The incoming media ms the blend has run: [inElapsedMs] (B's position less its cue) without a late entry; A's
     * clock ([outPosMs], at [mediaRatio] incoming over outgoing) while B is held; then B's own on from what the hold ran.
     * When the hold is over, B is due to start ([takeStart]).
     */
    fun elapsed(inElapsedMs: Double, outPosMs: Double, mediaRatio: Double): Double {
        if (!(holdMs > 0)) return inElapsedMs
        if (holding) {
            val ran = ((outPosMs - outAtStart) * mediaRatio).coerceAtLeast(0.0)
            if (ran < holdMs) return ran
            startDue = release(ran)
            return ran
        }
        return ranMs + inElapsedMs.coerceAtLeast(0.0)
    }

    /** Whether B's player is to be started now (once, after the hold ended in [elapsed]). */
    fun takeStart(): Boolean = startDue.also { startDue = false }

    /**
     * B starts now ([ran]: the incoming media ms the blend has run; the hold's length when not known). True when it
     * was held: the caller starts its player.
     */
    fun release(ran: Double = holdMs): Boolean {
        if (!holding) return false
        holding = false
        released = true
        ranMs = ran
        return true
    }

    /**
     * B's gain as heard, for the blend's headroom trim: 0 while it is held (silent whatever the curve says), then its
     * 10 ms rise ([ForkMixShelf.LATE_RISE_SEC]) up to its curve's [rise], then the curve. The trim read the curve alone,
     * which is part-way up during the hold, so A playing on its own was ducked by up to ~2 dB (1 / sqrt(rise + fall)):
     * past playback.md's 1 dB of the curve (audit 2026-11-26 part 1b #7). And B's entry is a step in its gain, so a
     * trim that read the curve from the release on stepped A down at once; mix-file.md rule 2 [all] "A while B is held"
     * (extension 6dcaf08) works the trim out from B's heard gain, heard = clamp((t − inDelaySec) / 0.01, 0, 1), so
     * there is no separate ramp. [elapsedMs]: the incoming media ms the blend has run ([elapsed]).
     */
    fun heardRise(rise: Float, elapsedMs: Double): Float {
        if (holding) return 0f
        if (!released) return rise
        val riseMs = ForkMixShelf.LATE_RISE_SEC * 1000.0 * inRate
        return rise * ((elapsedMs - ranMs) / riseMs).coerceIn(0.0, 1.0).toFloat()
    }

    fun reset() {
        holdMs = 0.0
        outAtStart = 0.0
        ranMs = 0.0
        holding = false
        released = false
        inRate = 1.0
        startDue = false
    }
}
