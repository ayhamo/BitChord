package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.ForkLyricsDefaults

/**
 * Fork: the PC side of [ForkLyricsDefaults], the owner's provider order set once over the saved one. Run by
 * `BitChordDesktopApp` at start, before its lyrics settings read the order (not on every [DesktopPersistence]: a
 * test's cleared preferences would get it too). NetEase (PC only, [ForkNetEaseLyrics])
 * isn't on the owner's list, so it goes to the end unticked with the rest.
 */
internal object ForkDesktopLyricsDefaults {

    fun apply(persistence: DesktopPersistence) {
        if (!ForkLyricsDefaults.due(persistence.int(ForkLyricsDefaults.KEY, 0))) return
        val ticked = ForkLyricsDefaults.ticked.map { it.label }
        val all = DesktopLyricsClient.sources.map { it.name }
        persistence.saveLyricsSourceOrder(ticked + all.filterNot { it in ticked })
        persistence.saveLyricsEnabledSources(ticked.filter { it in all }.toSet())
        persistence.saveInt(ForkLyricsDefaults.KEY, ForkLyricsDefaults.VERSION)
    }
}
