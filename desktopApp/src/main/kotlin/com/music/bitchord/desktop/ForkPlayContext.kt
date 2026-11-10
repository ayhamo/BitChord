package com.music.bitchord.desktop

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ContextRegistry
import com.music.bitchord.playback.ContextSnapshot
import com.music.bitchord.playback.ContinuationPage
import com.music.bitchord.playback.ContinuationResultStatus
import com.music.bitchord.playback.ContinuationState
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import com.music.bitchord.playback.QueueWindow
import com.music.bitchord.playback.contextEntryKey
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Play and Shuffle on a long album or playlist play all of it, as on the phone.
 *
 * A YouTube playlist page arrives ~100 rows at a time and the PC's page asks for the next page
 * only as the listener scrolls to it, so Play on a 1,000-song playlist used to queue the first
 * hundred and stop there. The phone hands the page and its continuation token to
 * `PlaybackService.startBoundedContext`, whose shared [ContextRegistry] walks the remaining pages
 * behind playback (`startBoundedContinuationLoading`) and grows the queue as they land. This is
 * that walk for the PC's queue: the same registry decides what is new (by [contextEntryKey], so a
 * song a playlist lists twice stays twice: YouTube tells the two rows apart by `setVideoId`) and
 * stops a feed that loops back on itself; the same [QueueWindow] decides the order the new rows go
 * in (canonical with shuffle off, shuffled among themselves with it on, appended after what is
 * already planned, exactly as `QueueWindow.extendTo` grows the phone's order).
 *
 * The PC's queue is a whole list rather than the phone's bounded Media3 window, so the rows are
 * put straight into it: at the end of the context's section, ahead of any AutoPlay already queued
 * ([withGrowth]). The walk ends when the listener has moved on to something else (no row of this
 * context is current or still to come), when another context starts ([start], [stop]), or when
 * the registry stops it (no more pages, a page with nothing new, a repeated token).
 *
 * Calls happen on the window's thread ([scope] is its composition scope); only the page request
 * goes to [io]. One instance per window.
 */
internal class ForkPlayContext(
    private val scope: CoroutineScope,
    /** One continuation page; null when the request failed (it is retried, as on the phone). */
    private val loadPage: suspend (String) -> ContinuationPage? = ::youtubePage,
    private val io: CoroutineContext = Dispatchers.IO,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
) {

    /** What the window lends; set on every composition, as [ForkQueues]' handles are. */
    class Hooks(
        val queue: () -> DesktopQueue,
        /** The window's shuffle switch, read at each page: a toggle mid-walk is followed. */
        val shuffleOn: () -> Boolean,
        /** A row as the window plays one: its canonical form with the page's source stamped on. */
        val prepare: (Song) -> Song,
        /**
         * Puts the grown queue in place and saves it. [preShuffle] maps the window's saved
         * pre-shuffle order (empty when shuffle is off) to one that also holds the new rows.
         */
        val apply: (queue: DesktopQueue, preShuffle: (List<String>) -> List<String>) -> Unit,
        /** The registry grew: the open page with the same browse id takes the new rows too. */
        val mirror: (ContextSnapshot) -> Unit,
        /**
         * True while a Listen Together party holds the queue. Joining one adopts the party's queue
         * reusing local rows with their entry ids, so this context can still look current; a page
         * spliced in then would change the shared queue under the party.
         */
        val inParty: () -> Boolean = { false },
    )

    var hooks: Hooks? = null

    private val registry = ContextRegistry()
    private var window: QueueWindow? = null
    private var contextToken: String? = null
    private var contextSource: QueueSource? = null
    private var startedShuffled = false
    private var job: Job? = null

    /** Queue entry ids of this context's rows, which is how the walk tells its rows from others. */
    private val entryIds = HashSet<String>()

    /**
     * Starts following [continuation] for the queue the window has just built from [pageSongs].
     *
     * [pageSongs] is the page's own order (not a shuffled copy: Shuffle passes [shuffled] = true
     * instead), [selected] the row that is playing, [started] the queue as the window built it.
     * With no token there is nothing more to load and any earlier walk is simply stopped.
     */
    fun start(
        browseId: String?,
        pageSongs: List<Song>,
        selected: Song,
        continuation: String?,
        source: QueueSource,
        shuffled: Boolean,
        started: DesktopQueue,
    ) {
        stop()
        val token = continuation?.takeIf(String::isNotBlank) ?: return
        if (pageSongs.isEmpty()) return
        val contextToken = "${browseId.orEmpty()}:${UUID.randomUUID()}"
        val registered = registry.register(
            contextToken = contextToken,
            sourceKey = "BROWSE:${browseId.orEmpty()}",
            browseId = browseId,
            songs = pageSongs,
            continuationToken = token,
        )
        val selectedKey = selected.contextEntryKey()
        val selectedIndex = registered.songs.indexOfFirst { it.contextEntryKey() == selectedKey }
            .takeIf { it >= 0 } ?: 0
        // A fresh seed per start, as the phone's: the default one is fixed, which would give the
        // same playlist the same "shuffled" later pages every time.
        window = QueueWindow(registered.songs.size, shuffleSeed = Random.nextLong())
            .startAt(selectedIndex, shuffled)
        this.contextToken = contextToken
        contextSource = source
        startedShuffled = shuffled
        started.songs.filter { it.queueTier == QueueTier.CONTEXT }
            .mapNotNullTo(entryIds) { it.queueEntryId }
        DesktopTrackLog.log("context: following ${browseId ?: "?"} past ${registered.songs.size} rows")
        walk(contextToken)
    }

    /** Ends the walk and forgets the context; a late page is then stale in the registry. */
    fun stop() {
        job?.cancel()
        job = null
        contextToken = null
        contextSource = null
        window = null
        entryIds.clear()
        registry.register(
            contextToken = "cleared:${UUID.randomUUID()}",
            sourceKey = "cleared",
            browseId = null,
            songs = emptyList(),
            continuationToken = null,
        )
    }

    /** The registry's state, for tests and the log. */
    internal fun snapshot(): ContextSnapshot? = registry.snapshot()

    /** Waits for the current walk to end (tests). */
    internal suspend fun awaitWalk() {
        job?.join()
    }

    private fun walk(token: String) {
        job = scope.launch {
            var failures = 0
            while (isActive && contextToken == token) {
                val request = registry.claimContinuation(token) ?: break
                val page = withContext(io) { runCatching { loadPage(request.continuationToken) }.getOrNull() }
                if (contextToken != token) break
                if (page == null) {
                    // fail() leaves the token claimable: one dropped request must not end the
                    // walk and leave the queue cut short, so it is retried with the phone's backoff.
                    registry.fail(request)
                    if (++failures > retryDelaysMs.size) {
                        DesktopTrackLog.log("context: next page failed ${failures - 1} times, stopped")
                        break
                    }
                    delay(retryDelaysMs[failures - 1])
                    continue
                }
                failures = 0
                val result = registry.complete(request, page)
                val snapshot = result.snapshot
                if (result.status != ContinuationResultStatus.COMPLETED || snapshot == null) {
                    DesktopTrackLog.log("context: stopped at ${snapshot?.songs?.size} rows (${result.diagnostic ?: result.status})")
                    break
                }
                if (!grow(snapshot, result.addedSongs.size)) break
                if (snapshot.continuationState == ContinuationState.EXHAUSTED) {
                    DesktopTrackLog.log("context: all ${snapshot.songs.size} rows queued")
                }
            }
        }
    }

    /** Puts one page's new rows into the queue; false once the listener has left this context. */
    private fun grow(snapshot: ContextSnapshot, added: Int): Boolean {
        val hooks = hooks ?: return false
        val window = window ?: return false
        val source = contextSource ?: return false
        val queue = hooks.queue()
        if (hooks.inParty()) {
            DesktopTrackLog.log("context: a party holds the queue, stopped at ${snapshot.songs.size} rows")
            stop()
            return false
        }
        if (!holdsContext(queue, entryIds)) {
            DesktopTrackLog.log("context: the queue moved on, stopped at ${snapshot.songs.size} rows")
            stop()
            return false
        }
        hooks.mirror(snapshot)
        if (added == 0) return true
        val oldSize = window.canonicalSize
        window.setShuffle(startedShuffled || hooks.shuffleOn())
        window.extendTo(snapshot.songs.size)
        val rows = window.order.filter { it >= oldSize }.map { index ->
            hooks.prepare(snapshot.songs[index]).copy(
                playbackSource = source.title,
                playbackSourceType = source.type,
                playbackSourceId = source.id,
            ).asQueueEntry(QueueTier.CONTEXT)
        }
        val before = HashSet(entryIds)
        // The new rows' keys in the playlist's own order, for the pre-shuffle order: the registry
        // holds one row per key, so the lookup is exact.
        val byKey = rows.associateBy { it.contextEntryKey() }
        val canonicalKeys = snapshot.songs.drop(oldSize).mapNotNull { song ->
            byKey[song.contextEntryKey()]?.let(DesktopQueue::orderKey)
        }
        rows.mapNotNullTo(entryIds) { it.queueEntryId }
        hooks.apply(withGrowth(queue, rows)) { order -> withGrowthKeys(order, before, canonicalKeys) }
        return true
    }

    companion object {
        /** The phone's `BOUNDED_CONTINUATION_RETRIES_MS`. */
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 15_000L)

        private suspend fun youtubePage(token: String): ContinuationPage? =
            YtMusicRepository.moreSongs(token).getOrNull()
                ?.let { ContinuationPage(songs = it.songs, continuationToken = it.continuation) }

        /**
         * [rows] put at the end of the context's section: ahead of the first AutoPlay row still to
         * come (AutoPlay can be queued while a slow page loads, and it belongs after the album, as
         * the phone's sections have it), else at the end. History and the current row stay.
         */
        internal fun withGrowth(queue: DesktopQueue, rows: List<Song>): DesktopQueue {
            if (rows.isEmpty()) return queue
            val from = (queue.index + 1).coerceAtMost(queue.songs.size)
            val autoplay = (from until queue.songs.size).firstOrNull {
                queue.songs[it].queueTier == QueueTier.AUTOPLAY || queue.songs[it].fromAutoplay
            } ?: queue.songs.size
            return queue.copy(songs = queue.songs.take(autoplay) + rows + queue.songs.drop(autoplay))
        }

        /**
         * The window's pre-shuffle order with [added] keys in after the last key of the context
         * ([contextKeys]), so turning shuffle off puts the later pages back where the playlist has
         * them rather than at the end in shuffled order. An empty order (shuffle off) stays empty.
         */
        internal fun withGrowthKeys(order: List<String>, contextKeys: Set<String>, added: List<String>): List<String> {
            if (order.isEmpty() || added.isEmpty()) return order
            val last = order.indexOfLast { it in contextKeys }
            val at = if (last < 0) order.size else last + 1
            return order.take(at) + added + order.drop(at)
        }

        /** Whether this context still has a row playing or to come. */
        internal fun holdsContext(queue: DesktopQueue, entryIds: Set<String>): Boolean =
            (queue.index until queue.songs.size).any { queue.songs[it].queueEntryId in entryIds }

        /**
         * A page's next rows merged into what it has: every row it has stays, and a new row is
         * dropped only when the page already holds that same row ([contextEntryKey]). Was
         * `distinctBy(videoId)`, which also dropped a song the playlist lists twice.
         */
        internal fun mergeRows(existing: List<Song>, more: List<Song>): List<Song> {
            val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
            val added = more.filter { known.add(it.contextEntryKey()) }
            return if (added.isEmpty()) existing else existing + added
        }

        /**
         * The open page after the walk grew: its rows plus the registry's new ones, and the token
         * to continue from, never moved backwards (the phone's `applyBoundedContextSnapshot`):
         * a page whose own loading is ahead keeps its token. Null when nothing changes.
         */
        internal fun mirrored(
            pageSongs: List<Song>,
            pageContinuation: String?,
            snapshot: ContextSnapshot,
        ): Pair<List<Song>, String?>? {
            val songs = mergeRows(pageSongs, snapshot.songs)
            val continuation = when {
                snapshot.songs.size < pageSongs.size -> pageContinuation
                snapshot.continuationState == ContinuationState.EXHAUSTED -> null
                snapshot.continuationState == ContinuationState.AVAILABLE -> snapshot.continuationToken
                else -> pageContinuation
            }
            if (songs === pageSongs && continuation == pageContinuation) return null
            return songs to continuation
        }

        /**
         * Whether [played] is the page's own list (any order), which is when the page's
         * continuation belongs to it. The phone asks the same of the tapped row; asking it of the
         * whole list also keeps a list started from the page that is not the page (a Navidrome
         * artist's radio, the "Suggested" rows) from being grown with the page's rows.
         */
        internal fun isPageList(played: List<Song>, page: List<Song>): Boolean {
            if (played.size != page.size) return false
            val keys = page.mapTo(HashSet()) { it.contextEntryKey() }
            return played.all { it.contextEntryKey() in keys }
        }
    }
}
