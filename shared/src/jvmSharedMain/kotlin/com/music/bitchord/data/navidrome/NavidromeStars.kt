package com.music.bitchord.data.navidrome

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus
import kotlinx.coroutines.launch

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

    /**
     * A like on a Navidrome song is a star on the server: shown at once, rolled back if the
     * server refuses. Returns whether it was one, so the caller's YouTube path is skipped.
     */
    fun setLike(videoId: String, status: LikeStatus, current: (String) -> LikeStatus, scope: kotlinx.coroutines.CoroutineScope): Boolean {
        if (!NavidromeSettings.isNavidromeId(videoId)) return false
        val previous = current(videoId)
        if (previous == status) return true
        LikeState.set(videoId, status)
        scope.launch {
            rate(videoId, status).onFailure { LikeState.set(videoId, previous) }
        }
        return true
    }

    /** [rate] for a Navidrome song, else [otherwise] (the YouTube path). */
    suspend fun rateOr(videoId: String, status: LikeStatus, otherwise: suspend () -> Result<Unit>): Result<Unit> =
        if (NavidromeSettings.isNavidromeId(videoId)) rate(videoId, status) else otherwise()

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> = runCatching {
        val id = videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        NavidromeRepository.client().setStarred(id, status == LikeStatus.LIKE)
    }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it } // the call may have gone through: no roll-back on a cancel

    /** The server's starred songs as liked, without undoing a change made in this session. */
    fun seed(starredVideoIds: Set<String>) = LikeState.seedLiked(starredVideoIds)
}
