package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.ForkLyricsRules

/**
 * Fork: lyrics drawn ahead of the sound (spec/lyrics/lyrics.md at 0836435, §1.5, Mornye a422ffd1
 * `lyricsDisplayLead`). A frame reaches the screen about two refresh intervals after it is drawn,
 * so the lyrics panel (the view, not the one-line strip) draws a playing song's lyrics for the
 * clock's position plus [ForkLyricsRules.displayLeadMs]: two refresh intervals, kept to 8-40 ms,
 * times the playback speed. Paused, loading or seeking the clock stops running (its frame loop
 * only runs while the song plays) and the exact position is drawn.
 *
 * Fed by `rememberLyricClock`'s own frame loop, one call per frame (a hook there):
 *  - the refresh interval is measured from those frames (an average of the 4-34 ms intervals; a
 *    skipped frame isn't one), 60 Hz until then, so a 120 Hz phone or a 144 Hz monitor gets its
 *    own;
 *  - the playback speed is how fast the clock itself moves against the frame clock (an average,
 *    jumps of a seek left out): `:sharedUi` isn't told the phone's speed setting, and the clock
 *    is steered by the player's readings, so it runs at whatever speed the song plays.
 *
 * "Lyrics by real timing" keeps it: it is not one of §2's leads, only the screen's delay.
 */
internal class ForkLyricsDrawAhead {
    private var lastFrameMs = Double.NaN
    private var lastPositionMs = 0L
    private var refreshMs = 1000.0 / 60.0
    private var speed = 1.0

    /** [positionMs] (the clock's, for the frame at [nowMs]) plus the lead while [playing]. */
    fun at(nowMs: Double, positionMs: Long, playing: Boolean): Long {
        val dt = nowMs - lastFrameMs
        if (dt > MIN_FRAME_MS && dt < MAX_FRAME_MS) {
            refreshMs += (dt - refreshMs) * SMOOTHING
            val rate = (positionMs - lastPositionMs) / dt
            if (rate > MIN_RATE && rate < MAX_RATE) speed += (rate - speed) * SMOOTHING
        }
        lastFrameMs = nowMs
        lastPositionMs = positionMs
        return positionMs + ForkLyricsRules.displayLeadMs(refreshMs, speed, playing)
    }

    private companion object {
        const val MIN_FRAME_MS = 4.0
        const val MAX_FRAME_MS = 34.0
        const val SMOOTHING = 0.05
        const val MIN_RATE = 0.2
        const val MAX_RATE = 5.0
    }
}
