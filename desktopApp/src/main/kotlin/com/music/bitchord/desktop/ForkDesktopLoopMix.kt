package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.ForkAutomix
import com.music.bitchord.playback.smart.ForkMixPicks
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * Fork: the phone's Automix loop mix on the PC (`app/.../playback/loopmix/`, `CrossfadeController`'s
 * "The loop mix" section). Both songs loop instrumental bars while one tempo ramps from the outgoing
 * song's to the incoming one's; the incoming loop lets go into its own song, which its deck hands over
 * to two bars later. The plan and its clock are the shared `LoopMix`; the gains go through
 * `ForkAutomix.trimmedGains` (the fix for the crackle a 16-bit output clipped into on the phone).
 *
 * What is different from the phone is where it plays. The phone has a player per song and renders the
 * mix inside the outgoing player's sink, then lines the incoming player up against it by measuring
 * both sinks' clocks. The PC has one output and one audio thread that writes it, so the whole mix is
 * on one frame count: the outgoing song is spliced into the renderer at the sample its loop starts,
 * the incoming deck's decoder is lined up by dropping frames to the very sample the renderer's
 * incoming deck reaches, and the two cross on identical audio. Nothing has to be measured on a clock
 * and nothing can drift. A pause pauses the frame count with everything else, so (unlike the phone)
 * a pause does not end the mix.
 *
 * The engine's side is the `fork: the Automix loop mix` section of `DesktopAudioEngine.kt`; this file
 * is everything that can live outside it.
 */

/** A stretch of a song decoded for a deck: planar, at the renderer's rate, from track second [start]. */
internal class ForkLoopRegion(val left: FloatArray, val right: FloatArray, val start: Double) {
    fun endAt(sampleRate: Int): Double = start + left.size.toDouble() / sampleRate
}

/** What [ForkDesktopLoopMix.consider] needs to know about the pair and the engine, every tick. */
internal class ForkLoopPair(
    val outSong: Song,
    val inSong: Song,
    val outStream: DesktopStream,
    val inStream: DesktopStream,
    val outAnalysis: TrackAnalysis,
    val inAnalysis: TrackAnalysis,
    val outState: TrackAnalysisState,
    val inState: TrackAnalysisState,
    val outDurationSec: Double,
    val inDurationSec: Double,
    val positionSec: Double,
    val sampleRate: Int,
    val channels: Int,
    val automix: Boolean,
    val inParty: Boolean,
    val speed: Float,
    val skipSilence: Boolean,
    /** The outgoing deck's own beatmatch stretch, still easing out of the mix that brought it in. */
    val deckRate: Double,
    /** A quality upgrade of the playing song is crossing in or waiting to. */
    val swapping: Boolean,
    /** The Automix overlap chosen in Settings: 0 adaptive, else a fixed 8 or 16 bars. */
    val phraseBars: Int = 0,
) {
    val pairKey: String get() = "${outSong.videoId}->${inSong.videoId}"
}

/** A loop mix is planned for the pair: hold the blend. The span is the scrubber's marker, in track seconds. */
internal class ForkLoopHold(val startSec: Double, val endSec: Double)

internal object ForkLoopMixRules {
    /** The earliest an outgoing loop may start: the playhead plus this, to decode and arm (phone: `LOOP_SETUP_SECONDS`). */
    const val SETUP_SECONDS = 15.0

    /** The decks are built this long before the loop (phone: `LOOP_ARM_LEAD_MS`). */
    const val ARM_LEAD_SECONDS = 12.0

    /** A cached plan is kept while its loop is at least this far ahead; nearer, a seek plans again (`LOOP_MIN_LEAD_MS`). */
    const val MIN_LEAD_SECONDS = 9.0

    /**
     * Decks not built by this long before the loop are given up on and the pair gets its blend: the
     * splice wants half a second of the song's own audio to measure against, and the incoming deck a
     * moment to seek.
     */
    const val BUILD_DEADLINE_SECONDS = 1.5

    /** Analysed: usable, or usable and being refined (a refined analysis plans the pair again). */
    private val MEASURED = setOf(TrackAnalysisState.ANALYSED, TrackAnalysisState.REFINING)

    /** A lyrics lookup still running after this no longer holds the loop mix back (phone: `FORK_LYRICS_WAIT_MS`). */
    const val LYRICS_WAIT_MS = 8_000L

