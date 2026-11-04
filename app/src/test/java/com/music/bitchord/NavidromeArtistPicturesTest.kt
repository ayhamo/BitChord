package com.music.bitchord

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.navidrome.NavidromeArtistPictures
import com.music.bitchord.data.navidrome.NavidromeArtistPictures.Entry
import com.music.bitchord.data.navidrome.NavidromeClient
import com.music.bitchord.data.navidrome.PlaceholderLearner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: Navidrome artists' pictures: who counts as the artist, the placeholder, what is kept. */
class NavidromeArtistPicturesTest {

    private fun artist(title: String, thumb: String? = "https://lh3.googleusercontent.com/$title=w120-h120-l90-rj", type: BrowseType = BrowseType.ARTIST) =
        SearchResult.Browse(BrowseItem(browseId = "UC$title", title = title, subtitle = "Artist", thumbnailUrl = thumb, type = type))

    @Test
    fun youTubeTakesOnlyTheSameName() {
        val hits = listOf(artist("Geiger"), artist("Levent Geiger"))
        assertEquals("https://lh3.googleusercontent.com/Levent Geiger=w120-h120-l90-rj", NavidromeArtistPictures.pickYouTube("Levent Geiger", hits))
        // A search returns someone for any name: a near name is somebody else.
        assertNull(NavidromeArtistPictures.pickYouTube("Levent Geigerr", hits))
        assertNull(NavidromeArtistPictures.pickYouTube("Levent", hits))
    }

    @Test
    fun youTubeFoldsCaseAccentsAndPunctuation() {
        assertEquals(
            "https://lh3.googleusercontent.com/Beyonce=w120-h120-l90-rj",
            NavidromeArtistPictures.pickYouTube("Beyoncé", listOf(artist("Beyonce"))),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/AC/DC=w120-h120-l90-rj",
            NavidromeArtistPictures.pickYouTube("ac dc", listOf(artist("AC/DC"))),
        )
    }

    @Test
    fun youTubeSkipsNonArtistsPictureLessHitsAndLooksAtThreeOnly() {
        assertNull(NavidromeArtistPictures.pickYouTube("Levent Geiger", listOf(artist("Levent Geiger", type = BrowseType.ALBUM))))
        assertEquals(
            "second",
            NavidromeArtistPictures.pickYouTube("Levent Geiger", listOf(artist("Levent Geiger", thumb = null), artist("Levent Geiger", thumb = "second"))),
        )
        val far = listOf(artist("A"), artist("B"), artist("C"), artist("Levent Geiger"))
        assertNull(NavidromeArtistPictures.pickYouTube("Levent Geiger", far))
        assertNull(NavidromeArtistPictures.pickYouTube("   ", listOf(artist("x"))))
    }

    @Test
    fun placeholderIsTheHashTwoUnrelatedArtistsShare() {
        val learner = PlaceholderLearner()
        assertFalse(learner.note("photo-a", "levent geiger"))
        assertFalse(learner.note("photo-b", "someone else"))
        assertFalse(learner.note("ph", "artist one"))
        assertTrue(learner.note("ph", "artist two"))
        assertTrue(learner.note("ph", "artist one")) // once learned, for everyone
        assertEquals(setOf("ph"), learner.known())
    }

    @Test
    fun theSamePhotoOnRelatedNamesIsNoPlaceholder() {
        val learner = PlaceholderLearner()
        assertFalse(learner.note("photo", "levent geiger"))
        assertFalse(learner.note("photo", "levent geiger"))
        assertFalse(learner.note("photo", "levent geiger band"))
        // An unrelated name with the same bytes: that is the placeholder.
        assertTrue(learner.note("photo", "joanna"))
        assertFalse(PlaceholderLearner().related("ann", "joanna")) // words, not letters
    }

