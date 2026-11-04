package com.music.bitchord.desktop

import java.util.concurrent.TimeUnit // fork
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What [DesktopDecodeAhead] reads from: a decoder, or a stand-in for one in tests. */
internal interface DesktopSampleSource {
    /** The next block as interleaved floats, or null at the end. The array may be reused. */
    fun readSamples(): FloatArray?

    /** How much of the array [readSamples] returned is actually this block. */
    val sampleCount: Int

    fun seek(micros: Long): Boolean

    /** Fork: where the first block after the open or the last seek really begins; see ForkCueCorrection. */
    val forkLandedUs: Long? get() = null

    fun close()
}

/**
 * Fork (from Desktop Revived 700e0d16): what the worker behind [DesktopDecodeAhead] is doing, so a
 * dead decoder can't pass for one that is still buffering, nor a failed one for the end of the song.
 */
internal enum class ForkDecodeState { RUNNING, EOF, FAILED, CLOSED }

/**
 * Fork: whether a deck reopened [attempt] times may be reopened again, out of [limit]. A deck that has
 * played [forgiveNs] since its last reopen has earned its tries back; one never reopened
 * ([recoveredAtNs] 0) has not, or a failing resolve would be retried for ever (nanoTime minus 0 is
 * always past the window).
 */
internal fun forkRecoveryAllowed(attempt: Int, recoveredAtNs: Long, nowNs: Long, limit: Int, forgiveNs: Long): Boolean {
    if (attempt > 0 && recoveredAtNs != 0L && nowNs - recoveredAtNs > forgiveNs) return true
    return attempt < limit
}

/**
 * Decodes a track a few seconds ahead of the speakers, on a thread of its own.
 *
 * The audio thread used to call the decoder directly, and the decoder reads straight from the
 * network: a YouTube stream stops for a whole 512 KiB range request every half-minute or so, and a
 * lossless stream waits on its socket whenever the connection hiccups. WASAPI holds only a fifth
 * of a second, so any read slower than that was heard as a gap of a few milliseconds. Here those
 * waits land on this thread while the audio thread plays what was decoded earlier.
 *
 * Only this thread touches [source] once constructed — seeks and the close are handed to it — so
 * the native decoder never sees two threads.
 */