    /**
     * Why [p] can't be a loop mix, or null when it can — the phone's `loopRefusalFor` with the PC's own
     * conditions. A refused pair gets the planner's blend, filter mix or crossfade exactly as before.
     */
    fun refusal(p: ForkLoopPair): String? = when {
        !p.automix -> "Automix is off"
        // From Desktop Revived d150d26f: a fixed 8 or 16-bar overlap asked for is the planner's blend;
        // the loop mix picks its own length. Checked before the picks: a Loop pick couldn't undo it, so the
        // overview keeps Loop greyed with this reason rather than offering it after a Blend/Filter pick.
        p.phraseBars != 0 -> "a fixed ${p.phraseBars}-bar overlap is chosen"
        // A blend, filter mix, fade or length picked for the pair in the Automix overview (ForkMixPicks).
        ForkMixPicks.loopRefusal(p.outSong.videoId, p.inSong.videoId) != null -> ForkMixPicks.PICKED_REASON
        p.inParty -> "in a Listen Together party"
        p.speed != 1f -> "playback speed is not 1x"
        p.skipSilence -> "skip silence is on"
        p.channels != 2 || p.sampleRate <= 0 -> "this output can't take it"
        abs(p.deckRate - 1.0) > 1e-9 -> "the song is still easing out of its own mix"
        p.swapping -> "a better copy is crossing in"
        p.outState !in MEASURED || p.inState !in MEASURED -> "waiting on analysis"
        !analysedCopy(p.outSong, p.outStream) || !analysedCopy(p.inSong, p.inStream) -> "analysed off another recording"
        p.outDurationSec <= 0 || p.inDurationSec <= 0 -> "no duration"
        else -> null
    }

    /**
     * Whether what plays is the recording the analysis measured: the file itself, the Navidrome server's
     * audio, or YouTube (measured on its Opus). An addon's, JioSaavn's or a module's copy is another
     * master, offset by anything up to seconds; bars cut out of it at the analysis's times would loop
     * on the wrong beats. The phone refuses its source-backed copies for the same reason.
     */
    fun analysedCopy(song: Song, stream: DesktopStream): Boolean =
        stream.isLocalFile || ForkNavidrome.owns(song) || stream.sourceId == "youtube"

    /** "out 4-bar, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars" (phone: `describeLoop`). */
    fun describe(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        val tempo = if (from == to) "$from BPM" else "$from → $to BPM over ${plan.rampBars} bars"
        val fold = when {
            plan.fold > 1 -> " (next in double time)"
            plan.fold < 1 -> " (next in half time)"
            else -> ""
        }
        fun side(pick: LoopMix.Pick): String {
            val by = when (pick.checkedBy) {
                LoopMix.Check.LYRICS -> "lyrics"
                LoopMix.Check.MASK -> "mask"
                LoopMix.Check.LYRICS_AND_MASK -> "lyrics + mask"
            }
            return "${pick.bars}-bar, clear by $by"
        }
        return "out ${side(plan.out)} · in ${side(plan.inn)} · $tempo$fold"
    }
}

/**
 * One loop mix on the engine's frame count, from the built decks to the incoming song's takeover.
 * Every frame here is a *mix* frame — 0 being the outgoing loop start — unless it says otherwise;
 * [originFrame] ties them to the engine's written-frame count once the splice begins.
 *
 * Audio thread only after [build].
 */
