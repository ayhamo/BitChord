package com.music.bitchord.desktop

import kotlin.math.abs

/*
 * Fork: a deck measured from the audio it actually hands over, not from the position it was asked
 * for (from PC-Continued be257dad, `correctCueFromDeck` / `reconcileIncomingFromDeck`, fitted to this
 * engine).
 *
 * Every position the engine publishes for a song is counted from where its deck was cued: Automix's
 * entry point, a restored queue's position, a seek, the write point a quality upgrade is lined up on.
 * The decoder cuts the audio between its stream's index point and that ask (`DesktopAudioDecoder.seek`)
 * — except where it can't: a stream with no timestamps, or an index point further back than its guard
 * allows. Then the deck plays from where it landed, and counting from the ask put the scrubber, and
 * the lyrics with it, that far ahead of the singing for the rest of the song. And a deck's stretch
 * buffer can still hold audio from before a cue it was moved to, which the blend's frame count then
 * counts as the song's present.
 *
 * Two measurements fix both. The decoder notes where the first block after an open or a seek really
 * begins ([landing]); [ForkDeckClock] carries that through the decode-ahead queue, block by block, so
 * the engine knows where in the song the last block it took ends. [correctedStart] moves a cue to its
 * landing; [drift] compares the blend's count with the deck's own clock at the hand-over.
 *
 * Their hold on quality upgrades while a mix is engaged is in `ForkMixUpgradeHold`: the held copy
 * goes in through this fork's crossfaded swap (`forkQueueSwap`), which lines the copy up on its
 * landing too.
 */
internal object ForkCueCorrection {
    /** A landing this far from the ask is the deck's real start; nearer is a block's own granularity (theirs). */
    const val CUE_CORRECTION_US = 150_000L

    /** How far the blend's count may sit from the deck's own clock before the start is moved (theirs). */
    const val DECK_RECONCILE_US = 250_000L

    /** Beyond this the deck's stamps are what is wrong, and are left alone (theirs). */
    const val DECK_RECONCILE_MAX_US = 30_000_000L

    /**
     * Where the first block after a seek really begins: its frame's timestamp plus what the seek trim
     * cut from it ([skipFrames] at [sampleRate]); the [askedUs] position when the stream gave no
     * timestamp to say.
     */
    fun landing(frameUs: Long?, skipFrames: Int, sampleRate: Int, askedUs: Long): Long =
        frameUs?.plus(skipFrames * 1_000_000L / sampleRate.coerceAtLeast(1)) ?: askedUs

    /** The start to count a deck cued at [askedUs] from, given where it [landedUs]; null to keep the ask. */
    fun correctedStart(askedUs: Long, landedUs: Long?): Long? {
        val landed = landedUs ?: return null
        return landed.takeIf { abs(it - askedUs) >= CUE_CORRECTION_US }
    }

    /**
     * How far the blend's count of an incoming deck ([startUs] plus the [elapsedUs] it has mixed) runs
     * ahead of the deck's own clock: the end of the last block it took ([deckEndUs]) less what its
     * stretch buffer still holds ([heldUs], source time). Null when they agree within
     * [DECK_RECONCILE_US], or disagree past [DECK_RECONCILE_MAX_US] (then the stamps are wrong). The
     * sink's queue is the same audio either way, so it is left out of both sides.
     */
    fun drift(startUs: Long, elapsedUs: Long, deckEndUs: Long?, heldUs: Long): Long? {
        val end = deckEndUs ?: return null
        val drift = (startUs + elapsedUs) - (end - heldUs)
        return drift.takeIf { abs(it) > DECK_RECONCILE_US && abs(it) <= DECK_RECONCILE_MAX_US }
    }

    /**
     * Where a quality upgrade's copy really starts, for lining it up on the write point: its landing,
     * or the point it was armed at while it has none.
     */
    fun swapStart(armedAtUs: Long, landedUs: Long?): Long = landedUs ?: armedAtUs
}

/**
 * Where a decode-ahead queue's blocks sit in the song, kept block for block with the queue: the
 * first block after a seek starts at the decoder's landing, every later one where the one before it
 * ended. Written under the queue's own lock (by its worker as it appends, by the reader as it takes);
 * read from the audio thread through the volatile fields.
 *
 * PC-Continued's version took the landing again whenever the queue ran empty, which once the reader
 * had caught up dated a block from the seek instead of from the block before it; here only a seek
 * starts the count over.
 */
internal class ForkDeckClock {
    private val starts = ArrayDeque<Double>()
    private val lengths = ArrayDeque<Double>()

    /** Where the next block appended begins, µs; NaN until the first block after a seek has said. */
    private var nextUs = Double.NaN
    private var started = false

    /** Where the first block since the last seek really begins, or null until one has been appended. */
    @Volatile var landedUs: Long? = null
        private set

    /** Where the block last taken ends, µs: everything handed to the engine so far. Null before one. */
    @Volatile var takenEndUs: Long? = null
        private set

    fun seeked() {
        starts.clear()
        lengths.clear()
        nextUs = Double.NaN
        started = false
        landedUs = null
        takenEndUs = null
    }

    /** A block of [frames] frames at [sampleRate] joined the queue; [sourceLandedUs] is the decoder's landing. */
    fun appended(frames: Int, sampleRate: Int, sourceLandedUs: Long?) {
        if (!started) {
            started = true
            landedUs = sourceLandedUs
            nextUs = sourceLandedUs?.toDouble() ?: Double.NaN
        }
        val length = frames * 1e6 / sampleRate.coerceAtLeast(1)
        starts.addLast(nextUs)
        lengths.addLast(length)
        if (!nextUs.isNaN()) nextUs += length
    }

    /** The reader took the oldest block. */
    fun taken() {
        val start = starts.removeFirstOrNull() ?: return
        val length = lengths.removeFirst()
        if (!start.isNaN()) takenEndUs = (start + length).toLong()
    }

    /** The queue was emptied without a seek (a close). */
    fun cleared() {
        starts.clear()
        lengths.clear()
    }
}
