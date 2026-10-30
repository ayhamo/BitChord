package com.music.bitchord.data.navidrome

import com.music.bitchord.data.ForkSongInfo
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.model.Song

/**
 * Song Info for a Navidrome song (fork-only), from the server's `getSong`:
 * the album, the release date (OpenSubsonic's original or release date, else
 * the year tag), your plays on the server, and your rating or star in the
 * Likes row. The upstream page only asks YouTube, which has never heard of
 * these songs, so every row read "Not available". There are no comments on a
 * server, so that row stays "Not available".
 */
object NavidromeSongInfo {

    fun init() {
        ForkSongInfo.provider = ::load
    }

    private suspend fun load(song: Song): SongInfo? {
        if (!NavidromeSettings.isNavidromeId(song.videoId)) return null
        val id = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        return runCatching { NavidromeRepository.client().songDetails(id) }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }.fold(
            onSuccess = { d ->
                SongInfo(
                    album = song.albumName,
                    releaseDate = d.releaseDate,
                    releaseYear = d.year?.toString(),
                    views = (d.playCount ?: 0).toString(),
                    likes = when {
                        (d.userRating ?: 0) > 0 -> d.userRating!!.coerceIn(0, 5).let { "★".repeat(it) + "☆".repeat(5 - it) }
                        d.starred -> "★"
                        else -> null
                    },
                )
            },
            onFailure = { SongInfo(album = song.albumName, failed = true) },
        )
    }
}
