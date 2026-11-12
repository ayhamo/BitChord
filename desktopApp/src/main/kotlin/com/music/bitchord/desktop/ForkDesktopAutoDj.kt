package com.music.bitchord.desktop

import com.music.bitchord.playback.ForkMixShelf
import com.music.bitchord.playback.TransitionFilter
import com.music.bitchord.playback.smart.AutomixStyle
import com.music.bitchord.playback.smart.ForkAutoDj
import com.music.bitchord.playback.smart.ForkAutoDjCurves
import com.music.bitchord.playback.smart.TransitionPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fork: the PC's "Automix style" (Settings > Playback, under Automix), the phone's
 * `ForkSettings.automixStyle`: BitChord's own Automix, or the YouTube Music extension's Original /
 * Advanced AutoDJ, planned by the shared [ForkAutoDj] and drawn by the shared [ForkAutoDjCurves], so
 * both apps play the same mix.
 */
internal object ForkDesktopAutomixStyle {
    private const val KEY = "automix_style"
    private val persistence by lazy { DesktopPersistence() }
    private val _style = MutableStateFlow(AutomixStyle.fromKey(persistence.string(KEY, AutomixStyle.BITCHORD.key)))
    val style: StateFlow<AutomixStyle> = _style.asStateFlow()

    fun set(value: AutomixStyle) {
        _style.value = value
        persistence.saveString(KEY, value.key)
    }

    private const val KEY_AWARE = "automix_key_aware"
    private val _keyAware = MutableStateFlow(persistence.boolean(KEY_AWARE, false).also { ForkAutoDj.keyAware = it })

    /** "Shorter blend when keys clash" (Advanced AutoDJ only), the phone's `ForkSettings.automixKeyAware`. */
    val keyAware: StateFlow<Boolean> = _keyAware.asStateFlow()

    fun setKeyAware(value: Boolean) {
        _keyAware.value = value
        ForkAutoDj.keyAware = value
        persistence.saveBoolean(KEY_AWARE, value)
    }
}

/**
 * Fork: the engine's side of an AutoDJ mix ([TransitionPlan.forkAutoDj]): faders and filters in
 * place of [DesktopTransitionRide]'s, the version-1 shelves on both decks, and no beat-roll, reverb
 * or texture (the extension has none). Audio thread only, hence the reused [frame].
 */
internal object ForkDesktopAutoDj {

    private val frame = ForkAutoDjCurves.Frame()
    private val outgoingShelf = ForkMixShelf()
    private val incomingShelf = ForkMixShelf()

    /** True when [plan] is an AutoDJ mix, which then takes the filters from here. */
    fun aim(plan: TransitionPlan, progress: Float, outgoing: TransitionFilter, incoming: TransitionFilter): Boolean {
        val shape = plan.forkAutoDj ?: return false
        ForkAutoDjCurves.at(shape, progress, frame)
        outgoing.setCutoffs(frame.outLowPassHz, frame.outHighPassHz)
        incoming.setCutoffs(frame.inLowPassHz, frame.inHighPassHz)
        return true
    }

    /** True when [plan] is an AutoDJ mix: [result] then holds its outgoing and incoming gains. */
    fun gains(plan: TransitionPlan, progress: Float, result: FloatArray): Boolean {
        val shape = plan.forkAutoDj ?: return false
        ForkAutoDjCurves.at(shape, progress, frame)
        result[0] = frame.gainOut
        result[1] = frame.gainIn
        return true
    }

    /** A new blend: both shelves start where [plan] starts, with no history. */
    fun arm(plan: TransitionPlan) {
        val shape = plan.forkAutoDj
        if (shape == null) {
            outgoingShelf.snap(0f, 0f)
            incomingShelf.snap(0f, 0f)
            return
        }
        ForkAutoDjCurves.at(shape, 0f, frame)
        outgoingShelf.snap(frame.outLowDb, frame.outHighDb)
        incomingShelf.snap(frame.inLowDb, frame.inHighDb)
    }

    /** Shelves one block of each deck in place, aimed at [progress] (flat for any other mix: untouched). */
    fun shelve(
        plan: TransitionPlan?,
        progress: Float,
        outgoing: FloatArray,
        outgoingCount: Int,
        incoming: FloatArray?,
        incomingCount: Int,
        channels: Int,
        sampleRate: Int,
    ) {
        val shape = plan?.forkAutoDj ?: return
        ForkAutoDjCurves.at(shape, progress.coerceIn(0f, 1f), frame)
        outgoingShelf.set(frame.outLowDb, frame.outHighDb)
        incomingShelf.set(frame.inLowDb, frame.inHighDb)
        outgoingShelf.process(outgoing, outgoingCount, channels, sampleRate)
        if (incoming != null) incomingShelf.process(incoming, incomingCount, channels, sampleRate)
    }

    /** Output seconds the mix lasts (an AutoDJ mix may speed the outgoing song up); the plan's own otherwise. */
    fun seconds(plan: TransitionPlan): Double = ForkAutoDj.outputSeconds(plan)

    /** Whether the engine's own sends (beat-roll, reverb, texture) may play over [plan]. */
    fun sends(plan: TransitionPlan): Boolean = plan.forkAutoDj == null
}
