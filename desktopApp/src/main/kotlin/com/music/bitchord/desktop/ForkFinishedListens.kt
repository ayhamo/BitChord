package com.music.bitchord.desktop

/**
 * Fork: which play a "finished" report ([DesktopScrobbling.onPlaybackEnded]: Navidrome's
 * submission scrobble, ListenBrainz's single listen, a Last.fm scrobble the timer didn't send) is
 * about, and that it goes once.
 *
 * Only the end of the queue reached `onEnded`; every other song that ran out handed over to the
 * next through the engine's `onCrossfaded` (a crossfade, Automix, a gapless or instant start), which
 * only said "now playing". So on the PC no song but the last of a queue counted as played on the
 * server or ListenBrainz. The hand-off now reports the outgoing song too, unless the hand-off was a
 * skip ([endedNaturally]), the phone's rule: a song a crossfade or Automix carried out of at its end
 * was listened to, one the listener skipped (a skip-mix) wasn't.
 *
 * The report can arrive before or after the player's state has moved on to the next song (the
 * scrobble timer follows that state on its own path), so the play that just ended is kept
 * ([songChanged]) and found by its id: before, a late report took the next song's start time and
 * cancelled its Last.fm timer. A play is reported once, whichever path gets there first.
 */
internal class ForkFinishedListens {

    /** One play of a song: when it started and whether the timer already sent its Last.fm scrobble. */
    data class Play(val songId: String, val startedAtMs: Long, val lastFmDone: Boolean)

    /** What a report goes out with; [isActive] when it is the play the scrobble timer is still on. */
    data class Ended(val play: Play, val isActive: Boolean)

    private var previous: Play? = null
    private var lastReported: Play? = null

    /** The player moved on from [old] (null: nothing was playing). */
    @Synchronized
    fun songChanged(old: Play?) {
        previous = old
    }

    /**
     * The play of [songId] that has ended: [active] when it is that song, else the one just before
     * it; one never seen gets [now] as its start. Null when that play was already reported.
     */
    @Synchronized
    fun ended(songId: String, active: Play?, now: Long): Ended? {
        val ended = when {
            active?.songId == songId -> Ended(active, isActive = true)
            previous?.songId == songId -> Ended(previous!!, isActive = false)
            else -> Ended(Play(songId, now, lastFmDone = false), isActive = false)
        }
        val last = lastReported
        if (last != null && last.songId == ended.play.songId && last.startedAtMs == ended.play.startedAtMs) return null
        lastReported = ended.play
        return ended
    }

    companion object {
        /**
         * Whether a hand-off onto [incomingId] ended the outgoing song naturally: not when it was the
         * skip-mix the listener's Next started into that song ([skipMixInto], the engine's).
         */
        fun endedNaturally(skipMixInto: String?, incomingId: String): Boolean = skipMixInto != incomingId
    }
}
