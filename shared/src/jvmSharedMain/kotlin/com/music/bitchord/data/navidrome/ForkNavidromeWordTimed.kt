package com.music.bitchord.data.navidrome

import com.music.bitchord.data.lyrics.LyricLine

/**
 * Fork: a Navidrome song's server lyrics against the online sources' (owner, 2026-11-25: "word timed wins from
 * bitchord, ignore navidrome first for this").
 *
 * The server's timed lyrics ended the lookup: they belong to this exact recording, so no online source was asked.
 * Most servers' lyrics are line-timed only, though, while BitChord's sources often have the same song word by word.
 * Now line-timed server lyrics show at once and the sources are still asked; a word-timed answer takes their place,
 * and anything less keeps the server's. Word-timed server lyrics still end the lookup. Both apps' players use this.
 */
object ForkNavidromeWordTimed {

    /** Whether, with [server]'s lyrics for a Navidrome song showing, the online sources are still asked. */
    fun asksOnline(server: List<LyricLine>?): Boolean = server != null && server.none { it.isWordSynced }

    /** Whether the online [found] lines take the place of the server's line-timed ones: only word-timed ones do. */
    fun wins(found: List<LyricLine>?): Boolean = found != null && found.any { it.isWordSynced }

    /** The lines to show: [found] when they [wins], else the [server]'s (else [found], with nothing from the server). */
    fun pick(server: List<LyricLine>?, found: List<LyricLine>?): List<LyricLine>? = when {
        server == null -> found
        !asksOnline(server) -> server
        wins(found) -> found
        else -> server
    }
}
