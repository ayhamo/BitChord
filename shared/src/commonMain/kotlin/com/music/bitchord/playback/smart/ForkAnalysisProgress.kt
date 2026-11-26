package com.music.bitchord.playback.smart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * Fork: where each song's Automix analysis stands, for the transition editor's progress row
 * (the extension's spec/automix/editor.md §11, spec fef76a9), and the analysis length limit
 * (versions.md "Analysis length", Laya 1a1f324b). Both apps' analysers report into it through
 * one-line hooks (the phone's `TrackAnalyzer`, the PC's `DesktopTrackAnalyzer`); the shared popup
 * reads it ([rowFor]).
 *
 * The stages are BitChord's own (rule 5: "each app's own, a fixed % at each stage's start, nothing
 * counted inside a stage"), the same on the phone and the PC since both run the same two passes:
 * getting the audio, the whole-song DSP pass (decode, tempo, key, the low end and Automix v2's
 * evidence), then the beat and vocal models over the opening and ending windows, then saving. The
 * %s are where each stage starts in a typical pass on the phone (the models take most of it).
 * BitChord also has an opening pass that gives a usable result while the rest runs; a whole pass
 * over a song that has one reads "Refining …" (BitChord only, as plan-progress-row.md's feedback
 * asked: neither the extension nor the phone patches analyse in two passes).
 *
 * Outcomes that aren't failures get their own words, never "failed" (rule 3): a song over
 * [ANALYSIS_MAX_SEC], an analysis stopped (a listening party, or the song moved past), a decode that
 * came out too short. A failure keeps its reason until the song is analysed again.
 */
object ForkAnalysisProgress {

    /**
     * Songs over 8 minutes aren't analysed (versions.md "Analysis length"): a long mix or an hour-long
     * set costs minutes of work and memory for a song that mixes at its very end, if at all. "Too
     * long" is its own outcome: never retried, not saved, not logged as an error; the song mixes as a
     * song without an analysis does (the plain crossfade of the user's length).
     */
    const val ANALYSIS_MAX_SEC = 480.0

    enum class Stage(val label: String, val pct: Int) {
        QUEUED("waiting its turn", 0),
        AUDIO("getting the audio", 0),
        OPENING("the opening's beats", 10),
        DECODING("decoding, the tempo, key and low end", 5),
        MODELS("finding the beats and the vocals", 35),
        SAVING("saving", 95),
    }

    private class Running(val stage: Stage, val refining: Boolean)

    /** Everything the row reads, replaced whole by one compare-and-set ([write]). */
    private data class State(
        val running: Map<String, Running> = emptyMap(),
        val failedWhy: Map<String, String> = emptyMap(),
        val notes: Map<String, String> = emptyMap(),
        val tooLongIds: Set<String> = emptySet(),
    )

    private val state = MutableStateFlow(State())

    /** The playing song and the next, as the planner last saw them. */
    @Volatile private var pair: Pair<String, String?>? = null

    private val _changes = MutableStateFlow(0L)

    /** Bumped on every change, for the popup to redraw the row. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun changed() {
        _changes.update { it + 1 }
    }

    // ---- the analysers' side ----

    /**
     * True when [id] is over the length limit (now, from [durationSec] when it is known, or from
     * before), which the analyser then leaves alone. Checked with the queue's length first, and by
     * the phone again with the file's own length before anything is decoded.
     */
    fun tooLong(id: String, durationSec: Double): Boolean {
        // Automix v2's rule only: under BitChord's own style upstream's analyser runs as upstream ships it
        if (ForkMixPicks.style != AutomixStyle.V2) return false
        if (id in state.value.tooLongIds) return true
        if (!(durationSec > ANALYSIS_MAX_SEC)) return false
        write { it.copy(tooLongIds = it.tooLongIds + id, running = it.running - id, failedWhy = it.failedWhy - id) }
        return true
    }

    /** Whether [id] was found over the limit, under Automix v2 (BitChord's style analyses and restores any length). */
    fun isTooLong(id: String?): Boolean = id != null && ForkMixPicks.style == AutomixStyle.V2 && id in state.value.tooLongIds

    fun queued(id: String) = stage(id, Stage.QUEUED)

    /** [id]'s pass reached [stage]; [refining]: a usable result from its opening pass is out already. */
    fun stage(id: String, stage: Stage, refining: Boolean? = null) {
        write { s ->
            val was = s.running[id]
            val r = Running(stage, refining ?: was?.refining ?: false)
            if (was != null && was.stage == r.stage && was.refining == r.refining) return@write s
            s.copy(
                running = s.running + (id to r),
                notes = if (stage == Stage.QUEUED || stage == Stage.AUDIO || stage == Stage.OPENING) s.notes - id else s.notes,
            )
        }
    }

    /** The pass failed with [error] (null: no reason given). [retrying]: it is tried again soon, so not a failure yet. */
    fun failed(id: String, error: Throwable?, retrying: Boolean = false) {
        if (retrying) return
        val why = error?.let { it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.simpleName } ?: "nothing usable came out of the audio"
        write { it.copy(failedWhy = it.failedWhy + (id to why.take(120))) }
    }

    /**
     * The pass is over. [usable]: what the analyser holds for [id] now (null: nothing recorded);
     * [stopped]: it was called off (a listening party, the song moved past); [short]: its decode came
     * out too short. A usable result also ends "over 8 minutes" (analysed under BitChord's style).
     */
    fun ended(id: String, usable: Boolean?, stopped: Boolean = false, short: Boolean = false) {
        write { s ->
            val r = s.copy(running = s.running - id)
            when {
                usable == true -> r.copy(failedWhy = r.failedWhy - id, notes = r.notes - id, tooLongIds = r.tooLongIds - id)
                stopped -> r.copy(notes = r.notes + (id to "stopped (a listening party, or the song was moved past)"))
                short -> r.copy(notes = r.notes + (id to "its audio decoded too short to measure"))
                usable == false && id !in r.failedWhy -> r.copy(failedWhy = r.failedWhy + (id to "nothing usable came out of the audio"))
                else -> r
            }
        }
    }

    /** The planner's pair (each app's Automix tick, any style). */
    fun notePair(curId: String?, nextId: String?) {
        if (curId.isNullOrBlank()) return
        val p = curId to nextId?.takeIf { it.isNotBlank() && it != curId }
        if (p == pair) return
        pair = p
        changed()
    }

    fun pair(): Pair<String, String?>? = pair

    /**
     * A change, then the redraw. The player's tick ("waiting its turn", "over 8 minutes") and the analyser's worker
     * write at once, so every change is one compare-and-set of the whole [State]: the plain read-modify-write it was
     * let a queued song's write put back a song that had just ended, left at "saving · 95%" for good.
     */
    private inline fun write(crossinline change: (State) -> State) {
        state.update { change(it) }
        changed()
    }

    // ---- the row ----

    /** What the row shows: its text, and its bar (0..1; null: none; NaN: indeterminate). */
    class Row(val text: String, val fill: Float?)

    /**
     * The row for the pair (editor.md §11): this song before the next, never both; a failure or
     * another outcome in its place; with neither song analysing and [loopSearching] (BitChord's own
     * loop-mix search still waiting), "Looking for a loop mix…"; else null (nothing to say).
     */
    fun rowFor(curId: String?, nextId: String?, loopSearching: Boolean): Row? {
        val st = state.value
        for ((id, which) in listOf(curId to "this song", nextId to "the next song")) {
            val r = id?.let { st.running[it] } ?: continue
            val verb = if (r.refining) "Refining" else "Analysing"
            return Row("$verb $which: ${r.stage.label} · ${r.stage.pct}%", r.stage.pct / 100f)
        }
        for ((id, which) in listOf(curId to "this song", nextId to "the next song")) {
            if (id == null) continue
            if (id in st.tooLongIds && ForkMixPicks.style == AutomixStyle.V2) return Row("${which.replaceFirstChar { it.uppercase() }} is over 8 minutes: not analysed, a plain crossfade", null)
            st.failedWhy[id]?.let { return Row("Analysis of $which failed: $it", null) }
            st.notes[id]?.let { return Row("Analysis of $which: $it", null) }
        }
        return if (loopSearching) Row("Looking for a loop mix…", Float.NaN) else null
    }

    /** Tests: start empty. */
    fun clear() {
        state.value = State()
        pair = null
        changed()
    }
}
