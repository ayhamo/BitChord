package com.music.bitchord.playback.smart.automixv2

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Fork: the bar, the extension's analysis-cases.md "The bar" [all] (dedb2a2d; automix.js `withBarPhase`, extension
 * commit c8dff6a). A grid's bars used to start at the beat model's first downbeat, so one stray downbeat (a pickup
 * in the intro, a fill) shifted every bar of the song. Now each of the model's downbeats in the grid's window (head:
 * [ForkV2Analysis.headBeats], tail: [ForkV2Analysis.tailBeats]) votes for its place in the bar, k mod 4 from the
 * first downbeat, when it is within a quarter beat of that grid beat; the most votes win, a tie keeps the first
 * downbeat's place, and `firstDownbeatSec` moves 0-3 beats later on the same grid.
 *
 * Applying it again changes nothing, so it runs on every analysis as it is loaded ([ForkV2FromTrackAnalysis.map]
 * for the apps' own analyses, [ForkV2Analysis.fromJson] for saved v2 JSON): one saved before it is phased too.
 */
object ForkV2BarPhase {

    /** [a] with each grid's bars on its model downbeats' place, in place; [a] back. */
    fun apply(a: ForkV2Analysis): ForkV2Analysis {
        a.firstDownbeatSec = voted(a.beatIntervalSec, a.firstDownbeatSec, a.headBeats?.downbeats)
        a.tailGrid?.let { it.firstDownbeatSec = voted(it.beatIntervalSec, it.firstDownbeatSec, a.tailBeats?.downbeats) }
        return a
    }

    /** The grid's first downbeat moved to the place most of [downs] fall on ([fd] itself on a tie or with no votes). */
    internal fun voted(beatSec: Double, fd: Double, downs: DoubleArray?): Double {
        if (!(beatSec > 0) || fd.isNaN() || downs == null || downs.isEmpty()) return fd
        val votes = IntArray(4)
        for (d in downs) {
            // Math.round as the extension's (halves up)
            val k = kotlin.math.floor((d - fd) / beatSec + 0.5).roundToLong()
            if (abs(fd + k * beatSec - d) <= beatSec / 4) votes[((k % 4).toInt() + 4) % 4]++
        }
        var best = 0
        for (p in 1 until 4) if (votes[p] > votes[best]) best = p
        return fd + best * beatSec
    }
}
