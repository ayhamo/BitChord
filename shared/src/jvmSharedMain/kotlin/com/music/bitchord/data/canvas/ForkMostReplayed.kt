package com.music.bitchord.data.canvas

/**
 * Fork: "Jump to the most replayed part", the player menu's row on the phone and the PC, as the
 * owner's phone patches have it (`BestPart.jump`, the "..." menu of YT Music's player): seek the
 * playing song to where YouTube's "Most replayed" curve rises to its peak.
 *
 * Both halves already existed for the music-video canvas: [ForkVideoCanvas.heatmap] fetches the
 * curve (the WEB `next` answer's `MARKER_TYPE_HEATMAP` markers, 20 answers kept in memory) and
 * [ForkBestPart.partStart] picks the start of its rise, the same pick the canvas loops. Here they are
 * read for the playing song's own id, as the patch does, not for the music video the canvas matched:
 * a video's timeline runs on its own intro and cuts, so its peak would land at the wrong second of
 * the song. The curve's own length stands for the song's ([ForkBestPart.partStart] with 0), also as
 * the patch does.
 *
 * Only a YouTube id (11 characters of the URL-safe alphabet) has a curve: a Navidrome song, a radio
 * station or a device file gets no row, so nothing about it is ever sent to YouTube.
 */
object ForkMostReplayed {

    /** What a jump found. */
    sealed interface Outcome {
        /** The part starts at [ms] of the song. */
        data class Jump(val ms: Long) : Outcome

        /** YouTube answered without a curve, or couldn't be reached. */
        data object NoCurve : Outcome

        /** A curve, but a flat one, or a song under a minute: nothing stands out. */
        data object NothingStandsOut : Outcome
    }

    const val MENU_TITLE = "Jump to the most replayed part"
    const val NO_CURVE = "YouTube has no most replayed part for this song"
    const val FLAT = "No part of this song stands out in YouTube's most replayed"

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Whether [videoId] is a YouTube id, the only kind with a "Most replayed" curve. */
    fun eligible(videoId: String): Boolean = YOUTUBE_ID.matches(videoId)

    /** The jump for a curve already read: [ForkBestPart.partStart] on the curve's own length. */
    fun outcomeOf(markers: List<ForkBestPart.Marker>?): Outcome {
        if (markers == null) return Outcome.NoCurve
        val at = ForkBestPart.partStart(markers, 0)
        return if (at < 0) Outcome.NothingStandsOut else Outcome.Jump(at)
    }

    /** Looks the curve up for [videoId] and picks the part. Blocking (a network call the first time): off the main thread. */
    fun find(videoId: String): Outcome =
        if (!eligible(videoId)) Outcome.NoCurve else outcomeOf(ForkVideoCanvas.heatmap(videoId))

    /** The message after a jump to [ms]: "Most replayed part, 1:23", as the patch's toast. */
    fun jumped(ms: Long): String {
        val s = ms / 1000
        return "Most replayed part, ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** The message for [outcome]. */
    fun message(outcome: Outcome): String = when (outcome) {
        is Outcome.Jump -> jumped(outcome.ms)
        Outcome.NoCurve -> NO_CURVE
        Outcome.NothingStandsOut -> FLAT
    }
}
