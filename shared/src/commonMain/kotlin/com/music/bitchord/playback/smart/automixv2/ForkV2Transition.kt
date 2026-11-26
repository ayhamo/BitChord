package com.music.bitchord.playback.smart.automixv2

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
    val incomingSongSeconds: Double = (mix.`in`.unwrappedAt(mix.t0 + mix.D) - mix.`in`.unwrappedAt(mix.t0 + startOffset)).let { if (it.isNaN()) 1e-6 else max(1e-6, it) } // max(1e-6, NaN) is NaN

    /** The incoming rate a player starts the blend at: the glide's first step, else the planned stretch. */
    val incomingStartRate: Double = if (glides) mix.`in`.rateAt(mix.t0) else (if (plan.style == "blend" || ForkSongSpeed.off1(plan.speedIn)) plan.rate else 1.0)

    /**
     * Automix v2's echo out (editor.md §5), or null. The outgoing fader stays up ([frameAt]): the dry cut
     * and the repeats are the outgoing sink's [com.music.bitchord.playback.ForkMixShelf]'s, which keeps
     * the beat before the catch and rings it from the blend's start ([ForkMixShelf.echoCatch]).
     */
    val echo: ForkV2Planner.Echo? = if (plan.style == "blend" && plan.shape == "echo") plan.echo else null

    /**
     * How loud the outgoing side is at mix second [t] under an echo out, for a headroom trim: whole
     * before the catch, then the dry cut or the repeats (each beat 0.53 of the last), silent after the
     * echo's beats. Null for any other blend.
     */
    fun echoLevel(t: Double): Float? {
        val e = echo ?: return null
        val sec = t - mix.t0
        if (sec < 0) return 1f
        if (!(e.delaySec > 0) || sec >= e.beats * e.delaySec) return 0f
        val wet = e.feedback.pow(floor(sec / e.delaySec))
        return max(mix.out.gain.at(t), wet).toFloat()
    }

    /**
     * Whether this mix sends to the effect returns or plays noise (editor.md §12): the players then run the returns, and
     * the phone fades the decks in their sinks so the sends are taken after the fader and the tails ring on.
     */
    val effects: Boolean = mix.effects

    /**
     * The Looping lane (editor.md §13): A's song seconds that repeat from the mix-out on (its first N beats), 0 for none. A
     * glide plays no loop (its rate steps every beat), nor does the loop mix.
     */
    val loopSongSec: Double = if (plan.loopSec > 0 && !glides && plan.style != "loop") plan.loopSec else 0.0

    /** [loopSongSec] as the outgoing deck plays it out, at its rate in the blend. */
    val loopOutputSec: Double get() = loopSongSec / (if (plan.style == "blend" && plan.outRate > 0) plan.outRate else 1.0)

    /** The echo's and the delay's seconds from the window's start, or null (editor.md §12 rule 3). */
    val fxTime: DoubleArray? get() = mix.fxTime

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

    /** The incoming song second at the blend's (possibly late) start; under B's late entry, where B starts. */
    val incomingCue: Double get() = mix.`in`.unwrappedAt(max(mix.t0 + startOffset, mix.`in`.startAt))

    /**
     * Output seconds from this blend's start that B stays silent and its player held (mix-file.md rule 2, B's late
     * entry); 0 without one. The incoming seconds ([incomingSongSeconds]) still count from the mix's start, as if B had
     * played from before its mix-in: a player counting B's media time adds this hold, read off another clock.
     */
    val incomingHoldSeconds: Double get() = max(0.0, mix.`in`.startAt - (mix.t0 + startOffset))

    /** The incoming rate at the blend's (possibly late) start. */
    val incomingRateAtStart: Double get() = if (glides) mix.`in`.rateAt(mix.t0 + startOffset) else incomingStartRate

    /**
     * Mix seconds at [progress] counted as the share of [incomingSongSeconds] the incoming song has
     * played (the phone's clock). Linear except under a glide, where the knots are inverted.
     */
    fun mixTimeForIncoming(progress: Double): Double {
        if (!glides) return mixTime(progress)
        val deck = mix.`in`
        val start = deck.unwrappedAt(mix.t0 + startOffset) // from where the blend was started, as mixTime counts ([startingAt])
        val target = start + progress.coerceIn(0.0, 1.0) * incomingSongSeconds
        val kt = deck.knotT
        val kp = deck.knotP
        for (i in 1 until kt.size) {
            if (kt[i] < mix.t0 + startOffset) continue
            if (kp[i] >= target) {
                val span = kp[i] - kp[i - 1]
                val f = if (span > 0) (target - kp[i - 1]) / span else 1.0
                return (kt[i - 1] + f * (kt[i] - kt[i - 1])).coerceIn(mix.t0 + startOffset, mix.t0 + mix.D)
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

        /** editor.md §12: each deck's 1 kHz band (dB), its noise (0..1) and its sends to the echo, reverb and delay returns (0..1). */
        var outMidDb = 0f
        var inMidDb = 0f
        var outNoise = 0f
        var inNoise = 0f
        val outSends = FloatArray(3)
        val inSends = FloatArray(3)

        /** Song seconds per mix second on each deck (the listener's own speed not included). */
        var outRate = 1.0
        var inRate = 1.0
    }

    /** Fills [f] with both decks at mix second [t] and returns it. */
    fun frameAt(t: Double, f: Frame): Frame {
        val o = mix.out
        val i = mix.`in`
        f.gainOut = if (echo != null) 1f else o.gain.at(t).toFloat().coerceIn(0f, 4f) // an echo out's fader stays up, see [echo]
        f.gainIn = i.gain.at(t).toFloat().coerceIn(0f, 4f)
        f.outLowDb = o.lowDb.at(t).toFloat()
        f.outHighDb = o.highDb.at(t).toFloat()
        f.outLowPassHz = o.lpHz.at(t).toFloat()
        f.outHighPassHz = o.hpHz.at(t).toFloat()
        f.inLowDb = i.lowDb.at(t).toFloat()
        f.inHighDb = i.highDb.at(t).toFloat()
        f.inLowPassHz = i.lpHz.at(t).toFloat()
        f.inHighPassHz = i.hpHz.at(t).toFloat()
        f.outMidDb = o.midDb.at(t).toFloat()
        f.inMidDb = i.midDb.at(t).toFloat()
        f.outNoise = o.noise.at(t).toFloat()
        f.inNoise = i.noise.at(t).toFloat()
        f.outSends[0] = o.echoSend.at(t).toFloat()
        f.outSends[1] = o.reverbSend.at(t).toFloat()
        f.outSends[2] = o.delaySend.at(t).toFloat()
        f.inSends[0] = i.echoSend.at(t).toFloat()
        f.inSends[1] = i.reverbSend.at(t).toFloat()
        f.inSends[2] = i.delaySend.at(t).toFloat()
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
        // a song at its playlist speed plays at it all along, no ramp (spotify-mixes.md rule 10)
        if (ForkSongSpeed.off1(plan.speedOut)) return plan.speedOut
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
        val gap = abs(incomingEndRate - plan.speedIn) // (a song's playlist speed: B stays at it, rule 10)
        if (gap < 1e-4) return null
        return max(1, ceil(gap / ForkV2Mix.GLIDE_STEP).toInt()) to ForkV2Mix.GLIDE_STEP_SEC
    }

    override fun toString(): String = mix.note ?: plan.toString()

    companion object {
        const val OPEN_HZ = 20000f
        const val OFF_HZ = 20f
    }
}
