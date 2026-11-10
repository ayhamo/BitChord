package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Where a track is between "not on this computer" and "on it". */
sealed interface DesktopDownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DesktopDownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DesktopDownloadState

    data class Failed(val reason: String) : DesktopDownloadState
}

/** One row of the downloads manager. */
data class DesktopDownloadItem(
    val song: Song,
    val state: DesktopDownloadState,
    /** The order it was asked for in, which is the order the queue reaches it in. */
    val sequence: Long,
)

/**
 * The download queue, and what came out of it.
 *
 * Android's `Downloads` split, kept: [active] is what is happening now and lives only in memory,
 * because a transfer interrupted by the process dying did not happen; what exists on disk is the
 * files themselves, which the library scan finds on its own.
 *
 * [WORKERS] run at once, each pulling from the same queue, so one slow track does not hold up the
 * rest and a release asked for as a whole arrives in the order it was asked for.
 */
object DesktopDownloadQueue {

    /** How many transfers run at once. Android's `DownloadService` uses the same number. */
    internal const val WORKERS = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val sequence = AtomicLong(0)

    /** Asked for, not yet claimed by a worker. Insertion-ordered, which is queue order. */
    private val pending = LinkedHashMap<String, DesktopDownloadItem>()

    /** Claimed by a worker; the job is attached once it exists. */
    private val running = LinkedHashMap<String, Job?>()

    private var workers = 0

    private val _active = MutableStateFlow<Map<String, DesktopDownloadItem>>(emptyMap())

    /** Everything queued, running or just failed, keyed by videoId. */
    val active: StateFlow<Map<String, DesktopDownloadItem>> = _active

    private val _finished = MutableSharedFlow<Song>(extraBufferCapacity = 64)

    /** Tracks that reached disk, for the library to record. */
    val finished: SharedFlow<Song> = _finished

    /**
     * Adds [song] to the queue, unless it is already in it.
     *
     * A second tap on a track already queued or running is a no-op rather than a second transfer:
     * the row it would produce is the same row.
     */
    fun enqueue(song: Song) {
        val id = song.videoId
        if (id.isBlank()) return
        synchronized(lock) {
            if (id in pending || id in running) return
            val item = DesktopDownloadItem(song, DesktopDownloadState.Queued, sequence.incrementAndGet())
            pending[id] = item
            // fork: published under the lock [cancel] and the workers take (Desktop Revived 8bb8152d's
            // race, fixed by hand). Published after it, a cancel landing in between found no row to
            // remove and the row then appeared as a "Queued" that nothing would ever run; and a
            // worker that had already taken the track had its Running row overwritten by Queued.
            _active.update { it + (id to item) }
        }
        ensureWorkers()
    }

    fun enqueueAll(songs: List<Song>) = songs.forEach(::enqueue)

