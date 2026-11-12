package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.ForkHomeChips
import com.music.bitchord.data.model.ForkShelfKind
import com.music.bitchord.data.model.HomeChip
import com.music.bitchord.data.model.ShelfLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: YouTube Music's Home read 1:1 (ForkHomeFeedParser). The fixtures under
 * `resources/forkhome` are shaped like WEB_REMIX `browse("FEmusic_home")` answers: the chip cloud in
 * `sectionListRenderer.header`, Speed dial and Quick picks as carousels with `numItemsPerColumn`,
 * a 16:9 video carousel, the large daily-discover cards, an immersive carousel with an artist
 * picture in its heading, Explore's navigation buttons, a card shelf, and a section of a kind the
 * reader doesn't know; a `sectionListContinuation` page with podcast episodes
 * (`musicMultiRowListItemRenderer`), a list shelf and a carousel of buttons; and a chip's page.
 */
class ForkHomeFeedParserTest {

    private fun fixture(name: String): JsonObject {
        val text = javaClass.getResource("/forkhome/$name")!!.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    @AfterTest
    fun resetChips() {
        ForkHomeChips.selected.value = null
        ForkHomeChips.chips.value = emptyList()
    }

    @Test
    fun firstPageKeepsEveryShelfInYouTubesOrderWithItsTitle() {
        val feed = ForkHomeFeedParser.parse(fixture("home_first_page.json"))
        assertEquals(
            listOf("Speed dial", "Quick picks", "Music videos for you", "Your daily discover", "KPop Demon Hunters Cast", "Explore", "Featured"),
            feed.shelves.map { it.title },
        )
        assertEquals("CONT_TOKEN_1", feed.continuation)
    }

    @Test
    fun chipsAreReadWithTheirParamsAndSelection() {
        val feed = ForkHomeFeedParser.parse(fixture("home_first_page.json"))
        assertEquals(listOf("Podcasts", "Relax", "Sleep", "Energize", "Workout"), feed.chips.map { it.title })
        val relax = feed.chips[1]
        assertEquals("ggNCSgQIBxADrelax", relax.params)
        assertFalse(relax.selected)
        assertEquals("ggNCSgQIDBAB", relax.deselectParams)

        val chipPage = ForkHomeFeedParser.parse(fixture("home_chip_relax.json"))
        assertEquals(listOf(false, true, false), chipPage.chips.map { it.selected })
        assertEquals(listOf("Relaxing picks"), chipPage.shelves.map { it.title })
        assertEquals("CONT_RELAX", chipPage.continuation)
    }

    @Test
    fun speedDialIsATwoRowGridWithItsArtistRound() {
        val speed = ForkHomeFeedParser.parse(fixture("home_first_page.json")).shelves[0]
        val style = speed.fork!!
        assertEquals(ForkShelfKind.GRID, style.kind)
        assertEquals(2, style.rows)
        assertEquals(6, speed.items.size)
        assertEquals(setOf(1), style.roundItems)
        assertEquals("speed01", speed.items[2].videoId)
        assertEquals("MPREb_kpop", speed.items[4].browseId)
    }

    @Test
    fun quickPicksIsAListWithPlayAll() {
        val quick = ForkHomeFeedParser.parse(fixture("home_first_page.json")).shelves[1]
        val style = quick.fork!!
        assertEquals(ForkShelfKind.LIST, style.kind)
        assertEquals(ShelfLayout.LIST, quick.layout)
        assertEquals(4, style.rows)
        assertEquals(8, quick.items.size)
        assertEquals("Start radio from a song", quick.subtitle)
        assertEquals("qp01", style.playAll?.videoId)
        assertEquals("Play all", style.playAll?.label)
        // A Play all button is not a See all link.
        assertNull(quick.moreBrowseId)
    }

    @Test
    fun videoShelfKeepsItsVideosAndItsMoreLink() {
        val videos = ForkHomeFeedParser.parse(fixture("home_first_page.json")).shelves[2]
        assertEquals(ForkShelfKind.VIDEO_CAROUSEL, videos.fork!!.kind)
        assertEquals(ShelfLayout.VIDEO, videos.layout)
        assertEquals(4, videos.items.size)
        assertTrue(videos.items.all { it.isVideo })
        assertEquals("FEmusic_mv_for_you", videos.moreBrowseId)
    }

    @Test
    fun dailyDiscoverIsLargeCards() {
        val discover = ForkHomeFeedParser.parse(fixture("home_first_page.json")).shelves[3]
        assertEquals(ForkShelfKind.LARGE_CARDS, discover.fork!!.kind)
        assertEquals(listOf("For fans of HUNTR/X", "Sounds like Golden"), discover.items.map { it.title })
    }

    /**
     * What the web feed really sends (the owner's account, 2026-11-11): "Listen again" with no
     * numItemsPerColumn, and "Your daily discover" as MEDIUM square song cards with Play all. The
     * YouTube Music app draws them as Speed dial's 2-row grid and as large cards.
     */
    @Test
    fun webFeedsListenAgainIsTheGridAndSquareSongCardsAreLargeCards() {
        fun song(id: String) = """{"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "Song $id"}]},
            "subtitle": {"runs": [{"text": "Artist"}]}, "aspectRatio": "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
            "navigationEndpoint": {"watchEndpoint": {"videoId": "$id"}},
            "thumbnailRenderer": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://x/$id", "width": 226, "height": 226}]}}}}}"""
        fun playlist(id: String) = """{"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "Mix $id"}]},
            "subtitle": {"runs": [{"text": "Playlist"}]},
            "navigationEndpoint": {"browseEndpoint": {"browseId": "VL$id", "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_PLAYLIST"}}}},
            "thumbnailRenderer": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://x/$id", "width": 226, "height": 226}]}}}}}"""
        fun carousel(title: String, more: String?, items: List<String>) = """{"musicCarouselShelfRenderer": {
            "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "$title"${more?.let { """, "navigationEndpoint": {"browseEndpoint": {"browseId": "$it"}}""" } ?: ""}}]}}},
            "contents": [${items.joinToString(",")}], "itemSize": "COLLECTION_STYLE_ITEM_SIZE_MEDIUM"}}"""
        val page = Json.parseToJsonElement(
            """{"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
                ${carousel("Listen again", "FEmusic_listen_again", listOf(song("a1"), playlist("p1"), song("a2"), playlist("p2")))},
                ${carousel("Your daily discover", null, listOf(song("d1"), song("d2"), song("d3")))},
                ${carousel("Mixed for you", "FEmusic_mixed_for_you", listOf(playlist("m1"), playlist("m2")))}
            ]}}}}]}}}""",
        ).jsonObject
        val shelves = ForkHomeFeedParser.parse(page).shelves
        assertEquals(listOf("Listen again", "Your daily discover", "Mixed for you"), shelves.map { it.title })
        assertEquals(ForkShelfKind.GRID, shelves[0].fork!!.kind)
        assertEquals(2, shelves[0].fork!!.rows)
        assertEquals(ForkShelfKind.LARGE_CARDS, shelves[1].fork!!.kind)
        assertEquals(ForkShelfKind.CAROUSEL, shelves[2].fork!!.kind)
    }

    @Test
    fun immersiveCarouselKeepsItsArtistHeading() {
        val cast = ForkHomeFeedParser.parse(fixture("home_first_page.json")).shelves[4]
        val style = cast.fork!!
        assertEquals("musicImmersiveCarouselShelfRenderer", style.renderer)
        assertEquals(ForkShelfKind.CAROUSEL, style.kind)
        assertEquals("More from", cast.subtitle)
        assertEquals("https://lh3.googleusercontent.com/artist=w88-h88", style.headerThumbnailUrl)
        assertEquals("UCkpopcast", style.headerBrowseId)
        assertEquals(listOf("kp01", "kp02"), cast.items.map { it.videoId })
    }

    @Test
    fun exploreButtonsKeepTheirPagesAndColours() {
        val explore = ForkHomeFeedParser.parse(fixture("home_first_page.json")).shelves[5]
        val style = explore.fork!!
        assertEquals(ForkShelfKind.BUTTONS, style.kind)
        assertEquals(
            listOf("FEmusic_new_releases", "FEmusic_charts", "FEmusic_moods_and_genres"),
            style.buttons.map { it.browseId },
        )
        assertEquals(listOf("New releases", "Charts", "Moods & genres"), explore.items.map { it.title })
        assertEquals(4282006158L, style.buttons[0].stripeColor)
        assertEquals("TRENDING_UP", style.buttons[1].icon)
    }

    @Test
    fun cardShelfLeadsWithItsCardThenItsRows() {
        val card = ForkHomeFeedParser.parse(fixture("home_first_page.json")).shelves[6]
        assertEquals(ForkShelfKind.CARD, card.fork!!.kind)
        assertEquals("Fresh Finds", card.items[0].title)
        assertEquals("VLRDCLAKfresh", card.items[0].browseId)
        assertEquals(listOf("cs01", "cs02"), card.items.drop(1).map { it.videoId })
    }

    @Test
    fun continuationReadsEpisodesListShelvesAndButtonsInOrder() {
        val feed = ForkHomeFeedParser.parseContinuation(fixture("home_continuation.json"))
        assertEquals(listOf("New episodes", "Long listens", "Mixed for you", "Explore"), feed.shelves.map { it.title })
        assertEquals("CONT_TOKEN_2", feed.continuation)

        val episodes = feed.shelves[0]
        assertEquals(ForkShelfKind.EPISODES, episodes.fork!!.kind)
        assertEquals(listOf("ep01", "ep02"), episodes.items.map { it.videoId })
        assertTrue(episodes.items[0].subtitle.contains("The Show"))
        assertEquals("What this week is about.", episodes.fork!!.descriptions[0])

        val long = feed.shelves[1]
        assertEquals(ForkShelfKind.LIST, long.fork!!.kind)
        assertEquals("FEmusic_long_listens", long.moreBrowseId)
        assertEquals(3, long.items.size)

        assertEquals("FEmusic_mixed_for_you", feed.shelves[2].moreBrowseId)
        assertEquals(ForkShelfKind.BUTTONS, feed.shelves[3].fork!!.kind)
        assertEquals(2, feed.shelves[3].fork!!.buttons.size)
    }

    @Test
    fun chipToggleSelectsThenGoesBackToPlainHome() {
        val relax = HomeChip("Relax", "p-relax")
        val sleep = HomeChip("Sleep", "p-sleep")
        assertEquals(relax, ForkHomeChips.toggle(relax))
        assertEquals("p-relax", ForkHomeChips.params)
        assertEquals(sleep, ForkHomeChips.toggle(sleep))
        assertEquals("p-sleep", ForkHomeChips.params)
        assertNull(ForkHomeChips.toggle(sleep.copy(selected = true)))
        assertNull(ForkHomeChips.params)
    }

    @Test
    fun aPageWithoutChipsKeepsTheRowThatIsShown() {
        ForkHomeChips.publish(listOf(HomeChip("Relax", "p")))
        ForkHomeChips.publish(emptyList())
        assertEquals(listOf("Relax"), ForkHomeChips.chips.value.map { it.title })
    }
}
