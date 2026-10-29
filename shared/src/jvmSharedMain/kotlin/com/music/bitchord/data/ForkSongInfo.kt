package com.music.bitchord.data

import com.music.bitchord.data.model.Song

/**
 * Fork: Song Info for songs YouTube doesn't know (the owner's Navidrome
 * server). Set by the app; [SongInfoRepository.load] asks it first, and a null
 * answer falls through to the YouTube lookup as before.
 */
object ForkSongInfo {
    @Volatile var provider: (suspend (Song) -> SongInfo?)? = null
}
