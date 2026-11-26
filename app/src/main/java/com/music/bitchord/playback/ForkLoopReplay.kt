package com.music.bitchord.playback

/**
 * Fork: the loop mix's plan for a pair is planned again when the outgoing song plays again
 * (spec/automix/playback.md, "The queue during a mix" rule 2; Vivimusic 4370de0).
 *
 * [CrossfadeController]'s `loopPlanFor` keeps one plan per pair and analyses, reused while its loop
 * is still far enough ahead. A plan of "no loop mix" (null) was reused for good: one planned from a
 * late playhead (analysis or lyrics landed late, a loop mix cut short and planned again past its
 * loop), or set by `loopFallBack` when an armed loop mix gave way to the blend. When the song then
 * played again — Previous after the mix, or back to its start after a mix cut short, with the pair's
 * key still in place because the next pair hadn't planned yet — it got the blend only, and the loop
 * mix was never looked at again. The PC forgets its loop plan on every seek and new song
 * (`ForkDesktopLoopMix.forget`), so it doesn't have this.
 *
 * "Plays again" is the playhead going back more than [REPLAY_LEAD_MS] behind the furthest point the
 * cached plan was used at; playing on, pausing or a short nudge back keeps it.
 *
 * Only under Automix v2 ([v2]), whose loop-or-blend choice is the fork's (`ForkV2Loop.plan`).
 * Under BitChord's own style the cache is upstream's and keeps upstream's rule: always true there.
 */
internal class ForkLoopReplay(
    private val v2: () -> Boolean = {
        com.music.bitchord.fork.ForkSettings.automixStyle.value == com.music.bitchord.playback.smart.AutomixStyle.V2
    },
) {

    /** The furthest playhead the cached plan was made or reused at, in ms. */
    private var furthestMs = 0L

    /** The pair was just planned at [positionMs]. */
    fun planned(positionMs: Long) {
        furthestMs = positionMs
    }

    /** Whether the cached plan may be reused at [positionMs]; false when the song is playing again. */
    fun keeps(positionMs: Long): Boolean {
        if (v2() && !keeps(furthestMs, positionMs)) return false
        if (positionMs > furthestMs) furthestMs = positionMs
        return true
    }

    companion object {
        /** The spec's "more than 5 s before". */
        const val REPLAY_LEAD_MS = 5_000L

        fun keeps(furthestMs: Long, positionMs: Long): Boolean = positionMs >= furthestMs - REPLAY_LEAD_MS
    }
}
