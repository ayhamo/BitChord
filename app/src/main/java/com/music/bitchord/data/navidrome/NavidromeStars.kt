package com.music.bitchord.data.navidrome

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus

/**
 * The app's heart (a star in this player) for Navidrome songs, kept on the
 * Navidrome server as its star (fork-only), so it matches the web UI and the
 * PC player.
 *
 * Shaped like `YtMusicRepository.rate`, so the player's and the notification's
 * optimistic like handling stays as it is: they set [LikeState] first, call
 * [rate] and roll back on a failure. [seed] puts the server's stars into
 * [LikeState] each time the library is read.
 */
object NavidromeStars {

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> = runCatching {
        val id = videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        NavidromeRepository.client().setStarred(id, status == LikeStatus.LIKE)
    }

    /** The server's starred songs as liked, without undoing a change made in this session. */
    fun seed(starredVideoIds: Set<String>) = LikeState.seedLiked(starredVideoIds)
}
