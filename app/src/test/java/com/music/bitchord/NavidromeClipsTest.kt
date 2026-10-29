package com.music.bitchord

import com.music.bitchord.data.navidrome.NavidromeClips
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fork: the x-clip URL is the song's folder and file name, with the clip suffix in place of the extension. */
class NavidromeClipsTest {

    private val base = "https://nd.example.test".toHttpUrl()

    @Test
    fun folderAndStemWithTheClipSuffix() {
        assertEquals(
            "https://nd.example.test/rest/x-clip/Youtube%20FLAC/005-Madkid%20-%20RISE.background.mp4",
            NavidromeClips.clipUrl(base, "/music/Youtube FLAC/005-Madkid - RISE.flac").toString(),
        )
    }

    @Test
    fun relativePathsAndDotsInTheNameWork() {
        assertEquals(
            "https://nd.example.test/rest/x-clip/Album/01.%20Intro%20v1.2.background.mp4",
            NavidromeClips.clipUrl(base, "Album/01. Intro v1.2.opus").toString(),
        )
    }

    @Test
    fun aFileWithNoFolderHasNoClip() {
        assertNull(NavidromeClips.clipUrl(base, "song.flac"))
    }
}
