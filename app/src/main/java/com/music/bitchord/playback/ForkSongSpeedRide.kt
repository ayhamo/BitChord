package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.automixv2.ForkSongSpeed
import kotlin.math.abs

/**
 * The phone's side of a song's playlist speed (spotify-mixes.md rule 10, [ForkSongSpeed]): with Automix on and the
 * listener's own speed at x1, the session player plays each song at its speed (ExoPlayer keeps the pitch).
 *
 * - Between mixes ([hold], from [CrossfadeController]'s idle tick): the speed is put on the player when it is at x1 or
 *   at one this put there, so a speed set by hand is never overridden; a song with none goes back to x1 from ours.
 * - In a blend the plan's rates are the songs' speeds already (A's ramp is skipped, [rampFrom]); the ease after it
 *   ends on B's speed, not x1 ([easeBase]).
 */
object ForkSongSpeedRide {

    /** The speed this put on a player for a song, 0 when none. */
    private var set = 0f

    /** [ForkSongSpeed.on] from the settings: Automix on and the listener's speed x1. */
    fun refresh() {
        ForkSongSpeed.on = AppSettings.smartFadeEnabled.value && AppSettings.playbackSpeed.value == 1f
    }

    /** The speed [player]'s song plays at between its neighbours in the queue. */
    fun speedOf(player: ExoPlayer): Double {
        val item = player.currentMediaItem ?: return 1.0
        fun idAt(i: Int) = if (i == C.INDEX_UNSET || i >= player.mediaItemCount) null else player.getMediaItemAt(i).mediaId
        val prev = idAt(player.previousMediaItemIndex)
        ForkSongSpeed.cameFrom = item.mediaId to prev // (the planner's A speed is this one)
        return ForkSongSpeed.of(item.mediaId, prev, idAt(player.nextMediaItemIndex))
    }

    /** Between mixes: [player] (the session's) at its song's speed, never over the listener's own. */
    fun hold(player: ExoPlayer) {
        refresh()
        val h = held(speedOf(player).toFloat(), player.playbackParameters.speed, set, AppSettings.playbackSpeed.value)
        h.speed?.let { player.setPlaybackSpeed(it) }
        set = h.set
    }

    /** What [hold] does: the speed to put on the player (null: leave it), and what [set] is then. */
    internal class Held(val speed: Float?, val set: Float)

    /**
     * [hold]'s choice for a player at [cur] whose song wants [want] (1: none), with [set] what this last put there and
     * [listener] the listener's own speed. A listener's speed other than x1 is theirs, whatever it is: this then puts
     * nothing on the player and forgets its own. Without that, a listener who set x1.08 by hand while the song had played
     * at its playlist x1.08 matched [set], and the song's "none" (off with a listener speed) put the player back to x1
     * over the listener's choice (audit 2026-11-26 part 1b #4; spotify-mixes.md rule 10: "A speed the user set ... is
     * left alone").
     */
    internal fun held(want: Float, cur: Float, set: Float, listener: Float): Held {
        if (abs(listener - 1f) >= EPS) return Held(null, 0f)
        if (ForkSongSpeed.off1(want.toDouble())) {
            if (abs(cur - want) > EPS && (abs(cur - 1f) < EPS || (set != 0f && abs(cur - set) < EPS))) return Held(want, want)
            return Held(null, set)
        }
        if (set != 0f && abs(cur - set) < EPS) return Held(1f, 0f)
        return Held(null, set)
    }

    /**
     * The rate [player] already plays at over the listener's speed: a song at its speed is at the plan's outgoing
     * rate before the blend, so the ramp up to it (from x1) is skipped.
     */
    fun rampFrom(player: ExoPlayer): Double {
        val hand = AppSettings.playbackSpeed.value
        return if (hand > 0f) (player.playbackParameters.speed / hand).toDouble() else 1.0
    }

    /** What an ease on [player] ends on: the listener's speed times its song's (noted as ours). */
    fun easeBase(player: ExoPlayer): Float {
        refresh()
        val s = speedOf(player)
        // (a song with none: nothing of ours on the player after the ease, so a later match with an old one isn't taken
        // for it)
        set = if (ForkSongSpeed.off1(s)) (AppSettings.playbackSpeed.value * s).toFloat() else 0f
        return (AppSettings.playbackSpeed.value * s).toFloat()
    }

    private const val EPS = 1e-4f
}
