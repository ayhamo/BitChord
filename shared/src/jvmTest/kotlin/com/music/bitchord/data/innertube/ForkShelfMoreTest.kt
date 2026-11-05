package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.ShelfLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: Home shelves' See all links and the pages behind them (ForkShelfMore,
 * from Desktop Revived 367bf58e / e7affc8b / fbd0a9a0). Shapes trimmed from
 * YouTube Music's FEmusic_home, FEmusic_explore and FEmusic_new_releases_*.
 */
class ForkShelfMoreTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun thumbs(width: Int, height: Int) =
        """{ "thumbnails": [ { "url": "https://i.ytimg.com/x.jpg", "width": $width, "height": $height } ] }"""

    private fun album(id: String, title: String) = """
        { "musicTwoRowItemRenderer": {
          "title": { "runs": [ { "text": "$title" } ] },
          "subtitle": { "runs": [ { "text": "Album" }, { "text": " • " }, { "text": "Artist" } ] },
          "navigationEndpoint": { "browseEndpoint": { "browseId": "$id" } },
          "thumbnailRenderer": { "musicThumbnailRenderer": { "thumbnail": ${thumbs(226, 226)} } }
        } }"""

    private fun videoCard(id: String, title: String) = """
        { "musicTwoRowItemRenderer": {
          "title": { "runs": [ { "text": "$title" } ] },
          "subtitle": { "runs": [ { "text": "Artist" } ] },
          "navigationEndpoint": { "watchEndpoint": { "videoId": "$id" } },
          "thumbnailRenderer": { "musicThumbnailRenderer": { "thumbnail": ${thumbs(400, 225)} } }
        } }"""

    private fun row(id: String, title: String, width: Int, height: Int) = """
        { "musicResponsiveListItemRenderer": {
          "playlistItemData": { "videoId": "$id" },
          "thumbnail": { "musicThumbnailRenderer": { "thumbnail": ${thumbs(width, height)} } },
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "$title" } ] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Artist" } ] } } }
          ]
        } }"""

    private fun page(vararg sections: String) = json(
        """
        { "contents": { "singleColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": { "content": {
          "sectionListRenderer": { "contents": [ ${sections.joinToString(",")} ] }
        } } } ] } } }
        """,
    )

    @Test
    fun carouselMoreButton_isTheShelfsSeeAllLink() {
        val home = page(
            """
            { "musicCarouselShelfRenderer": {
              "header": { "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [ { "text": "New albums & singles" } ] },
                "moreContentButton": { "buttonRenderer": { "navigationEndpoint": {
                  "browseEndpoint": { "browseId": "FEmusic_new_releases_albums", "params": "P1" }
                } } }
              } },
              "contents": [ ${album("MPREb_a", "A")}, ${album("MPREb_b", "B")} ]
            } }
            """,
        )
        val shelf = InnertubeParser.parseHome(home).single()
        assertEquals("FEmusic_new_releases_albums", shelf.moreBrowseId)
        assertEquals("P1", shelf.moreParams)
    }

    @Test
    fun titleRunLink_onACarouselOfRows_andTrendingKeepsItsVideos() {
        val explore = page(
            """
            { "musicCarouselShelfRenderer": {
              "header": { "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [ { "text": "Trending",
                  "navigationEndpoint": { "browseEndpoint": { "browseId": "VLPL4fGSI1pDJn6O1LS0XSdF3RyO0Rq_LDeI" } } } ] }
              } },
              "contents": [ ${row("vid1", "Official video", 1280, 720)}, ${row("aud1", "Song", 544, 544)} ]
            } }
            """,
        )
        val shelf = InnertubeParser.parseHome(explore).single()
        assertEquals("VLPL4fGSI1pDJn6O1LS0XSdF3RyO0Rq_LDeI", shelf.moreBrowseId)
        assertNull(shelf.moreParams)
        // The country's Trending keeps its official music videos (Revived 855e855f
        // had to restore this; this parser never dropped them).
        assertEquals(listOf("vid1", "aud1"), shelf.items.map { it.videoId })
        assertTrue(shelf.items.first().isVideo)
    }

    @Test
    fun plainShelfBottomEndpoint_isItsLink_andAShelfWithoutOneHasNone() {
        val home = page(
            """
            { "musicShelfRenderer": {
              "title": { "runs": [ { "text": "Quick picks" } ] },
              "bottomEndpoint": { "browseEndpoint": { "browseId": "FEmusic_quick_picks" } },
              "contents": [ ${row("s1", "One", 544, 544)} ]
            } }
            """,
            """
            { "musicCarouselShelfRenderer": {
              "header": { "musicCarouselShelfBasicHeaderRenderer": { "title": { "runs": [ { "text": "Listen again" } ] } } },
              "contents": [ ${album("MPREb_c", "C")} ]
            } }
            """,
        )
        val (quickPicks, listenAgain) = InnertubeParser.parseHome(home)
        assertEquals("FEmusic_quick_picks", quickPicks.moreBrowseId)
        assertNull(listenAgain.moreBrowseId)
        assertNull(listenAgain.moreParams)
    }

    /**
     * Headers as the live WEB_REMIX responses had them (2026-11-05, signed out, DE): FEmusic_home's
     * shelves carry no link anywhere in the header, so their headings stay plain; FEmusic_explore's
     * carry it on both the title run and the More button, with the playlist's page-type config.
     */
    @Test
    fun liveHeaderShapes_signedOutHomeHasNoLink_exploreHasOne() {
        val response = page(
            """
            { "musicCarouselShelfRenderer": {
              "header": { "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [ { "text": "Throwback" } ] },
                "strapline": { "runs": [ { "text": "KLASSIKER AUS JEDEM JAHRZEHNT" } ] },
                "accessibilityData": { "accessibilityData": { "label": "KLASSIKER AUS JEDEM JAHRZEHNT Throwback" } },
                "headerStyle": "MUSIC_CAROUSEL_SHELF_BASIC_HEADER_STYLE_DEFAULT",
                "trackingParams": "CMYD"
              } },
              "contents": [ ${album("VLRDCLAK5uy_a", "Hits")} ]
            } }
            """,
            """
            { "musicCarouselShelfRenderer": {
              "header": { "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [ { "text": "Trending", "navigationEndpoint": { "clickTrackingParams": "CLwF",
                  "browseEndpoint": { "browseId": "VLOLAK5uy_nPQBmQpOIYYxuvYW4gIHsjgDqX9kc--Dg",
                    "browseEndpointContextSupportedConfigs": { "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_PLAYLIST" } } } } } ] },
                "moreContentButton": { "buttonRenderer": { "style": "STYLE_TEXT", "text": { "runs": [ { "text": "More" } ] },
                  "navigationEndpoint": { "clickTrackingParams": "CL0F", "browseEndpoint": { "browseId": "VLOLAK5uy_nPQBmQpOIYYxuvYW4gIHsjgDqX9kc--Dg" } } } }
              } },
              "itemSize": "COLLECTION_STYLE_ITEM_SIZE_SMALL",
              "numItemsPerColumn": "4",
              "contents": [ ${row("t1", "One", 544, 544)} ]
            } }
            """,
        )
        val (throwback, trending) = InnertubeParser.parseHome(response)
        assertNull(throwback.moreBrowseId)
        assertEquals("VLOLAK5uy_nPQBmQpOIYYxuvYW4gIHsjgDqX9kc--Dg", trending.moreBrowseId)
    }

    @Test
    fun seeAllGridPage_isReadAsOneShelf() {
        val videos = page(
            """
            { "gridRenderer": {
              "header": { "gridHeaderRenderer": { "title": { "runs": [ { "text": "New music videos" } ] } } },
              "items": [ ${videoCard("v1", "One")}, ${videoCard("v2", "Two")} ]
            } }
            """,
        )
        // parseHome has no grid reader, which keeps Home and the mood pages as they were.
        assertTrue(InnertubeParser.parseHome(videos).isEmpty())
        val shelf = ForkShelfMore.parsePage(videos).single()
        assertEquals("New music videos", shelf.title)
        assertEquals(listOf("v1", "v2"), shelf.items.map { it.videoId })
        assertEquals(ShelfLayout.VIDEO, shelf.layout)

        val albums = page(
            """
            { "gridRenderer": {
              "header": { "gridHeaderRenderer": { "title": { "runs": [ { "text": "New albums & singles" } ] } } },
              "items": [ ${album("MPREb_a", "A")}, ${album("MPREb_b", "B")}, ${album("MPREb_c", "C")} ]
            } }
            """,
        )
        assertEquals(listOf("MPREb_a", "MPREb_b", "MPREb_c"), ForkShelfMore.parsePage(albums).single().items.map { it.browseId })
    }
}
