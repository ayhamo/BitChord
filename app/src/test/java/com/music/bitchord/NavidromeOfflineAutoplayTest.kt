package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.playback.loadAutoplayTracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fork: offline, AutoPlay and "Start radio" on a Navidrome song queue nothing (the server's songs
 * wouldn't play), as on the PC, instead of asking the server.
 */
class NavidromeOfflineAutoplayTest {

    private val seed = Song(videoId = "navidrome:s1", title = "Song", artist = "Artist", thumbnailUrl = null)

    @After
    fun online() {
        NavidromePlatform.offline = MutableStateFlow(false)
    }

    @Test
    fun offlineAServerSongContinuesWithNothing() = runBlocking {
        NavidromePlatform.offline = MutableStateFlow(true)
        assertEquals(emptyList<Song>(), loadAutoplayTracks(emptyList(), seed).getOrThrow())
        assertEquals(emptyList<Song>(), loadAutoplayTracks(emptyList(), seed, forkStation = true).getOrThrow())
    }
}
