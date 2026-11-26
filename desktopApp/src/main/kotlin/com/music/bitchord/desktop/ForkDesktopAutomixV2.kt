package com.music.bitchord.desktop

import com.music.bitchord.playback.ForkMixShelf
import com.music.bitchord.playback.TransitionFilter
import com.music.bitchord.playback.smart.AutomixStyle
import com.music.bitchord.playback.smart.ForkMixPicks
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import com.music.bitchord.playback.smart.automixv2.ForkV2EvidenceStore
import com.music.bitchord.playback.smart.automixv2.ForkV2OverrideStore
import com.music.bitchord.playback.smart.automixv2.ForkV2Transition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Fork: the PC's "Automix style" (Settings > Playback, under Automix), the phone's
 * `ForkSettings.automixStyle`: BitChord's own Automix, or Automix v2 (the YouTube Music extension's
 * engine, ported under `playback/smart/automixv2`), so both apps plan the same mix.
 *
 * A saved "Web AutoDJ" / "Advanced AutoDJ" (both removed on 2026-11-19) reads as Automix v2 and is
 * stored as it, and the removed "Shorter blend when keys clash" switch's pref is deleted (Automix
 * v2 always weighs the keys); left stored it would also have gone out in a backup as a PC-only
 * setting. With no style saved (a fresh install) it is Automix v2, the phone's default too
 * ([AutomixStyle.fromSaved]); a saved choice stays.
 */
internal object ForkDesktopAutomixStyle {
    private const val KEY = "automix_style"
    private const val OLD_KEY_AWARE = "automix_key_aware"
    private val persistence by lazy { DesktopPersistence() }
    private val _style = MutableStateFlow(read().also { ForkMixPicks.style = it })
    val style: StateFlow<AutomixStyle> = _style.asStateFlow()

    private fun read(): AutomixStyle {
        // "" is no key saved: DesktopPersistence.string gives the default for an absent key.
        val saved = persistence.string(KEY, "")
        val migrated = AutomixStyle.migratedKey(saved)
        if (migrated != null && migrated != saved) persistence.saveString(KEY, migrated)
        runCatching { persistence.preferences.remove(OLD_KEY_AWARE) }
        return AutomixStyle.fromSaved(saved.ifEmpty { null })
    }

    /**
     * DesktopApp's "automix" switch on a fresh install: on, as on the phone (`ForkSettings.DEFAULT_AUTOMIX_ON`),
     * the extension and the phone patches. Only `Preferences.getBoolean`'s default, so a saved choice stays.
     */
    const val DEFAULT_AUTOMIX_ON = true

    fun set(value: AutomixStyle) {
        _style.value = value
        ForkMixPicks.style = value
        persistence.saveString(KEY, value.key)
    }

    /** The overview's picks before the editor: only read once, to move them into [PAIR_OVERRIDES]. */
    private const val PAIR_PICKS = "automix_pair_picks"

    /** The transition editor's overrides, `{"a>b": override}` JSON ([ForkV2OverrideStore]); the phone's key too. */
    internal const val PAIR_OVERRIDES = "automix_pair_overrides"

    /**
     * Loads the transition editor's overrides per pair of songs ([ForkV2OverrideStore], which
     * [ForkMixPicks] now shows as picks) and saves them as they change, after moving the older picks'
     * lines into them once (the old key is then removed); also opens Automix v2's evidence beside the analyses ([ForkV2EvidenceStore], measured by
     * `DesktopTrackAnalyzer`, or by [ForkDesktopV2Evidence] for a song analysed before it) and wires
     * the kick lock ([ForkDesktopKickLock]). Called once from the engine's `init`.
     */
    fun loadPairPicks() {
        ForkV2OverrideStore.install(
            load = { persistence.string(PAIR_OVERRIDES, "") },
            save = { persistence.saveString(PAIR_OVERRIDES, it) },
        )
        val old = persistence.string(PAIR_PICKS, "")
        if (old.isNotEmpty()) {
            ForkV2OverrideStore.migrateLegacyPicks(old)
            runCatching { DesktopPreferenceChunks.remove(persistence.preferences, PAIR_PICKS) }
        }
        ForkMixPicks.style = _style.value
        runCatching { ForkV2EvidenceStore.install(DesktopAnalysisRuntime.analysisHome()) }
        ForkDesktopKickLock.install()
        ForkDesktopMixFiles.install() // the transition editor's mix import and export
    }
}