internal class ForkLoopMixSession private constructor(
    val plan: LoopMix.Plan,
    val clock: LoopMix.Clock,
    val sampleRate: Int,
    val renderer: ForkLoopMixRenderer,
    val outId: String,
    val inId: String,
    /** The streams the regions were decoded from: a deck since replaced by another copy can't take this mix. */
    val outUrl: String,
    val inUrl: String,
) {
    private val sr = sampleRate.toDouble()

    /**
     * The incoming deck's loudness against the outgoing one's ([ForkLoudness.ratio]): the rendered incoming loop
     * carries it, and so does its own decoder's audio added after the handover, so the two cross at one level.
     */
    var inLevel: Float = 1f
        private set

    /** The element <-> deck crossings: 25 ms, as on the phone. */
    val fadeFrames: Long = (LoopMix.HANDOVER_SEC * sr).roundToLong().coerceAtLeast(1L)

    /** The mix frame the incoming song's own decoder starts crossing in at, and the one it has taken over by. */
    val handoverFrame: Long = (clock.elementAt * sr).roundToLong()
    val handoverEndFrame: Long = handoverFrame + fadeFrames

    /** Mid-ramp: the player moves to the incoming song (the phone's `handoffAt`). */
    val handoffFrame: Long = (clock.handoffAt * sr).roundToLong()

    /**
     * Where the incoming deck waits, seeked ahead of time: half a second before its handover, so the
     * frames dropped to line it up are always there to drop whatever its stream's index points are.
     */
    val inStartUs: Long = ((clock.elementTrackTime - INCOMING_PREROLL_SECONDS) * 1e6).roundToLong().coerceAtLeast(0L)

    /** The engine's written-frame count at mix frame 0, once the splice has begun; [NONE] before. */
    var originFrame: Long = NONE
        private set

    /** The splice is behind: every block from here is the renderer's. */
    var spliced: Boolean = false
        private set

    /** The player shows the incoming song (past [handoffFrame], heard). */
    var handedOff: Boolean = false

    /** The incoming decoder has been lined up with the incoming deck; [handoverTrackUs] is where, in its song. */
    var inAligned: Boolean = false
        private set
    var handoverTrackUs: Long = 0L
        private set

    /** The splice's own measurement of the outgoing region against the playing song, frames; for the log. */
    var spliceLag: Int? = null
        private set
    var spliceScore: Double = Double.NaN
        private set

    var lastPositionMs: Long = -1L
    var lastBeat: Int = -1

    val engaged: Boolean get() = originFrame != NONE

    /** The mix frame [trackUs] of the outgoing song is at, by the analysis's clock. */
    fun mixFrameOf(trackUs: Long): Long = ((trackUs / 1e6 - plan.out.loopStart) * sr).roundToLong()

    /** Whether the write point [writeUs] is close enough to the loop for the splice path to read the song. */
    fun spliceNear(writeUs: Long): Boolean =
        writeUs >= ((plan.out.loopStart - LoopMix.HANDOVER_SEC - CAPTURE_SECONDS) * 1e6).roundToLong()

    // ---- the splice -------------------------------------------------------------------------

    private val capture = FloatArray(CAPTURE_FRAMES)
    private var captured = 0
    private var captureWrite = 0
    private var captureEnd = NONE
    private var mixed = FloatArray(0)

    /**
     * Takes one block of the outgoing song's own audio ([count] interleaved stereo samples, the first
     * at track µs [writeUs], written at engine frame [writeFrame]) and crosses it into the renderer in
     * place: the song as it is up to [fadeFrames] before mix frame 0, both over those frames (the
     * renderer's outgoing deck rises over them on its own gain curve), the renderer alone from 0.
     * Before the crossing the song is only listened to, for [alignOut].
     */
    fun splice(block: FloatArray, count: Int, writeUs: Long, writeFrame: Long) {
        val frames = count / 2
        if (frames <= 0) return
        val first = if (engaged) writeFrame - originFrame else mixFrameOf(writeUs)
        if (!engaged && first + frames <= -fadeFrames) {
            listen(block, frames, first)
            return
        }
        if (!engaged) {
            alignOut()
            originFrame = writeFrame - first
        }
        if (mixed.size < count) mixed = FloatArray(count)
        renderer.render(first, mixed, frames)
        for (i in 0 until frames) {
            val mf = first + i
            val keep = when {
                mf < -fadeFrames -> 1f
                mf < 0 -> 1f - (mf + fadeFrames).toFloat() / fadeFrames
                else -> 0f
            }
            val l = 2 * i
            block[l] = block[l] * keep + if (mf < -fadeFrames) 0f else mixed[l]
            block[l + 1] = block[l + 1] * keep + if (mf < -fadeFrames) 0f else mixed[l + 1]
        }
        if (first + frames >= 0) spliced = true
    }

    /** The song's own audio before the splice, mono, into a ring of the last [CAPTURE_FRAMES] frames. */
    private fun listen(block: FloatArray, frames: Int, first: Long) {
        // Contiguous within the frame a microsecond write position can round to either way.
        if (captureEnd == NONE || abs(captureEnd - first) > 1) {
            captured = 0
            captureWrite = 0
        }
        for (i in 0 until frames) {
            capture[captureWrite] = block[2 * i] + block[2 * i + 1]
            captureWrite = (captureWrite + 1) % CAPTURE_FRAMES
            if (captured < CAPTURE_FRAMES) captured++
        }
        captureEnd = first + frames
    }

    /**
     * Lines the outgoing region up with the song it takes over from, before anything is rendered: the
     * lag (region frames) at which the region best matches the last [CAPTURE_FRAMES] frames of the
     * song's own audio, applied as the deck's [ForkLoopDeck.shift] when the match is clear.
     *
     * The phone only logs this (`LoopMixPort.spliceLag`): its splice is between two decoders of one
     * file. Here the region comes from a second decoder opened on the same stream, and the playing one
     * may have started at 0 (no seek) while this one sought, so a codec's priming or a container's
     * first timestamp can put them a few milliseconds apart — a comb-filtered 25 ms and every loop seam
     * that far off the bar. Measured, not guessed: a match under [MIN_SPLICE_SCORE] leaves it alone.
     */
    fun alignOut() {
        val deck = renderer.decks[ForkLoopMixRenderer.OUT] ?: return
        val n = captured
        if (n < MIN_CAPTURE_FRAMES) return
        val firstMf = captureEnd - n
        val base = deck.heard(firstMf.toDouble()).roundToLong()
        val lagMax = MAX_SPLICE_LAG_FRAMES
        val region = FloatArray(n + 2 * lagMax)
        for (i in region.indices) {
            val index = deck.sampleAt(base - lagMax + i)
            region[i] = if (index >= 0) deck.left[index] + deck.right[index] else 0f
        }
        // The ring in order, oldest first.
        val song = FloatArray(n) { capture[Math.floorMod(captureWrite - n + it, CAPTURE_FRAMES)] }
        val (lag, score) = bestLag(song, n, region, lagMax)
        spliceLag = lag
        spliceScore = score
        if (score >= MIN_SPLICE_SCORE && abs(lag) < lagMax) deck.shift = lag.toLong()
    }

    // ---- the mix ----------------------------------------------------------------------------

    /** How many frames the block at mix frame [mf] may be: none may straddle the handover's start or end. */
    fun blockFrames(mf: Long, max: Int): Int = when {
        mf < handoverFrame -> min(max.toLong(), handoverFrame - mf).toInt()
        mf < handoverEndFrame -> min(max.toLong(), handoverEndFrame - mf).toInt()
        else -> max
    }

    /** The renderer's [frames] frames from mix frame [mf], interleaved; the array is reused. */
    fun render(mf: Long, frames: Int): FloatArray {
        if (mixed.size < frames * 2) mixed = FloatArray(frames * 2)
        renderer.render(mf, mixed, frames)
        return mixed
    }

    /** The incoming song's own gain at mix frame [mf]: 0 before the handover, rising over [fadeFrames] as its deck falls. */
    fun inGain(mf: Long): Float = ((mf - handoverFrame).toFloat() / fadeFrames).coerceIn(0f, 1f)

    /**
     * Where in its song (µs, the analysis's clock) the incoming deck actually is at mix frame [mf]: its
     * grain's own source position, which after the release runs at rate 1 and is the exact sample the
     * incoming decoder has to be lined up on.
     */
    fun inTrackUsAt(mf: Long): Long {
        val deck = renderer.decks[ForkLoopMixRenderer.IN] ?: return (clock.elementTrackTime * 1e6).roundToLong()
        return (deck.trackSecondsAt(deck.sourceAt(mf).toDouble()) * 1e6).roundToLong()
    }

    /** Frames to read off and drop from an incoming decoder whose first frame is at [landedUs], to line it up. */
    fun dropFramesFor(landedUs: Long): Long {
        handoverTrackUs = inTrackUsAt(handoverFrame)
        inAligned = true
        return ((handoverTrackUs - landedUs) * sr / 1e6).roundToLong()
    }

    /** The incoming song's position, µs, at mix frame [mf] after the handover (rate 1 from [handoverTrackUs]). */
    fun inTrackUsAfter(mf: Long): Long = handoverTrackUs + ((mf - handoverFrame) * 1e6 / sr).roundToLong()

    /**
     * Where the shown song is at heard mix frame [heardMf], from the mix's own clock: looping and
     * following the ramp, then on its own after the release. Null before mix frame 0, where the song's
     * own playhead is the truth.
     */
    fun positionMs(heardMf: Long): Long? {
        if (heardMf < 0) return null
        val m = heardMf / sr
        val side = if (handedOff) LoopMix.Side.IN else LoopMix.Side.OUT
        return LoopMix.positionAt(clock, side, m)?.let { (it * 1000).roundToLong() }
    }

    /**
     * Where a mix that has to stop at heard mix frame [heardMf] settles: the shown song at the mix's
     * own position for it (the phone's `loopBail`). Null before the splice, where nothing was heard of
     * the mix and the song is simply where it is.
     */
    fun settleMs(heardMf: Long): Long? {
        if (!engaged) return null
        if (handedOff) {
            return LoopMix.positionAt(clock, LoopMix.Side.IN, max(0L, heardMf) / sr)?.let { (it * 1000).roundToLong() }
        }
        val out = if (heardMf >= 0) LoopMix.positionAt(clock, LoopMix.Side.OUT, heardMf / sr) else null
        return ((out ?: plan.out.loopStart) * 1000).roundToLong()
    }

    companion object {
        const val NONE = Long.MIN_VALUE

        /** Seconds of the song listened to before the splice, for [alignOut]. */
        const val CAPTURE_SECONDS = 0.5
        const val CAPTURE_FRAMES = 8_192
        const val MIN_CAPTURE_FRAMES = 2_048

        /** The furthest [alignOut] looks either way: 20 ms at 48 kHz. */
        const val MAX_SPLICE_LAG_FRAMES = 960
        const val MIN_SPLICE_SCORE = 0.6

        const val INCOMING_PREROLL_SECONDS = 0.5

        /** Frames the engine writes per loop-mix block: ~21 ms, the size of a decoder's. */
        const val BLOCK_FRAMES = 1_024

        /** Seconds of each song decoded either side of what its deck plays (the phone's). */
        const val PREROLL_SECONDS = 1.0
        const val TAIL_SECONDS = 3.0

        /** Track seconds of each side's region, for the decoder. */
        fun outRange(plan: LoopMix.Plan): Pair<Double, Double> =
            max(0.0, plan.out.loopStart - PREROLL_SECONDS) to plan.out.loopEnd + 0.5

        fun inRange(plan: LoopMix.Plan): Pair<Double, Double> =
            max(0.0, plan.inn.loopStart - PREROLL_SECONDS) to LoopMix.Clock(plan).elementTrackTime + TAIL_SECONDS

        /**
         * The lag (frames of [region] past its centre) at which [song] (its first [n] values) best
         * matches, and the normalised correlation there: every lag on every 4th sample (a lag step of
         * two would step over the peak of anything bright, which is one sample wide), then every
         * sample around the best. [region] is [n] + 2 [lagMax] long, its centre at index [lagMax].
         */
        fun bestLag(song: FloatArray, n: Int, region: FloatArray, lagMax: Int): Pair<Int, Double> {
            fun score(lag: Int, stride: Int): Double {
                var dot = 0.0
                var a = 0.0
                var b = 0.0
                var i = 0
                while (i < n) {
                    val x = song[i].toDouble()
                    val y = region[lagMax + lag + i].toDouble()
                    dot += x * y
                    a += x * x
                    b += y * y
                    i += stride
                }
                val norm = sqrt(a * b)
                return if (norm > 1e-12) dot / norm else 0.0
            }
            var best = 0
            var bestScore = score(0, 4)
            for (lag in -lagMax..lagMax) {
                val s = score(lag, 4)
                if (s > bestScore + 1e-9) {
                    bestScore = s
                    best = lag
                }
            }
            var fine = best
            var fineScore = score(best, 1)
            for (l in (best - 2).coerceAtLeast(-lagMax)..(best + 2).coerceAtMost(lagMax)) {
                val s = score(l, 1)
                if (s > fineScore + 1e-9) {
                    fineScore = s
                    fine = l
                }
            }
            return fine to fineScore
        }

        /**
         * The renderer for [plan] at [sampleRate] from the two decoded regions, laid out as the phone's
         * `LoopMixSession.build` lays its decks out; null when either region doesn't hold what its deck
         * plays. [inLevel] scales the incoming deck: its loudness against the outgoing song's, since the mix goes
         * out through the outgoing song's loudness stage ([ForkLoudness.ratio], the phone's `inLevel`).
         */
        fun build(
            plan: LoopMix.Plan,
            sampleRate: Int,
            outRegion: ForkLoopRegion,
            inRegion: ForkLoopRegion,
            outId: String,
            inId: String,
            outUrl: String,
            inUrl: String,
            inLevel: Float = 1f,
        ): ForkLoopMixSession? {
            if (sampleRate <= 0) return null
            val clock = LoopMix.Clock(plan)
            if (outRegion.start > plan.out.loopStart || inRegion.start > plan.inn.loopStart) return null
            if (outRegion.endAt(sampleRate) < plan.out.loopEnd) return null
            if (inRegion.endAt(sampleRate) < clock.elementTrackTime + 0.2) return null
            val sr = sampleRate.toDouble()
            val beats = clock.beats
            fun samplesOf(region: ForkLoopRegion, trackSeconds: Double) = (trackSeconds - region.start) * sr

            val renderer = ForkLoopMixRenderer(sampleRate)
            // Outgoing: its loop, knot by knot, looping until it falls silent at the release.
            val outDeck = ForkLoopDeck(
                left = outRegion.left,
                right = outRegion.right,
                regionStart = outRegion.start,
                loopStart = samplesOf(outRegion, plan.out.loopStart).roundToLong(),
                loopEnd = samplesOf(outRegion, plan.out.loopEnd).roundToLong(),
                releaseAt = 1e18,
                knotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr },
                knotPositions = DoubleArray(beats + 1) { samplesOf(outRegion, clock.outPositionAt(it)) },
                runOn = false,
                stopAt = clock.releaseAt + 0.05,
                preroll = 0.4,
                level = 1f,
                sampleRate = sampleRate,
            )
            // Incoming: its loop until the release, then on from the loop end at rate 1.
            val inDeck = ForkLoopDeck(
                left = inRegion.left,
                right = inRegion.right,
                regionStart = inRegion.start,
                loopStart = samplesOf(inRegion, plan.inn.loopStart).roundToLong(),
                loopEnd = samplesOf(inRegion, plan.inn.loopEnd).roundToLong(),
                releaseAt = samplesOf(inRegion, clock.inReleasePosition),
                knotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr },
                knotPositions = DoubleArray(beats + 1) { samplesOf(inRegion, clock.inPositionAt(it)) },
                runOn = true,
                stopAt = clock.elementAt + 2.0,
                preroll = 0.4,
                level = inLevel,
                sampleRate = sampleRate,
            )
            // Gains: the outgoing deck takes over from its song, the incoming loop rises over the
            // entry and the ramp, the outgoing one falls away — trimmed where they overlap past full
            // scale, the phone's fix for the crackle (ForkAutomix.trimmedGains).
            val h = LoopMix.HANDOVER_SEC
            val gains = ForkAutomix.trimmedGains(
                outTimes = doubleArrayOf(-h, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
                outGains = floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
                inTimes = doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd),
                inGains = floatArrayOf(0f, 0.55f, 1f),
            )
            outDeck.gain = ForkLoopEnvelope(gains.outTimes, gains.outGains)
            inDeck.gain = ForkLoopEnvelope(gains.inTimes, gains.inGains)
            // Bass: the incoming loop comes in without it; they swap over one bar mid-ramp.
            val swapFrom = clock.handoffAt - clock.swapBar / 2
            val swapTo = clock.handoffAt + clock.swapBar / 2
            val cut = LoopMix.BASS_CUT_DB.toFloat()
            outDeck.lowDb = ForkLoopEnvelope(doubleArrayOf(swapFrom, swapTo), floatArrayOf(0f, cut))
            inDeck.lowDb = ForkLoopEnvelope(doubleArrayOf(swapFrom, swapTo), floatArrayOf(cut, 0f))
            // The outgoing loop drains away through a rising high-pass once the tempo has arrived.
            outDeck.highPassHz = ForkLoopEnvelope(
                doubleArrayOf(clock.rampEnd, clock.releaseAt),
                floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                exponential = true,
            )
            val session = ForkLoopMixSession(plan, clock, sampleRate, renderer, outId, inId, outUrl, inUrl)
            session.inLevel = inLevel
            // The incoming deck hands over to its own song's decoder, crossing on identical audio.
            inDeck.fadeOutFrame = session.handoverFrame
            inDeck.fadeFrames = session.fadeFrames
            renderer.decks[ForkLoopMixRenderer.OUT] = outDeck
            renderer.decks[ForkLoopMixRenderer.IN] = inDeck
            return session
        }
    }
}

