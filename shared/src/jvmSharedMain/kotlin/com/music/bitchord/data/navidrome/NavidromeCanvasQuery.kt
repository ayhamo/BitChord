package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.Song

/**
 * Fork: how a server song is asked about when looking for its clips, on the phone (`ForkCanvas`)
 * and the PC (`ForkDesktopCanvas`) alike.
 *
 * - Without a repeated "Artist - " in front of its title: files tagged that way ("TheFatRat -
 *   Monody (…)", artist "TheFatRat") were matched against no catalogue title at all.
 * - Without its album: a server album ("test", "Youtube Topic", a collection) is not the
 *   catalogue's, and the services' album check turned real hits away. The PC still sent it until
 *   2026-11-03, so "Midnight Sun" found no clip there while the phone found Apple Music's.
 */
object NavidromeCanvasQuery {

    /** [song] as the clip services are asked about it; a non-server song unchanged. */
    fun searchable(song: Song): Song {
        if (!NavidromeSettings.isNavidromeId(song.videoId)) return song
        val title = withoutArtistPrefix(song.title, song.artist)
        return song.copy(title = title, albumName = null)
    }

    fun withoutArtistPrefix(title: String, artist: String): String {
        val dash = Regex("""^\s*(.+?)\s+[-–—]\s+(.+)$""").matchEntire(title) ?: return title
        val (front, rest) = dash.destructured
        val artists = artist.split(Regex("""\s*(?:,|&|\bx\b|\bfeat\b\.?|\bft\b\.?)\s*""", RegexOption.IGNORE_CASE)) // feat/ft whole words: "Feather" stays one name
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val frontNames = front.split(Regex("""\s*(?:,|&)\s*""")).map { it.trim().lowercase() }
        return if (frontNames.isNotEmpty() && frontNames.all { it in artists } || front.trim().lowercase() == artist.trim().lowercase()) rest else title
    }
}
