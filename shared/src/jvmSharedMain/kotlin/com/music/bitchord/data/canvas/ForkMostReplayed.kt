package com.music.bitchord.data.canvas

/**
 * Fork: the song's best part from YouTube's "Most replayed" curve, for "Play from the best part" on
 * the phone and the PC.
 *
 * The menu's "Jump to the most replayed part" went with the switch to v1.10's Best Part pill (owner,
 * 2026-11-24): it sought the same spot the pill shows (the curve's peak, snapped to a lyric line), and
 * "Play from the best part" seeks a playing song there too, your own part first.
 *
 * Both halves already existed for the music-video canvas: [ForkVideoCanvas.heatmap] fetches the
 * curve (the WEB `next` answer's `MARKER_TYPE_HEATMAP` markers, 20 answers kept in memory) and
 * [ForkBestPart.partStart] picks the start of its rise, the same pick the canvas loops. Here they are
 * read for the song's own id, not for the music video the canvas matched: a video's timeline runs on
 * its own intro and cuts, so its peak would land at the wrong second of the song.
 *
 * Only a YouTube id (11 characters of the URL-safe alphabet) has a curve: for a Navidrome song, a
 * radio station or a device file nothing is ever sent to YouTube.
 */
object ForkMostReplayed {

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Whether [videoId] is a YouTube id, the only kind with a "Most replayed" curve. */
    fun eligible(videoId: String): Boolean = YOUTUBE_ID.matches(videoId)

    /**
     * The song's best part for "Play from the best part": the listener's own, else the most replayed
     * part, else the chorus in the lyrics the player had, snapped to a line; null for none. Blocking:
     * off the main thread. [durationMs]: the song's length (0 unknown).
     */
    fun bestPart(videoId: String, durationMs: Long): Long? {
        val lines = ForkBestParts.lines(videoId)
        ForkBestParts.mine(videoId)?.let { return it }
        val peak = if (eligible(videoId)) ForkBestPart.partStart(ForkVideoCanvas.heatmap(videoId), 0) else -1L
        return ForkBestParts.start(null, peak, lines, durationMs)
    }

    /**
     * "Play from the best part"'s answer, the same steps on both apps: where to play the song from,
     * or null to do nothing. A party that locks the controls is told at once and nothing is looked up
     * (it was looked up first, then "playing from the start" was said right before "only the host",
     * and nothing played); it is checked again once the part is found. With no part within
     * [ForkBestParts.FIND_MS] the answer is 0 and [ForkBestParts.FROM_START] is said, and it is
     * then kept: the apps play the song from the start, a playing one too (it used to stay put).
     */
    suspend fun startForPlay(locked: () -> Boolean, lockedText: String, say: (String) -> Unit, find: suspend () -> Long?): Long? {
        if (locked()) { say(lockedText); return null }
        val found = kotlinx.coroutines.withTimeoutOrNull(ForkBestParts.FIND_MS) { find() }
        if (locked()) { say(lockedText); return null }
        if (found == null) say(ForkBestParts.FROM_START)
        return found ?: 0L
    }
}
