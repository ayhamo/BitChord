package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackFeatures
import com.music.bitchord.playback.smart.heuristicVocalOnModelScale
import com.music.bitchord.playback.smart.CurveWindow // fork
import com.music.bitchord.playback.smart.VocalSpectrogram // fork
import com.music.bitchord.playback.smart.VocalTracker // fork
import kotlin.math.max // fork
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** A file on this machine — a download or a local track — rather than anything streamed. */
internal val DesktopStream.isLocalFile: Boolean
    get() = !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)

/**
 * Produces the evidence Automix mixes on: tempo, beat grid, key, structure and where a track can be
 * entered and left.
 */
internal class DesktopTrackAnalyzer(
    /**
     * How much CPU the listener has allowed background analysis, read fresh on every pass so a
     * change takes effect on the next track rather than the next launch.
     */
    private val performance: () -> AutomixPerformanceMode = { AutomixPerformanceMode.BALANCED },
    /**
     * True while analysis has no use — in a party, where Automix is off. Checked on request, before
     * a queued pass starts, and between and during its decodes, as Android's `TrackAnalyzer` does:
     * a request made a moment before the party started would otherwise run a whole-track decode
     * and a model pass for a transition that cannot happen. A stopped pass records nothing.
     */
    private val stopped: () -> Boolean = { false },
    private val onAnalysed: (String) -> Unit = {},
) {
    /** One analysis at a time, on a thread of this class's own making. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "BitChord-Analysis", ANALYSIS_STACK_BYTES).apply {
            isDaemon = true
        }
    }

    /**
     * Analyses kept between runs, so a track measured yesterday is not measured again today.
     *
     * fork (Desktop Revived d77475ba): a namespace of its own. The analyses before it were measured
     * without the head and tail model windows, and on audio at the decoder's own rate handed over as
     * if it were the analyser's; they are measured again rather than trusted.
     */
    private val store = AnalysisStore {
        DesktopAnalysisRuntime.analysisHome().resolve(STORE_NAMESPACE).also { it.mkdirs() }
    }

    /**
     * Analyses in RAM, newest-used first. Fork, from PC-Continued (`c310d0e3`): a window rather than
     * a history. [analysisFor] is asked for the whole queue every tick and every ask promotes a
     * stored analysis into here, so unbounded this held the curves of everything a session touched.
     * The disk store keeps them all; this only has to hold what the planner might mix with.
     */
    private val results: MutableMap<String, TrackAnalysis> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, TrackAnalysis>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysis>) = size > 12
        },
    )
    private val running = ConcurrentHashMap.newKeySet<String>()

    /**
     * fork (Desktop Revived 573478be / a5603aa5): queued work, the playing song and the next only,
     * playing first. One drain runs at a time; a song that stops being either is dropped from the
     * queue, and its pass in flight stops at its next check.
     */
    private class Job(val song: Song, val stream: DesktopStream?, val seconds: Double, val key: String = song.videoId) // fork: key (ForkCopyAnalysis)
    private val pending = ForkAnalysisQueue<Job>()
    private val schedulerLock = Any()
    private var drainScheduled = false
    @Volatile private var activeId: String? = null
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Tracks the analyser could not measure. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** When each of [failed] last failed, so it is retried on a cooldown rather than every tick. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Said once, not once a tick. */
    private val warnedNoAnalyser = java.util.concurrent.atomic.AtomicBoolean(false)

    private val beats by lazy {
        BeatTracker(
            modelPath = { DesktopAnalysisRuntime.modelPath(BeatTracker.MODEL_ASSET) },
            inferenceThreads = { performance().inferenceThreads },
        )
    }

    /** fork (Desktop Revived d77475ba): the phone's open-unmix vocal model, over the same windows as the beat model. */
    private val vocals by lazy {
        VocalTracker({ DesktopAnalysisRuntime.modelPath(VocalTracker.MODEL_ASSET) }, { performance().inferenceThreads })
    }
    @Volatile private var beatsUsed = false
    @Volatile private var vocalsUsed = false

    /** A pass that is no longer wanted: released, a party, or a song that left the playing pair. */
    private fun cancelled(): Boolean {
        val active = activeId
        return released.get() || Thread.currentThread().isInterrupted || stopped() ||
            (active != null && !pending.isRelevant(active))
    }

    /** fork (Desktop Revived a5603aa5): the songs worth measuring now; anything else queued is dropped. */
    fun focusOn(currentId: String?, nextId: String?) {
        synchronized(schedulerLock) { pending.focus(currentId, nextId).forEach { com.music.bitchord.playback.smart.ForkAnalysisProgress.ended(it, results[it]?.isUsable, stopped = true) } } // fork: a queued song dropped here never reaches drain's ended; it read "waiting its turn" for good
    }

    /** What is known now, never a computation — the planner asks this every tick. */
    fun analysisFor(trackId: String): TrackAnalysis =
        results[trackId] ?: restored(trackId) ?: TrackAnalysis(trackId = trackId)

    /** A stored analysis, promoted into memory on first ask. */
    private fun restored(trackId: String): TrackAnalysis? =
        store.load(trackId)?.takeUnless { com.music.bitchord.playback.smart.ForkAnalysisProgress.isTooLong(trackId) }?.also { results[trackId] = it } // fork: a saved analysis over 8 minutes isn't restored under Automix v2, as on the phone (versions.md "Analysis length")

    fun isAnalysed(trackId: String): Boolean =
        results.containsKey(trackId) || restored(trackId) != null

    /** How far this track has got, for the player's Automix line. */
    fun stateFor(trackId: String): TrackAnalysisState = when {
        trackId.isBlank() -> TrackAnalysisState.WAITING
        !DesktopAnalysisRuntime.available -> TrackAnalysisState.FAILED
        // Usable first and a pass in flight second, the order Android settled on.
        results[trackId]?.isUsable == true ->
            if (trackId in running) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        trackId in running -> TrackAnalysisState.ANALYSING
        // A recorded-but-unusable result is the analyser saying it tried and got nothing, and that
        // it will not try again.
        trackId in failed || results.containsKey(trackId) -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    /**
     * Asks for [song] to be analysed, if it has not been already.
     *
     * [stream] is only read when it is a file on this machine. Anything streamed is measured on
     * the track's YouTube Opus instead — never on the addon, JioSaavn or lossless copy playback
     * may be using — exactly as Android's analyser reads its own pinned Opus rendition (see
     * `AutomixAnalysisSource`). That keeps an addon from being asked for a track nobody is
     * playing yet, and one recording's measurements from being stored under another's id.
     */
    fun request(song: Song, stream: DesktopStream?, durationSeconds: Double) {
        val trackId = song.videoId
        if (trackId.isBlank()) return
        if (!ForkAnalysisLength.asks(durationSeconds)) return // fork: was `<= 0` return; an unknown length is measured from the decode (ForkAnalysisLength)
        if (com.music.bitchord.playback.smart.ForkAnalysisProgress.tooLong(trackId, durationSeconds)) return // fork: over 8 minutes isn't analysed, a saved one isn't restored (versions.md "Analysis length")
        if (stopped() || released.get()) return
        if (!DesktopAnalysisRuntime.available) {
            if (warnedNoAnalyser.compareAndSet(false, true)) {
                DesktopTrackLog.log("automix: this build has no analyser, so nothing will be measured")
            }
            return
        }
        if (results.containsKey(trackId) || restored(trackId) != null) return if (durationSeconds <= 0) Unit else ForkDesktopV2Evidence.offer(song, stream, durationSeconds) { stopped() || released.get() } // fork: Automix v2 evidence for a song analysed before it, once (ForkV2EvidenceBackfill); was `return`
        // Asked every tick; a track that just failed is not resolved and decoded again each time.
        failedAt[trackId]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MS) return }
        val job = Job(song, stream?.takeIf { it.isLocalFile }, durationSeconds)
        synchronized(schedulerLock) {
            if (trackId in running || !pending.offer(trackId, job)) return
            com.music.bitchord.playback.smart.ForkAnalysisProgress.queued(trackId) // fork: the editor's progress row
            if (!drainScheduled) {
                drainScheduled = true
                try {
                    worker.execute(::drain)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    drainScheduled = false
                    pending.clear().forEach { com.music.bitchord.playback.smart.ForkAnalysisProgress.ended(it, null, stopped = true) } // fork: dropped, not left "waiting its turn"
                }
            }
        }
    }

    /** The one queued task: the playing song's pass first, then the next one's. */
    private fun drain() {
        while (!released.get()) {
            val picked = synchronized(schedulerLock) {
                pending.poll().also {
                    if (it == null) drainScheduled = false else running.add(it.first)
                }
            } ?: return
            activeId = picked.first
            try {
                run(picked.second)
            } finally {
                // fork (Desktop Revived 8f9ed4a8): released however the pass ends, so no song stays "analysing"
                val forkCalledOff = cancelled() // fork: read while activeId still names the pass (a song moved past is an abort)
                activeId = null
                running.remove(picked.first)
                com.music.bitchord.playback.smart.ForkAnalysisProgress.ended(picked.first, results[picked.first]?.isUsable ?: if (picked.first in failed) false else null, forkCalledOff) // fork: a failure that threw nothing (no audio, an empty decode) reads as one
            }
        }
    }

    private fun run(job: Job) {
        val song = job.song
        val trackId = job.key // fork: was song.videoId; a copy's own key (ForkCopyAnalysis)
        if (cancelled()) return
        // Android's rule: the lowest rung yields to playback rather than competing for a core, and
        // the thread count stays the speed knob for the other two.
        Thread.currentThread().priority =
            if (performance().yieldsToPlayback) Thread.MIN_PRIORITY else Thread.NORM_PRIORITY
        failed.remove(trackId)
        com.music.bitchord.playback.smart.ForkAnalysisProgress.stage(trackId, com.music.bitchord.playback.smart.ForkAnalysisProgress.Stage.AUDIO) // fork
        val local = job.stream
        DesktopTrackLog.log(
            "automix: analysing '${song.title}' (${if (job.seconds > 0) "%.0fs".format(job.seconds) else "length from the audio"})" + // fork: an unknown length
                if (trackId != song.videoId) " from ${local?.sourceId}'s copy" else if (local != null) " from the file" else if (ForkNavidrome.owns(song)) " from the server" else " from YouTube Opus", // fork: the server
        )
        val serverCopy = if (local == null) ForkPreload.analysisCopy(song) else null // fork: one /rest/stream, not one per window
        val analysis = runCatching {
            val source = local ?: serverCopy?.let { DesktopStream(it.absolutePath) } ?: ForkNavidrome.stream(song).takeIf { ForkNavidrome.owns(song) } ?: youTubeOpus(song) ?: return@runCatching null // fork: a server's song is measured on its own audio; it has no YouTube id
            analyse(trackId, source, job.seconds)
        }.also { serverCopy?.delete() } // fork
            .onFailure { if (!cancelled()) DesktopTrackLog.log("analysis of '${song.title}' failed: ${it.message}") }
            .onFailure { if (!cancelled()) com.music.bitchord.playback.smart.ForkAnalysisProgress.failed(trackId, it) } // fork: the row's reason
            .getOrNull()
        // Called off, not failed: it is free to be measured once it is wanted again.
        if (cancelled()) return
        if (analysis == null) {
            failed += trackId
            failedAt[trackId] = System.currentTimeMillis()
            return
        }
        failedAt.remove(trackId)
        com.music.bitchord.playback.smart.ForkAnalysisProgress.stage(trackId, com.music.bitchord.playback.smart.ForkAnalysisProgress.Stage.SAVING) // fork
        runCatching { store.save(trackId, analysis) }
            .onFailure { DesktopTrackLog.log("automix: could not keep the analysis of '${song.title}': ${it.message}") }
        results[trackId] = analysis
        ForkMixWaves.keep(trackId) // fork: the waveform measured by this pass's decode, kept beside the analysis
        DesktopTrackLog.log(
            "analysed '${song.title}': ${"%.1f".format(analysis.bpm)} bpm" +
                ", ${analysis.downbeats.size} downbeats" +
                ", key ${analysis.key.ifBlank { "unknown" }}" +
                ", grid ${if (analysis.headBeats != null || analysis.tailBeats != null) "model" else "dsp"}" +
                ", ${analysis.vocalWindows.size} vocal windows",
        )
        runCatching { onAnalysed(trackId) }
    }

    /**
     * [song]'s YouTube Opus stream, bypassing the source race playback runs — or null when there
     * is no YouTube copy of it to measure.
     */
    private fun youTubeOpus(song: Song): DesktopStream? = runBlocking {
        val identity = DesktopSourceRegistry.youTubeIdentity(song)
        if (identity == null) {
            DesktopTrackLog.log("automix: no YouTube copy of '${song.title}' to measure")
            return@runBlocking null
        }
        if (identity.videoId != song.videoId) {
            DesktopTrackLog.log("automix: measuring '${song.title}' on YouTube's ${identity.videoId}")
        }
        DesktopStreamClient.resolve(identity).getOrThrow()
    }

    /**
     * fork (Desktop Revived d77475ba, as the phone's `TrackAnalyzer`): the DSP pass over the whole
     * track, then both models over the opening and the ending only — [HEAD_MODEL_WINDOWS] windows from
     * the start, [TAIL_MODEL_WINDOWS] to the end, each exactly one vocal-model input wide and
     * overlapping by [WINDOW_OVERLAP_SECONDS]. A transition only reads the tail of the outgoing song
     * and the head of the incoming one. Each side keeps its own grid (`headBeats` / `tailBeats`), the
     * vocal model's mask replaces the heuristic where it ran, and the windows' vocal and level curves
     * are kept for the loop mix. A window that won't decode is a missing window, not a failed song.
     */
    private fun analyse(
        trackId: String,
        stream: DesktopStream,
        knownSeconds: Double, // fork: was durationSeconds; 0 when the song carried no length
    ): TrackAnalysis? {
        com.music.bitchord.playback.smart.ForkAnalysisProgress.stage(trackId, com.music.bitchord.playback.smart.ForkAnalysisProgress.Stage.DECODING) // fork
        val samples = decode(stream, TrackFeatures.sampleRate) ?: return null // fork: split out, for the length
        val durationSeconds = ForkAnalysisLength.seconds(knownSeconds, samples.size, TrackFeatures.sampleRate) // fork
        if (com.music.bitchord.playback.smart.ForkAnalysisProgress.tooLong(trackId, durationSeconds)) return null // fork: a length only the decode knew
        val features = samples
            .also { mono -> forkEvidence?.invoke(trackId, mono) ?: com.music.bitchord.playback.smart.automixv2.ForkV2EvidenceStore.measure(DesktopAnalysisRuntime.analysisHome(), trackId, mono, TrackFeatures.sampleRate) } // fork: Automix v2's whole-song evidence from this same buffer (the benchmark's kept apart)
            .let { TrackFeatures.analyze(it, durationSeconds) }
            ?: return null
        if (cancelled()) return null

        com.music.bitchord.playback.smart.ForkAnalysisProgress.stage(trackId, com.music.bitchord.playback.smart.ForkAnalysisProgress.Stage.MODELS) // fork
        val span = if (VocalSpectrogram.available) {
            (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        } else {
            BeatTracker.WINDOW_SECONDS
        }
        val headEnd = minOf(durationSeconds, HEAD_MODEL_WINDOWS * span - (HEAD_MODEL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS)
        val tailStart = max(0.0, durationSeconds - (TAIL_MODEL_WINDOWS * span - (TAIL_MODEL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS))
        val regions = ArrayList<Region>()
        for ((from, to) in ForkAnalysisWindows.windows(headEnd, tailStart, durationSeconds, span, WINDOW_OVERLAP_SECONDS)) {
            // An abort leaves a window missing, which would be published as one the models found
            // nothing in: a result put together out of a half-finished pass is worse than none.
            if (cancelled()) return null
            region(stream, from, to, features)?.let(regions::add)
        }
        if (cancelled()) return null

        // fork: Beat This! over one 59 s span at each end, as the phone's analyser (ForkBeatSpans); was the vocal windows' beats seamed
        val (headSpan, tailSpan) = com.music.bitchord.playback.smart.ForkBeatSpans.spans(durationSeconds)
        val headSide = forkBeatSpan(stream, headSpan)
        val tailSide = if (tailSpan == null) headSide else forkBeatSpan(stream, tailSpan)
        if (cancelled()) return null
        val head = headSide?.let(ForkAnalysisWindows::grid)
        val tail = tailSide?.let(ForkAnalysisWindows::grid)
        // The tail governs where the outgoing song is mixed out, so it leads; the head is what the
        // song uses as the incoming side of another transition.
        val leading = tail ?: head
        val merged = ForkAnalysisWindows.mergeMasks(features.energyCurve.size, regions.mapNotNull { it.vocalMask }, NEUTRAL_VOCAL)

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = durationSeconds,
            bpm = leading?.bpm ?: features.bpm,
            beatInterval = leading?.beatInterval ?: features.beatInterval,
            beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
            downbeats = (head?.downbeats.orEmpty() + tail?.downbeats.orEmpty()).ifEmpty { features.downbeats }.sorted(),
            firstBeat = head?.firstBeat ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: durationSeconds,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            // The model's mask where it ran, the heuristic's where it didn't (fork: on the model's
            // scale, as the phone since v1.9).
            vocalActivityMask = merged ?: features.vocalActivityMask.map(::heuristicVocalOnModelScale),
            vocalProbability = features.vocalProbability,
            headBeats = headSide,
            tailBeats = tailSide,
            vocalWindows = regions.mapNotNull { it.vocalWindow },
            levelWindows = regions.mapNotNull { it.levelWindow },
        )
    }

    /**
     * Fork: [song] measured on the copy [stream] that plays it, when that is an add-on's (ForkCopyAnalysis):
     * kept under the copy's own key beside the song's YouTube analysis, which [request] still makes.
     */
    fun forkRequestCopy(song: Song, stream: DesktopStream?, durationSeconds: Double) {
        if (!ForkCopyAnalysis.isCopy(song, stream)) return
        val key = ForkCopyAnalysis.key(song, stream)
        if (!ForkAnalysisLength.asks(durationSeconds) || durationSeconds <= 0) return
        if (com.music.bitchord.playback.smart.ForkAnalysisProgress.tooLong(song.videoId, durationSeconds)) return
        if (stopped() || released.get() || !DesktopAnalysisRuntime.available) return
        if (results.containsKey(key) || restored(key) != null) return
        failedAt[key]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MS) return }
        val job = Job(song, stream, durationSeconds, key)
        synchronized(schedulerLock) {
            if (key in running || !pending.offer(key, job)) return
            if (!drainScheduled) {
                drainScheduled = true
                try {
                    worker.execute(::drain)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    drainScheduled = false
                    pending.clear().forEach { com.music.bitchord.playback.smart.ForkAnalysisProgress.ended(it, null, stopped = true) }
                }
            }
        }
    }

    /** Fork: the model's beats over [span], decoded on its own as the phone's analyser does ([com.music.bitchord.playback.smart.ForkBeatSpans]). */
    private fun forkBeatSpan(stream: DesktopStream, span: Pair<Double, Double>): com.music.bitchord.playback.smart.BeatSide? {
        if (cancelled() || span.second - span.first < 1.0) return null
        val stereo = runCatching { ForkDesktopLoopMix.decodeRegion(stream, span.first, span.second, REGION_RATE) { !cancelled() } }
            .onFailure { if (!cancelled()) DesktopTrackLog.log("automix: beat span ${"%.0f".format(span.first)}-${"%.0f".format(span.second)}s skipped: ${it.message}") }
            .getOrNull() ?: return null
        beatsUsed = true
        return runCatching { com.music.bitchord.playback.smart.ForkBeatSpans.track(beats, stereo.left, stereo.right, REGION_RATE, stereo.start) }
            .onFailure { DesktopTrackLog.log("automix: beat pass skipped: ${it.message}") }
            .getOrNull()
    }

    /** Fork: the Spotify benchmark's evidence hook ([ForkSpotifyBench]); null: the app's store. */
    @Volatile internal var forkEvidence: ((String, FloatArray) -> Unit)? = null

    /** Fork: [file] (any format FFmpeg reads) analysed as a song is, for the Spotify benchmark ([ForkSpotifyBench]). */
    internal fun forkAnalyseFile(trackId: String, file: java.io.File): TrackAnalysis? =
        analyse(trackId, DesktopStream(url = file.absolutePath), 0.0)

    /** Fork: [file] decoded as [analyse] decodes a song for its DSP pass and evidence, for the Spotify benchmark ([ForkSpotifyBench]). */
    internal fun forkDecodeFile(file: java.io.File): FloatArray? =
        decode(DesktopStream(url = file.absolutePath), TrackFeatures.sampleRate)

    /** Fork: whether a pass would stop now ([cancelled]), for the Spotify benchmark's failure line. */
    internal fun forkCancelled(): Boolean = cancelled()

    /** What a decoded window contributes, once its audio has been let go of. */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
        val seconds: Double,
        val start: Double,
        val vocalWindow: CurveWindow?,
        val levelWindow: CurveWindow?,
    )

    /** One window decoded in stereo at 44.1 kHz from its own decoder, and both models run over it. */
    private fun region(stream: DesktopStream, from: Double, to: Double, features: TrackFeatures.Features): Region? {
        val stereo = runCatching { ForkDesktopLoopMix.decodeRegion(stream, from, to, REGION_RATE) { !cancelled() } }
            .onFailure { if (!cancelled()) DesktopTrackLog.log("automix: window ${"%.0f".format(from)}-${"%.0f".format(to)}s skipped: ${it.message}") }
            .getOrNull() ?: return null
        if (stereo.left.size < REGION_RATE || stereo.left.size != stereo.right.size) return null
        val seconds = stereo.left.size / REGION_RATE.toDouble()
        val grid: BeatTracker.Grid? = null // fork: the beats run over ForkBeatSpans' 59 s spans (forkBeatSpan), not these windows
        if (cancelled()) return null
        val vocal = runCatching {
            if (!VocalSpectrogram.available) return@runCatching null
            // The model refuses anything wider than its graph; two frames of margin for a rate conversion's ±1.
            val maxSamples = ((VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate * REGION_RATE)
                .toInt().coerceAtMost(stereo.left.size)
            if (maxSamples <= 0) return@runCatching null
            vocalsUsed = true
            vocals.track(
                if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left,
                if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right,
                REGION_RATE.toDouble(),
            )
        }.onFailure { DesktopTrackLog.log("automix: vocal pass skipped: ${it.message}") }.getOrNull()
        val mask = vocal?.let { values ->
            val curve = features.energyCurve
            if (curve.isEmpty()) {
                null
            } else {
                DoubleArray(curve.size) { index ->
                    val frame = ((curve[index].time - stereo.start) * VocalSpectrogram.frameRate).toInt()
                    if (frame in values.indices) values[frame].toDouble() else NEUTRAL_VOCAL
                }
            }
        }
        return Region(
            grid = grid,
            vocalMask = mask,
            seconds = seconds,
            start = stereo.start,
            vocalWindow = vocal?.let { CurveWindow(stereo.start, VocalSpectrogram.frameRate, it) },
            levelWindow = ForkAnalysisWindows.levelCurve(stereo.left, stereo.right, REGION_RATE.toDouble(), stereo.start),
        )
    }

    /**
     * The whole track as mono float at [rate].
     *
     * fork (Desktop Revived d77475ba / 8f9ed4a8): resampled to [rate] with upstream's resampler. The
     * decoder hands over the stream's own rate, whatever is asked of it, and that audio used to be
     * measured as though it were at [rate].
     */
    private fun decode(stream: DesktopStream, rate: Double): FloatArray? {
        val decoder = DesktopAudioDecoder()
        val opened = decoder.open(
            url = stream.url,
            headers = stream.headers,
            requested = DesktopPcmFormat(rate.toInt(), channels = 1, bytesPerSample = 4, isFloat = true),
            windowed = stream.windowedReads,
            transport = stream.transport,
        )
        if (opened.isFailure) {
            DesktopTrackLog.log("automix: could not open the analysis stream: ${opened.exceptionOrNull()?.message}")
            decoder.close()
            return null
        }
        return try {
            val sourceRate = decoder.outputFormat.sampleRate.toDouble().takeIf { it > 0 } ?: rate
            val limit = (MAX_SECONDS * sourceRate).toInt()
            val out = ArrayList<FloatArray>()
            var total = 0
            while (true) {
                if (cancelled()) return null
                val block = decoder.readSamples() ?: break
                val count = decoder.sampleCount
                if (count <= 0) continue
                out += block.copyOf(count)
                total += count
                if (total > limit) break
            }
            if (total == 0) return null
            val samples = FloatArray(total)
            var at = 0
            for (block in out) {
                block.copyInto(samples, at)
                at += block.size
            }
            ForkMixWaves.measured(samples, sourceRate) // fork: the mix popup's waveform from this same decode (ForkMixWaves)
            if (kotlin.math.abs(sourceRate - rate) < 1.0) samples else MelSpectrogram.resample(samples, sourceRate, rate)
        } finally {
            decoder.close()
        }
    }

    fun release() {
        released.set(true)
        pending.clear()
        worker.shutdownNow()
        // A model pass may still be inside its native session after the interrupt: released only
        // once the worker has really stopped.
        Thread({
            if (worker.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                if (vocalsUsed) runCatching { vocals.release() }
                if (beatsUsed) runCatching { beats.release() }
            }
        }, "BitChord-Analysis-Cleanup").apply { isDaemon = true; start() }
        results.clear()
        running.clear()
        failed.clear()
        failedAt.clear()
    }

    internal companion object {

        /** How long a track that failed to be measured waits before it is tried again. */
        const val RETRY_AFTER_MS = 60_000L

        /** A ceiling of twenty minutes, so a mis-tagged eight-hour upload cannot eat the heap. */
        const val MAX_SECONDS = 20 * 60

        /** Stack for the analysis thread. */
        const val ANALYSIS_STACK_BYTES = 64L * 1024 * 1024

        /** fork: the phone's model windows (TrackAnalyzer.HEAD_MODEL_WINDOWS / TAIL_MODEL_WINDOWS / WINDOW_OVERLAP_SECONDS / NEUTRAL_VOCAL). */
        const val HEAD_MODEL_WINDOWS = 2
        const val TAIL_MODEL_WINDOWS = 3
        const val WINDOW_OVERLAP_SECONDS = 1.0
        const val NEUTRAL_VOCAL = 0.2
        const val REGION_RATE = 44_100

        /** fork: where these analyses are kept, apart from the ones measured before the model windows. */
        const val STORE_NAMESPACE = "windows-v1"
    }
}

/** The queue facts the shared planner needs, from a [Song]. */
internal fun Song.transitionInfo(durationMs: Long) = com.music.bitchord.playback.smart.TransitionTrackInfo(
    id = videoId,
    durationMs = durationMs,
    title = title,
    artist = artist,
    album = albumName.orEmpty(),
    albumId = albumId.orEmpty(),
)

/** "3:27" as seconds, or zero when there is nothing to read. */
internal fun String?.durationSeconds(): Double {
    val parts = this?.split(':')?.mapNotNull { it.trim().toIntOrNull() } ?: return 0.0
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]).toDouble()
        3 -> (parts[0] * 3_600 + parts[1] * 60 + parts[2]).toDouble()
        else -> 0.0
    }
}
