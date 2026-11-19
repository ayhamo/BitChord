package com.music.bitchord.playback.smart.automixv2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the transition editor (sharedUi `ForkTransitionEditor`) draws and edits: the last blend
 * Automix v2 planned, with what the planner was given for it.
 *
 * The overview's own data ([com.music.bitchord.data.settings.MixTransitionView]) is upstream's: the
 * decks' waveforms and the faders sampled from upstream's levels, with no song ids, no curves, no
 * grids and no limits. The editor needs the plan itself ([ForkV2Planner.Plan]: its curves, its
 * window, its warnings) and the two analyses (the grids a drag snaps to, the limits it is held at),
 * so [ForkV2Automix.planTransition] hands them here each time it plans (at most once a second, and
 * not while a plan is held for the blend: that plan was handed over when it was made). Both apps
 * plan through it, so the phone and the PC fill this the same way.
 */
object ForkV2EditorFeed {

    class Planned(
        val fromId: String,
        val toId: String,
        /** The plan as the decks play it (the kick lock applied). */
        val plan: ForkV2Planner.Plan,
        /** A's analysis with the playing length as its duration (what windowLimits reads), or null: not analysed. */
        val from: ForkV2Analysis?,
        val to: ForkV2Analysis?,
        /** The earliest song second the mix could start when this was planned (the planner's `earliest`). */
        val earliest: Double,
    ) {
        val limits: ForkV2EditorGeometry.Limits by lazy { ForkV2EditorGeometry.windowLimits(plan, from, to, earliest) }
        val gridOut: ForkV2EditorGeometry.EditorGrid? by lazy { ForkV2EditorGeometry.editorGrid(from, true) }
        val gridIn: ForkV2EditorGeometry.EditorGrid? by lazy { ForkV2EditorGeometry.editorGrid(to, false) }
    }

    private val _planned = MutableStateFlow<Planned?>(null)
    val planned: StateFlow<Planned?> = _planned.asStateFlow()

    /**
     * From [ForkV2Automix.planTransition], each fresh plan. [length]: the playing song's length as the
     * player reports it, which the planner uses over the analysis' own.
     */
    internal fun note(fromId: String, toId: String, plan: ForkV2Planner.Plan, from: ForkV2Analysis?, to: ForkV2Analysis?, length: Double, earliest: Double) {
        val a = from?.copy()?.also { if (length > 0) it.durationSec = length }
        _planned.value = Planned(fromId, toId, plan, a, to, earliest)
    }

    /** Back to nothing planned (tests). */
    internal fun reset() {
        _planned.value = null
    }
}
