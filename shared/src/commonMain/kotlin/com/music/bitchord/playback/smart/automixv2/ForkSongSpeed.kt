package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.ForkMixPicks
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * A song's playlist speed (the extension's spotify-mixes.md rule 10, "How it plays"): a Spotify mix export
 * saves each song's speed in its playlist with the mixes ([ForkV2MixOverride.speedOut], [ForkV2MixOverride.speedIn]).
 * With Automix v2 on, each song plays at it with its pitch kept; a speed the listener set by hand is left alone
 * (then no song speed plays at all). A blend plays A at its speed and B at its own, with no ramp, glide or
 * loop mix, and B stays at its speed after the mix.
 *
 * Each app keeps [on] current (Automix v2 on, its own speed setting at x1) and plays the speeds on its players;
 * the planner reads [pair] for the blend.
 */
object ForkSongSpeed {

    /** Set by the app: Automix on under Automix v2, and the listener's own speed x1. */
    @Volatile
    var on = false

    /** The speed [id] plays at between [prevId] and [nextId] (the queue's neighbours), 1 when none or not [on]. */
    fun of(id: String?, prevId: String?, nextId: String?): Double {
        if (!on || !ForkMixPicks.active || id.isNullOrEmpty()) return 1.0
        return ForkV2MixOverride.songSpeed(id, prevId, nextId) { a, b -> ForkV2OverrideStore.get(a, b) }
    }

    /**
     * The playing song and the one before it ([of]'s prevId), set by each app as it plays (the phone from its
     * queue, the PC when a mix brings a song in), so the planner gives A the same speed the player does.
     */
    @Volatile
    var cameFrom: Pair<String, String?>? = null

    /** A blend's speeds, [out, in], or null when both are 1: A's as it plays ([cameFrom]), B's from this mix. */
    fun pair(fromId: String, toId: String): DoubleArray? {
        val o = of(fromId, cameFrom?.takeIf { it.first == fromId }?.second, toId)
        val i = of(toId, fromId, null)
        return if (off1(o) || off1(i)) doubleArrayOf(o, i) else null
    }

    fun off1(s: Double): Boolean = abs(s - 1) >= 1e-4

    /** Why the loop mix stays off for a pair whose songs play at a playlist speed, or null. */
    fun loopRefusal(fromId: String, toId: String): String? =
        if (pair(fromId, toId) != null) "a song plays at its playlist speed" else null
}
