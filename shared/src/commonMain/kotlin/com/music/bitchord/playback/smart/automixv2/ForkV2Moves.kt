package com.music.bitchord.playback.smart.automixv2

import kotlin.concurrent.Volatile

/**
 * The moves Automix v2's mixes were heard as, for "No effect three times in a row" (the extension's
 * versions.md, spec fef76a9; automix.js `heard`, `avoidedMove`; Prism `MixMoves.pick`). The same
 * effect three mixes running sounds like a trick, so when the last two mixes were the same effect
 * move (a filter mix, an echo out, a drop-in, a muffle) the planner skips it for the next one and
 * takes the next rung of its ladder: a filter mix falls to the echo out, an echo out to the
 * hand-over, a drop-in to the muffle, a muffle to the hand-over.
 *
 * A move counts when its mix reaches its real end ([ended]: the phone's blend at progress 1, the
 * PC's blend when its fade runs out, a loop mix at its handover's end), not at its start or its
 * hand-over, so a mix called off before or during it doesn't count (versions.md, audit 2026-11-24). Not effects, and
 * free to repeat: the blend, the loop, the bridge, the hand-over and the plain crossfade. A pick
 * from the editor is never changed by this (the planner reads [avoidedMove] only on its own ladder).
 *
 * Kept in memory, the last [RECENT_MOVES]; empty at each app start, as the extension's list is at
 * each page load. The list is replaced, never changed in place, so a planner on another thread
 * reads a whole one.
 */
object ForkV2Moves {
    const val RECENT_MOVES = 16
    private val EFFECT_MOVES = setOf("filter", "echo", "dropin", "muffle")

    @Volatile
    private var recent: List<String> = emptyList()

    /** A plan's move (automix.js moveOf): its shape for a shaped blend, else its style. */
    fun moveOf(p: ForkV2Planner.Plan?): String? = when {
        p == null -> null
        p.style == "filter" -> "filter"
        p.style == "loop" -> "loop"
        p.handoff -> "handoff"
        p.bridge -> "bridge"
        p.style == "blend" -> p.shape ?: "blend"
        else -> "crossfade"
    }

    /** The mix [p] was heard (it reached its real end). */
    fun heard(p: ForkV2Planner.Plan?) = heardMove(moveOf(p))

    @Volatile
    private var endedFor: Any? = null

    /**
     * The mix [key] playing [p] stopped: it counts once, and only when it [reachedEnd] (a mix called
     * off never counts), however often the app's controller says so.
     */
    fun ended(key: Any, p: ForkV2Planner.Plan?, reachedEnd: Boolean) {
        if (!reachedEnd || endedFor === key) return
        endedFor = key
        heard(p)
    }

    /** The same for a loop mix, which has no blend plan of its own. */
    fun heardLoop() = heardMove("loop")

    private fun heardMove(m: String?) {
        if (m == null) return
        val l = recent + m
        recent = if (l.size > RECENT_MOVES) l.subList(l.size - RECENT_MOVES, l.size).toList() else l
    }

    /** The effect move heard on both of the last two mixes, which the next mix doesn't pick; else null. */
    fun avoidedMove(): String? {
        val l = recent
        val n = l.size
        if (n < 2) return null
        val m = l[n - 1]
        return if (m in EFFECT_MOVES && l[n - 2] == m) m else null
    }

    fun recentMoves(): List<String> = recent

    /** Tests: start empty. */
    fun clear() {
        recent = emptyList()
        endedFor = null
    }
}
