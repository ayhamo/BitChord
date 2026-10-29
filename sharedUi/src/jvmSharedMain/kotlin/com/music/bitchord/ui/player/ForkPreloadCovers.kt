package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt

/**
 * Fork: the next two songs' covers, fetched at the player's size before they
 * are needed.
 *
 * At a track change (a crossfade, a skip) the player asks for the new cover at
 * [ART_PX] and draws its black placeholder and black palette until it arrives.
 * A YouTube cover comes off a CDN in a blink; a Navidrome cover is resized by
 * the server on first request, and the player sat black for about a second on
 * every change. Warmed here with the same request the sleeve makes (same URL,
 * same size), so the change finds it in Coil's memory cache.
 */
@Composable
internal fun ForkPreloadCovers(queue: List<Song>, current: Song) {
    val index = queue.indexOfFirst { it.videoId == current.videoId }
    val next = if (index < 0) emptyList() else queue.drop(index + 1).take(2)
    val first = rememberRemoteArtworkUrl(next.getOrNull(0))
    val second = rememberRemoteArtworkUrl(next.getOrNull(1))
    val context = LocalPlatformContext.current
    LaunchedEffect(first, second) {
        val loader = SingletonImageLoader.get(context)
        listOfNotNull(first, second).forEach { url ->
            loader.enqueue(ImageRequest.Builder(context).data(url.artworkAt(ART_PX)).size(ART_PX).build())
        }
    }
}
