package com.music.bitchord.data.navidrome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Similar artists" without the page's own collaborations, and a collaboration's picture from its lead. */
class NavidromeSimilarArtistsTest {

    @Test
    fun creditsSplitOnTheUsualSeparators() {
        assertEquals(listOf("Metro Boomin", "The Weeknd", "21 Savage"), NavidromeArtistInfo.creditParts("Metro Boomin, The Weeknd & 21 Savage"))
        assertEquals(listOf("The Weeknd", "Daft Punk"), NavidromeArtistInfo.creditParts("The Weeknd feat. Daft Punk"))
        assertEquals(listOf("Lil Nas X"), NavidromeArtistInfo.creditParts("Lil Nas X"))
        assertEquals(listOf("KAROL G", "Peso Pluma"), NavidromeArtistInfo.creditParts("KAROL G x Peso Pluma"))
    }

    @Test
    fun aCreditIncludingThePageArtistIsLeftOut() {
        val rows = listOf(
            NavidromeClient.ArtistRow(id = "1", name = "The Weeknd & Future", coverArt = null),
            NavidromeClient.ArtistRow(id = "2", name = "Metro Boomin, The Weeknd & 21 Savage", coverArt = null),
            NavidromeClient.ArtistRow(id = "3", name = "Lady Gaga", coverArt = null),
            NavidromeClient.ArtistRow(id = "4", name = "The Weeknd", coverArt = null),
            NavidromeClient.ArtistRow(id = "5", name = "Ariana Grande & Justin Bieber", coverArt = null),
        )
        val titles = NavidromeArtistInfo.shelfItems("The Weeknd", rows) { "cover:$it" }.map { it.title }
        assertEquals(listOf("Lady Gaga", "Ariana Grande & Justin Bieber"), titles)
        assertTrue(NavidromeArtistInfo.credits("ariana grande & the weeknd", "The Weeknd"))
        assertFalse(NavidromeArtistInfo.credits("The Weeknd", "The Weeknd"))
    }

    @Test
    fun aCollaborationNeedsTwoKnownArtistsSoBandsStayWhole() {
        val known = setOf("Metro Boomin", "The Weeknd", "21 Savage")
        assertEquals("Metro Boomin", NavidromeArtistInfo.collabLead("Metro Boomin, The Weeknd & 21 Savage") { it in known })
        assertNull(NavidromeArtistInfo.collabLead("Simon & Garfunkel") { it in known })
        assertNull(NavidromeArtistInfo.collabLead("Earth, Wind & Fire") { it in known })
        assertNull(NavidromeArtistInfo.collabLead("The Weeknd") { it in known })
    }
}