/**
 * Plans the loop mix for each pair (once per pair and analysis, as the phone does), asks for both
 * songs' synced lyrics, and builds the decks on a worker shortly before the loop. The engine calls
 * [consider] from its audio thread on every position tick and runs the built [ForkLoopMixSession].
 */
internal class ForkDesktopLoopMix(private val scope: CoroutineScope) {
    private var planKey: String? = null
    private var planned: LoopMix.Plan? = null
    /** [planned]'s verdict, noted again when the cached plan is reused. */
    private var plannedWhy: String? = null
    private var lastVerdict = ""

    /** Why the pair gets no loop mix; null when it does. For the log. */
    var refusal: String? = null
        private set

    private var buildFor: String? = null
    private var buildJob: Job? = null

    /**
     * Bumped by every build started or cancelled; a build hands its decks over only while it is still
     * the newest. Checking [buildFor] instead let a cancelled build (a seek, then the same pair planned
     * again from the new playhead) finish under the new one's name and hand over decks of the old plan:
     * `cancel()` doesn't stop a decode that never suspends.
     *
     * The generation check and the hand-over are one step under [buildLock]: checked and then
     * written as two, a [cancelBuild] between them (a seek on the audio thread as the worker
     * finished) left [built] standing with [buildFor] null, which [consider] never starts another
     * build over nor lets the deadline fall back from, so the pair held its blend to the end of the
     * song and cut; and after a seek on the same pair [takeBuilt] handed those decks of the old plan
     * over. The lock is held for a field write only, never while decoding.
     */
    private val buildLock = Any()
    @Volatile private var buildGeneration = 0
    @Volatile private var built: ForkLoopMixSession? = null
    @Volatile private var buildFailed: String? = null

