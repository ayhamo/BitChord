package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fork: [ForkGeniusMeanings]'s parsing against saved Genius answers (`fork-genius/`: the shapes of
 * `api/search/multi`, `api/songs/<id>?text_format=plain` and `api/referents?text_format=plain` as
 * measured 2026-11-04, with made-up songs and text). No network.
 */
class ForkGeniusMeaningsTest {

    private fun fixture(name: String) = javaClass.getResource("/fork-genius/$name")!!.readText()

    @Test
    fun theExactTitleByTheArtistWinsOverTheTopHit() {
        val search = fixture("search.json")
        val hit = ForkGeniusMeanings.pickHit(search, "Night Train", "Some Band")!!
        assertEquals(109096L, hit.id)
        assertEquals("https://genius.com/Some-band-night-train-lyrics", hit.url)
        assertEquals(555L, ForkGeniusMeanings.pickHit(search, "Night Train (Live)", "Some Band")!!.id)
    }

    @Test
    fun anotherArtistsPageIsNoAnswer() {
        val search = fixture("search.json")
        // The translation page carries the title, but it is not the artist's song.
        assertNull(ForkGeniusMeanings.pickHit(search, "Night Train", "Another Artist"))
        assertNull(ForkGeniusMeanings.pickHit(search, "Unknown Song", "Some Band"))
        assertNull(ForkGeniusMeanings.pickHit("<html>challenge</html>", "Night Train", "Some Band"))
    }

    @Test
    fun theDescriptionIsTidiedAndAQuestionMarkIsNone() {
        val about = ForkGeniusMeanings.aboutFrom(fixture("song.json"), "fallback")!!
        assertEquals(
            "“Night Train” was written on a long ride home. The band recorded it in one take.\n\nIt became their first single.",
            about.text,
        )
        assertEquals("https://genius.com/Some-band-night-train-lyrics", about.url)
        assertNull(ForkGeniusMeanings.aboutFrom(fixture("song-empty.json"), "fallback"))
    }

    @Test
    fun referentsLeaveOutTheDescriptionAndEmptyAnnotations() {
        val page = ForkGeniusMeanings.referentsFrom(fixture("referents.json"))
        assertEquals(4, page.count)
        assertEquals(listOf("I wish I could stay", "Lights go by\n Out in the dark\n I wish I could stay"), page.meanings.map { it.fragment })
        assertEquals(0, ForkGeniusMeanings.referentsFrom("not json").count)
    }

    @Test
    fun aLineFindsTheFragmentItBelongsTo() {
        val meanings = ForkGeniusMeanings.referentsFrom(fixture("referents.json")).meanings
        // Equal (case and punctuation aside): that one, not the verse that also holds it.
        assertEquals("The singer wants the ride to last.", ForkGeniusMeanings.bestReferent(meanings, "I wish I could stay!")!!.text)
        // Inside a longer fragment: the smallest holding it.
        assertEquals("The whole verse looks out of the window.", ForkGeniusMeanings.bestReferent(meanings, "Out in the dark")!!.text)
        // Two picked lines joined, as the share sheet asks first.
        assertEquals(
            "The whole verse looks out of the window.",
            ForkGeniusMeanings.bestReferent(meanings, "Lights go by\nOut in the dark")!!.text,
        )
        // A line longer than any fragment: the longest fragment it holds.
        assertEquals(
            "https://genius.com/40000001/Some-band-night-train/Lights-go-by",
            ForkGeniusMeanings.bestReferent(meanings, "Lights go by, out in the dark, I wish I could stay, oh")!!.url,
        )
        assertNull(ForkGeniusMeanings.bestReferent(meanings, "Something nobody wrote about"))
    }
}
