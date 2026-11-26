package com.music.bitchord.data.lyrics

/**
 * Fork: the owner's lyrics-provider order, the one the PC extension uses ("also bitchord should set it as
 * default", owner via the master, 2026-11-24). Both apps set it once over whatever was saved ([VERSION] under
 * [KEY]); later changes in Settings stay the owner's. Sources not on the list are kept at the end, unticked.
 *
 * The extension's names as BitChord's: "Better Lyrics Unison" is [LyricsSource.UNISON], "Paxsenix (Apple
 * Music)" is [LyricsSource.PAXSENIX], "Spotify" is [LyricsSource.PAXSENIX_SPOTIFY] (BitChord's only Spotify
 * lyrics), "YouTube captions" is [LyricsSource.YOUTUBE_TRANSCRIPT]. The extension's "wait 2 s for a
 * higher-priority provider" has no switch here: [LyricsRepository] already never takes a lower source while a
 * higher one is pending.
 */
object ForkLyricsDefaults {
    const val KEY = "fork_lyrics_defaults"
    const val VERSION = 2

    val ticked: List<LyricsSource> = listOf(
        LyricsSource.LRC_RED,
        LyricsSource.UNISON,
        LyricsSource.BETTER_LYRICS,
        LyricsSource.BINI_LYRICS,
        LyricsSource.MUSIXMATCH,
        LyricsSource.LRCLIB,
        LyricsSource.LYRICS_PLUS,
        LyricsSource.PAXSENIX,
        LyricsSource.SIMP_MUSIC,
        LyricsSource.YOUTUBE_TRANSCRIPT,
        LyricsSource.PAXSENIX_SPOTIFY,
        // last (owner, 2026-11-25: "u can move genius"); version 2 sets the order once more over a saved one
        LyricsSource.GENIUS,
    )

    /** The whole order: [ticked], then every other offered source. */
    val order: List<LyricsSource> = ticked + LyricsSource.offered.filterNot { it in ticked }

    /** Whether the order is still to be set over [applied], the version saved under [KEY]. */
    fun due(applied: Int): Boolean = applied < VERSION
}
