package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.util.concurrent.Executors

/**
 * Fork (from Desktop Revived 88dcc5d0, `DesktopPlaybackWrites`): the queue and the history are written
 * on a writer thread of their own, not on the click that starts a song. Each is a snapshot: a newer one
 * replaces one not yet written, and reads see the newest at once (`DesktopPersistence.queue()`,
 * `history()`, `queue_index`). What is still waiting is written when the app exits (a shutdown hook,
 * which `exitProcess` runs), so a quit straight after a skip keeps it.
 */
internal object ForkPlaybackWrites {

    @Volatile var latestQueue: List<Song>? = null
    @Volatile var latestQueueIndex: Int? = null
    @Volatile var latestHistory: List<Song>? = null

    private val pending = LinkedHashMap<String, () -> Unit>()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitChord-Writes").apply { isDaemon = true }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ flush() }, "BitChord-Writes-Exit"))
    }

    /** Writes [block] soon, replacing a write for [key] that hasn't happened yet. */
    fun submit(key: String, block: () -> Unit) {
        val schedule = synchronized(pending) {
            val idle = pending.isEmpty()
            pending[key] = block
            idle
        }
        if (schedule) runCatching { writer.execute(::drain) }.onFailure { drain() }
    }

    /** Everything still waiting, written now on the caller's thread. */
    fun flush() = drain()

    /**
     * Held across take-and-write, so the exit hook's drain waits for a write the writer thread is in
     * the middle of: two writes of one key at once could interleave their preference chunks (a broken
     * queue at the next start), or the older snapshot finish last.
     */
    private val writeLock = Any()

    private fun drain() {
        while (true) {
            synchronized(writeLock) {
                val next = synchronized(pending) {
                    val key = pending.keys.firstOrNull() ?: return
                    pending.remove(key)
                } ?: return
                runCatching { next() }.onFailure { DesktopTrackLog.log("could not save the queue or history: ${it.message}") }
            }
        }
    }
}