    /** Synced-lyric spans per song, once asked: [Spans.spans] null when it has none. */
    private class Spans(val spans: List<LoopMix.Span>?)

    private val lyricSpans = ConcurrentHashMap<String, Spans>()
    private val lyricsAskedAt = ConcurrentHashMap<String, Long>()
    private val noLyricsYet = Spans(null)

    /**
     * The loop mix for [p], or null when the pair gets its blend. While it returns a hold the engine
     * starts no blend; the decks are started building [ForkLoopMixRules.ARM_LEAD_SECONDS] before the
     * loop and handed over through [takeBuilt].
     */
    fun consider(p: ForkLoopPair): ForkLoopHold? {
        val pair = p.pairKey
        if (buildFor != null && buildFor != pair) cancelBuild()
        ForkLoopMixRules.refusal(p)?.let { why ->
            if (buildFor == pair) cancelBuild()
            note(pair, null, why)
            return null
        }
        buildFailed?.let { why ->
            buildFailed = null
            fallBack(why)
            return null
        }
        val plan = if (buildFor == pair) planned else plan(p) ?: return null
        plan ?: return null
        val lead = plan.out.loopStart - p.positionSec
        if (built == null && lead < ForkLoopMixRules.BUILD_DEADLINE_SECONDS) {
            fallBack(if (buildFor == pair) "the decks took too long" else "too close to the loop to build it")
            return null
        }
        if (lead <= ForkLoopMixRules.ARM_LEAD_SECONDS && buildFor == null && built == null) startBuild(plan, p)
        return ForkLoopHold(plan.out.loopStart, plan.out.loopStart + LoopMix.Clock(plan).releaseAt)
    }

