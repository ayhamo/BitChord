package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt

/**
 * Fork: the next two songs' covers, fetched at the player's size before they are needed. A copy of
 * the phone's `sharedUi` file, since no module depends on both.
 *
 * At a track change the player asks for the new cover at [ART_PX] and draws its black placeholder
 * and black palette until it arrives. A Navidrome cover is resized by the server on first request,
 * about a second of black player on every change. Warmed here with the same request the sleeve
 * makes (same URL, same size), so the change finds it in Coil's memory cache.
 */
@Composable
internal fun ForkPreloadCovers(queue: List<Song>, current: Song) {
    // Once per queue and song, not on every recomposition of the player (a Navidrome queue runs to thousands).
    val index = remember(queue, current.videoId) { queue.indexOfFirst { it.videoId == current.videoId } }
    // Only songs that carry an address: a local file without one has nothing to fetch ahead.
    val first = rememberRemoteArtworkUrl(queue.getOrNull(index + 1).takeIf { index >= 0 && it?.thumbnailUrl != null })
    val second = rememberRemoteArtworkUrl(queue.getOrNull(index + 2).takeIf { index >= 0 && it?.thumbnailUrl != null })
    val context = LocalPlatformContext.current
    LaunchedEffect(first, second) {
        val loader = SingletonImageLoader.get(context)
        listOfNotNull(first, second).forEach { url ->
            loader.enqueue(ImageRequest.Builder(context).data(url.artworkAt(ART_PX)).size(ART_PX).build())
        }
    }
}