internal class DesktopDecodeAhead(
    private val source: DesktopSampleSource,
    val outputFormat: DesktopPcmFormat,
    val durationUs: Long?,
    val measuredFormat: DesktopStreamFormat?,
    /** How far ahead to decode, in samples across all channels. */
    private var aheadSamples: Int = outputFormat.sampleRate * outputFormat.channels * AHEAD_SECONDS, // fork: var, see forkPrepareForPlayback
    name: String = "BitChord-Decode",
) {

    private val lock = ReentrantLock()
    /** Signalled when there is something for the audio thread to take. */
    private val ready = lock.newCondition()
    /** Signalled when the worker has room, a seek, or a close to act on. */
    private val wake = lock.newCondition()

    private val queue = ArrayDeque<FloatArray>()
    private var queuedSamples = 0
    private var ended = false
    private var closed = false
    /** Fork (Revived 700e0d16): the decoder threw; see [forkState]. */
    private var failed: Throwable? = null
    /** Fork (Revived 418d328d): when the audio thread first found the queue empty with the worker live, else 0. */
    private var underrunSinceNanos = 0L
    private var pendingSeekUs: Long? = null
    /** Bumped by every seek, so a block decoded before it is never played after it. */
    private var generation = 0
    /** Whether a block has been handed out since the last seek; a wait before the first is a load. */
    private var started = false

    /** How much of the array [readSamples] returned is actually this block. */
    var sampleCount: Int = 0
        private set

    /** Fork: where the queued blocks sit in the song (ForkCueCorrection). */
    internal val forkClock = ForkDeckClock()

    /** Fork: where the first block since the last seek really begins; null until one is decoded. */
    val forkLandedUs: Long? get() = forkClock.landedUs

    private val worker = Thread(::decodeLoop, name).apply {
        isDaemon = true
        // Above the UI, below the audio thread itself.
        priority = Thread.NORM_PRIORITY + 1
        start()
    }

    /** The next decoded block, waiting for the decoder only if it has fallen behind. */
    fun readSamples(): FloatArray? = readSamples(Long.MAX_VALUE)

    /**
     * Fork (from Desktop Revived 418d328d): the next block, but the caller has control back after
     * [maxWaitMillis]. A decoder can sit in a network read for seconds, and the audio thread also
     * drains Pause, Seek and track changes, so it must not wait on one for ever. A null is the end
     * (see [forkState]) or, while it is [ForkDecodeState.RUNNING], an underrun.
     */
    fun readSamples(maxWaitMillis: Long): FloatArray? = lock.withLock {
        if (queue.isEmpty() && !ended && !closed && failed == null) {
            val waitedFrom = System.nanoTime()
            if (maxWaitMillis == Long.MAX_VALUE) {
                while (queue.isEmpty() && !ended && !closed && failed == null) ready.await()
            } else {
                var left = TimeUnit.MILLISECONDS.toNanos(maxWaitMillis.coerceAtLeast(0L))
                while (queue.isEmpty() && !ended && !closed && failed == null && left > 0L) left = ready.awaitNanos(left)
            }
            val waitedMs = (System.nanoTime() - waitedFrom) / 1_000_000
            if (started && queue.isNotEmpty() && waitedMs >= STARVED_LOG_MS) {
                DesktopTrackLog.log("decode-ahead ran dry: the audio thread waited ${waitedMs}ms for the decoder")
            }
        }
        val block = queue.removeFirstOrNull()
        if (block == null) {
            sampleCount = 0
            // fork: an empty queue with the worker live is an underrun, timed for the stall check
            if (!ended && !closed && failed == null) {
                if (underrunSinceNanos == 0L) underrunSinceNanos = System.nanoTime()
            } else {
                underrunSinceNanos = 0L
            }
            return null
        }
        underrunSinceNanos = 0L // fork
        queuedSamples -= block.size
        forkClock.taken() // fork
        started = true
        wake.signal()
        sampleCount = block.size
        block
    }

    /** Fork (Desktop Revived 88dcc5d0): a deck opened ahead with a short read-ahead grows to the full one when it plays. */
    fun forkPrepareForPlayback() = lock.withLock {
        aheadSamples = maxOf(aheadSamples, outputFormat.sampleRate * outputFormat.channels * AHEAD_SECONDS)
        wake.signalAll()
    }

    /** Fork: whether a block is decoded and waiting, so a read now would not wait on the worker. */
    fun forkHasSamples(): Boolean = lock.withLock { queue.isNotEmpty() }

    /** Fork (Revived 700e0d16): the worker's lifecycle; FAILED is a decoder that threw, not the song's end. */
    val forkState: ForkDecodeState
        get() = lock.withLock {
            when {
                closed -> ForkDecodeState.CLOSED
                failed != null -> ForkDecodeState.FAILED
                ended && queue.isEmpty() -> ForkDecodeState.EOF
                else -> ForkDecodeState.RUNNING
            }
        }

    /** Fork: why [forkState] is FAILED. */
    val forkFailure: Throwable? get() = lock.withLock { failed }

    /** Fork (Revived 418d328d): how long the queue has been empty while the worker is live, 0 when it isn't. */
    val forkUnderrunMs: Long
        get() = lock.withLock {
            if (underrunSinceNanos == 0L) 0L else (System.nanoTime() - underrunSinceNanos).coerceAtLeast(0L) / 1_000_000
        }

    /** Moves to [micros]; whatever was decoded ahead of the old position is thrown away. */
    fun seek(micros: Long): Boolean {
        lock.withLock {
            if (closed || failed != null) return false // fork: the worker is gone; see forkState
            generation++
            queue.clear()
            queuedSamples = 0
            ended = false
            started = false
            underrunSinceNanos = 0L // fork
            pendingSeekUs = micros
            forkClock.seeked() // fork
            wake.signal()
        }
        return true
    }

    /** Stops decoding; the decoder itself is closed by the worker, once any read in flight returns. */
    fun close() {
        lock.withLock {
            if (closed) return // fork
            closed = true
            queue.clear()
            queuedSamples = 0
            underrunSinceNanos = 0L // fork
            wake.signal()
            ready.signalAll()
        }
        // fork (Revived 700e0d16): a read stuck in the network gives up sooner (the stream's callbacks
        // answer an interrupted read as an I/O error), so a closed deck doesn't keep its thread
        worker.interrupt()
    }

    private fun decodeLoop() {
        try {
            while (true) {
                var seekTo: Long? = null
                val decodingFor = lock.withLock {
                    while (!closed && failed == null && pendingSeekUs == null && (ended || queuedSamples >= aheadSamples)) {
                        wake.await()
                    }
                    if (closed || failed != null) return
                    seekTo = pendingSeekUs
                    pendingSeekUs = null
                    generation
                }
                val micros = seekTo
                if (micros != null) {
                    if (!source.seek(micros)) DesktopTrackLog.log("decode-ahead: seek to ${micros / 1_000}ms failed")
                    continue
                }

                // fork (Revived 700e0d16): a decoder that throws is FAILED, not the end of the song, so the
                // engine can reopen the stream at the heard position instead of skipping the rest
                val decoded = try {
                    source.readSamples()?.copyOf(source.sampleCount)
                } catch (failure: Exception) {
                    lock.withLock {
                        if (decodingFor == generation && !closed) {
                            DesktopTrackLog.log("decode-ahead: decoder failed: ${failure.message}")
                            failed = failure
                            underrunSinceNanos = 0L
                            ready.signalAll()
                        }
                    }
                    if (lock.withLock { failed != null || closed }) return
                    continue
                }
                val forkLanded = source.forkLandedUs // fork: read on the decoder's own thread
                lock.withLock {
                    // A seek came in while this block was being read; it belongs to the old position.
                    if (decodingFor != generation) return@withLock
                    // Close came in while this block was being read. Committing it would leave the
                    // queue non-empty after close, and the caller's very next readSamples() — which
                    // the contract says is null — would return this block instead. Seen on Windows
                    // CI, where scheduling lets the caller's close win this race consistently;
                    // Linux's timing hides it. The block dies with the closed reader.
                    if (closed) return@withLock
                    if (decoded == null) {
                        ended = true
                    } else if (decoded.isNotEmpty()) {
                        queue.addLast(decoded)
                        forkClock.appended(decoded.size / outputFormat.channels.coerceAtLeast(1), outputFormat.sampleRate, forkLanded) // fork
                        queuedSamples += decoded.size
                    }
                    ready.signalAll()
                }
            }
        } catch (_: InterruptedException) {
            // Closing the app; the decoder goes with the process.
        } finally {
            runCatching { source.close() }
        }
    }

    internal companion object { // fork: internal, for the instant next deck's short read-ahead
        /** Long enough to ride out a slow range request or a reconnect. */
        const val AHEAD_SECONDS = 8

        /** Fork (Desktop Revived 88dcc5d0): what a next deck opened only for instant playback holds until it plays. */
        const val FORK_INSTANT_AHEAD_SECONDS = 2

        /** Waits shorter than this are ordinary scheduling, not a network stall worth logging. */
        const val STARVED_LOG_MS = 20L
    }
}