/**
 * Fork: the engine's side of an Automix v2 blend ([TransitionPlan.forkV2]).
 *
 * What the PC plays from the plan, and how exactly:
 * - The blend runs on output frames: its length is [ForkV2Transition.outputSeconds] ([seconds]),
 *   and each moment is the plan's mix clock at that share of it ([ForkV2Transition.mixTime]).
 * - Faders: the decks' gain lanes, evaluated per 64-frame sub-block of the mix ([gains]; 1.3 ms at
 *   48 kHz), in place of [DesktopTransitionRide]'s.
 * - EQ and cuts: both decks' shelves and 12 dB/oct low- and high-pass in their [ForkMixShelf]
 *   ([shelve]), aimed once per mix block and gliding to it per 64 frames (0.5 dB a step, a
 *   geometric Hz glide) so no step clicks; the 24 dB transition filters are held open ([aim]).
 * - Rates: a constant stretch is the engine's own (the incoming deck's tempo buffer at the plan's
 *   rate, the outgoing ramp a beat at a time before the mix-out). A glide's per-beat rates are
 *   returned by [shelve] for the engine to put on both decks' tempo buffers; a buffer takes a new
 *   rate on its next decoder push, so a step lands within one decoder block of its beat.
 * - The start: the blend is opened from the decoder's position ([fromDecoder]), which runs the
 *   sink's queue ahead of the playhead that armed it. The mix clock starts that far in and the
 *   incoming cue moves with it, so the two songs start in step.
 * - Not played: kick lock (the plan's cue as the planner placed it), and the engine's own sends
 *   (beat-roll, reverb, texture: the extension has none).
 *
 * Audio thread only, hence the reused [frame] and shelves.
 */
internal object ForkDesktopV2 {

    private val frame = ForkV2Transition.Frame()
    private val outgoingShelf = ForkMixShelf()
    private val incomingShelf = ForkMixShelf()
    private val rates = DoubleArray(2)

    /**
     * An editor mix's effect returns and noise (editor.md §12): both decks' sends after their faders ([send], from the
     * mixer's loop) into one set of returns, played into the mix ([returns]) and ringing on after it ([tail]).
     */
    private val fx = com.music.bitchord.playback.ForkMixFx()
    private var feeding = false
    private val outSends = FloatArray(3)
    private val inSends = FloatArray(3)
    private var noiseOut = 0f
    private var noiseIn = 0f
    private var noiseOutWas = 0f
    private var noiseInWas = 0f

    /** True when [plan] is an Automix v2 blend, which then takes the filters from here. */
    fun aim(plan: TransitionPlan, outgoing: TransitionFilter, incoming: TransitionFilter): Boolean {
        if (plan.forkV2 == null) return false
        // The cuts are the shelves' 12 dB sections ([shelve]), as the extension's; these 24 dB filters stay open.
        outgoing.open()
        incoming.open()
        return true
    }

    /** True when [plan] is an Automix v2 blend: [result] then holds its outgoing and incoming gains at [progress]. */
    fun gains(plan: TransitionPlan, progress: Float, result: FloatArray): Boolean {
        val t = plan.forkV2 ?: return false
        t.frameAt(t.mixTime(progress.toDouble()), frame)
        result[0] = frame.gainOut
        result[1] = frame.gainIn
        return true
    }

    /**
     * The blend [plan] ran its fade out: its move counts (versions.md "No effect three times in a row": at the mix's
     * real end, so a blend called off by a skip, a seek or a flush doesn't count). Counted at [arm] before.
     */
    fun ended(plan: TransitionPlan?) {
        val t = plan?.forkV2 ?: return
        com.music.bitchord.playback.smart.automixv2.ForkV2Moves.ended(t, t.plan, reachedEnd = true)
    }

    /** Whether B's late entry still waits [elapsed] samples into the blend ([heldSamples]). */
    fun waiting(plan: TransitionPlan?, elapsed: Int, total: Int, channels: Int): Boolean =
        heldSamples(plan, elapsed, total, channels, channels) > 0

    /** Frames of B's rise after its late entry played ([lateRise]); -1: none under way. */
    private var riseAt = -1

    /**
     * B's late entry (mix-file.md rule 2): its gain is 0 until the delay ends ([heldSamples] of [block] are silent and
     * unread), then rises to the curve over [ForkMixShelf]'s 10 ms ([com.music.bitchord.playback.ForkMixShelf.LATE_RISE_SEC]):
     * [block]'s frames from [held] on, carried across blocks.
     */
    fun lateRise(block: FloatArray?, held: Int, count: Int, channels: Int, sampleRate: Int) {
        if (held > 0) riseAt = 0
        if (riseAt < 0 || block == null || channels < 1 || sampleRate <= 0) return
        val len = maxOf(1, (com.music.bitchord.playback.ForkMixShelf.LATE_RISE_SEC * sampleRate).toInt())
        var i = held
        while (i + channels <= count && riseAt < len) {
            val g = riseAt.toFloat() / len
            for (c in 0 until channels) block[i + c] *= g
            riseAt++
            i += channels
        }
        if (riseAt >= len) riseAt = -1
    }