    /**
     * Drops [videoId] from the queue, or stops it if it is one of the ones running.
     *
     * Dropping it from [running] is what makes this safe in the gap between a track being dequeued
     * and its job existing: a cancel landing in that window finds no job to stop, but [onRunning]
     * then finds the id it was told to run is no longer wanted and stops it on arrival.
     */
    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            // A download called off is not something anyone needs reminding to check on, so it
            // leaves the manager rather than sitting in it as a permanent "cancelled" row.
            // fork: under the lock, with the claim (Desktop Revived 8bb8152d): removed after it, an
            // enqueue of the same track in between (cancel, then download again) lost its new row.
            _active.update { it - videoId }
            running.remove(videoId)
        }
        job?.cancel()
        DesktopDownloadManager.cancel(videoId)
    }

    /**
     * fork: every row at once, failed ones included, under one lock (Desktop Revived 8bb8152d). It
     * cancelled the rows it had read one by one, so a track enqueued meanwhile, or one whose row was
     * not published yet, was left behind running.
     */
    fun cancelAll() {
        val (ids, jobs) = synchronized(lock) {
            val ids = (pending.keys + running.keys + _active.value.keys).toSet()
            val jobs = running.values.filterNotNull()
            pending.clear()
            running.clear()
            _active.value = emptyMap()
            ids to jobs
        }
        jobs.forEach(Job::cancel)
        ids.forEach(DesktopDownloadManager::cancel)
    }

    /** Drops the finished and failed rows, leaving whatever is still going. */
    fun clearFinished() {
        _active.update { current ->
            current.filterValues { it.state !is DesktopDownloadState.Failed }
        }
    }

    /** Puts a failed track back at the end of the queue. */
    fun retry(videoId: String) {
        val song = _active.value[videoId]?.song ?: return
        _active.update { it - videoId }
        enqueue(song)
    }

    private fun ensureWorkers() {
        val toStart = synchronized(lock) {
            val wanted = minOf(WORKERS, pending.size + running.size)
            val starting = (wanted - workers).coerceAtLeast(0)
            workers += starting
            starting
        }
        repeat(toStart) { scope.launch { work() } }
    }

    /**
     * The transfer itself; a seam for tests, which can't reach the network or the Music folder.
     */
    internal var transfer: suspend (Song, (downloaded: Long, total: Long?) -> Unit) -> Result<Song> =
        { song, progress -> DesktopDownloadManager.download(song, onProgress = progress) }

    /**
     * Each track runs in its own child job, and that is the job [cancel] stops, as Android's
     * `DownloadService` does it. The worker's own job used to be the one registered, so a cancel
     * killed the worker: the queue lost one of its [WORKERS] for good, and with every worker
     * cancelled the rest of the queue sat there with nobody left to take it.
     */
    private suspend fun work() {
        // fork: whether takeNext already gave this worker's place back, under the lock that found the
        // queue empty; see takeNext.
        var released = false
        try {
            while (true) {
                val item = takeNext() ?: run { released = true; return }
                val id = item.song.videoId
                val track = scope.async(start = CoroutineStart.LAZY) {
                    transfer(item.song) { downloaded, total ->
                        val fraction = if (total != null && total > 0L) {
                            (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        _active.update { current ->
                            // Only while it is still the running row: a cancel removes it, and
                            // putting it back here would resurrect a row nobody asked for.
                            // fork: this request's row, by its sequence (Desktop Revived 8bb8152d): by
                            // id, a cancelled transfer's last progress overwrote the Queued row of the
                            // same track asked for again, and its cancel then took that row away.
                            if (current[id]?.sequence != item.sequence) current
                            else current + (id to item.copy(state = DesktopDownloadState.Running(fraction)))
                        }
                    }
                }
                onRunning(id, track)
                val result = try {
                    track.await()
                } catch (cancelled: CancellationException) {
                    // The worker itself going is the only cancellation that ends it; a cancelled
                    // track ends only that track, and its row leaves the manager.
                    currentCoroutineContext().ensureActive()
                    Result.failure(cancelled)
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
                onIdle(id, track)
                result.fold(
                    onSuccess = { saved ->
                        // fork: only this request's row (by sequence, Desktop Revived 8bb8152d); a newer
                        // request for the same track keeps its own. The file is on disk either way, so
                        // the library still hears of it.
                        _active.update { current -> if (current[id]?.sequence == item.sequence) current - id else current }
                        _finished.tryEmit(saved)
                    },
                    onFailure = { failed(id, item, it) },
                )
            }
        } finally {
            // fork: only a worker that ended some other way still holds its place here.
            if (!released) synchronized(lock) { workers-- }
        }
    }

    private fun failed(id: String, item: DesktopDownloadItem, cause: Throwable) {
        // A cancelled transfer is not a failure to report: its row leaves the manager (a cancel
        // landing before its job existed found no row to take away yet). Only this request's row:
        // the track may already be queued again under a new one.
        if (cause is CancellationException) {
            _active.update { current -> if (current[id]?.sequence == item.sequence) current - id else current }
            return
        }
        // fork: by sequence (Desktop Revived 8bb8152d). Checked by id, a failure of a request already
        // cancelled turned the same track's new Queued or Running row into this old "Failed".
        if (_active.value[id]?.sequence != item.sequence) return
        val reason = cause.message?.takeIf(String::isNotBlank) ?: "The download did not finish"
        _active.update { current ->
            if (current[id]?.sequence != item.sequence) current
            else current + (id to item.copy(state = DesktopDownloadState.Failed(reason)))
        }
        DesktopTrackLog.log("download failed for '${item.song.title}' — $reason")
    }

    /**
     * The next track to fetch, or null when the queue is empty.
     *
     * Claims it as running under the same lock that removed it, so there is no instant where a
     * track is in neither the queue nor the running slot and a [cancel] for it would quietly do
     * nothing.
     *
     * fork: its Running row is published under the same lock (Desktop Revived 8bb8152d). Published
     * after it, a cancel landing in between removed the row, and the worker then put it back as a
     * Running row for a track nobody wanted any more.
     *
     * fork: an empty queue gives the worker's place back under the lock that found it empty. It
     * used to be given back afterwards (in `work`'s finally), and a track enqueued in that gap found
     * every place still taken, started no worker, and stayed Queued for good once the leaving
     * workers were gone: the same race Revived fixed by starting workers again from the finally.
     */
    private fun takeNext(): DesktopDownloadItem? = synchronized(lock) {
        val entry = pending.entries.firstOrNull() ?: run {
            workers--
            return null
        }
        pending.remove(entry.key)
        running[entry.key] = null
        val item = entry.value
        _active.update { it + (item.song.videoId to item.copy(state = DesktopDownloadState.Running(0f))) }
        item
    }

    /** Attaches the job fetching [videoId] and starts it, unless it has been cancelled meanwhile. */
    private fun onRunning(videoId: String, job: Job) {
        val cancelled = synchronized(lock) {
            if (videoId !in running) return@synchronized true
            running[videoId] = job
            false
        }
        if (cancelled) job.cancel() else job.start()
    }

    /**
     * Only [job]'s own claim: a track cancelled and asked for again at once is already running
     * under a new job on another worker, and taking that claim away left its cancel nothing to stop.
     */
    private fun onIdle(videoId: String, job: Job) {
        synchronized(lock) { if (running[videoId] === job) running.remove(videoId) }
    }
}
