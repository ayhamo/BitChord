package com.music.bitchord.fork

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-24, 3B #5: v1.10's queue picker and album menu sent server songs to YouTube's playlists. */
class ForkPlaylistTargetsTest {

    private fun song(id: String) = Song(videoId = id, title = "t", artist = "a", thumbnailUrl = null)

    @Test
    fun aServerSongOrStationIsNotSentToYouTube() {
        assertTrue(ForkPlaylistTargets.youTubeCanHold(song("dQw4w9WgXcQ")))
        assertFalse(ForkPlaylistTargets.youTubeCanHold(song("navidrome:9f2c")))
        assertFalse(ForkPlaylistTargets.youTubeCanHold(song("radio:3")))
        assertTrue(ForkPlaylistTargets.albumOffersPlaylists("MPREb_abc"))
        assertTrue("a downloads album keeps it", ForkPlaylistTargets.albumOffersPlaylists("local:group:album:downloads:Some Album"))
        assertFalse(ForkPlaylistTargets.albumOffersPlaylists("local:group:album:navidrome:Some Album"))
    }
}
