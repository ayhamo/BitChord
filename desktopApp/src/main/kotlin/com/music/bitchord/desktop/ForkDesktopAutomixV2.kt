package com.music.bitchord.desktop

import com.music.bitchord.playback.ForkMixShelf
import com.music.bitchord.playback.TransitionFilter
import com.music.bitchord.playback.smart.AutomixStyle
import com.music.bitchord.playback.smart.ForkMixPicks
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import com.music.bitchord.playback.smart.automixv2.ForkV2EvidenceStore
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
 * setting.
 */
internal object ForkDesktopAutomixStyle {
    private const val KEY = "automix_style"
    private const val OLD_KEY_AWARE = "automix_key_aware"
    private val persistence by lazy { DesktopPersistence() }
    private val _style = MutableStateFlow(read().also { ForkMixPicks.style = it })
    val style: StateFlow<AutomixStyle> = _style.asStateFlow()

    private fun read(): AutomixStyle {
        val saved = persistence.string(KEY, AutomixStyle.BITCHORD.key)
        val migrated = AutomixStyle.migratedKey(saved)
        if (migrated != null && migrated != saved) persistence.saveString(KEY, migrated)
        runCatching { persistence.preferences.remove(OLD_KEY_AWARE) }
        return AutomixStyle.fromKey(saved)
    }

    fun set(value: AutomixStyle) {
        _style.value = value
        ForkMixPicks.style = value
        persistence.saveString(KEY, value.key)
    }

    private const val PAIR_PICKS = "automix_pair_picks"

    /**
     * Loads the Automix overview's picks per pair of songs ([ForkMixPicks]) and saves them as they
     * change; also opens Automix v2's evidence beside the analyses ([ForkV2EvidenceStore], measured by
     * `DesktopTrackAnalyzer`). Called once from the engine's `init`.
     */
    fun loadPairPicks() {
        ForkMixPicks.load(persistence.string(PAIR_PICKS, ""))
        ForkMixPicks.save = { persistence.saveString(PAIR_PICKS, it) }
        ForkMixPicks.style = _style.value
        runCatching { ForkV2EvidenceStore.install(DesktopAnalysisRuntime.analysisHome()) }
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

    /** A new blend: both shelves start where [plan] starts, with no history. */
    fun arm(plan: TransitionPlan) {
        val t = plan.forkV2
        if (t == null) {
            outgoingShelf.snap(0f, 0f)
            incomingShelf.snap(0f, 0f)
            return
        }
        t.frameAt(t.mixTime(0.0), frame)
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
        outgoingShelf.set(frame.outLowDb, frame.outHighDb, frame.outLowPassHz, frame.outHighPassHz)
        incomingShelf.set(frame.inLowDb, frame.inHighDb, frame.inLowPassHz, frame.inHighPassHz)
        outgoingShelf.process(outgoing, outgoingCount, channels, sampleRate)
        if (incoming != null) incomingShelf.process(incoming, incomingCount, channels, sampleRate)
        if (!t.glides) return null
        rates[0] = frame.outRate
        rates[1] = frame.inRate
        return rates
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
        return plan.outgoingPlaybackRate.takeIf { it > 1.0001 }
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
