package com.music.bitchord.desktop

import com.music.bitchord.data.model.ShelfItem

/**
 * Which of the sidebar's page rows (Home … Navidrome) is lit, or null for none of them.
 *
 * Settings and an opened playlist stand in for the page without changing `destination`, so the
 * sidebar lit the page underneath as well: Settings opened from Navidrome lit both, and a sidebar
 * playlist opened from Downloads lit Downloads and the playlist. Only the page shown is lit now:
 * Settings while it is open, a playlist's own row while it is the page ([openedCollectionId] is
 * null when it is covered, see the call), otherwise the destination. Pages without a row of their
 * own (an artist, an album, Replay) keep the destination they were opened from lit.
 *
 * Generic because `DesktopDestination` is private to DesktopApp.kt.
 */
internal fun <T : Any> forkSidebarDestination(
    destination: T,
    settingsOpen: Boolean,
    openedCollectionId: String?,
    accountPlaylists: List<ShelfItem>,
    localPlaylists: List<DesktopPlaylist>,
    hidden: Map<String, String>,
): T? {
    if (settingsOpen) return null
    if (openedCollectionId == null) return destination
    // The same keys and filters the sidebar lists its playlist rows with: a hidden row lights
    // nothing, so the destination stays lit for it.
    val playlistRowShown =
        accountPlaylists.any { it.browseId == openedCollectionId && "account:${it.browseId}" !in hidden } ||
            localPlaylists.any { it.id == openedCollectionId && "local:${it.id}" !in hidden }
    return destination.takeUnless { playlistRowShown }
}
