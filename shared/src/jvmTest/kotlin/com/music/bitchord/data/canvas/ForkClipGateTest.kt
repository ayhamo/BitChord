package com.music.bitchord.data.canvas

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Prism's clip gates as ported: what is a song, the official type, playable, and the title's words. */
class ForkClipGateTest {

    private fun song(id: String, localUri: String? = null) = Song(videoId = id, title = "t", artist = "a", thumbnailUrl = null, localUri = localUri)

    @Test
    fun songsAndNotSongs() {
        assertTrue(ForkClipGate.isSong(song("dQw4w9WgXcQ")))
        assertTrue(ForkClipGate.isSong(song("navidrome:abc", localUri = "https://server/rest/stream")))
        assertFalse(ForkClipGate.isSong(song("")))
        assertFalse(ForkClipGate.isSong(song("radio:http://stream")))
        assertFalse(ForkClipGate.isSong(song("content://media/external/audio/media/42", localUri = "content://media/external/audio/media/42")))
    }

    @Test
    fun officialAndPlayable() {
        assertTrue(ForkClipGate.official("MUSIC_VIDEO_TYPE_OMV"))
        assertFalse(ForkClipGate.official("MUSIC_VIDEO_TYPE_UGC"))
        assertFalse(ForkClipGate.official(null))
        assertTrue(ForkClipGate.playable("OK", isPrivate = false, isLive = false, isLiveContent = false, lengthSec = 200))
        assertFalse(ForkClipGate.playable("OK", isPrivate = true, isLive = false, isLiveContent = false, lengthSec = 200))
        assertFalse(ForkClipGate.playable("OK", isPrivate = false, isLive = true, isLiveContent = true, lengthSec = 200))
        assertFalse(ForkClipGate.playable("OK", isPrivate = false, isLive = false, isLiveContent = true, lengthSec = 0))
        assertTrue(ForkClipGate.playable("OK", isPrivate = false, isLive = false, isLiveContent = true, lengthSec = 200))
        assertFalse(ForkClipGate.playable("LOGIN_REQUIRED", isPrivate = false, isLive = false, isLiveContent = false, lengthSec = 200))
    }

    @Test
    fun titleWords() {
        assertTrue(ForkClipGate.titleSays("Dynamite", "BTS Dynamite FANCAM"))
        assertTrue(ForkClipGate.titleSays("Dynamite", "BTS - Dynamite (Live Performance)"))
        assertTrue(ForkClipGate.titleSays("Song", "Artist - Song Jukebox"))
        assertTrue(ForkClipGate.titleSays("Song", "Artist 'Song' 직캠"))
        assertFalse(ForkClipGate.titleSays("Song", "Artist - Song (Official Music Video)"))
        // whole words only, and a word in the song's own title doesn't count
        assertFalse(ForkClipGate.titleSays("Alive", "Artist - Alive (Official Video)"))
        assertFalse(ForkClipGate.titleSays("Live Forever", "Artist - Live Forever (Official Video)"))
        // "full song" without "video" is an audio upload; "Full Video Song" is the video
        assertTrue(ForkClipGate.titleSays("Song", "Song Full Song"))
        assertFalse(ForkClipGate.titleSays("Song", "Song Full Video Song"))
        // lyric videos pass only when allowed
        assertTrue(ForkClipGate.titleSays("Song", "Artist - Song (Lyric Video)"))
        assertFalse(ForkClipGate.titleSays("Song", "Artist - Song (Lyric Video)", lyricsOk = true))
        assertFalse(ForkClipGate.titleSays("Song", "Artist 'Song' 가사", lyricsOk = true))
        assertEquals(setOf(ForkClipGate.Refusal.NOT_OFFICIAL, ForkClipGate.Refusal.TITLE), ForkClipGate.SEARCH_AGAIN)
    }

    @Test
    fun lyricVideosOnlyWhenAllowed() {
        val lyric = ForkMusicVideoMatch.Video("abcdefghijk", "Artist - Song (Lyric Video)", "Artist", 200)
        assertEquals(emptyList(), ForkMusicVideoMatch.candidates("Song", listOf("Artist"), 200, listOf(lyric)))
        assertEquals(listOf(lyric), ForkMusicVideoMatch.candidates("Song", listOf("Artist"), 200, listOf(lyric), lyricsOk = true))
    }
}
