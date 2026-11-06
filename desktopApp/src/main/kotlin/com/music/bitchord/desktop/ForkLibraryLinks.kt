package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.screens.LibraryLink
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork (owner, 2026-11-06): the phone's Library rows for the server, on the PC's Library page, so
 * both read the same: Downloads, Local Music, Navidrome, Internet radio, Spotify (the phone's
 * `forkLibraryLinks` in `ForkSources.kt`, same wording). Only once a server is set, and Internet
 * radio not while offline (it needs the network), as on the phone.
 *
 * Navidrome opens the sidebar's Navidrome page; Internet radio opens that page on its stations
 * ([openStations], which the page reads once).
 */
internal object ForkLibraryLinks {

    /** Set by the Internet radio row; the Navidrome page switches to its stations and clears it. */
    val openStations = MutableStateFlow(false)

    @Composable
    fun links(): List<LibraryLink> {
        val url by NavidromeSettings.url.collectAsState()
        val user by NavidromeSettings.username.collectAsState()
        val offline by ForkOffline.offline.collectAsState()
        if (!NavidromeSettings.isConfigured(url, user)) return emptyList()
        val library = link("Navidrome", "Your Navidrome library", NavidromeSettings.BROWSE_ID, Icons.Rounded.LibraryMusic)
        val radio = link(ForkRadio.TITLE, "Radio stations from your Navidrome server", NavidromeRadio.PAGE_ID, Icons.Rounded.Radio)
        return if (offline) listOf(library) else listOf(library, radio)
    }

    /** True when [browseId] is one of these rows: the caller opens the Navidrome page. */
    fun opens(browseId: String): Boolean = when (browseId) {
        NavidromeSettings.BROWSE_ID -> true
        NavidromeRadio.PAGE_ID -> { openStations.value = true; true }
        else -> false
    }

    private fun link(title: String, subtitle: String, browseId: String, icon: androidx.compose.ui.graphics.vector.ImageVector) =
        LibraryLink(
            item = ShelfItem(title = title, subtitle = subtitle, thumbnailUrl = null, videoId = null, browseId = browseId),
            icon = icon,
        )
}