    @Test
    fun learnerStartsFromWhatWasKept() {
        val learner = PlaceholderLearner()
        learner.reset(placeholders = listOf("ph"), pictures = listOf("photo" to "kept artist"))
        assertTrue(learner.note("ph", "anyone"))
        assertTrue(learner.note("photo", "unrelated")) // a kept "real" picture shared with another artist
        learner.reset(emptyList(), emptyList())
        assertTrue(learner.known().isEmpty())
    }

    @Test
    fun entriesRoundTrip() {
        val entries = listOf(
            Entry.Server("https://nd.example/rest/getCoverArt?id=ar-1&size=600", "abc", "levent geiger", 10L),
            Entry.Picture("https://lh3.googleusercontent.com/x=w120-h120", "youtube", 20L),
            Entry.None(30L),
        )
        entries.forEach { assertEquals(it, NavidromeArtistPictures.decode(NavidromeArtistPictures.encode(it))) }
        assertNull(NavidromeArtistPictures.decode("picture\n\nyoutube\n1\n"))
        assertNull(NavidromeArtistPictures.decode("garbage"))
        assertNull(NavidromeArtistPictures.decode("none\n\n\nnot-a-time\n"))
    }

    @Test
    fun missesExpireAfterTwoWeeksPicturesDoNot() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        assertTrue(NavidromeArtistPictures.stands(Entry.None(now - 13 * day), now))
        assertFalse(NavidromeArtistPictures.stands(Entry.None(now - 15 * day), now))
        assertTrue(NavidromeArtistPictures.stands(Entry.Picture("u", "apple", now - 400 * day), now))
        assertFalse(NavidromeArtistPictures.stands(Entry.None(now + day), now)) // a clock set back: asked again
    }

    @Test
    fun applePortraitsOnly() {
        assertTrue(NavidromeArtistPictures.isApplePortrait("https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages126/v4/aa/bb/x.jpg/1200x630cw.png"))
        assertTrue(NavidromeArtistPictures.isApplePortrait("https://is1-ssl.mzstatic.com/image/thumb/Features116/v4/aa/x.jpg/1200x630cw.png"))
        assertFalse(NavidromeArtistPictures.isApplePortrait("https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/aa/x.jpg/1200x630bb.jpg"))
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages126/v4/x.jpg/1200x1200cc.jpg",
            NavidromeArtistPictures.squarePortrait("https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages126/v4/x.jpg/1200x630cw.png"),
        )
    }

    @Test
    fun artistIndexIsEveryLetter() {
        val result = Json.parseToJsonElement(
            """{"status":"ok","artists":{"ignoredArticles":"The","index":[
                {"name":"L","artist":[{"id":"a1","name":"Levent Geiger","coverArt":"ar-a1_0"},{"id":"a2","name":"Lorde"}]},
                {"name":"Z","artist":{"id":"a3","name":"Zedd","coverArt":"ar-a3_0"}}
            ]}}""",
        ).jsonObject
        val rows = NavidromeClient.parseArtistIndex(result)
        assertEquals(listOf("Levent Geiger", "Lorde", "Zedd"), rows.map { it.name })
        assertEquals("ar-a1_0", rows[0].coverArt)
        assertNull(rows[1].coverArt)
        assertTrue(NavidromeClient.parseArtistIndex(Json.parseToJsonElement("""{"status":"ok"}""").jsonObject).isEmpty())
    }

    @Test
    fun subsonicErrorsAreNoPicture() {
        assertTrue(NavidromeClient.isErrorDocument("""{"subsonic-response":{"status":"failed","error":{"code":70}}}""".toByteArray()))
        assertTrue(NavidromeClient.isErrorDocument("""<?xml version="1.0"?><subsonic-response status="failed"/>""".toByteArray()))
        assertTrue(NavidromeClient.isErrorDocument(ByteArray(0)))
        assertFalse(NavidromeClient.isErrorDocument(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertFalse(NavidromeClient.isErrorDocument("RIFF....WEBPVP8 ".toByteArray()))
    }
}