    private fun plan(p: ForkLoopPair): LoopMix.Plan? {
        val pair = p.pairKey
        askLyrics(p.outSong, p.outDurationSec)
        askLyrics(p.inSong, p.inDurationSec)
        val outLyrics = lyricsFor(p.outSong.videoId)
        val inLyrics = lyricsFor(p.inSong.videoId)
        if (outLyrics == null || inLyrics == null) {
            note(pair, null, "waiting on lyrics")
            return null
        }
        val key = "$pair|${System.identityHashCode(p.outAnalysis)}|${System.identityHashCode(p.inAnalysis)}" +
            "|${outLyrics === noLyricsYet}${inLyrics === noLyricsYet}"
        val cached = planned
        // Kept while its loop is still far enough ahead to set up; a seek past that plans again.
        // Noted again: a refusal shown meanwhile (a fixed length, a pick, then undone) would otherwise stay
        // in the Automix overview, greying Loop, until the next song.
        if (key == planKey && (cached == null || cached.out.loopStart - p.positionSec >= ForkLoopMixRules.MIN_LEAD_SECONDS)) {
            note(pair, cached, plannedWhy)
            return cached
        }
        val why = StringBuilder()
        val plan = LoopMix.plan(
            out = p.outAnalysis,
            outDuration = p.outDurationSec,
            inn = p.inAnalysis,
            inDuration = p.inDurationSec,
            earliestOutSec = p.positionSec + ForkLoopMixRules.SETUP_SECONDS,
            sungOut = outLyrics.spans,
            sungIn = inLyrics.spans,
            outVideo = p.outSong.isVideoOrigin,
            inVideo = p.inSong.isVideoOrigin,
            why = why,
        )
        planKey = key
        planned = plan
        val lyricsNote = "lyrics ${if (outLyrics.spans != null) "out" else "-"}/${if (inLyrics.spans != null) "in" else "-"}"
        plannedWhy = if (plan == null) "$why; $lyricsNote" else null
        note(pair, plan, plannedWhy)
        return plan
    }

