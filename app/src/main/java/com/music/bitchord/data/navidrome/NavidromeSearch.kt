package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState

/**
 * The Search tab's "Library" source with the Navidrome library in it
 * (fork-only). Upstream's Library source is this device's music only, filtered
 * on the phone as you type; the Navidrome songs (the cached listing) are added
 * after them, so they're searched the same way. Without device music (none, or
 * no storage permission) the Navidrome songs alone answer.
 */
object NavidromeSearch {

    suspend fun withLibrary(device: UiState<List<Song>>): UiState<List<Song>> {
        if (!NavidromeSettings.isConfigured()) return device
        val ours = runCatching { NavidromeRepository.getSongs() }.getOrDefault(emptyList())
        if (ours.isEmpty()) return device
        return if (device is UiState.Success) UiState.Success(device.data + ours) else UiState.Success(ours)
    }
}
