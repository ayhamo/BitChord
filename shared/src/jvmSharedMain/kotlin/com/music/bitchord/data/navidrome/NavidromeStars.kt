package com.music.bitchord.data.navidrome

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's heart (a star in this player) for Navidrome songs, kept on the
 * Navidrome server as its star (fork-only), so it matches the web UI and the
 * PC player.
 *
 * Shaped like `YtMusicRepository.rate`, so the player's and the notification's
 * optimistic like handling stays as it is: they set [LikeState] first, call
 * [rate] and roll back on a failure. [seed] puts the server's stars into
 * [LikeState] each time the library is read; [seedAll] a full listing, which also
 * drops the stars the server no longer has.
 */
object NavidromeStars {

    /**
     * A like on a Navidrome song is a star on the server: shown at once, rolled back if the
     * server refuses. Returns whether it was one, so the caller's YouTube path is skipped.
     */
    fun setLike(videoId: String, status: LikeStatus, current: (String) -> LikeStatus, scope: CoroutineScope): Boolean {
        if (!NavidromeSettings.isNavidromeId(videoId)) return false
        val previous = current(videoId)
        if (previous == status) return true
        LikeState.set(videoId, status)
        taps.tap(videoId, status, previous, scope) { LikeState.set(videoId, it) }
        return true
    }

    /**
     * The PC's heart on a Navidrome song (its own liked list is already changed): sent in tap order,
     * [rollBack] given what the server last had when this tap failed and no newer tap followed.
     */
    fun tap(videoId: String, status: LikeStatus, previous: LikeStatus, scope: CoroutineScope, rollBack: (LikeStatus) -> Unit) =
        taps.tap(videoId, status, previous, scope, rollBack)

    /** [rate] for a Navidrome song, else [otherwise] (the YouTube path). */
    suspend fun rateOr(videoId: String, status: LikeStatus, otherwise: suspend () -> Result<Unit>): Result<Unit> =
        if (NavidromeSettings.isNavidromeId(videoId)) rate(videoId, status) else otherwise()

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> = runCatching {
        val id = videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        NavidromeRepository.client().setStarred(id, status == LikeStatus.LIKE)
    }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it } // the call may have gone through: no roll-back on a cancel

    /** The server's starred songs as liked, without undoing a change made in this session. */
    fun seed(starredVideoIds: Set<String>) = LikeState.seedLiked(starredVideoIds)

    /**
     * [starredVideoIds] is every star the server has (`getStarred2`, the whole library's listing):
     * Navidrome hearts not in it go, so a star taken off in the web UI or on the other device no
     * longer shows here until a restart. A song with a tap still on its way keeps what the tap set.
     */
    fun seedAll(starredVideoIds: Set<String>) {
        val busy = taps.busy()
        LikeState.forkUpdate { current -> merged(current, starredVideoIds, busy) }
    }

    /** Another server or account: none of the old one's hearts apply. */
    fun forgetAll() = LikeState.forkUpdate { current -> current.filterKeys { !NavidromeSettings.isNavidromeId(it) } }

    /** [seedAll]'s rule: other overrides kept, a busy song's kept, every other Navidrome heart the listing's. */
    internal fun merged(current: Map<String, LikeStatus>, starred: Set<String>, busy: Set<String>): Map<String, LikeStatus> {
        val next = current.filterKeys { !NavidromeSettings.isNavidromeId(it) || it in busy }.toMutableMap()
        starred.forEach { if (it !in busy) next[it] = LikeStatus.LIKE }
        return next
    }

    private val taps = Taps { videoId, status -> rate(videoId, status) }

    /**
     * Star and unstar taps on one song, in order: a quick star-unstar-star used to send three
     * requests at once, landing in any order (the server kept whichever came last), and the first
     * one's failure rolled the heart back over the newer taps. Now one request per song at a time;
     * a tap superseded before its turn isn't sent (the newest says what the listener wants), and a
     * failure rolls back only when no newer tap followed, to what the server last accepted.
     */
    internal class Taps(private val send: suspend (String, LikeStatus) -> Result<Unit>) {

        private class Pending(var latest: Long, var confirmed: LikeStatus)

        private val pending = HashMap<String, Pending>()
        private var counter = 0L // guarded by [pending]
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun busy(): Set<String> = synchronized(pending) { pending.keys.toSet() }

        fun tap(videoId: String, status: LikeStatus, previous: LikeStatus, scope: CoroutineScope, rollBack: (LikeStatus) -> Unit) {
            val ticket = synchronized(pending) {
                val p = pending.getOrPut(videoId) { Pending(0L, previous) }
                // Unique across songs and runs of taps: a tap still queued from an earlier run can't match a new one's.
                (++counter).also { p.latest = it }
            }
            scope.launch {
                locks.getOrPut(videoId) { Mutex() }.withLock {
                    if (synchronized(pending) { pending[videoId]?.latest } != ticket) return@withLock // a newer tap sends its own
                    val result = try {
                        send(videoId, status)
                    } catch (e: CancellationException) {
                        synchronized(pending) { if (pending[videoId]?.latest == ticket) pending.remove(videoId) }
                        throw e
                    }
                    val back = synchronized(pending) {
                        val p = pending[videoId] ?: return@synchronized null
                        if (result.isSuccess) p.confirmed = status
                        if (p.latest != ticket) return@synchronized null // a newer tap is on its way: it decides
                        pending.remove(videoId)
                        if (result.isFailure) p.confirmed else null
                    }
                    back?.let(rollBack)
                }
            }
        }
    }
}
