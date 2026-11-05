package com.music.bitchord.data.model

/**
 * Fork: the release-feed half of [withoutRepeatsOf] (Desktop Revived bbfb5a78).
 *
 * YouTube serves "New albums & singles" and "New music videos" on both
 * FEmusic_new_releases and FEmusic_explore, which Home fetches as two
 * supplements. The album shelves were already caught as repeats by their
 * browse ids, but a music-video shelf's cards are video ids with no browse id,
 * so [withoutRepeatsOf] had nothing to compare and Home drew the same video
 * shelf twice. Both copies link to the same See all feed, which is what marks
 * them as the same shelf; for those the cards are compared by browse id *or*
 * video id.
 */
private val RELEASE_FEEDS = setOf("FEmusic_new_releases_albums", "FEmusic_new_releases_videos")

private fun HomeShelf.releaseFeedId(): String? = moreBrowseId?.takeIf { it in RELEASE_FEEDS }

private fun HomeShelf.releaseFeedCards(): Set<String> =
    items.mapNotNullTo(HashSet()) { it.browseId ?: it.videoId }

/** The keys [shelf] is compared by when it links to a release feed, else null (browse ids only). */
internal fun forkReleaseFeedKeys(shelf: HomeShelf): Set<String>? =
    if (shelf.releaseFeedId() != null) shelf.releaseFeedCards() else null

/**
 * The keys of [other], already on the page, to hold against [shelf]: the
 * release cards (browse and video ids) when both link to the same release
 * feed, else null, and the caller compares [other]'s browse ids as before. As
 * those are browse ids only, a release shelf never counts as a repeat of an
 * unrelated one by its video ids, and still does by its albums' browse ids.
 */
internal fun forkReleaseFeedKeysOf(other: HomeShelf, shelf: HomeShelf): Set<String>? {
    val feed = shelf.releaseFeedId() ?: return null
    return if (feed == other.releaseFeedId()) other.releaseFeedCards() else null
}
