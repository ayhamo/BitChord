package com.music.bitchord.fork

/**
 * Fork: upstream rows the rulebook replaces with one control of its own, kept in their files (so
 * merges stay small) and never shown: v1.10's "Disable Spotify Canvas" toggle on the Spotify Canvas
 * setup screen (`SpotifyCanvasAuthScreen`; the one Spotify switch is elsewhere). ("Show Best Part"
 * was one until 288fe8ea showed it again in Settings.)
 */
object ForkUpstreamRows {
    const val SHOWN = false
}
