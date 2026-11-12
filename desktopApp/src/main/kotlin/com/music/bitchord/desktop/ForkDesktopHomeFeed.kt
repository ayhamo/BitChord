package com.music.bitchord.desktop

import com.music.bitchord.data.model.ForkHomeChips
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.ui.screens.ForkHomeFeedActions

/*
 * Fork: the PC's side of YouTube Music's Home drawn 1:1 (the shared ForkHomeFeedUi.kt), as the
 * phone's ForkHomeFeedPhone.kt. DesktopApp passes it to HomeScreen in one hook line.
 */
internal object ForkDesktopHomeFeed {

    /** Explore's "Moods & genres" button: the Explore page is that page already. */
    private const val MOODS_AND_GENRES = "FEmusic_moods_and_genres"

    /**
     * HomeScreen's `forkFeed`: a chip toggles and Home [reload]s from the top; Moods & genres opens
     * [explore], the other Explore buttons their page as a See all page ([openMore]); Play all
     * [play]s the shelf; an artist heading [open]s the artist.
     */
    fun actions(
        reload: () -> Unit,
        explore: () -> Unit,
        openMore: (HomeShelf) -> Unit,
        play: (HomeShelf) -> Unit,
        open: (browseId: String, title: String) -> Unit,
    ) = ForkHomeFeedActions(
        onChip = { chip ->
            ForkHomeChips.toggle(chip)
            reload()
        },
        onButton = { button ->
            if (button.browseId == MOODS_AND_GENRES) explore()
            else openMore(HomeShelf(button.title, emptyList(), moreBrowseId = button.browseId, moreParams = button.params))
        },
        onPlayAll = play,
        onHeaderOpen = open,
    )
}