    /** A new blend: both shelves start where [plan] starts, with no history. */
    fun arm(plan: TransitionPlan) {
        riseAt = -1
        outgoingShelf.echoEndRing() // the last blend's echo (and its tail) never carries into this one
        val t = plan.forkV2
        if (t == null) {
            outgoingShelf.snap(0f, 0f)
            incomingShelf.snap(0f, 0f)
            return
        }
        t.frameAt(t.mixTime(0.0), frame)
        outgoingShelf.loopStop()
        t.fxTime?.let { fx.times(it[0], it[1]) } // (set at the window's start, editor.md §12 rule 3)
        noiseOutWas = 0f
        noiseInWas = 0f
        outgoingShelf.aimMid(frame.outMidDb)
        incomingShelf.aimMid(frame.inMidDb)
        // The outgoing song is playing: it starts open and glides into the curve, as the transition filters did.
        // The incoming one is silent here, so it can start right on the curve.
        outgoingShelf.startOpen(frame.outLowDb, frame.outHighDb, frame.outLowPassHz, frame.outHighPassHz)
        incomingShelf.snap(frame.inLowDb, frame.inHighDb, frame.inLowPassHz, frame.inHighPassHz)
    }

    /**
     * Shelves one block of each deck in place, aimed at [progress] (any other plan: untouched).
     * Returns both decks' rates (outgoing, incoming) at [progress] under a glide, else null.
     */
    fun shelve(
        plan: TransitionPlan?,
        progress: Float,
        outgoing: FloatArray,
        outgoingCount: Int,
        incoming: FloatArray?,
        incomingCount: Int,
        channels: Int,
        sampleRate: Int,
    ): DoubleArray? {
        val t = plan?.forkV2 ?: return null
        t.frameAt(t.mixTime(progress.coerceIn(0f, 1f).toDouble()), frame)
        t.echo?.let { outgoingShelf.echoCatch(it.delaySec, it.beats, it.feedback, it.cutSec) } // the echo out rings from the blend's start
        if (t.loopSongSec > 0) outgoingShelf.loopStart(t.loopOutputSec) // the Looping lane from the mix-out (editor.md §13)
        outgoingShelf.set(frame.outLowDb, frame.outHighDb, frame.outLowPassHz, frame.outHighPassHz)
        incomingShelf.set(frame.inLowDb, frame.inHighDb, frame.inLowPassHz, frame.inHighPassHz)
        outgoingShelf.aimMid(frame.outMidDb)
        incomingShelf.aimMid(frame.inMidDb)
        feeding = t.effects
        if (feeding || fx.ringing) {
            frame.outSends.copyInto(outSends)
            frame.inSends.copyInto(inSends)
            noiseOut = frame.outNoise
            noiseIn = frame.inNoise
            fx.begin(outgoingCount / channels.coerceAtLeast(1), channels, sampleRate)
        }
        outgoingShelf.process(outgoing, outgoingCount, channels, sampleRate)
        if (incoming != null) incomingShelf.process(incoming, incomingCount, channels, sampleRate)
        if (!t.glides) return null
        rates[0] = frame.outRate
        rates[1] = frame.inRate
        return rates
    }

    /**
     * The playing deck's block before any blend: kept (never changed) for an echo out, whose repeats
     * are the beat before its catch ([ForkMixShelf.listen]).
     */
    /** A seek or a new song: what the shelves kept, an echo's ring and tail included, is from before it. */
    fun flush() {
        outgoingShelf.flush()
        incomingShelf.flush()
        fx.clear()
    }

    /** Sample [i] of the blend's block: the decks after their faders ([leaving], [arriving]) to the returns. */
    fun send(i: Int, leaving: Float, arriving: Float) {
        if (!feeding) return
        fx.send(i, leaving, outSends[0], outSends[1], outSends[2])
        fx.send(i, arriving, inSends[0], inSends[1], inSends[2])
    }

    /** The returns and the noise into the blend's [mixed] block. */
    fun returns(mixed: FloatArray, count: Int, channels: Int) {
        if (!feeding && !fx.ringing) return
        fx.render(mixed, count / channels.coerceAtLeast(1), noiseOutWas, noiseOut, noiseInWas, noiseIn)
        noiseOutWas = noiseOut
        noiseInWas = noiseIn
    }