    private fun startBuild(plan: LoopMix.Plan, p: ForkLoopPair) {
        val pair = p.pairKey
        buildFor = pair
        val generation = synchronized(buildLock) {
            built = null
            buildFailed = null
            ++buildGeneration
        }
        val sampleRate = p.sampleRate
        val outStream = p.outStream
        val inStream = p.inStream
        val outId = p.outSong.videoId
        val inId = p.inSong.videoId
        DesktopTrackLog.log(
            "loop mix: building the decks for $pair at ${sampleRate}Hz, loop at " +
                "${"%.2f".format(Locale.ROOT, plan.out.loopStart)}s, playhead ${"%.1f".format(Locale.ROOT, p.positionSec)}s",
        )
        buildJob = scope.launch {
            val began = System.nanoTime()
            val session = runCatching {
                val (outFrom, outTo) = ForkLoopMixSession.outRange(plan)
                val (inFrom, inTo) = ForkLoopMixSession.inRange(plan)
                // A cancelled build stops decoding (and reading the network) at its next block.
                val alive = { isActive && buildGeneration == generation }
                val outRegion = decodeRegion(outStream, outFrom, outTo, sampleRate, alive) ?: return@runCatching null
                val inRegion = decodeRegion(inStream, inFrom, inTo, sampleRate, alive) ?: return@runCatching null
                ForkLoopMixSession.build(
                    plan, sampleRate, outRegion, inRegion, outId, inId, outStream.url, inStream.url,
                    inLevel = ForkLoudness.ratio(p.inSong, inStream.url, p.outSong, outStream.url),
                )
            }.onFailure { DesktopTrackLog.log("loop mix: building the decks failed: ${it.message}") }.getOrNull()
            val handedOver = synchronized(buildLock) {
                if (buildGeneration != generation) return@launch
                if (session == null) buildFailed = "the decks would not build" else built = session
                session != null
            }
            if (handedOver) DesktopTrackLog.log("loop mix: decks built in ${(System.nanoTime() - began) / 1_000_000}ms")
        }
    }

    /** The built session for [outId] -> [inId], once; the engine arms it. */
    fun takeBuilt(outId: String, inId: String): ForkLoopMixSession? {
        val session = built ?: return null
        if (session.outId != outId || session.inId != inId) return null
        built = null
        buildFor = null
        buildJob = null
        return session
    }

    /** The loop mix isn't happening after all: the pair takes the planner's blend instead (the phone's `loopFallBack`). */
    fun fallBack(reason: String) {
        DesktopTrackLog.log("loop mix falls back to a blend: $reason")
        cancelBuild()
        planned = null
        plannedWhy = reason // the reused (null) plan is noted with this
        refusal = reason
    }

    /**
     * The loop mix planned for [outId] -> [inId], for the stats line (`ForkAutomixDetail.loopPlanned`,
     * the phone's `loopPlanned` read against its `loopPlanKey`); null when the pair gets its blend.
     */
    fun plannedFor(outId: String, inId: String): LoopMix.Plan? =
        planned?.takeIf { planKey?.startsWith("$outId->$inId|") == true }

    /**
     * A seek or a new song: whatever was planned is planned again from the new playhead. The refusal
     * goes too: it is the old pair's, and the stats line read it as the new song's until that pair's
     * first [consider] (which is never, for a song with nothing after it).
     */
    fun forget() {
        cancelBuild()
        planKey = null
        planned = null
        plannedWhy = null
        refusal = null
    }

    private fun cancelBuild() {
        synchronized(buildLock) {
            buildGeneration++
            built = null
            buildFailed = null
        }
        buildJob?.cancel()
        buildJob = null
        buildFor = null
    }

