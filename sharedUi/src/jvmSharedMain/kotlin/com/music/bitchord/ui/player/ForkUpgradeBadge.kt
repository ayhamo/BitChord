package com.music.bitchord.ui.player

import com.music.bitchord.data.NerdStats

/**
 * Fork: the quality badge while a better copy is fetched and fitted over an addon copy that already plays (the
 * upgrade pass, protocol.md 8.2.1; quality badge rule, 2026-11-27, extension 0ab9b80 M3): the playing copy keeps its
 * badge. "Upgrading Quality" is for YouTube's own audio while a copy is found for it. A lossless copy already kept
 * its badge (the rule's `isLossless != true`); this keeps a lossy addon copy's "High Quality", which the pass after a
 * lossy copy blanked for its whole run.
 */
internal object ForkUpgradeBadge {
    /** Whether [stats]' copy, an addon's (not YouTube's, which says "YouTube" or names no source), keeps its badge. */
    fun keeps(stats: NerdStats.Snapshot?): Boolean {
        if (stats == null || !stats.isHiQuality) return false
        val source = stats.sourceName?.trim()
        return !source.isNullOrEmpty() && !source.equals("YouTube", ignoreCase = true) && !source.equals("YouTube Music", ignoreCase = true)
    }
}
