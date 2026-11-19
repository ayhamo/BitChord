package com.music.bitchord.playback.smart.automixv2

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Fork: one Automix v2 blend as the players receive it, carried on
 * [com.music.bitchord.playback.smart.TransitionPlan.forkV2]. It holds the ported planner's
 * [plan] and the [mix] built from it ([ForkV2Mix.blend], the extension's crossfade.js
 * scheduling), and answers the only questions a player asks while it plays: where on the mix clock
 * a progress fraction is ([mixTime], [mixTimeForIncoming]), what both decks' faders, EQ, cuts and
 * rates are there ([frameAt]), how the outgoing song is sped up before the blend
 * ([outgoingRampRate]) and how the incoming song gets back to its own tempo after it ([easeBack]).
 *
 * Why the players read [ForkV2Mix]'s lanes and not [ForkV2Planner.Plan.curves]: the curves are the
 * overview's drawing (a rate step drawn as two points, a crossfade's EQ absent), while the mix's
 * lanes are what the extension's Web Audio graph is told to do, the loudness trim, the outgoing
 * filters' 30 ms ease onto their first value, and a preset's curves fitted to the seconds the mix
 * really has all included. Both are built from the same plan, so they never disagree on a value;
 * the lanes just carry the playing details the drawing leaves out.
 *
 * Two progress clocks, because the two players count a blend differently:
 * - The PC counts output frames ([mixTime]: linear in the blend's output seconds [outputSeconds]).
 * - The phone counts the incoming player's media position from its cue ([mixTimeForIncoming]): it is
 *   the only clock that stops when the listener pauses. Under a constant stretch that is still
 *   linear in output time; under Automix v2's glide the incoming song's rate changes every beat, so
 *   the fraction of its song covered is mapped back to the mix clock through the incoming deck's
 *   beat knots, which are exactly the glide's steps.
 */
class ForkV2Transition internal constructor(
    val plan: ForkV2Planner.Plan,
    val mix: ForkV2Mix,
    /**
     * Mix seconds of the blend already gone when a player starts it ([startingAt]): the PC opens a
     * blend from its decoder's position, which runs its sink's queue ahead of the playhead that
     * armed it, so the first moments are skipped rather than the two songs started out of step.
     */
    val startOffset: Double = 0.0,
) {
    /** The blend's length as heard from where a player starts it (the outgoing song may be sped up, or glide). */
    val outputSeconds: Double get() = max(0.0, mix.D - startOffset)

    /** An Automix v2 glide: both songs change rate every beat, the incoming one ends at x1.0. */
    val glides: Boolean = plan.style == "blend" && plan.glide != null

    /** The incoming song's own seconds the blend covers (its cue to where it is at the blend's end). */
    val incomingSongSeconds: Double = max(1e-6, mix.`in`.unwrappedAt(mix.t0 + mix.D) - mix.`in`.unwrappedAt(mix.t0))

    /** The incoming rate a player starts the blend at: the glide's first step, else the planned stretch. */
    val incomingStartRate: Double = if (glides) mix.`in`.rateAt(mix.t0) else (if (plan.style == "blend") plan.rate else 1.0)

    /** The incoming rate at the blend's end, which the player then eases back from ([easeBack]). */
    val incomingEndRate: Double = if (glides) 1.0 else incomingStartRate

    /** Mix seconds at output-time [progress] (0..1 of [outputSeconds]). */
    fun mixTime(progress: Double): Double = mix.t0 + startOffset + progress.coerceIn(0.0, 1.0) * outputSeconds

    /**
     * The mix second at which the outgoing song is at its song second [songSec] during the blend
     * (its deck's knots inverted; before the mix-out: the blend's start, past its end: the end).
     */
    fun mixTimeForOutgoing(songSec: Double): Double {
        val deck = mix.out
        val kt = deck.knotT
        val kp = deck.knotP
        for (i in 1 until kt.size) {
            if (kt[i] <= mix.t0) continue
            if (kp[i] >= songSec) {
                val span = kp[i] - kp[i - 1]
                val f = if (span > 0) (songSec - kp[i - 1]) / span else 1.0
                return (kt[i - 1] + f * (kt[i] - kt[i - 1])).coerceIn(mix.t0, mix.t0 + mix.D)
            }
        }
        return mix.t0 + mix.D
    }

    /**
     * This blend started [offset] mix seconds in (see [startOffset]); its incoming cue is where that
     * song is then, [ForkV2Mix.Deck.unwrappedAt].
     */
    fun startingAt(offset: Double): ForkV2Transition = ForkV2Transition(plan, mix, offset.coerceIn(0.0, mix.D))

    /** The incoming song second at the blend's (possibly late) start. */
    val incomingCue: Double get() = mix.`in`.unwrappedAt(mix.t0 + startOffset)

    /** The incoming rate at the blend's (possibly late) start. */
    val incomingRateAtStart: Double get() = if (glides) mix.`in`.rateAt(mix.t0 + startOffset) else incomingStartRate

    /**
     * Mix seconds at [progress] counted as the share of [incomingSongSeconds] the incoming song has
     * played (the phone's clock). Linear except under a glide, where the knots are inverted.
     */
    fun mixTimeForIncoming(progress: Double): Double {
        if (!glides) return mixTime(progress)
        val deck = mix.`in`
        val start = deck.unwrappedAt(mix.t0)
        val target = start + progress.coerceIn(0.0, 1.0) * incomingSongSeconds
        val kt = deck.knotT
        val kp = deck.knotP
        for (i in 1 until kt.size) {
            if (kt[i] < mix.t0) continue
            if (kp[i] >= target) {
                val span = kp[i] - kp[i - 1]
                val f = if (span > 0) (target - kp[i - 1]) / span else 1.0
                return (kt[i - 1] + f * (kt[i] - kt[i - 1])).coerceIn(mix.t0, mix.t0 + mix.D)
            }
        }
        return mix.t0 + mix.D
    }

    /** Both decks at one moment. Hz are the cut-offs [com.music.bitchord.playback.ForkMixShelf] takes. */
    class Frame {
        var gainOut = 1f
        var gainIn = 0f
        var outLowDb = 0f
        var outHighDb = 0f
        var outLowPassHz = OPEN_HZ
        var outHighPassHz = OFF_HZ
        var inLowDb = 0f
        var inHighDb = 0f
        var inLowPassHz = OPEN_HZ
        var inHighPassHz = OFF_HZ

        /** Song seconds per mix second on each deck (the listener's own speed not included). */
        var outRate = 1.0
        var inRate = 1.0
    }

    /** Fills [f] with both decks at mix second [t] and returns it. */
    fun frameAt(t: Double, f: Frame): Frame {
        val o = mix.out
        val i = mix.`in`
        f.gainOut = o.gain.at(t).toFloat().coerceIn(0f, 4f)
        f.gainIn = i.gain.at(t).toFloat().coerceIn(0f, 4f)
        f.outLowDb = o.lowDb.at(t).toFloat()
        f.outHighDb = o.highDb.at(t).toFloat()
        f.outLowPassHz = o.lpHz.at(t).toFloat()
        f.outHighPassHz = o.hpHz.at(t).toFloat()
        f.inLowDb = i.lowDb.at(t).toFloat()
        f.inHighDb = i.highDb.at(t).toFloat()
        f.inLowPassHz = i.lpHz.at(t).toFloat()
        f.inHighPassHz = i.hpHz.at(t).toFloat()
        f.outRate = if (o.knotT.isEmpty()) 1.0 else o.rateAt(t)
        f.inRate = if (i.knotT.isEmpty()) 1.0 else i.rateAt(t)
        return f
    }

    /**
     * The outgoing song's speed at its song second [pos] while it is brought up to a faster next song
     * (crossfade.js rampOutgoing: one 0.75% step a beat over its last [ForkV2Planner.Plan.rampBeats]
     * beats before the mix-out, the full rate from the mix-out on). Null for no ramp: another style,
     * no speed-up, a glide (which starts at the mix-out, see [frameAt]), or before the ramp.
     */
    fun outgoingRampRate(pos: Double): Double? {
        val target = plan.outRate
        val n = plan.rampBeats
        val beat = plan.beatSec
        if (plan.style != "blend" || glides || !(target > 1.0001) || n <= 0 || !(beat > 0)) return null
        val rampStart = plan.mixOutTrackTime - n * beat
        if (pos < rampStart) return null
        val due = min(n, floor((pos - rampStart) / beat).toInt() + 1)
        return 1.0 + (target - 1.0) * due / n
    }

    /**
     * How the incoming song gets back to x1.0 after the blend: steps, and seconds between them
     * (crossfade.js deckGlideBack: 0.25% every 0.25 s, so a 4% stretch takes 4 s). Null when there is
     * nothing to ease ([incomingEndRate] is 1: no stretch, or a glide, which ends at x1.0).
     */
    fun easeBack(): Pair<Int, Double>? {
        val gap = abs(incomingEndRate - 1.0)
        if (gap < 1e-4) return null
        return max(1, ceil(gap / ForkV2Mix.GLIDE_STEP).toInt()) to ForkV2Mix.GLIDE_STEP_SEC
    }

    override fun toString(): String = mix.note ?: plan.toString()

    companion object {
        const val OPEN_HZ = 20000f
        const val OFF_HZ = 20f
    }
}
