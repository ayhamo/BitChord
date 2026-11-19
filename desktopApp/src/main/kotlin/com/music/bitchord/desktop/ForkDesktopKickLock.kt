package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import com.music.bitchord.playback.smart.automixv2.ForkV2KickLock
import com.music.bitchord.playback.smart.automixv2.ForkV2Planner
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Fork: Automix v2's kick lock on the PC (the extension's crossfade.js `kickLockFor`, the phone
 * app's `Automix.kickLocked`).
 *
 * Why: the beat grids put a beat tens of ms off its kick, differently per song, so a blend that
 * lines the two grids up exactly can still put the two kicks 20-30 ms apart, which is heard as a
 * flam on every beat of the blend. [ForkV2KickLock.kickLock] finds the shift of the next song's cue
 * (under an eighth of a beat) that lines up the two songs' kick onsets over the blend and 8 beats
 * either side; [ForkV2Automix.kickLocked] moves the plan's mix-in by it before the deck schedule is
 * built, so the incoming deck is cued that much later or earlier and its whole clock moves with it.
 * The PC can play that: both decks are mixed on one audio thread onto one frame count, the
 * incoming decoder is seeked and trimmed to the sample (`forkLandedUs`), and the schedule runs on
 * output frames, so a shift of 4 ms is heard as 4 ms.
 *
 * How:
 * - The engine says which streams the pair plays ([streams], each tick of a blend being planned;
 *   the copies the decks actually play, as the loop mix reads them).
 * - [ForkV2Automix.kickLock] asks [resultFor] on every plan of a beatmatched blend. It answers from
 *   memory: a result measured for this exact plan (pair, mix-out, mix-in, beat, length and rates),
 *   or null. Within [START_LEAD_SEC] of the outgoing ramp it starts measuring once on its own
 *   thread: both regions the lock reads (10 beats and a second before each side's mix point to 1.2
 *   blends and 10 beats after it) are decoded from decoders of their own
 *   ([ForkDesktopLoopMix.decodeRegion], at [RATE]: the lock low-passes under 400 Hz and keeps about
 *   3 kHz, so more would only cost; see [RATE] for why exactly 12 kHz), turned into [ForkV2KickLock.kickSource] sides and locked.
 * - The plan is latched for its blend 4.5 s before the ramp ([ForkV2Automix.LATCH_LEAD_SEC]); a
 *   result after that is for nothing, which is why it starts this far ahead. Decoding two minutes'
 *   regions takes well under a second from a file and a few from a stream.
 *
 * Every outcome is kept, a refusal too ("not enough audio", "the kicks already line up, or match
 * too loosely", a decode that failed), so a plan is measured at most once; a re-plan that moves the
 * mix points measures the new plan. The result is logged once, as the phone logs it.
 */
internal object ForkDesktopKickLock {

    /** How long before the outgoing ramp the measuring starts. */
    const val START_LEAD_SEC = 40.0

    /**
     * The decode rate for the lock's regions. Not any low rate: [ForkV2KickLock.kickOnsets] reads
     * its values as exactly 1 ms apart, but takes one every `round(0.001 × r)` samples of the side's
     * rate `r` (the decode rate over `floor(rate / 3000)`). That is exactly 1 ms only where `r` is a
     * multiple of 1 kHz: 12 kHz keeps 3 kHz, a value every 3 samples. At 11 025 Hz it kept 3675 Hz,
     * a value every 4 samples = 1.088 ms, and over the ~24 s the lock folds, the kicks drifted ~2 s
     * through the beat and the profile came out flat ("no clear kick"; ForkV2KickLockTest, measured
     * 2026-11-19). The phone app's decodes are mostly 48 kHz (exact); its 44.1 kHz songs keep 3150 Hz
     * and read 0.952 ms as 1 ms, the same flaw at 4.8 % (in the original, not this port).
     */
    const val RATE = 12_000

    private class Decks(val outId: String, val outStream: DesktopStream, val inId: String, val inStream: DesktopStream)

    @Volatile
    private var pair: Decks? = null

    private val results = object : LinkedHashMap<String, ForkV2KickLock.Result>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ForkV2KickLock.Result>) = size > 8
    }

    @Volatile
    private var measuring: String? = null
    private val generation = AtomicInteger()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitChord-Automix-KickLock").apply { isDaemon = true }
    }

    /** Wires [resultFor] into the planner (idempotent; from the engine's `init`). */
    fun install() {
        ForkV2Automix.kickLock = ::resultFor
    }

    /** The pair's streams as the decks play them; from the engine while a blend is being planned. */
    fun streams(outId: String, outStream: DesktopStream, inId: String, inStream: DesktopStream) {
        val p = pair
        if (p != null && p.outId == outId && p.inId == inId && p.outStream.url == outStream.url && p.inStream.url == inStream.url) return
        pair = Decks(outId, outStream, inId, inStream)
    }

    /** The plan's identity for the lock: what [ForkV2KickLock.kickLock] reads of it. */
    internal fun keyOf(fromId: String, toId: String, p: ForkV2Planner.Plan): String = String.format(
        Locale.ROOT,
        "%s->%s|%.3f|%.3f|%.4f|%.3f|%.4f|%.4f|%s",
        fromId, toId, p.mixOutTrackTime, p.mixInTrackTime, p.beatSec, p.durationSec, p.rate, p.outRate,
        p.glide?.let { "${it.q}/${it.beats}/${it.beatOutSec}" } ?: "-",
    )

    /** The lock for [p] if measured; else null, starting the measuring when it is time. */
    fun resultFor(fromId: String, toId: String, p: ForkV2Planner.Plan, pos: Double): ForkV2KickLock.Result? {
        val key = keyOf(fromId, toId, p)
        synchronized(results) { results[key] }?.let { return it }
        if (!due(p, pos)) return null
        if (measuring == key) return null
        val streams = pair?.takeIf { it.outId == fromId && it.inId == toId } ?: return null
        measuring = key
        val gen = generation.incrementAndGet()
        val plan = p.copy()
        try {
            worker.execute { measure(key, gen, plan, streams) }
        } catch (_: RejectedExecutionException) {
            measuring = null
        }
        return null
    }

    /** Whether [pos] is within [START_LEAD_SEC] of the ramp and still before the plan is latched. */
    internal fun due(p: ForkV2Planner.Plan, pos: Double): Boolean {
        val ramp = p.mixOutTrackTime - max(0.0, p.rampSongSec)
        return pos >= ramp - START_LEAD_SEC && pos < ramp - ForkV2Automix.LATCH_LEAD_SEC
    }

    private fun measure(key: String, gen: Int, p: ForkV2Planner.Plan, streams: Decks) {
        val began = System.nanoTime()
        val result = runCatching { lock(p, streams) { generation.get() == gen } }
            .getOrElse { error -> ForkV2KickLock.Result().also { it.none = "failed: ${error.message}" } }
        if (generation.get() != gen) return // a newer plan is being measured; this one is for nothing
        if (result != null) synchronized(results) { results[key] = result }
        if (measuring == key) measuring = null
        val ms = (System.nanoTime() - began) / 1_000_000
        DesktopTrackLog.log("automix v2 ${streams.outId}->${streams.inId}: ${result ?: "kick lock: not a blend"} ($ms ms)")
    }

    /** The phone's `kickLocked` read of the two decoded regions; null for a plan that isn't a blend. */
    private fun lock(p: ForkV2Planner.Plan, streams: Decks, alive: () -> Boolean): ForkV2KickLock.Result? {
        val (outFrom, outTo) = regionOf(p, p.mixOutTrackTime)
        val (inFrom, inTo) = regionOf(p, p.mixInTrackTime)
        val out = ForkDesktopLoopMix.decodeRegion(streams.outStream, outFrom, outTo, RATE, alive)
        val inn = if (out != null) ForkDesktopLoopMix.decodeRegion(streams.inStream, inFrom, inTo, RATE, alive) else null
        if (out == null || inn == null) return ForkV2KickLock.Result().also { it.none = "no decoded audio" }
        val os = ForkV2KickLock.kickSource(arrayOf(out.left, out.right), RATE.toDouble(), out.start, 0.0, Double.POSITIVE_INFINITY)
        val ins = ForkV2KickLock.kickSource(arrayOf(inn.left, inn.right), RATE.toDouble(), inn.start, 0.0, Double.POSITIVE_INFINITY)
        return ForkV2KickLock.kickLock(p, os, ins)
    }

    /** The phone's span around a mix point: 10 beats and a second before it, 1.2 blends, 10 beats and a second after. */
    internal fun regionOf(p: ForkV2Planner.Plan, at: Double): Pair<Double, Double> {
        val b = p.beatSec
        val span = p.durationSec * 1.2 + 10 * b + 1
        return max(0.0, at - 10 * b - 1) to at + span
    }

    /** Forgets every measurement (tests). */
    internal fun forget() {
        generation.incrementAndGet()
        synchronized(results) { results.clear() }
        measuring = null
        pair = null
    }
}