    private fun note(pair: String, plan: LoopMix.Plan?, why: String?) {
        ForkMixPicks.noteLoop(pair, plan?.out?.loopStart, why)
        refusal = why
        val verdict = "$pair: " + (
            plan?.let { "loop mix at ${"%.2f".format(Locale.ROOT, it.out.loopStart)}s · ${ForkLoopMixRules.describe(it)}" }
                ?: "no loop mix ($why)"
            )
        if (verdict == lastVerdict) return
        lastVerdict = verdict
        DesktopTrackLog.log("loop $verdict")
    }

    /** Fork, as the phone: a lookup still running after [ForkLoopMixRules.LYRICS_WAIT_MS] stops holding the plan up. */
    private fun lyricsFor(id: String): Spans? = lyricSpans[id]
        ?: lyricsAskedAt[id]?.takeIf { System.currentTimeMillis() - it >= ForkLoopMixRules.LYRICS_WAIT_MS }?.let { noLyricsYet }

    /** Asks for [song]'s synced lyrics once; a loop they vouch for needs no vocal mask. */
    private fun askLyrics(song: Song, durationSec: Double) {
        val id = song.videoId
        if (lyricsAskedAt.putIfAbsent(id, System.currentTimeMillis()) != null) return
        scope.launch {
            val lines = runCatching { DesktopLyricsClient.lookup(song, (durationSec * 1000).roundToLong()).getOrNull()?.lines }.getOrNull()
            // Only line-synced lyrics say where a voice is; plain text says nothing about time.
            val spans = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 }?.let { found ->
                LoopMix.sungSpans(
                    found.map { line ->
                        val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs
                        LoopMix.Line(line.timeMs, end, blank = line.isGap || line.text.isBlank())
                    },
                )
            }
            lyricSpans[id] = Spans(spans)
            com.music.bitchord.playback.smart.ForkAutoDj.noteSung(id, spans) // Advanced AutoDJ reads the same sung lines (Automix style)
        }
    }

    companion object {
        /**
         * Track seconds [from]..[to] of [stream], stereo at [sampleRate], from a decoder of its own (the
         * playing deck can't be seeked under the song); null when it won't open or ends short. Blocking.
         * The region starts where the decoder says it landed (`forkLandedUs`), not where it was asked.
         * Gives up (null) as soon as [alive] says the build is no longer wanted.
         */
        fun decodeRegion(
            stream: DesktopStream,
            from: Double,
            to: Double,
            sampleRate: Int,
            alive: () -> Boolean = { true },
        ): ForkLoopRegion? {
            val decoder = DesktopAudioDecoder()
            try {
                decoder.open(
                    url = stream.url,
                    headers = stream.headers,
                    requested = DesktopPcmFormat(44_100, 2, bytesPerSample = 4, isFloat = true),
                    windowed = stream.windowedReads,
                    transport = stream.transport,
                ).getOrElse { return null }
                if (decoder.outputFormat.channels != 2) return null
                val fromUs = (from * 1e6).roundToLong()
                if (fromUs > 0 && !decoder.seek(fromUs)) return null
                val sourceRate = decoder.outputFormat.sampleRate
                val converter = if (sourceRate != sampleRate) DesktopRateConverter(sourceRate, sampleRate, 2) else null
                // Sized from where the stream landed, not from [from]: a seek the decoder could not trim
                // (no timestamps, or an index point further back than its MAX_SEEK_TRIM_US) lands
                // seconds early, and a region of [to] - [from] frames from there ended before [to],
                // so `build` refused it and the pair got its blend. A landing further back than that
                // trim allows would be a region of most of the song: given up instead.
                var wanted = ((to - from) * sampleRate).roundToInt().coerceAtLeast(1)
                var left = FloatArray(0)
                var right = FloatArray(0)
                var frames = 0
                var start: Double? = null
                while (frames < wanted || start == null) {
                    if (!alive()) return null
                    val block = decoder.readSamples() ?: break
                    var count = decoder.sampleCount
                    if (count <= 0) continue
                    if (start == null) {
                        val landed = (decoder.forkLandedUs ?: fromUs) / 1e6
                        if (from - landed > DesktopAudioDecoder.MAX_SEEK_TRIM_US / 1e6) return null
                        start = landed
                        wanted = ((to - landed) * sampleRate).roundToInt().coerceAtLeast(1)
                        left = FloatArray(wanted)
                        right = FloatArray(wanted)
                    }
                    val data = if (converter != null) {
                        converter.process(block, count).also { count = converter.outputCount }
                    } else {
                        block
                    }
                    var i = 0
                    while (i + 1 < count && frames < wanted) {
                        left[frames] = data[i]
                        right[frames] = data[i + 1]
                        frames++
                        i += 2
                    }
                }
                if (frames <= 0) return null
                return ForkLoopRegion(left.copyOf(frames), right.copyOf(frames), start ?: from)
            } finally {
                decoder.close()
            }
        }
    }
}
