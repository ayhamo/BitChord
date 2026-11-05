package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Fork: [withoutRepeatsOf] on release-feed shelves (ForkShelfRepeats.kt, Desktop Revived bbfb5a78). */
class ForkShelfRepeatsTest {
    private fun video(id: String) = ShelfItem(id, "", null, id, null, isVideo = true)

    private fun videos(title: String, feed: String?, ids: List<String>) =
        HomeShelf(title, ids.map(::video), moreBrowseId = feed)

    @Test
    fun theSameMusicVideoShelfFromNewReleasesAndExploreIsShownOnce() {
        val fromNewReleases = videos("New music videos", "FEmusic_new_releases_videos", (1..8).map { "v$it" })
        val fromExplore = videos("New music videos", "FEmusic_new_releases_videos", (8 downTo 2).map { "v$it" } + "w")
        assertEquals(emptyList<HomeShelf>(), listOf(fromExplore).withoutRepeatsOf(listOf(fromNewReleases)))
        assertEquals(listOf(fromNewReleases), listOf(fromNewReleases, fromExplore).withoutRepeatsOf(emptyList()))
    }

    @Test
    fun videoShelvesOfOtherFeedsAreNotComparedByVideoId() {
        val releases = videos("New music videos", "FEmusic_new_releases_videos", (1..8).map { "v$it" })
        // Same videos, but not the release feed: compared by browse ids as before, of which it has none.
        val trending = videos("Trending", "VLPL_trending", (1..8).map { "v$it" })
        val unlinked = videos("Recommended music videos", null, (1..8).map { "v$it" })
        assertEquals(listOf(trending, unlinked), listOf(trending, unlinked).withoutRepeatsOf(listOf(releases)))
        assertEquals(listOf(releases), listOf(releases).withoutRepeatsOf(listOf(trending, unlinked)))
    }
}