    /** One deck playing on after a mix: what the returns still ring added to its [block] (editor.md §12: tails ring on). */
    fun tail(block: FloatArray, count: Int, channels: Int, sampleRate: Int) {
        if (!fx.ringing) return
        feeding = false
        fx.begin(count / channels.coerceAtLeast(1), channels, sampleRate)
        fx.render(block, count / channels.coerceAtLeast(1), noiseOutWas, 0f, noiseInWas, 0f)
        noiseOutWas = 0f
        noiseInWas = 0f
    }

    fun listen(block: FloatArray, count: Int, channels: Int, sampleRate: Int) {
        outgoingShelf.echoRecord()
        outgoingShelf.listen(block, count, channels, sampleRate)
    }

    /** The outgoing level under an echo out for the headroom trim ([ForkV2Transition.echoLevel]); null otherwise. */
    fun echoLevel(plan: TransitionPlan?, progress: Float): Float? {
        val t = plan?.forkV2 ?: return null
        return t.echoLevel(t.mixTime(progress.coerceIn(0f, 1f).toDouble()))
    }

    /**
     * Interleaved samples at the start of this [count]-sample block the incoming deck stays silent and unread (B's late
     * entry, mix-file.md rule 2: its first point is [ForkV2Transition.incomingHoldSeconds] into the blend, as the PC's
     * deck starts at t0 + delay). [elapsed] is the samples the blend has played, [total] its fader span; whole frames.
     */
    fun heldSamples(plan: TransitionPlan?, elapsed: Int, total: Int, count: Int, channels: Int): Int {
        val t = plan?.forkV2 ?: return 0
        val hold = t.incomingHoldSeconds
        if (!(hold > 0) || !(t.outputSeconds > 0) || total <= 0) return 0
        val frames = kotlin.math.ceil(hold / t.outputSeconds * (total / channels)).toLong()
        val left = frames * channels - elapsed
        return left.coerceIn(0L, count.toLong()).toInt()
    }

    /**
     * The progress the player moves to the incoming song at: [at], or later while B's late entry is still silent
     * ([ForkV2Transition.incomingHoldSeconds]), so the display, and a pause's choice of song (playback.md "The queue
     * during a mix" rule 5), never go to a B that hasn't started. The phone's midpoint waits the same way.
     */
    fun afterLateEntry(plan: TransitionPlan?, at: Float): Float {
        val t = plan?.forkV2 ?: return at
        val hold = t.incomingHoldSeconds
        if (!(hold > 0) || !(t.outputSeconds > 0)) return at
        return maxOf(at, (hold / t.outputSeconds).toFloat())
    }

    /** Output seconds the blend lasts ([ForkV2Transition.outputSeconds]); the plan's own otherwise. */
    fun seconds(plan: TransitionPlan): Double = ForkV2Automix.outputSeconds(plan)

    /** Whether the engine's own sends (beat-roll, reverb, texture) may play over [plan]. */
    fun sends(plan: TransitionPlan): Boolean = plan.forkV2 == null

    /**
     * The outgoing deck's rate from the blend's start: the speed-up a constant blend ramped to, or a
     * glide's rate there; null for none (any other plan, or no speed change).
     */
    fun outgoingRateAtStart(plan: TransitionPlan): Double? {
        val t = plan.forkV2 ?: return null
        if (t.glides) return t.frameAt(t.mixTime(0.0), frame).outRate
        // (a song at its playlist speed stays at it, under x1 too: spotify-mixes.md rule 10)
        return plan.outgoingPlaybackRate.takeIf { it > 1.0001 || com.music.bitchord.playback.smart.automixv2.ForkSongSpeed.off1(t.plan.speedOut) }
    }

    /**
     * [plan] as it is opened with the outgoing decoder at song second [decoderSec]: an Automix v2 blend
     * starts that far into its mix clock ([ForkV2Transition.startingAt]), with the incoming cue and
     * rate where they are then. Any other plan, or a decoder still before the mix-out, is unchanged.
     * At most half the blend is skipped: a decoder further on means something else moved it (a seek),
     * and the blend then plays from its middle rather than from nothing.
     */
    fun fromDecoder(plan: TransitionPlan, decoderSec: Double): TransitionPlan {
        val t = plan.forkV2 ?: return plan
        if (t.startOffset > 0 || !(decoderSec > t.plan.mixOutTrackTime + 0.001)) return plan
        val offset = (t.mixTimeForOutgoing(decoderSec) - t.mix.t0).coerceIn(0.0, t.mix.D / 2)
        if (offset < 0.001) return plan
        val late = t.startingAt(offset)
        return plan.copy(
            incomingCueTime = late.incomingCue.coerceAtLeast(0.0),
            incomingPlaybackRate = (late.incomingRateAtStart * 10000).roundToInt() / 10000.0,
            forkV2 = late,
        )
    }
}
