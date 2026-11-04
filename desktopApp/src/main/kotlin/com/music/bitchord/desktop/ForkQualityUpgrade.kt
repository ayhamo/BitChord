package com.music.bitchord.desktop

/**
 * Fork: the shared player's `qualityUpgraded` on the PC, the phone's `EXTRA_QUALITY_UPGRADED`.
 *
 * The phone marks a song when its upgrade swap installs a better copy mid-play, and the mark goes
 * when "Revert to original" puts YouTube's back; the player then shows its revert cue on the "…"
 * for a few seconds. The PC's engine publishes the copy it plays as [DesktopPlaybackState]'s
 * source and format, and both its upgrade paths (the crossfaded `forkSwap` and the old cut-over)
 * end there, so the mark is read off that state with no hook in the engine: the same song, its
 * source changed to a copy that is not YouTube's and is better than the one before it.
 *
 * A song opening straight on a lossless copy (the raced resolve won) is not marked, as on the
 * phone: nothing was swapped. A reload goes through a state with no source; that keeps the mark,
 * so a revert (back to "youtube") clears it and "Upgrade quality" sets it.
 */
internal object ForkQualityUpgrade {

    /** What the last state said: the song, the copy it played, and whether a swap made it better. */
    data class Mark(
        val songId: String? = null,
        val sourceId: String? = null,
        val format: DesktopStreamFormat? = null,
        val upgraded: Boolean = false,
    )

    private const val YOUTUBE = "youtube"

    /** The mark after one more published state. */
    fun step(mark: Mark, songId: String?, sourceId: String?, format: DesktopStreamFormat?): Mark = when {
        songId != mark.songId -> Mark(songId, sourceId, format)
        sourceId == null -> mark
        mark.sourceId == null -> mark.copy(sourceId = sourceId, format = format)
        sourceId == mark.sourceId -> mark.copy(format = format ?: mark.format)
        else -> Mark(
            songId = songId,
            sourceId = sourceId,
            format = format,
            upgraded = sourceId != YOUTUBE &&
                (mark.format == null || format != null && DesktopSourceRegistry.worthSwapping(format, mark.format)),
        )
    }

    /**
     * Kept here, not in the composition: the player is left and opened again mid-song, and the
     * mark has to outlive that, as the phone's lives on the media item. [step] gives the same
     * answer for a state read twice, so recomposing changes nothing.
     */
    @Volatile
    private var mark = Mark()

    /** Whether the playing song's copy was upgraded mid-play, for `NowPlayingScreen(qualityUpgraded = …)`. */
    fun upgraded(state: DesktopPlaybackState): Boolean {
        val next = step(mark, state.song?.videoId, state.streamSourceId, state.streamFormat)
        mark = next
        return next.upgraded
    }
}
