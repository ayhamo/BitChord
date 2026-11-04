package com.music.bitchord

import com.music.bitchord.data.navidrome.NavidromeYouTubeCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fork: a server song without its own cover takes its video's thumbnail, never another song's cover. */
class NavidromeYouTubeCoverTest {

    private val comment = "Provided to YouTube by Universal;https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun readsTheVideoIdAtTheEndOfTheComment() {
        assertEquals("dQw4w9WgXcQ", NavidromeYouTubeCover.youtubeId(comment))
    }

    @Test
    fun takesTheLastVideoWhenTheCommentNamesSeveral() {
        val two = "see https://www.youtube.com/watch?v=AAAAAAAAAAA&t=3;https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", NavidromeYouTubeCover.youtubeId(two))
        assertEquals("dQw4w9WgXcQ", NavidromeYouTubeCover.youtubeId("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun noVideoIdInOtherComments() {
        assertNull(NavidromeYouTubeCover.youtubeId(null))
        assertNull(NavidromeYouTubeCover.youtubeId("USUM71703861")) // an ISRC ("Youtube FLAC")
        assertNull(NavidromeYouTubeCover.youtubeId("https://www.youtube.com/watch?v=short"))
        assertNull(NavidromeYouTubeCover.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQxx")) // not 11 characters
    }

    @Test
    fun aSongWithItsOwnArtKeepsTheServersCover() {
        assertNull(NavidromeYouTubeCover.artFor("mf-2kTqZ9_6650a1b2", comment))
    }

    @Test
    fun aSongWithTheAlbumsCoverTakesItsVideosThumbnail() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", NavidromeYouTubeCover.artFor("al-7fd2c1_6650a1b2", comment))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", NavidromeYouTubeCover.artFor(null, comment))
    }

    @Test
    fun withoutAVideoIdTheAlbumsCoverStays() {
        assertNull(NavidromeYouTubeCover.artFor("al-7fd2c1_6650a1b2", null))
        assertNull(NavidromeYouTubeCover.artFor("al-7fd2c1_6650a1b2", "USUM71703861"))
    }

    @Test
    fun aKeptThumbnailIsNamedByItsVideo() {
        val art = NavidromeYouTubeCover.artFor(null, comment)!!
        assertEquals("yt-dQw4w9WgXcQ", NavidromeYouTubeCover.keptName(art))
        // Only the address artFor writes: a server cover, another size or host are not this one.
        assertNull(NavidromeYouTubeCover.keptName("https://nd.test/rest/getCoverArt?id=al-1&size=600"))
        assertNull(NavidromeYouTubeCover.keptName("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"))
        assertNull(NavidromeYouTubeCover.keptName("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=x"))
        assertNull(NavidromeYouTubeCover.keptName("https://evil.test/vi/dQw4w9WgXcQ/hqdefault.jpg"))
    }
}
