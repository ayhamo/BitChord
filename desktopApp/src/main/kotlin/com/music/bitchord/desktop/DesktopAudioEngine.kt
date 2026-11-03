package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.MAX_INCOMING_RATE // fork
import com.music.bitchord.playback.smart.MIN_INCOMING_RATE // fork
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.TransitionFilter
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.echoTailSeconds
import com.music.bitchord.playback.smart.planTransition
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** Playback, decoded here rather than behind JavaFX. */
/**
 * Interleaved samples the blend has fed the incoming decoder, as that track's own elapsed time.
 *
 * A crossfade plays the incoming track underneath the outgoing one for the whole fade, so by the
 * time it becomes current it is already seconds in. Reporting its start instead left the scrubber
 * and the lyrics a fade-length behind the audio — words arriving late, fixable only by dragging
 * the scrubber.
 */
internal fun blendElapsedUs(samples: Long, channels: Int, sampleRate: Int): Long {
    if (sampleRate <= 0 || channels <= 0 || samples <= 0L) return 0L
    return (samples / channels) * 1_000_000L / sampleRate
}

class DesktopPlaybackEngine(
    private val onEnded: () -> Unit,
    private val onCrossfaded: (Song) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DesktopPlaybackState())
    val state: StateFlow<DesktopPlaybackState> = _state.asStateFlow()

    private val sink = DesktopAudioSink()

    /** Automix's evidence. */
    private val analyzer = DesktopTrackAnalyzer(
        performance = { automixPerformance },
        // Automix is off in a party, so whatever was queued for it before the party began is
        // dropped rather than run to completion.
        stopped = { DesktopListenTogether.state.value.inParty },
    )
    private val commands = ConcurrentLinkedQueue<Command>()
    private val running = AtomicBoolean(true)

    // The output sheet changes the persisted mixer directly. Observe that single source of truth
    // here so both the shared main-player sheet and the desktop settings dialog move the live
    // stream, on Windows and Linux alike.
    private var observedOutputDevice = DesktopAudioDevices.selected.value
    private val outputDeviceJob = scope.launch {
        DesktopAudioDevices.selected.collect { selected ->
            if (selected != observedOutputDevice) {
                observedOutputDevice = selected
                commands += Command.Reconfigure
            }
        }
    }

    init {
        // Windows' endpoint watcher bumps the same signal from the OS's own push; Linux asks the
        // kernel on a poll — see [DesktopLinuxAudioWatcher]. A device change reconfigures a
        // playing line only when the line was opened on a per-device mixer that is no longer
        // there; the plain `default` mixer follows the desktop's routing on its own under
        // PipeWire, so it needs no help.
        DesktopLinuxAudioWatcher.ensureStarted()
        scope.launch {
            DesktopAudioDevices.changes.collect {
                if (DesktopPlatform.isWindows) return@collect // its native side moves the stream itself
                observedOutputDevice.let { chosen ->
                    if (chosen != DesktopAudioDevices.SYSTEM_DEFAULT &&
                        DesktopAudioDevices.mixerFor(chosen) == null
                    ) {
                        // The stored device is gone: fall back to the system default rather than
                        // leaving playback pointed at a mixer that no longer exists.
                        DesktopAudioDevices.select(DesktopAudioDevices.SYSTEM_DEFAULT)
                        observedOutputDevice = DesktopAudioDevices.SYSTEM_DEFAULT
                        commands += Command.Reconfigure
                    }
                }
            }
        }
    }

    private var resolveJob: Job? = null
    private var upgradeJob: Job? = null
    private var nextResolveJob: Job? = null

    @Volatile private var playbackSpeed = 1f
    @Volatile private var volume = 1f
    @Volatile private var paused = true
    // Volatile, every one of them: these are set from the UI thread when a setting changes and read
    // from the audio thread on the next block.
    @Volatile private var audioQuality = "HIGH"
    @Volatile private var crossfadeSeconds = 0
    @Volatile private var automixEnabled = false
    /**
     * Whether a manual skip may blend into the next track instead of cutting. When on but neither
     * Automix nor a Crossfade length is set, it still keeps the incoming deck prepared so a skip has
     * something to mix into — see [transitionSecondsFor] and [beginSkipMix].
     */
    @Volatile private var skipMixEnabled = false
    /**
     * Apple-style Automix phrase length in bars: 0 leaves the adaptive planner in
     * charge, 8 or 16 asks the shared planner for a mix of exactly that many bars.
     */
    @Volatile private var automixPhraseBars = 0
    @Volatile private var nextSong: Song? = null

    /** [nextSong]'s downloaded file, when it has one, for its analysis. */
    @Volatile private var nextLocalStream: DesktopStream? = null

    /**
     * The track whose incoming deck is open and ready to blend into, read from outside the audio
     * thread only to answer [beginSkipMix] — a manual skip can only mix when this already names the
     * song being skipped to. It is set when a [Command.Upcoming] lands and cleared once the deck is
     * promoted, flushed or dropped.
     */
    @Volatile private var nextDeckOpenId: String? = null

    /** Whether a blend is currently running, so a second skip cannot interrupt one at its seam. */
    @Volatile private var blendActive = false

    /**
     * A [nextSong] an addon may serve, not yet resolved: it is held until the transition into it
     * is close, then armed by [armAddonUpcoming].
     */
    private val awaitingArm = java.util.concurrent.atomic.AtomicReference<Song?>(null)
    private var retryingSongId: String? = null

    /** Owned by the audio thread once handed over; never touched from outside. */
    private class Track(
        val song: Song,
        val decoder: DesktopDecodeAhead,
        val stream: DesktopStream,
        /**
         * Where in this track its first rendered sample sits.
         *
         * A `var` because Automix moves it: cueing the incoming track to a downbeat seeks its
         * decoder, and a position published against an unmoved start is short by the whole cue.
         */
        var startUs: Long,
    ) {
        var gain = 1f
        var finished = false
        var tempo: DesktopTempoBuffer? = null
        var tempoRate = 1.0
        var tempoEaseStep = 0.0
        var tempoEaseEveryFrames = 0L
        var tempoEaseNextFrame = Long.MAX_VALUE
        var tempoOutputFrames = 0L
        /** The stretch has eased out and [tempo] is only playing off what it still held. */
        var tempoDrained = false
        /** Brings this track to the output stream's rate, when the two differ; see [readSource]. */
        var converter: DesktopRateConverter? = null

        /** Back to the decoder at the listener's speed, dropping any beatmatch stretch. */
        fun dropTempo() {
            // The converter's history is from before whatever moved the decoder.
            converter = null
            tempo = null
            tempoRate = 1.0
            tempoEaseStep = 0.0
            tempoEaseNextFrame = Long.MAX_VALUE
            tempoDrained = false
        }
    }

    private sealed interface Command {
        class Start(val track: Track, val playWhenReady: Boolean) : Command
        /** Replaces only the playing deck; a quality upgrade must not discard the prepared next deck. */
        class ReplaceCurrent(val track: Track, val playWhenReady: Boolean) : Command
        class Upcoming(val track: Track) : Command
        /** A better copy of the incoming track, found after it was opened; see [upgradeIncoming]. */
        class ReplaceUpcoming(val track: Track) : Command
        class SeekTo(val millis: Long) : Command
        /** Blend into the already-prepared next track right now, over [fadeSeconds]. */
        class MixNow(val fadeSeconds: Int) : Command
        /** fork: a manual skip's blend; see [forkSkipTo]. [track] is null for the deck already open. */
        class ForkSkip(val song: Song, val track: Track?, val fadeSeconds: Int, val ticket: Any) : Command
        data object ClearUpcoming : Command
        data object Reconfigure : Command
        data object Flush : Command
    }

    private val thread = Thread(::pump, "BitChord-Audio").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY + 2
        start()
    }

    // ---- the surface the application uses -------------------------------

    fun load(song: Song, playWhenReady: Boolean = true, startAtMs: Long = 0L) {
        retryingSongId = null
        loadInternal(
            song,
            playWhenReady,
            excludedSourceId = refusedSources[song.videoId],
            startAtMs = if (com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) 0L else startAtMs.coerceAtLeast(0L), // fork: a restored station starts live, see seekTo
        )
    }

    /**
     * Sources that handed over a URL they could not actually serve, by track.
     *
     * Remembered for the session, not just for the retry: without it every play of the same track
     * asks the same dead source again and waits for it to fail before falling back.
     */
    private val refusedSources = ConcurrentHashMap<String, String>()

    private fun loadInternal(
        song: Song,
        playWhenReady: Boolean,
        excludedSourceId: String? = null,
        startAtMs: Long = 0L,
        /** Why the first attempt failed, when this call is the retry. */
        priorFailure: Throwable? = null,
    ) {
        forkSkipTicket = null // fork: whatever a skip was still opening is no longer wanted
        resolveJob?.cancel()
        nextResolveJob?.cancel()
        upgradeJob?.cancel()
        commands += Command.Flush
        searchingBetterFor = null
        incomingSearchingFor = null
        _state.value = DesktopPlaybackState(song = song, volume = volume, isLoading = true)
        resolveJob = scope.launch {
            // Only when the file is really there: a download record can outlive the file it names,
            // and handing the decoder a path that is not there fails as "could not open stream"
            // with nothing to say why. A missing one falls through to the sources instead.
            // The plain path, not a file:// URI. FFmpeg does not percent-decode what it is given,
            // so a URI for "Justin Bieber - Peaches (feat. …).m4a" sent it looking for a file
            // literally named "Justin%20Bieber%20-%20…" and it answered ENOENT for a file that was
            // sitting right there.
            val localUrl = DesktopDownloadManager.savedFile(song)?.toAbsolutePath()?.toString()
            val resolved = localUrl?.let { Result.success(DesktopLiveResolution(DesktopStream(it))) }
                ?: DesktopMusicSources.resolveLive(song, audioQuality, excludedSourceId)
            resolved.fold(
                onSuccess = { live ->
                    val opened = openTrack(song, live.stream, startAtMs)
                    opened.fold(
                        onSuccess = { track ->
                            searchingBetterFor = song.videoId.takeIf { live.pendingSubstitute != null }
                            commands += Command.Start(track, playWhenReady)
                            live.pendingSubstitute?.let { watchForUpgrade(song, it) }
                        },
                        onFailure = { failure -> retryAfterFailure(song, playWhenReady, live.stream, failure) },
                    )
                },
                onFailure = { failure ->
                    val reported = priorFailure ?: failure
                    _state.value = DesktopPlaybackState(
                        song = song,
                        volume = volume,
                        error = reported.message ?: "Playback failed",
                    )
                },
            )
        }
    }

    /** Opens a decoder on [stream], off the audio thread. */
    private suspend fun openTrack(song: Song, stream: DesktopStream, startAtMs: Long): Result<Track> =
        withContext(Dispatchers.IO) {
            val decoder = DesktopAudioDecoder()
            decoder.open(
                url = stream.url,
                headers = stream.headers,
                // Float throughout: the processors work in it and the sink converts once, at the
                // end.
                requested = DesktopPcmFormat(44_100, 2, bytesPerSample = 4, isFloat = true),
                windowed = stream.windowedReads,
                transport = stream.transport,
            ).map {
                if (startAtMs > 0) decoder.seek(startAtMs * 1_000)
                // From here the decoder belongs to its own read-ahead thread: the audio thread only
                // ever takes what has already been decoded, so a slow network read is not a gap.
                val ahead = DesktopDecodeAhead(
                    source = decoder,
                    outputFormat = decoder.outputFormat,
                    durationUs = decoder.durationUs,
                    measuredFormat = decoder.measuredFormat,
                    name = "BitChord-Decode ${song.title.take(24)}",
                )
                Track(song, ahead, stream, startAtMs * 1_000)
            }.onFailure { decoder.close() }
        }

    /**
     * A source that cannot actually be decoded is struck off and the track asked for again, once.
     */
    private fun retryAfterFailure(song: Song, playWhenReady: Boolean, stream: DesktopStream, failure: Throwable) {
        if (retryingSongId != song.videoId) {
            retryingSongId = song.videoId
            DesktopTrackLog.log("could not decode '${song.title}' from ${DesktopMusicSources.sourceNameFor(stream)}: ${failure.message}")
            stream.sourceId?.let { refusedSources[song.videoId] = it }
            loadInternal(song, playWhenReady, excludedSourceId = stream.sourceId, priorFailure = failure)
        } else {
            _state.value = DesktopPlaybackState(
                song = song,
                volume = volume,
                error = "Could not play this track: ${failure.message}",
            )
        }
    }

    /**
     * The second look: a track that started on YouTube because the other sources were still
     * searching gets swapped once one of them answers.
     */
    private fun watchForUpgrade(song: Song, pending: Deferred<DesktopStream?>) {
        upgradeJob = scope.launch {
            try {
                if (take(song, runCatching { pending.await() }.getOrNull())) return@launch
                // The live race takes the first copy that beats YouTube, so a slower lossless
                // source can still be searching when a lossy one wins — and a source that failed
                // that minute may answer properly now. Android asks again; so does this.
                repeat(LOSSLESS_FOLLOW_UPS) {
                    val current = _state.value
                    if (current.song?.videoId != song.videoId) return@launch
                    if (current.streamFormat?.isLossless == true) return@launch
                    if (current.streamFormat?.isDolbyAtmos == true) return@launch
                    if (DesktopMusicSources.ceiling(null) != DesktopAudioQuality.LOSSLESS) return@launch
                    val playing = DesktopStream(url = "", format = current.streamFormat ?: DesktopStreamFormat(), sourceId = current.streamSourceId)
                    if (take(song, DesktopMusicSources.upgradeFor(song, playing))) return@launch
                }
                DesktopTrackLog.log("no better copy of '${song.title}' was found")
            } finally {
                // Settled either way: the badge stops saying "upgrading" the moment the search
                // stops, never on a timer.
                // Only this song's: by now the queue may have blended on to another whose own
                // search is still running.
                if (searchingBetterFor == song.videoId) searchingBetterFor = null
                _state.update { if (it.song?.videoId == song.videoId) it.copy(searchingBetter = false) else it }
            }
        }
    }

    /** Swaps [better] in when it is worth the break, and says whether the question is settled. */
    private suspend fun take(song: Song, better: DesktopStream?): Boolean {
        if (better == null) return false
        val current = _state.value
        if (current.song?.videoId != song.videoId) return true
        if (!DesktopMusicSources.worthSwapping(better.format, current.streamFormat)) {
            DesktopTrackLog.log(
                "keeping '${song.title}' on what is playing — " +
                    "${DesktopMusicSources.sourceNameFor(better)} offered nothing better",
            )
            return false
        }
        DesktopTrackLog.log(
            "upgrading '${song.title}' to ${better.format.summary.ifBlank { "another rendition" }}" +
                " from ${DesktopMusicSources.sourceNameFor(better)}",
        )
        swapStream(song, better, current.positionMs)
        return true
    }

    /** Re-opens the current track on a different stream, carrying the playhead over. */
    private fun swapStream(song: Song, stream: DesktopStream, positionMs: Long) {
        scope.launch {
            openTrack(song, stream, positionMs).fold(
                onSuccess = { track ->
                    val latest = _state.value
                    if (latest.song?.videoId != song.videoId) {
                        track.decoder.close()
                        return@fold
                    }
                    // Resolving and opening the better rendition can take a few seconds. Hand it
                    // over at the live playhead rather than jumping back to the position at which
                    // the upgrade was first offered.
                    if (kotlin.math.abs(latest.positionMs - positionMs) >= UPGRADE_RESEEK_THRESHOLD_MS) {
                        track.decoder.seek(latest.positionMs * 1_000)
                        track.startUs = latest.positionMs * 1_000
                    }
                    commands += Command.ReplaceCurrent(track, playWhenReady = latest.isPlaying)
                },
                onFailure = { failure ->
                    DesktopTrackLog.log(
                        "could not open the copy of '${song.title}' from " +
                            "${DesktopMusicSources.sourceNameFor(stream)}: ${failure.message}",
                    )
                    _state.update { it.copy(
                        error = "Could not switch version: ${failure.message ?: "that copy would not open"}",
                    ) }
                },
            )
        }
    }

    /** Re-opens the current track, keeping the playhead. */
    fun reloadCurrent(forceSourceRefresh: Boolean = false) {
        val current = _state.value
        val song = current.song ?: return
        if (forceSourceRefresh) DesktopAddonSource.clearCompletedTrackCalls()
        upgradeJob?.cancel()
        DesktopTrackLog.log(
            "re-opening '${song.title}' — pinned to the original: " +
                "${DesktopOriginalVersion.isPinned(song.videoId)}",
        )
        scope.launch {
            DesktopMusicSources.resolve(song, audioQuality).fold(
                onSuccess = { stream ->
                    if (_state.value.song?.videoId != song.videoId) return@fold
                    DesktopTrackLog.log("re-opened from ${DesktopMusicSources.sourceNameFor(stream)}")
                    swapStream(song, stream, current.positionMs)
                },
                onFailure = { failure ->
                    DesktopTrackLog.log("could not re-open '${song.title}': ${failure.message}")
                    _state.update { it.copy(
                        error = "Could not switch version: ${failure.message ?: "no source answered"}",
                    ) }
                },
            )
        }
    }

    fun togglePlayPause() {
        if (paused) play() else pause()
    }

    fun play() {
        paused = false
        _state.update { it.copy(isPlaying = true) }
    }

    fun pause() {
        paused = true
        _state.update { it.copy(isPlaying = false) }
    }

    fun seekTo(positionMs: Long) {
        // fork: nothing to seek in a live stream, and FFmpeg's generic seek reads forward to its target.
        if (com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(_state.value.song?.videoId)) return
        // TEMP seek diagnostics: who asked, so a seek undone by a second one shows up.
        DesktopTrackLog.log(
            "seek requested: ${positionMs}ms (at ${_state.value.positionMs}ms) from " +
                Throwable().stackTrace.drop(1).take(6).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" },
        )
        commands += Command.SeekTo(positionMs.coerceAtLeast(0))
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 3.0f)
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        sink.gain = volume
        _state.update { it.copy(volume = volume) }
    }

    /** Sets the source quality ceiling used the next time a track is resolved. */
    fun setAudioQuality(quality: String) {
        audioQuality = quality.uppercase()
    }

    fun setCrossfadeSeconds(seconds: Int) {
        crossfadeSeconds = seconds.coerceIn(0, MAX_CROSSFADE_SECONDS)
        if (crossfadeSeconds == 0 && !automixEnabled && !skipMixEnabled) clearUpcoming()
    }

    /** Enables Android's separate Automix setting. */
    fun setAutomixEnabled(enabled: Boolean) {
        automixEnabled = enabled
        if (!enabled && crossfadeSeconds == 0 && !skipMixEnabled) clearUpcoming()
    }

    /** Enables blending into the next track on a manual skip; see [beginSkipMix]. */
    fun setSkipMixEnabled(enabled: Boolean) {
        skipMixEnabled = enabled
        if (!enabled && !automixEnabled && crossfadeSeconds == 0) clearUpcoming()
    }

    /**
     * Sets the Apple-style Automix phrase length. Only 8 or 16 bars are meaningful;
     * anything else (including 0) turns the fixed-phrase mix off and hands timing
     * back to the adaptive planner.
     */
    fun setAutomixPhraseBars(bars: Int) {
        automixPhraseBars = if (bars == 8 || bars == 16) bars else 0
    }

    /**
     * Asks the engine to blend into the already-prepared next track right now, rather than waiting
     * for the outgoing one to run out. Returns whether such a blend could be started — a manual
     * skip only mixes when the incoming deck for [nextVideoId] is already open (it is whenever
     * Automix or a Crossfade length is set) and no blend is already under way; otherwise the caller
     * does an ordinary cut, exactly as before. The mix itself runs on the audio thread; this just
     * queues it.
     */
    fun beginSkipMix(nextVideoId: String, fadeSeconds: Int): Boolean {
        if (blendActive || nextDeckOpenId != nextVideoId) return false
        commands += Command.MixNow(fadeSeconds.coerceIn(2, MAX_CROSSFADE_SECONDS * 2))
        return true
    }

    /** Resolves and prepares the next queue item without starting it. */
    fun prepareNext(song: Song?) {
        // Past the midpoint of a blend the player already shows the incoming song, and the app
        // answers that by asking for the track *after* it — which would clear the upcoming track
        // this blend is still playing. Held until the blend ends, and then carried out.
        synchronized(blendLock) {
            if (displaySwitched || forkSkipTicket != null) { // fork: a skip's deck is on its way, and this would open another
                deferredNext = song
                hasDeferredNext = true
                return
            }
        }
        nextResolveJob?.cancel()
        clearUpcoming()
        if (song == null || transitionSecondsFor(song) == 0) return
        if (com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)) return // fork: nothing to read ahead on a live stream (`ForkPreload.skipsReadAhead` on the phone); it opens when it plays
        nextSong = song
        // A download plays from its file, here as in [loadInternal] — never from whichever source
        // would have answered for it.
        val local = DesktopDownloadManager.savedFile(song)?.toAbsolutePath()?.toString()?.let { DesktopStream(it) }
        nextLocalStream = local
        // Measured on YouTube Opus (or the file) whatever will end up serving it, so Automix has
        // its analysis without anything being fetched from the source that will play it.
        if (automixEnabled && !song.isVideoOrigin) {
            analyzer.request(song, local, song.durationText.durationSeconds())
        }
        if (local == null && DesktopMusicSources.mayServeFromAddon(song)) {
            // Android does not read an addon's track ahead; it is asked for a stream only once the
            // track is about to play. Held here and armed shortly before the transition — see
            // [armAddonUpcoming].
            DesktopTrackLog.log("'${song.title}' may come from an addon, so it is not read ahead")
            awaitingArm.set(song)
            return
        }
        resolveUpcoming(song, local)
    }

    /**
     * Resolves [song] (or takes its file) and opens it as the incoming track, off the audio thread.
     *
     * [race] is for a track armed shortly before its transition: the sources ranked above YouTube
     * are raced against it, as when a track is started by hand, so the blend never waits on a slow
     * addon — an addon routinely takes 10-15s to answer, which outlasted the arming lead and left
     * the marker on the scrubber with no transition behind it. Whatever the race was still waiting
     * on is swapped in when it answers; see [upgradeIncoming].
     */
    private fun resolveUpcoming(song: Song, local: DesktopStream? = null, race: Boolean = false) {
        nextResolveJob = scope.launch {
            val askedAt = System.nanoTime()
            val resolved: Result<DesktopLiveResolution> = when {
                local != null -> Result.success(DesktopLiveResolution(local))
                race -> DesktopMusicSources.resolveLive(song, audioQuality)
                else -> DesktopMusicSources.resolve(song, audioQuality).map { DesktopLiveResolution(it) }
            }
            resolved.fold(
                onSuccess = { live ->
                    openTrack(song, live.stream, startAtMs = 0).fold(
                        onSuccess = { track ->
                            // A newer [prepareNext] may have asked for another track while this one
                            // was resolving.
                            if (nextSong?.videoId != song.videoId) {
                                track.decoder.close()
                                live.pendingSubstitute?.cancel()
                                return@fold
                            }
                            DesktopTrackLog.log(
                                "incoming '${song.title}' ready from ${DesktopMusicSources.sourceNameFor(live.stream)}" +
                                    " in ${(System.nanoTime() - askedAt) / 1_000_000}ms",
                            )
                            commands += Command.Upcoming(track)
                            live.pendingSubstitute?.let { upgradeIncoming(song, track.stream, it) }
                        },
                        onFailure = { failure ->
                            DesktopTrackLog.log("could not open incoming '${song.title}': ${failure.message}")
                            live.pendingSubstitute?.cancel()
                            if (nextSong?.videoId == song.videoId) nextSong = null
                        },
                    )
                },
                onFailure = { failure ->
                    DesktopTrackLog.log("could not resolve incoming '${song.title}': ${failure.message}")
                    if (nextSong?.videoId == song.videoId) nextSong = null
                },
            )
        }
    }

    /**
     * Swaps in the better copy a raced resolve of the incoming track was still waiting on.
     *
     * Not a child of [nextResolveJob]: the next [prepareNext] cancels that the moment the blend
     * moves the queue on, and by then this answer belongs to the track now playing.
     */
    private fun upgradeIncoming(song: Song, opened: DesktopStream, pending: Deferred<DesktopStream?>) {
        // The player says "upgrading" for this track from the moment it is shown, mid-blend.
        incomingSearchingFor = song.videoId
        scope.launch {
            val better = runCatching { pending.await() }.getOrNull()
                ?.takeIf { DesktopMusicSources.worthSwapping(it.format, opened.format) }
            if (better == null) {
                settleIncomingSearch(song)
                return@launch
            }
            // Not heard yet: the incoming deck itself is replaced, with nothing to interrupt.
            if (upcoming?.song?.videoId == song.videoId && fadeRemaining <= 0 && !displaySwitched) {
                openTrack(song, better, startAtMs = 0).fold(
                    onSuccess = { commands += Command.ReplaceUpcoming(it) },
                    onFailure = { settleIncomingSearch(song) },
                )
                return@launch
            }
            playingUpgrade(song, better)
        }
    }

    /** The incoming track's search is over without anything for the playing-track upgrade. */
    private fun settleIncomingSearch(song: Song) {
        if (incomingSearchingFor != song.videoId) return
        incomingSearchingFor = null
        _state.update { if (it.song?.videoId == song.videoId) it.copy(searchingBetter = false) else it }
    }

    /**
     * Hands a better copy of [song] to the ordinary playing-track upgrade, once the blend into it
     * has finished — [replaceCurrent] refuses anything mid-blend.
     */
    private fun playingUpgrade(song: Song, better: DesktopStream) {
        scope.launch {
            repeat(BLEND_WAIT_POLLS) {
                val playing = _state.value.song?.videoId == song.videoId
                if (playing && !displaySwitched && fadeRemaining <= 0) {
                    // Handed over: from here the search is the playing track's, and ends with it.
                    searchingBetterFor = song.videoId
                    if (incomingSearchingFor == song.videoId) incomingSearchingFor = null
                    watchForUpgrade(song, kotlinx.coroutines.CompletableDeferred(better))
                    return@launch
                }
                // Skipped past, or never reached.
                if (!playing && upcoming?.song?.videoId != song.videoId) {
                    settleIncomingSearch(song)
                    return@launch
                }
                delay(BLEND_WAIT_POLL_MS)
            }
            settleIncomingSearch(song)
        }
    }

    fun release() {
        analyzer.release()
        running.set(false)
        resolveJob?.cancel()
        nextResolveJob?.cancel()
        upgradeJob?.cancel()
        thread.interrupt()
        scope.cancel()
    }

    /**
     * [release], then waits for the audio thread to close its decoders and the output stream.
     *
     * For quitting. exitProcess tears native libraries down underneath any thread still inside
     * them, and the audio thread is always inside FFmpeg or WASAPI — quitting from the tray
     * mid-song ended in a native crash rather than a clean exit.
     */
    fun shutdown(timeoutMs: Long = 2_000) {
        release()
        runCatching { thread.join(timeoutMs) }
    }

    private fun clearUpcoming() {
        nextSong = null
        nextLocalStream = null
        awaitingArm.set(null)
        commands += Command.ClearUpcoming
    }

    private fun transitionSecondsFor(next: Song?): Int = when {
        next == null -> 0
        crossfadeSeconds > 0 -> crossfadeSeconds
        automixEnabled -> AUTOMIX_FALLBACK_SECONDS
        // Skip-mix needs the incoming deck open to blend into, even when neither Automix nor a
        // Crossfade length would otherwise prepare one.
        skipMixEnabled -> AUTOMIX_FALLBACK_SECONDS
        else -> 0
    }

    // ---- the audio thread -----------------------------------------------

    private var current: Track? = null
    private var upcoming: Track? = null
    private var speedProcessor: DesktopAudioSpeed? = null
    /** One widener per side of a mix, because widening is a property of a track. */
    private var spatial: DesktopSpatialAudio? = null
    private var spatialIncoming: DesktopSpatialAudio? = null

    /**
     * One equaliser over each side of a mix, matching Android's per-player pair.
     *
     * Ahead of the transition filter for the reason Android's chain gives: the equaliser belongs to
     * the listener and the whole session, while the filter belongs to one handoff and has to have
     * the last word on it.
     */
    private var equalizer = DesktopEqualizer()
    private var equalizerIncoming = DesktopEqualizer()
    private var silence: DesktopSilenceSkipper? = null

    /** The playing track's heard position, on the sink's played-frame count; see [play]. */
    private val playhead = DesktopPlayhead()
    private var fadeRemaining = 0
    private var fadeTotal = 0
    /** Fade plus any echo/roll/reverb tail. [fadeTotal] remains the nominal fader span. */
    private var transitionTotal = 0
    private var transitionEcho: DesktopTransitionEcho? = null

    /**
     * Beat-roll and reverb-tail sends on the outgoing deck, for a smart transition whose plan asked
     * for no echo. The echo already owns "repeats as it leaves" when the planner chose one; these
     * are the subtler variant — a frozen 1/4- or 1/8-beat roll plus a decaying tail — and never
     * stack with it. [beatRepeatLatched] flips once, at the point the roll freezes.
     */
    private var transitionBeatRepeat: DesktopTransitionBeatRepeat? = null
    private var transitionReverb: DesktopTransitionReverb? = null

    /**
     * The one rotating texture send on the outgoing deck — flanger, crush, phaser, vibrato or
     * stereo width — chosen per transition by [chooseTexture] from what the plan reports about the
     * pair. Only one is live at a time, and, like the roll and reverb, it never arms alongside an
     * echo; the echo already owns "repeats as it leaves".
     */
    private var transitionTexture: DesktopTransitionTexture? = null
    private var beatRepeatActive = false
    private var beatRepeatLatched = false
    private var reverbActive = false
    private var textureActive = false

    /**
     * Samples of the incoming track already rendered under the outgoing one.
     *
     * A blend plays the next track for the whole length of the fade before it
     * becomes the current one, so at handover it is seconds in — not at its
     * start. Android has no equivalent because it crossfades between two whole
     * players and the session simply moves onto the one already playing; here
     * there is a single sink, so what the incoming track has actually consumed
     * has to be counted to be known.
     */
    private var incomingSourceFrames = 0.0
    private var mixed = FloatArray(0)
    private var endPadding = FloatArray(0)
    private val transitionGains = FloatArray(2)
    private var currentReadCount = 0
    private var incomingReadCount = 0

    /**
     * Output frames handed to the sink, on the same count as [DesktopAudioSink.framesPlayed]:
     * the difference is what is queued but not yet heard. Re-synced wherever the sink drops or
     * restarts its queue.
     */
    private var framesWritten = 0L

    /**
     * Whether the player has already moved to the incoming song. Set halfway through a blend,
     * matching Android: the outgoing song stays on screen while it is the louder of the two, and
     * the incoming one takes over the moment it is. The audio carries on blending to the end.
     */
    @Volatile private var displaySwitched = false
    private val blendLock = Any()
    private var deferredNext: Song? = null
    private var hasDeferredNext = false

    /** One filter over each side of a mix. */
    private val outgoingFilter = TransitionFilter()
    private val incomingFilter = TransitionFilter()

    /** The plan the running fade was started from, for the filter ride. */
    private var activePlan: TransitionPlan? = null

    private fun pump() {
        while (running.get()) {
            runCatching { step() }.onFailure { failure ->
                if (failure is InterruptedException) return
                DesktopTrackLog.log("audio thread recovered from: ${failure.message}")
            }
        }
        closeEverything()
    }

    private fun step() {
        drainCommands()
        forkReportAutomix(current) // fork: the stats line and the mix popup (ForkAutomixReport)
        val track = current
        if (track == null || paused) {
            if (paused) sink.pause()
            if (paused && fadeRemaining > 0) publishBlend(playing = false)
            Thread.sleep(20)
            return
        }
        sink.resume()
        if (forkInstallHeldUpgrade(track)) return // fork: a better copy held through a mix (ForkMixUpgradeHold)

        if (forkLoopStep(track)) return // fork: the loop mix writes this block (ForkDesktopLoopMix)
        forkStartWaitingSwap(track) // fork
        val block = readCurrent(track)
        val count = currentReadCount
        if (block != null && count > 0) forkMixSwap(block, count) // fork
        if (block == null && forkSwap != null) { forkFinishSwap(); return } // fork: the old copy ran out first
        if (block == null) {
            if (fadeRemaining > 0 && upcoming != null) {
                val channels = sink.format.channels.coerceAtLeast(1)
                val wanted = MIX_END_PADDING_FRAMES * channels
                if (endPadding.size < wanted) endPadding = FloatArray(wanted)
                java.util.Arrays.fill(endPadding, 0, wanted, 0f)
                val (blended, blendedCount) = blend(track, endPadding, wanted)
                val (stretched, stretchedCount) = stretch(blended, blendedCount)
                play(track, stretched, stretchedCount)
                publishPosition(track)
                return
            }
            finishTrack(track)
            return
        }
        if (count == 0) return
        forkCheckCue(track) // fork: a start or seek measured from where its stream landed (ForkCueCorrection)

        val (blended, blendedCount) = blend(track, block, count)
        val quiet = silence?.also { it.enabled = skipSilenceEnabled }
        val skippedBefore = quiet?.skippedFrames ?: 0L
        val trimmed = quiet?.process(blended, blendedCount) ?: blended
        val trimmedCount = quiet?.outputCount ?: blendedCount
        val skipped = (quiet?.skippedFrames ?: 0L) - skippedBefore
        val (stretched, stretchedCount) = stretch(trimmed, trimmedCount)
        play(track, stretched, stretchedCount, skipped)
        publishPosition(track)
    }

    /**
     * Hands a block to the sink, first telling [playhead] how it maps onto [track]: at what rate,
     * and how much silence was cut ahead of it. Recorded against the frame it starts on, so it
     * takes effect when that frame is heard rather than a queue's length early.
     */
    private fun play(track: Track, samples: FloatArray, count: Int, skippedFrames: Long = 0L) {
        val rate = sink.format.sampleRate
        if (rate > 0) {
            val deck = deckRate(track)
            playhead.change(
                frame = framesWritten,
                usPerFrame = usPerFrame(track),
                // Cut before the listener's stretch, so it is the deck's time, not the speaker's.
                skippedUs = skippedFrames.coerceAtLeast(0L) * 1_000_000.0 / rate * deck,
            )
        }
        sink.write(samples, count)
        framesWritten += count / sink.format.channels.coerceAtLeast(1)
        if (count > 0 && _state.value.awaitingAudio) _state.update { it.copy(awaitingAudio = false) }
    }

    /** The beatmatch stretch [track] is actually rendered at — [DesktopTempoBuffer]'s clamp included. */
    private fun deckRate(track: Track): Double =
        // fork (PC-Continued be257dad): the planner's window both ways, as the shared planner plans it
        if (track.tempo != null && !track.tempoDrained) track.tempoRate.coerceIn(MIN_INCOMING_RATE, MAX_INCOMING_RATE) else 1.0

    /** Source time each output frame of [track] covers: the listener's speed times the deck's. */
    private fun usPerFrame(track: Track): Double =
        1_000_000.0 / sink.format.sampleRate.coerceAtLeast(1) * playbackSpeed * deckRate(track)

    /** Starts [playhead] over: what the speakers are playing right now is [positionUs] of [track]. */
    private fun resetPlayhead(track: Track, positionUs: Long) {
        playhead.reset(sink.framesPlayed(), positionUs, usPerFrame(track))
    }

    /** Mixes the outgoing track with the one coming in, when a crossfade is running. */
    /** Mixes the outgoing track with the one coming in, filtering each side. */
    private fun blend(track: Track, block: FloatArray, count: Int): Pair<FloatArray, Int> {
        val incoming = upcoming
        // Widened first, whether or not a mix is running, then equalised.
        widen(spatial, track, block, count)
        equalise(equalizer, block, count)
        if (fadeRemaining <= 0 || incoming == null) return block to count

        if (mixed.size < count) mixed = FloatArray(count)
        val other = readIncoming(incoming, count)
        val otherCount = incomingReadCount
        val incomingRate = deckRate(incoming)
        if (incomingSourceFrames == 0.0 && otherCount > 0) forkCorrectCue(incoming) // fork: ForkCueCorrection
        incomingSourceFrames += otherCount.toDouble() / sink.format.channels.coerceAtLeast(1) * incomingRate
        if (other != null) {
            widen(spatialIncoming, incoming, other, otherCount)
            equalise(equalizerIncoming, other, otherCount)
        }
        val channels = sink.format.channels.coerceAtLeast(1)
        val plan = activePlan
        val subBlock = TransitionFilter.GLIDE_FRAMES * channels

        var index = 0
        while (index < count) {
            val progress = ((transitionTotal - fadeRemaining).toFloat() / fadeTotal.coerceAtLeast(1))
            val styleProgress = progress.coerceIn(0f, 1f)
            plan?.let { DesktopTransitionRide.aim(it, styleProgress, outgoingFilter, incomingFilter) }
            outgoingFilter.advance()
            incomingFilter.advance()

            if (plan != null) DesktopTransitionRide.gains(plan, styleProgress, transitionGains)
            val out = if (plan != null) transitionGains[0] else kotlin.math.sqrt(1f - styleProgress)
            val into = if (plan != null) transitionGains[1] else kotlin.math.sqrt(styleProgress)
            // Equal-power gains sum past 1 — up to 1.41 at the midpoint — and two loud masters
            // peaking together would be clipped hard by the sink. A smooth trim of
            // 1 / sqrt(out + into) holds that to 1.19 for at most 1.5 dB, and reshapes nothing:
            // the same trim Android's two players take.
            val outgoingLevel = if (plan != null && plan.echoSeconds > 0.0) {
                DesktopTransitionRide.echoLevel(plan, progress)
            } else {
                out
            }
            val trim = 1f / kotlin.math.sqrt((outgoingLevel + into).coerceAtLeast(1f))
            val stop = minOf(count, index + subBlock)
            val span = stop - index
            while (index < stop) {
                val channel = index % channels
                var leaving = outgoingFilter.filter(channel, block[index])
                if (plan != null && plan.echoSeconds > 0.0) {
                    val echoAt = DesktopTransitionRide.echoAt(plan)
                    val echoFrom = DesktopTransitionRide.echoFrom(plan)
                    if (progress >= echoFrom) {
                        val send = if (progress >= echoAt) 0f else ((progress - echoFrom) / (echoAt - echoFrom)).coerceIn(0f, 1f)
                        val kill = DesktopTransitionRide.echoKill(plan)
                        val dry = (1f - ((progress - echoAt) / kill).coerceIn(0f, 1f))
                        leaving = transitionEcho?.process(leaving, send, dry) ?: leaving
                    }
                }
                val arriving = if (index < otherCount) {
                    incomingFilter.filter(channel, other!![index])
                } else {
                    0f
                }
                // Post-fader sends on the outgoing deck: a frozen beat-roll and a reverb tail. Added
                // after the fader so they keep sounding once the outgoing dry has faded to nothing,
                // ringing out over the incoming track through the extended tail.
                var fxSend = 0f
                if (beatRepeatActive) {
                    val roll = transitionBeatRepeat
                    if (roll != null) {
                        if (progress >= ROLL_START && !beatRepeatLatched) {
                            roll.latch()
                            beatRepeatLatched = true
                        }
                        // Fed every sample so the ring always holds the last slice of live audio.
                        val wet = roll.process(leaving, 1f, 0f)
                        val ramp = ((progress - ROLL_START) / (1f - ROLL_START)).coerceIn(0f, 1f) * ROLL_SEND
                        fxSend += wet * ramp
                    }
                }
                if (reverbActive) {
                    val reverb = transitionReverb
                    if (reverb != null) {
                        val ramp = ((progress - REVERB_START) / (1f - REVERB_START)).coerceIn(0f, 1f) * REVERB_SEND
                        // Only fed once the ramp opens, so the tail is of the outgoing's exit, not a
                        // wash of its whole fade; afterwards it rings from the combs' own feedback.
                        if (ramp > 0f) fxSend += reverb.process(channel, leaving) * ramp
                    }
                }
                if (textureActive) {
                    val texture = transitionTexture
                    if (texture != null) {
                        val ramp = ((progress - TEXTURE_START) / (1f - TEXTURE_START)).coerceIn(0f, 1f) * TEXTURE_SEND
                        if (ramp > 0f) fxSend += texture.process(channel, leaving) * ramp
                    }
                }
                mixed[index] = ((leaving * out + arriving * into) + fxSend) * trim
                index++
            }
            fadeRemaining -= span
        }
        val progress = ((transitionTotal - fadeRemaining).toFloat() / fadeTotal.coerceAtLeast(1))
        val handoffAt = plan?.let(DesktopTransitionRide::handoffAt) ?: DISPLAY_SWITCH_AT
        if (!displaySwitched && progress >= handoffAt && fadeRemaining > 0) switchDisplay(incoming)
        if (fadeRemaining <= 0) promoteUpcoming() else publishBlend(playing = true)
        return mixed to count
    }

    /** Reads the playing deck, preserving a tempo buffer inherited from its incoming handoff. */
    private fun readCurrent(track: Track): FloatArray? {
        // An eased-out stretch whose held audio has all been played: back on the decoder itself.
        // Left in place, the stretcher went on windowing the track at 1x until it ended — never
        // quite transparent, and heard as the tempo and the pitch never coming back.
        if (track.tempoDrained && track.tempo?.available == 0) {
            track.tempo = null
            track.tempoDrained = false
        }
        val tempo = track.tempo ?: run {
            val block = readSource(track)
            currentReadCount = if (block == null) 0 else sourceReadCount
            return block
        }
        while (tempo.available == 0) {
            val source = readSource(track) ?: run {
                currentReadCount = 0
                return null
            }
            tempo.speed = track.tempoRate.toFloat()
            tempo.push(source, sourceReadCount)
        }
        val block = tempo.take(DEFAULT_TEMPO_OUTPUT_SAMPLES)
        currentReadCount = tempo.outputCount
        advanceTempoEase(track, currentReadCount / sink.format.channels.coerceAtLeast(1))
        if (track.tempoRate == 1.0 && !track.tempoDrained) { // fork: a slowed deck is still stretching
            // Nothing more is pushed from here; what the stretcher holds is queued unstretched and
            // the decoder takes over once it has played.
            tempo.finish()
            track.tempoDrained = true
        }
        return block
    }

    /** Supplies exactly one outgoing block of the incoming deck, retaining any stretched surplus. */
    private fun readIncoming(track: Track, wanted: Int): FloatArray? {
        // Always through a buffer, even unstretched. Read raw, the incoming deck gave one decoder
        // block per outgoing block whatever its size: a shorter one (Opus hands over 20ms at a
        // time) left the rest of the mix block silent, and a longer one had its end cut off — the
        // crackle all through an Automix blend. At 1x the buffer is a plain FIFO, and once the
        // track is promoted [readCurrent] drains it and goes back to the decoder.
        val tempo = track.tempo ?: DesktopTempoBuffer(
            sink.format.channels.coerceAtLeast(1),
            sink.format.sampleRate,
        ).also { track.tempo = it }
        while (tempo.available < wanted) {
            val source = readSource(track) ?: break
            tempo.speed = track.tempoRate.toFloat()
            tempo.push(source, sourceReadCount)
        }
        if (tempo.available == 0) {
            incomingReadCount = 0
            return null
        }
        val block = tempo.take(wanted)
        incomingReadCount = tempo.outputCount
        return block
    }

    /** How much of the array [readSource] returned is this block. */
    private var sourceReadCount = 0

    /**
     * The next block of [track], at the output stream's rate.
     *
     * The stream is opened at the rate of the track that started it, and a decoder hands over its
     * file's own rate. Mixed in unconverted — the incoming track of every Automix blend whose rate
     * differed — 48 kHz audio on a 44.1 kHz stream plays 8% slow and a semitone and a half flat,
     * and stayed that way for the rest of the track, until Next reopened the stream at its rate.
     * Android never meets it: each of its players has its own output.
     */
    private fun readSource(track: Track): FloatArray? {
        val block = track.decoder.readSamples() ?: return null
        val count = track.decoder.sampleCount
        val from = track.decoder.outputFormat.sampleRate
        val to = sink.format.sampleRate
        if (from <= 0 || to <= 0 || from == to) {
            track.converter = null
            sourceReadCount = count
            return block
        }
        val converter = track.converter?.takeIf { it.fromRate == from && it.toRate == to }
            ?: DesktopRateConverter(from, to, sink.format.channels.coerceAtLeast(1)).also {
                DesktopTrackLog.log("converting '${track.song.title}' from $from Hz to the stream's $to Hz")
                track.converter = it
            }
        val converted = converter.process(block, count)
        sourceReadCount = converter.outputCount
        return converted
    }

    /** Eases a promoted beatmatch stretch back by at most 0.75% on each beat. */
    private fun advanceTempoEase(track: Track, outputFrames: Int) {
        if (track.tempoEaseStep <= 0.0 || outputFrames <= 0) return
        track.tempoOutputFrames += outputFrames
        while (track.tempoOutputFrames >= track.tempoEaseNextFrame && track.tempoRate != 1.0) { // fork: eases back from either side
            track.tempoRate =
                if (track.tempoRate > 1.0) (track.tempoRate - track.tempoEaseStep).coerceAtLeast(1.0)
                else (track.tempoRate + track.tempoEaseStep).coerceAtMost(1.0)
            track.tempoEaseNextFrame += track.tempoEaseEveryFrames
        }
        if (kotlin.math.abs(track.tempoRate - 1.0) <= FORK_TEMPO_UNITY) track.tempoRate = 1.0 // fork
    }

    /** Widens one track's samples, unless the audio is Dolby Atmos. */
    private fun widen(widener: DesktopSpatialAudio?, track: Track, samples: FloatArray, count: Int) {
        val processor = widener ?: return
        processor.enabled = spatialEnabled && !track.stream.isDolbyAtmos
        processor.process(samples, count)
    }

    /** The listener's own tuning, applied to one side of the mix. */
    private fun equalise(processor: DesktopEqualizer, samples: FloatArray, count: Int) {
        processor.setTuning(equalizerEnabled, equalizerCurve, equalizerBalance)
        processor.process(samples, count)
    }

    private fun stretch(samples: FloatArray, count: Int): Pair<FloatArray, Int> {
        val processor = speedProcessor ?: return samples to count
        processor.speed = playbackSpeed
        val out = processor.process(samples, count)
        return out to processor.outputCount
    }

    /** End of a track. */
    private fun finishTrack(track: Track) {
        if (upcoming != null) {
            promoteUpcoming()
            return
        }
        track.finished = true
        sink.drain()
        current = null
        track.decoder.close()
        _state.update {
            it.copy(isPlaying = false, positionMs = it.durationMs, positionSampledAtNanos = System.nanoTime())
        }
        if (forkSkipTicket != null) return // fork: the old song ran out under a skip that has already moved the queue; ending it would move it again
        onEnded()
    }

    private fun promoteUpcoming() {
        val incoming = upcoming ?: return
        forkDropSwap() // fork: a better copy of the song being left must not land on the next one
        val plan = activePlan
        activePlan = null
        transitionEcho = null
        transitionBeatRepeat = null
        transitionReverb = null
        transitionTexture = null
        beatRepeatActive = false
        beatRepeatLatched = false
        reverbActive = false
        textureActive = false
        outgoingFilter.open()
        incomingFilter.open()
        current?.decoder?.close()
        current = incoming
        if (incoming.tempo != null && kotlin.math.abs(incoming.tempoRate - 1.0) > FORK_TEMPO_UNITY) { // fork: slowed too
            val gap = kotlin.math.abs(incoming.tempoRate - 1.0)
            val steps = ceil(gap / TEMPO_STEP_PER_BEAT).toInt().coerceAtLeast(1)
            incoming.tempoEaseStep = gap / steps
            incoming.tempoEaseEveryFrames = (
                (plan?.beatSeconds ?: 0.0).takeIf { it > 0.0 } ?: EASE_FALLBACK_SECONDS
                ).times(sink.format.sampleRate).toLong().coerceAtLeast(1L)
            incoming.tempoEaseNextFrame = incoming.tempoOutputFrames + incoming.tempoEaseEveryFrames
        }
        // The equalisers swap with the tracks they belong to. The incoming one has been filtering
        // this track for the whole crossfade, and its sections — a 60 Hz shelf above all — are
        // ringing with that audio; handing the track over to the other one instead would hand it
        // the outgoing track's history at the seam.
        val promoted = equalizerIncoming
        equalizerIncoming = equalizer
        equalizer = promoted
        // Whichever is now idle starts the next incoming track clean.
        equalizerIncoming.reset()
        upcoming = null
        nextSong = null
        fadeRemaining = 0
        speedProcessor?.reset()
        // Where the incoming track really is, not where it starts. Published as
        // its start left the scrubber and the lyrics a whole fade behind the
        // audio, which only a manual seek could put right.
        // Less what is still queued in the sink: [playhead] restarts from the played-frame count
        // here, so the queued audio — this track's — is counted again as it plays out.
        forkReconcileIncoming(incoming) // fork: ForkCueCorrection
        val handoverUs = incoming.startUs + incomingElapsedUs() - queuedSourceUs(deckRate(incoming))
        resetPlayhead(incoming, handoverUs)
        DesktopTrackLog.log(
            "transition complete: '${incoming.song.title}' resumes at " +
                "${"%.1f".format(handoverUs / 1_000_000.0)}s " +
                "(cued ${"%.1f".format(incoming.startUs / 1_000_000.0)}s " +
                "+ ${"%.1f".format(incomingElapsedUs() / 1_000_000.0)}s blended)",
        )
        publishTrack(incoming, isPlaying = !paused, positionUs = handoverUs)
        incomingSourceFrames = 0.0
        val announced = displaySwitched
        endBlend()
        nextDeckOpenId = null
        blendActive = false
        if (!announced) onCrossfaded(incoming.song)
    }

    /**
     * Moves the player onto the incoming song halfway through the blend: its title, its
     * artwork, its position — and the app's queue, through [onCrossfaded]. The audio is
     * untouched and keeps blending to the end; [promoteUpcoming] then only retires the
     * outgoing decoder.
     */
    private fun switchDisplay(incoming: Track) {
        synchronized(blendLock) { displaySwitched = true }
        DesktopTrackLog.log("blend midpoint: showing '${incoming.song.title}'")
        forkReconcileIncoming(incoming) // fork: ForkCueCorrection
        publishTrack(incoming, isPlaying = !paused, positionUs = incomingHeardUs(incoming))
        onCrossfaded(incoming.song)
    }

    /**
     * Clears everything a blend leaves behind — the scrubber's beat, the switched display — and
     * carries out a [prepareNext] that arrived while it ran. Called however the blend ended.
     */
    private fun endBlend() {
        DesktopPlayerSettings.smartMixBlend.value = null
        var had = false
        var next: Song? = null
        synchronized(blendLock) {
            displaySwitched = false
            had = hasDeferredNext
            next = deferredNext
            hasDeferredNext = false
            deferredNext = null
        }
        if (had) prepareNext(next)
    }

    /** Source time of audio written to the sink but not yet heard. */
    private fun queuedSourceUs(deckRate: Double = 1.0): Long {
        val rate = sink.format.sampleRate
        if (rate <= 0) return 0L
        val queued = (framesWritten - sink.framesPlayed()).coerceAtLeast(0L)
        return (queued * 1_000_000L / rate * playbackSpeed * deckRate).toLong()
    }

    /** Where in the incoming track the listener is right now, mid-blend. */
    private fun incomingHeardUs(incoming: Track): Long =
        (incoming.startUs + incomingElapsedUs() - queuedSourceUs(deckRate(incoming))).coerceAtLeast(incoming.startUs)

    /**
     * Tells the shared scrubber where the blend's beats fall — see Android's
     * `CrossfadeController.publishBlend`, whose rules this follows: the grid of whichever song is
     * the louder, placed against what is heard rather than what is decoded, and only re-sent when
     * it has moved further than a tick's jitter.
     */
    private fun publishBlend(playing: Boolean) {
        if (!automixEnabled) return
        val track = current ?: return
        val incoming = upcoming ?: return
        val last = DesktopPlayerSettings.smartMixBlend.value
        if (last != null && !playing && !last.playing) return
        val listenerSpeed = playbackSpeed.toDouble().takeIf { it > 0.0 } ?: 1.0
        val outgoingHeardUs = _state.value.let { state ->
            if (state.song?.videoId == track.song.videoId) state.positionMs * 1_000 else null
        } ?: outgoingHeardUs()
        val sides = listOf(
            Triple(analyzer.analysisFor(track.song.videoId), outgoingHeardUs, listenerSpeed * track.tempoRate),
            Triple(analyzer.analysisFor(incoming.song.videoId), incomingHeardUs(incoming), listenerSpeed * incoming.tempoRate),
        ).let { if (displaySwitched) it.reversed() else it }
        val side = sides.firstOrNull { (analysis, _, _) -> analysis.beatInterval > 0.0 || analysis.bpm > 0.0 }
        var beatMs = 0f
        var anchor = 0L
        if (side != null) {
            val (analysis, heardUs, speed) = side
            val beat = if (analysis.beatInterval > 0.0) analysis.beatInterval else 60.0 / analysis.bpm
            val sinceBeat = ((heardUs / 1_000_000.0 - analysis.firstBeat) % beat + beat) % beat
            beatMs = (beat * 1000.0 / speed).toFloat()
            anchor = System.nanoTime() - (sinceBeat / speed * 1e9).toLong()
        }
        if (last != null && last.playing == playing && sameGrid(last, beatMs, anchor)) return
        DesktopPlayerSettings.smartMixBlend.value = MixBlend(beatMs = beatMs, beatAnchorNanos = anchor, playing = playing)
    }

    /** The outgoing track's heard position, from the sink's clock — [publishPosition]'s sum. */
    private fun outgoingHeardUs(): Long = playhead.at(sink.framesPlayed())

    private fun sameGrid(last: MixBlend, beatMs: Float, anchor: Long): Boolean {
        if (beatMs <= 0f || last.beatMs <= 0f) return beatMs <= 0f && last.beatMs <= 0f
        if (kotlin.math.abs(beatMs - last.beatMs) > beatMs * 0.005f) return false
        val beatNanos = (beatMs * 1_000_000.0).toLong().coerceAtLeast(1L)
        val offset = Math.floorMod(anchor - last.beatAnchorNanos, beatNanos)
        return minOf(offset, beatNanos - offset) <= ANCHOR_TOLERANCE_NANOS
    }

    private fun drainCommands() {
        while (true) {
            when (val command = commands.poll() ?: return) {
                is Command.Start -> startTrack(command.track, command.playWhenReady)
                is Command.ReplaceCurrent -> replaceCurrent(command.track, command.playWhenReady)
                is Command.Upcoming -> {
                    upcoming?.decoder?.close()
                    upcoming = command.track
                    nextDeckOpenId = command.track.song.videoId
                }
                is Command.ReplaceUpcoming -> {
                    val track = command.track
                    if (upcoming?.song?.videoId == track.song.videoId && fadeRemaining <= 0 && !displaySwitched && forkLoopRun == null) { // fork: not under a loop mix's decks
                        DesktopTrackLog.log(
                            "incoming '${track.song.title}' upgraded to " +
                                "${track.stream.format.summary.ifBlank { "another rendition" }} before its transition",
                        )
                        upcoming?.decoder?.close()
                        upcoming = track
                        settleIncomingSearch(track.song)
                    } else {
                        // The blend began while it was opening: it is heard on the playing deck instead.
                        track.decoder.close()
                        playingUpgrade(track.song, track.stream)
                    }
                }
                is Command.SeekTo -> performSeek(command.millis)
                is Command.MixNow -> startSkipMix(command.fadeSeconds)
                is Command.ForkSkip -> forkStartSkip(command)
                Command.Reconfigure -> { forkLoopBail("the output was reopened", settle = true); reconfigureSink() } // fork
                Command.ClearUpcoming -> {
                    forkLoopBail("the next song changed", settle = true) // fork
                    // fork: past the switch the listener is on the incoming song (a skip's blend is from its first frame); a setting turned off mid-blend ends the blend there rather than dropping that song and playing on the old one under its title.
                    if (displaySwitched && upcoming != null) promoteUpcoming()
                    upcoming?.decoder?.close()
                    upcoming = null
                    nextDeckOpenId = null
                    val wasBlending = fadeRemaining > 0
                    fadeRemaining = 0
                    blendActive = false
                    if (wasBlending) endBlend()
                }
                Command.Flush -> {
                    forkLoopBail("a new song", settle = false) // fork
                    forkDropSwap() // fork
                    forkDropHeldUpgrade() // fork
                    current?.decoder?.close()
                    current = null
                    upcoming?.decoder?.close()
                    upcoming = null
                    nextDeckOpenId = null
                    fadeRemaining = 0
                    blendActive = false
                    sink.flush()
                    framesWritten = sink.framesPlayed()
                    endBlend()
                }
            }
        }
    }

    /**
     * Starts a skip-mix on the audio thread: the already-open [upcoming] deck blends in over the
     * remaining seconds, capped at the requested length, instead of the outgoing track cutting. The
     * request is gated in [beginSkipMix], which only queues it when the incoming deck is open and no
     * blend is running; this just builds the forced plan the shared [armIncomingBlend] arms with.
     */
    private fun startSkipMix(fadeSeconds: Int) {
        forkLoopYieldToSkip() // fork: ForkDesktopLoopMix
        val outgoing = current ?: return
        val incoming = upcoming ?: return
        if (fadeRemaining > 0) return
        val positionMs = _state.value.positionMs
        val remainingOutMs = (outgoing.decoder.durationUs ?: 0L).let { if (it > 0) it / 1_000 - positionMs else Long.MAX_VALUE }
        val remainingInMs = incoming.decoder.durationUs?.let { it / 1_000 } ?: Long.MAX_VALUE
        val fade = fadeSeconds.toDouble()
            .coerceAtMost(remainingOutMs / 1000.0)
            .coerceAtMost(remainingInMs / 1000.0)
            .coerceAtLeast(2.0)
        val nowSec = positionMs / 1000.0
        val plan = TransitionPlan(
            shouldStart = true,
            transitionStart = nowSec,
            transitionEnd = nowSec + fade,
            fadeSeconds = fade,
            transitionStyle = TransitionStyle.EQUAL_POWER,
        )
        DesktopTrackLog.log("skip-mix into '${incoming.song.title}': ${"%.1f".format(fade)}s")
        armIncomingBlend(incoming, plan)
    }

    /**
     * Installs a better rendition without treating it as a new queue item.
     *
     * The old full [Command.Flush] path also closed [upcoming]. Analysis is keyed by song id, so
     * Stats for nerds still said both tracks were measured even though the incoming decoder had
     * vanished; with no incoming deck there could be neither a seekbar marker nor an Automix.
     */
    private fun replaceCurrent(track: Track, playWhenReady: Boolean) {
        if (forkHoldUpgrade(track)) return // fork: kept for after the mix instead of closed (ForkMixUpgradeHold)
        // An upgrade that arrives after the handoff has begun is no longer worth interrupting the
        // audible transition. Keep the running pair intact and retire the unused decoder.
        if (fadeRemaining > 0 || displaySwitched) {
            track.decoder.close()
            return
        }
        if (forkLoopRefusesUpgrade(track)) return // fork: ForkDesktopLoopMix
        if (forkQueueSwap(track)) return // fork: crossfade onto the better copy instead of cutting over
        sink.flush()
        framesWritten = sink.framesPlayed()
        activePlan = null
        transitionEcho = null
        transitionBeatRepeat = null
        transitionReverb = null
        transitionTexture = null
        beatRepeatActive = false
        beatRepeatLatched = false
        reverbActive = false
        textureActive = false
        outgoingFilter.open()
        incomingFilter.open()
        startTrack(track, playWhenReady)
    }

    // ---- fork: a quality upgrade crossfades instead of cutting ----------------------------------

    /**
     * Fork: the better copy of the playing song, fading in over [FORK_SWAP_FRAMES_MS] while the old
     * copy fades out; audio thread only. [forkWaitingSwap] holds one that arrived while a beatmatch
     * stretch was still easing out, started once it has.
     *
     * The upgrade used to go through [startTrack]: the sink was flushed (the queued half-second or so
     * of music dropped, heard as the sound cutting out when an addon's lossless copy replaced
     * YouTube's), the output reopened whenever the copy's sample rate differed (a longer cut), and
     * the Automix plan thrown away (heard as a mix that stopped and started again). The copies are
     * the same recording at the same point, so a short equal-power cross between them is not heard;
     * [readSource] already brings either one to the stream's rate.
     */
    private var forkSwap: Track? = null
    private var forkWaitingSwap: Track? = null
    /** Seeked [FORK_SWAP_LEAD_US] ahead, decoding there, until the write point reaches [forkArmedAtUs]. */
    private var forkArmedSwap: Track? = null
    private var forkArmedAtUs = 0L
    private var forkArmTries = 0
    private var forkSwapDone = 0L
    private var forkSwapTotal = 0L

    /** Takes [track] as a crossfaded swap, or false to fall back to the old cut-over. */
    private fun forkQueueSwap(track: Track): Boolean {
        val playing = current ?: return false
        if (paused || !sink.isOpen || sink.format.sampleRate <= 0) return false
        if (playing.song.videoId != track.song.videoId) return false
        if (track.decoder.outputFormat.channels != sink.format.channels) return false
        // With the listener's speed or Skip silence in play, the decoder runs ahead of what is
        // written by what those still hold, so the write point is not where the new copy belongs.
        if (playbackSpeed != 1f || skipSilenceEnabled) return false
        // A swap already fading in is left to finish; this one waits behind it.
        forkWaitingSwap?.decoder?.close()
        forkWaitingSwap = track
        forkArmTries = 0
        forkStartWaitingSwap(playing)
        return true
    }

    /**
     * Seeks the waiting copy a second ahead of the write point, off the audio thread's way: the
     * decoder's own worker does the seek (over the network for an addon's copy), and the cross only
     * starts once the write point has caught up with audio already decoded there ([forkMixSwap]).
     * Seeking to the write point itself left the next read waiting on that worker, an underrun.
     */
    private fun forkStartWaitingSwap(playing: Track) {
        forkArmedSwap?.let { if (it.song.videoId != playing.song.videoId) forkDropSwap() }
        val track = forkWaitingSwap ?: return
        if (track.song.videoId != playing.song.videoId) {
            forkDropSwap()
            return
        }
        if (forkSwap != null || forkArmedSwap != null || playing.tempo != null) return
        forkWaitingSwap = null
        val atUs = playhead.peek(framesWritten) + FORK_SWAP_LEAD_US
        track.decoder.seek(atUs)
        track.startUs = atUs
        forkArmedSwap = track
        forkArmedAtUs = atUs
        forkArmTries++
    }

    /** Closes any pending swap, except [keep] (the track being started from it). */
    private fun forkDropSwap(keep: Track? = null) {
        listOfNotNull(forkSwap, forkArmedSwap, forkWaitingSwap).filter { it !== keep }.forEach { it.decoder.close() }
        forkSwap = null
        forkArmedSwap = null
        forkWaitingSwap = null
    }

    /** A seek mid-swap: one fading in becomes the playing copy (then is seeked); one not yet in waits again. */
    private fun forkSwapBeforeSeek() {
        val playing = current ?: return forkDropSwap()
        val mixing = forkSwap
        if (mixing != null && mixing.song.videoId == playing.song.videoId) {
            forkFinishSwap()
            return
        }
        val armed = forkArmedSwap
        forkArmedSwap = null
        if (armed != null && forkWaitingSwap == null && armed.song.videoId == playing.song.videoId) {
            forkWaitingSwap = armed
        } else {
            armed?.decoder?.close()
        }
    }

    /** Mixes the better copy into [block] (the old copy's), equal power, and hands over once it is in. */
    private fun forkMixSwap(block: FloatArray, count: Int) {
        forkEnterArmedSwap()
        val track = forkSwap ?: return
        val channels = sink.format.channels.coerceAtLeast(1)
        val other = readIncoming(track, count)
        val otherCount = if (other == null) 0 else incomingReadCount
        val frames = count / channels
        for (frame in 0 until frames) {
            val t = ((forkSwapDone + frame).toDouble() / forkSwapTotal).coerceIn(0.0, 1.0)
            val out = kotlin.math.cos(t * Math.PI / 2).toFloat()
            val inn = kotlin.math.sin(t * Math.PI / 2).toFloat()
            for (c in 0 until channels) {
                val i = frame * channels + c
                val add = if (other != null && i < otherCount) other[i] else 0f
                block[i] = block[i] * out + add * inn
            }
        }
        forkSwapDone += frames
        if (forkSwapDone >= forkSwapTotal) forkFinishSwap()
    }

    /**
     * Starts the cross once the write point (where this block goes) has reached the armed copy's
     * seek point and that copy has audio decoded: the few frames between are read off and dropped,
     * so both copies line up. A copy that has nothing decoded two seconds past its point is armed
     * again further on, and after [FORK_SWAP_TRIES] tries given up, keeping the old copy playing.
     */
    private fun forkEnterArmedSwap() {
        val track = forkArmedSwap ?: return
        val atUs = playhead.peek(framesWritten)
        if (atUs < forkArmedAtUs) return
        val rate = sink.format.sampleRate
        val channels = sink.format.channels.coerceAtLeast(1)
        val lateUs = atUs - forkArmedAtUs
        if (!track.decoder.forkHasSamples() || lateUs > FORK_SWAP_LATE_US) {
            if (lateUs <= FORK_SWAP_LATE_US) return
            forkArmedSwap = null
            if (forkArmTries >= FORK_SWAP_TRIES) {
                DesktopTrackLog.log("better copy of '${track.song.title}' never caught up; staying on what plays")
                track.decoder.close()
                return
            }
            if (forkWaitingSwap == null) forkWaitingSwap = track else track.decoder.close()
            current?.let { forkStartWaitingSwap(it) }
            return
        }
        // Lined up on where the copy's stream really starts, not on where it was asked to (ForkCueCorrection).
        val fromUs = ForkCueCorrection.swapStart(forkArmedAtUs, track.decoder.forkLandedUs)
        if (fromUs > atUs) return
        if (atUs - fromUs > FORK_SWAP_LEAD_US + FORK_SWAP_LATE_US) {
            DesktopTrackLog.log("better copy of '${track.song.title}' landed ${(atUs - fromUs) / 1000} ms back; staying on what plays")
            forkArmedSwap = null
            track.decoder.close()
            return
        }
        val skipFrames = ((atUs - fromUs) * rate / 1_000_000L).toInt()
        var left = skipFrames * channels
        while (left > 0) {
            readIncoming(track, minOf(left, 4096 * channels)) ?: break
            left -= incomingReadCount.coerceAtLeast(1)
        }
        forkArmedSwap = null
        forkSwap = track
        forkSwapDone = 0L
        forkSwapTotal = (rate * FORK_SWAP_FRAMES_MS / 1000L).coerceAtLeast(1L)
        DesktopTrackLog.log("crossfading '${track.song.title}' onto ${track.stream.format.summary.ifBlank { "the better copy" }} at ${atUs / 1000} ms")
    }

    /** The better copy is the playing one now; the old decoder goes, the plan and the playhead stay. */
    private fun forkFinishSwap() {
        val track = forkSwap ?: return
        forkSwap = null
        val old = current
        current = track
        if (old !== track) old?.decoder?.close()
        _state.update {
            it.copy(
                streamFormat = track.decoder.measuredFormat ?: track.stream.format,
                streamSourceId = track.stream.sourceId,
            )
        }
    }

    private fun startTrack(track: Track, playWhenReady: Boolean) {
        forkDropSwap(keep = track) // fork: a swap for the song being left is stale
        // Never the one being started: a restart of the current track would otherwise close the
        // decoder it is about to read from.
        if (current !== track) current?.decoder?.close()
        current = track
        fadeRemaining = 0
        forkArmCueCheck(track, track.startUs) // fork: ForkCueCorrection

        val decoded = track.decoder.outputFormat
        // Only renegotiate when the device would actually have to change.
        val output = if (
            !sink.isOpen ||
            !sink.isUsingSelection(DesktopAudioDevices.selected.value) ||
            sink.format.sampleRate != decoded.sampleRate ||
            sink.format.channels != decoded.channels
        ) {
            sink.open(decoded.copy(bytesPerSample = precisionBytes(), isFloat = preferFloat))
        } else {
            Result.success(sink.format)
        }
        output.onFailure { failure ->
            paused = true
            DesktopTrackLog.log("audio output failed: ${failure.message}")
            _state.update { it.copy(isPlaying = false, error = "No audio output: ${failure.message}") }
            return
        }
        sink.gain = volume
        framesWritten = sink.framesPlayed()
        speedProcessor = DesktopAudioSpeed(sink.format.channels, sink.format.sampleRate)
        spatial = DesktopSpatialAudio(sink.format.channels, sink.format.sampleRate)
        spatialIncoming = DesktopSpatialAudio(sink.format.channels, sink.format.sampleRate)
        equalizer.configure(sink.format.channels, sink.format.sampleRate)
        equalizerIncoming.configure(sink.format.channels, sink.format.sampleRate)
        silence = DesktopSilenceSkipper(sink.format.channels, sink.format.sampleRate)
            .apply { enabled = skipSilenceEnabled }
        outgoingFilter.configure(sink.format.channels, sink.format.sampleRate)
        incomingFilter.configure(sink.format.channels, sink.format.sampleRate)
        resetPlayhead(track, track.startUs)
        paused = !playWhenReady
        publishTrack(track, isPlaying = playWhenReady)
    }

    /** Reopens the output on the current decoder, keeping the track playing. */
    private fun reconfigureSink() {
        val track = current ?: return
        val decoded = track.decoder.outputFormat
        // Where the next block written carries on from: what was queued on the old line is dropped
        // with it, and the decoder is already past it.
        val positionUs = playhead.peek(framesWritten)
        sink.open(decoded.copy(bytesPerSample = precisionBytes(), isFloat = preferFloat))
            .onSuccess {
                sink.gain = volume
                framesWritten = sink.framesPlayed()
                buildChain()
                // A reopened line counts frames from zero again, so the clock has to be rebased
                // onto wherever the track had got to.
                resetPlayhead(track, positionUs)
            }
            .onFailure { failure ->
                _state.update { it.copy(error = "No audio output: ${failure.message}") }
            }
    }

    private fun buildChain() {
        speedProcessor = DesktopAudioSpeed(sink.format.channels, sink.format.sampleRate)
        spatial = DesktopSpatialAudio(sink.format.channels, sink.format.sampleRate)
        spatialIncoming = DesktopSpatialAudio(sink.format.channels, sink.format.sampleRate)
        equalizer.configure(sink.format.channels, sink.format.sampleRate)
        equalizerIncoming.configure(sink.format.channels, sink.format.sampleRate)
        silence = DesktopSilenceSkipper(sink.format.channels, sink.format.sampleRate)
            .apply { enabled = skipSilenceEnabled }
        outgoingFilter.configure(sink.format.channels, sink.format.sampleRate)
        incomingFilter.configure(sink.format.channels, sink.format.sampleRate)
    }

    /** Asks for a track to be analysed, if Automix is on and there is anything to analyse. */
    private fun requestAnalysis(track: Track) {
        if (!automixEnabled) return
        val seconds = (track.decoder.durationUs ?: 0L) / 1_000_000.0
        val known = seconds.takeIf { it > 0 }
            ?: (track.song.durationText.durationSeconds())
        analyzer.request(track.song, track.stream, known)
    }

    /** Measures the pair around the playhead — the playing track first, then the one after it. */
    private fun requestAnalysisAround(track: Track) {
        if (!automixEnabled) return
        val next = upcoming
        // A track held back from read-ahead has no decoder yet, but is measured all the same.
        val held = if (next == null) nextSong else null
        if (track.song.isVideoOrigin || next?.song?.isVideoOrigin == true || held?.isVideoOrigin == true) return
        requestAnalysis(track)
        next?.let(::requestAnalysis)
        held?.let { analyzer.request(it, nextLocalStream, it.durationText.durationSeconds()) }
    }

    private fun performSeek(millis: Long) {
        forkLoopBail("a seek", settle = false) // fork: the seek itself says where to be
        // Past the midpoint the listener is looking at — and seeking in — the incoming song, so
        // the blend is finished on the spot and the seek lands on that one.
        if (displaySwitched && upcoming != null) promoteUpcoming()
        forkSwapBeforeSeek() // fork
        val track = current ?: return
        val landed = track.decoder.seek(millis * 1_000)
        // TEMP seek diagnostics.
        DesktopTrackLog.log("seek performed: ${millis}ms on '${track.song.title}' ${if (landed) "ok" else "FAILED"}")
        // The stretcher still holds audio from before the seek, which would play first; and a
        // listener who has moved the playhead is past the handoff, so the ease ends here — as it
        // does on Android.
        track.dropTempo()
        sink.flush()
        framesWritten = sink.framesPlayed()
        speedProcessor?.reset()
        spatial?.reset()
        spatialIncoming?.reset()
        // A seek is not a continuous signal, so the filters are cleared rather than left ringing
        // with the audio from before it — a strong band otherwise rings that state out over the
        // first moments of the new position.
        equalizer.reset()
        equalizerIncoming.reset()
        silence?.reset()
        resetPlayhead(track, millis * 1_000)
        forkArmCueCheck(track, millis * 1_000) // fork: ForkCueCorrection
        // Cleared by [play] once the first of the new position's audio is on its way out.
        _state.update {
            it.copy(
                positionMs = millis,
                positionSampledAtNanos = System.nanoTime(),
                seeks = it.seeks + 1,
                awaitingAudio = true,
            )
        }
    }

    /** The track a second look is running for, started as it began playing. */
    @Volatile
    private var searchingBetterFor: String? = null

    /**
     * The incoming track whose raced resolve is still waiting on a better copy — see
     * [upgradeIncoming]. Separate from [searchingBetterFor] because both can be running at once:
     * the playing track's search and the next one's.
     */
    @Volatile
    private var incomingSearchingFor: String? = null

    /** How much of the incoming track the blend has already played, in source time. */
    private fun incomingElapsedUs(): Long =
        (incomingSourceFrames * 1_000_000.0 / sink.format.sampleRate.coerceAtLeast(1)).toLong()

    private fun publishTrack(track: Track, isPlaying: Boolean, positionUs: Long = track.startUs) {
        _state.value = DesktopPlaybackState(
            song = track.song,
            isPlaying = isPlaying,
            volume = volume,
            isLoading = false,
            positionMs = positionUs / 1_000,
            positionSampledAtNanos = System.nanoTime(),
            // A track opened, or the playing one replaced, starts the playhead somewhere new.
            seeks = _state.value.seeks + 1,
            durationMs = (track.decoder.durationUs ?: 0L) / 1_000,
            // What the decoder is actually being fed, falling back to what the source promised only
            // until something has been measured.
            streamFormat = track.decoder.measuredFormat ?: track.stream.format,
            streamSourceId = track.stream.sourceId,
            searchingBetter = track.song.videoId.let { it == searchingBetterFor || it == incomingSearchingFor },
            smartAnalysis = analysisStatus(track),
        )
    }

    /** Position from what the device has actually rendered, not from what has been decoded. */
    private fun publishPosition(track: Track) {
        val format = sink.format
        if (format.sampleRate == 0) return
        if (displaySwitched) {
            val incoming = upcoming ?: return
            val positionMs = incomingHeardUs(incoming) / 1_000
            val sampledAt = System.nanoTime()
            if (_state.value.positionMs / 250 == positionMs / 250) return
            _state.update {
                it.copy(
                    positionMs = positionMs,
                    positionSampledAtNanos = sampledAt,
                    isPlaying = !paused,
                    mixing = isSmartMixInProgress(),
                )
            }
            return
        }
        val positionMs = (playhead.at(sink.framesPlayed()) / 1_000).coerceAtLeast(0)
        // Taken with the reading, here on the audio thread: see DesktopPlaybackState.
        val sampledAt = System.nanoTime()
        if (_state.value.song?.videoId != track.song.videoId) return
        requestAnalysisAround(track)
        val status = analysisStatus(track)
        // Published when the clock moves *or* when Automix's answer does.
        val moved = _state.value.positionMs / 250 != positionMs / 250
        if (!moved && _state.value.smartAnalysis == status) return
        _state.update {
            it.copy(
                positionMs = positionMs,
                positionSampledAtNanos = sampledAt,
                isPlaying = !paused,
                smartAnalysis = status,
                mixing = isSmartMixInProgress(),
            )
        }

        maybeStartCrossfade(track, positionMs)
    }

    /**
     * Matches Android's Automix signal: a plain equal-power fallback is still a crossfade, but it
     * does not light the Automix animation unless analysis changed the style, cue or tempo.
     */
    private fun isSmartMixInProgress(): Boolean {
        val plan = activePlan ?: return false
        return automixEnabled && fadeRemaining > 0 && (
            plan.transitionStyle == TransitionStyle.DJ_BLEND ||
                plan.transitionStyle == TransitionStyle.DJ_FILTER ||
                plan.incomingCueTime > 0.0 ||
                plan.incomingPlaybackRate != 1.0
            )
    }

    /** Both halves of the next transition, for the player's Automix line. */
    private fun analysisStatus(track: Track): SmartAnalysis = SmartAnalysis(
        current = analyzer.stateFor(track.song.videoId),
        next = analyzer.stateFor(upcoming?.song?.videoId ?: nextSong?.videoId.orEmpty()),
    )

    private fun maybeStartCrossfade(track: Track, positionMs: Long) {
        val incoming = upcoming
        if (incoming == null) {
            // A next track not open yet — held back from read-ahead, or still resolving — is
            // still planned for on its catalogue length, so the marker shows from the start of the
            // song rather than only once the incoming deck is open.
            val held = nextSong
            val duration = (track.decoder.durationUs ?: 0L) / 1_000
            if (held == null || duration <= 0) {
                _state.update { it.copy(transitionWindow = null) }
                return
            }
            val nextMs = (held.durationText.durationSeconds() * 1_000).toLong()
            val plan = transitionPlan(track, held, nextMs, positionMs, duration)
            publishTransitionWindow(track, plan, duration)
            armAddonUpcoming(held, plan, positionMs, duration)
            return
        }
        if (fadeRemaining > 0) return
        val duration = (track.decoder.durationUs ?: return) / 1_000
        if (duration <= 0) return

        val plan = transitionPlan(track, incoming.song, (incoming.decoder.durationUs ?: 0L) / 1_000, positionMs, duration)
        publishTransitionWindow(track, plan, duration)
        if (forkLoopConsider(track, incoming, positionMs, duration)) return // fork: a loop mix holds the blend (ForkDesktopLoopMix)

        if (!plan.shouldStart || plan.blocked) return
        armIncomingBlend(incoming, plan)
    }

    /**
     * Open the blend that is already armed on both decks. Shared by the position-gated planner and
     * by a manual skip that asks to mix now: the only difference is which [TransitionPlan] is handed
     * in, so both routes get the same cue, tempo, echo and texture arming.
     */
    private fun armIncomingBlend(incoming: Track, plan: TransitionPlan) {
        val seconds = plan.fadeSeconds.takeIf { it > 0 } ?: return
        // A planned transition says where the incoming track should be entered, which is the whole
        // difference between Automix and a crossfade.
        if (plan.incomingCueTime > 0) {
            val cueUs = (plan.incomingCueTime * 1_000_000).toLong()
            incoming.decoder.seek(cueUs)
            // The track now begins here, and every position reported for it is measured from it.
            // Left at zero, the scrubber and the lyrics ran the whole cue behind the audio for the
            // rest of the track — which only a manual seek could put right.
            incoming.startUs = cueUs
        }
        DesktopTrackLog.log(
            "transition into '${incoming.song.title}': ${"%.1f".format(seconds)}s" +
                ", ${plan.transitionStyle}" +
                (if (plan.transitionBeats > 0) ", ${plan.transitionBeats} beats" else "") +
                (if (plan.incomingCueTime > 0) ", cued at ${"%.1f".format(plan.incomingCueTime)}s" else ""),
        )
        fadeTotal = (seconds * sink.format.sampleRate * sink.format.channels).toInt()
        val echoTail = (echoTailSeconds(plan.echoSeconds) * sink.format.sampleRate * sink.format.channels).toInt()
        // The beat-roll and reverb-tail are the subtler alternative to a planned echo, so they only
        // arm on a smart DJ transition the planner gave no echo to, and never stack with one.
        val djStyle = plan.transitionStyle == TransitionStyle.DJ_BLEND ||
            plan.transitionStyle == TransitionStyle.DJ_FILTER
        val noEcho = plan.echoSeconds <= 0.0
        beatRepeatActive = noEcho && djStyle && plan.beatSeconds > 0.0 && plan.transitionBeats >= 4
        reverbActive = noEcho && djStyle
        val textureKind = if (noEcho && djStyle) chooseTexture(plan) else TransitionTexture.NONE
        textureActive = textureKind != TransitionTexture.NONE
        beatRepeatLatched = false
        transitionBeatRepeat = if (beatRepeatActive) {
            val division = if (plan.beatSeconds < EIGHTH_NOTE_MAX_BEAT_SECONDS) 2.0 else 1.0
            DesktopTransitionBeatRepeat(sink.format.sampleRate, sink.format.channels)
                .also { it.configure(plan.beatSeconds / division) }
        } else null
        transitionReverb = if (reverbActive) {
            DesktopTransitionReverb(sink.format.sampleRate, sink.format.channels).also { it.reset() }
        } else null
        transitionTexture = if (textureActive) {
            newTexture(textureKind, sink.format.sampleRate, sink.format.channels).also { it.reset() }
        } else null
        val fxTail = (
            (if (beatRepeatActive) ROLL_TAIL_SECONDS else 0.0) +
                (if (reverbActive) REVERB_TAIL_SECONDS else 0.0) +
                (if (textureActive) TEXTURE_TAIL_SECONDS else 0.0)
            ).let { (it * sink.format.sampleRate * sink.format.channels).toInt() }
        transitionTotal = fadeTotal + echoTail + fxTail
        fadeRemaining = transitionTotal
        incomingSourceFrames = 0.0
        activePlan = plan
        incoming.tempo = if (kotlin.math.abs(plan.incomingPlaybackRate - 1.0) > FORK_TEMPO_UNITY) { // fork: slowed too
            DesktopTempoBuffer(sink.format.channels, sink.format.sampleRate).also {
                it.speed = plan.incomingPlaybackRate.toFloat()
            }
        } else null
        // fork: was coerceAtLeast(1.0); a rate within FORK_TEMPO_UNITY of 1 is 1, or a deck that got no
        // stretcher would never read as eased out and stay on its buffer for the whole song
        incoming.tempoRate = plan.incomingPlaybackRate.coerceIn(MIN_INCOMING_RATE, MAX_INCOMING_RATE)
            .let { if (kotlin.math.abs(it - 1.0) <= FORK_TEMPO_UNITY) 1.0 else it }
        transitionEcho = if (plan.echoSeconds > 0.0) {
            DesktopTransitionEcho(sink.format.sampleRate, sink.format.channels).also { it.configure(plan.echoSeconds) }
        } else null
        outgoingFilter.flush()
        incomingFilter.flush()
        blendActive = true
    }

    /**
     * Which texture the outgoing tail gets for this plan, read from what the planner already knows
     * about the pair rather than from a user choice — one effect is applied per transition, never a
     * stack. Fast, sparse material takes the bit-crush; a tempo gap between the two grids takes the
     * phaser's whoosh to bridge it; slow, sustained material takes the vibrato's pitch sway; two
     * vocals singing over each other take the stereo spread to pull them apart. The flanger is the
     * default when nothing more specific applies.
     */
    private fun chooseTexture(plan: TransitionPlan): TransitionTexture {
        val bpm = plan.outgoingBpm
        if (bpm <= 0.0) return TransitionTexture.FLANGER
        val gap = kotlin.math.abs(plan.incomingBpm - plan.outgoingBpm)
        return when {
            plan.vocalOverlap >= 0.5 -> TransitionTexture.STEREO
            bpm >= 128.0 && plan.vocalOverlap <= 0.15 -> TransitionTexture.CRUSH
            gap > 4.0 -> TransitionTexture.PHASER
            bpm < 95.0 -> TransitionTexture.VIBRATO
            else -> TransitionTexture.FLANGER
        }
    }

    private fun newTexture(
        kind: TransitionTexture,
        sampleRate: Int,
        channels: Int,
    ): DesktopTransitionTexture = when (kind) {
        TransitionTexture.CRUSH -> DesktopTransitionCrush(sampleRate, channels)
        TransitionTexture.PHASER -> DesktopTransitionPhaser(sampleRate, channels)
        TransitionTexture.VIBRATO -> DesktopTransitionVibrato(sampleRate, channels)
        TransitionTexture.STEREO -> DesktopTransitionStereo(sampleRate, channels)
        else -> DesktopTransitionFlanger(sampleRate, channels)
    }

    /** The marker on the scrubber, showing where the mix will happen before it happens. */
    private fun publishTransitionWindow(track: Track, plan: TransitionPlan, duration: Long) {
        forkLastPlan = plan // fork: the stats line's planned mix (ForkAutomixReport)
        val status = analysisStatus(track)
        val markable = !plan.blocked &&
            plan.markerVisible &&
            status.current == TrackAnalysisState.ANALYSED &&
            status.next in MEASURED_ENOUGH_TO_ENTER_ON
        _state.update {
            it.copy(
                transitionWindow = if (markable) {
                    TransitionWindow(
                        start = (plan.transitionStart * 1_000.0 / duration).toFloat().coerceIn(0f, 1f),
                        end = (plan.transitionEnd * 1_000.0 / duration).toFloat().coerceIn(0f, 1f),
                    )
                } else {
                    null
                },
            )
        }
    }

    /**
     * Resolves the held-back addon track once the transition into it is [ADDON_ARM_LEAD_MS] away —
     * Android's arming, with a longer lead because an addon is slower to answer than a cache.
     *
     * The plan is made on the next song's catalogue length, since it has no decoder yet; the
     * analysis it reads is already there, measured on YouTube Opus. A track that would not blend
     * is armed the same distance before its end, so the hand-over is still gapless.
     */
    private fun armAddonUpcoming(song: Song, plan: TransitionPlan, positionMs: Long, duration: Long) {
        if (awaitingArm.get() !== song) return
        val startMs = if (!plan.blocked && plan.fadeSeconds > 0) {
            (plan.transitionStart * 1_000).toLong()
        } else {
            duration - transitionSecondsFor(song) * 1_000L
        }
        if (startMs - positionMs > ADDON_ARM_LEAD_MS) return
        // Whichever of this thread and a newer [prepareNext] gets there first owns it.
        if (!awaitingArm.compareAndSet(song, null)) return
        DesktopTrackLog.log(
            "arming '${song.title}' ${"%.1f".format((startMs - positionMs).coerceAtLeast(0) / 1_000.0)}s " +
                "before its transition",
        )
        resolveUpcoming(song, race = true)
    }

    /** What the shared planner makes of this pair. */
    private fun transitionPlan(
        track: Track,
        next: Song,
        nextDurationMs: Long,
        positionMs: Long,
        durationMs: Long,
    ): TransitionPlan {
        val manualSeconds = crossfadeSeconds
        val smart = automixEnabled
        if (!smart && manualSeconds <= 0) return TransitionPlan()
        return planTransition(
            analysis = analyzer.analysisFor(track.song.videoId),
            nextAnalysis = analyzer.analysisFor(next.videoId),
            currentTrack = track.song.transitionInfo(durationMs),
            nextTrack = next.transitionInfo(nextDurationMs),
            currentTime = positionMs / 1_000.0,
            duration = durationMs / 1_000.0,
            fadeSeconds = if (manualSeconds > 0) manualSeconds.toDouble() else AUTOMIX_FALLBACK_SECONDS.toDouble(),
            mode = if (smart) CrossfadeMode.SMART else CrossfadeMode.STANDARD,
            advanced = smart,
            // A fixed phrase length only means anything under Automix; a manual
            // crossfade keeps its slider length.
            phraseBars = if (smart) automixPhraseBars else 0,
            // The last/first 30 s window; the phone's planner calls leave it off.
            mixWindow = true,
        )
    }

    // ---- fork: Crossfade on skip, see ForkCrossfadeOnSkip ----------------

    /**
     * Who owns the skip being opened: set at the press, gone once the audio thread has taken it or
     * anything newer has replaced it. Everything on the way checks it rather than being cancelled:
     * a cancelled coroutine loses a decoder that finished opening under it, with its thread.
     */
    @Volatile private var forkSkipTicket: Any? = null

    /**
     * Next or Previous, the queue already moved onto [song] by the caller. The player moves at once
     * while the old song keeps playing, and the two blend over [fadeSeconds] once [song] is open.
     *
     * Only from a song really playing with nothing in flight; everything else, and everything that
     * goes wrong on the way, is an ordinary [load]. That is what makes a skip impossible to lose:
     * the queue is already right, so any path that cannot blend just opens the same song.
     */
    fun forkSkipTo(song: Song, playWhenReady: Boolean, fadeSeconds: Int) {
        val now = _state.value
        val playingId = now.song?.videoId.takeIf { now.isPlaying && !now.isLoading }
        // A press during a blend, or while one is opening, ends it with a cut, as on the phone.
        if (fadeSeconds <= 0 || !playWhenReady || paused || playingId == null || playingId == song.videoId ||
            blendActive || displaySwitched || fadeRemaining > 0
        ) {
            load(song, playWhenReady)
            return
        }
        retryingSongId = null
        val ticket = Any()
        forkSkipTicket = ticket
        resolveJob?.cancel()
        upgradeJob?.cancel()
        searchingBetterFor = null
        val deckOpen = nextDeckOpenId == song.videoId
        if (!deckOpen) {
            nextResolveJob?.cancel()
            incomingSearchingFor = null
            // The deck that was meant to follow the old song must not be what its end falls into.
            clearUpcoming()
        }
        // Still "playing": something is. Until the blend starts the old song's own ticks stop here,
        // [publishPosition] answering only for the song on the state.
        _state.value = DesktopPlaybackState(song = song, isPlaying = true, volume = volume, isLoading = true, seeks = now.seeks + 1)
        if (deckOpen) {
            commands += Command.ForkSkip(song, null, fadeSeconds, ticket)
            return
        }
        scope.launch {
            delay(ForkCrossfadeOnSkip.ARM_TIMEOUT_MS)
            forkSkipPlainly(song, ticket)
        }
        scope.launch {
            val localUrl = DesktopDownloadManager.savedFile(song)?.toAbsolutePath()?.toString()
            val live = (
                localUrl?.let { Result.success(DesktopLiveResolution(DesktopStream(it))) }
                    ?: DesktopMusicSources.resolveLive(song, audioQuality, refusedSources[song.videoId])
                ).getOrNull()
            if (live == null || forkSkipTicket !== ticket) {
                live?.pendingSubstitute?.cancel()
                forkSkipPlainly(song, ticket)
                return@launch
            }
            val track = openTrack(song, live.stream, startAtMs = 0).getOrNull()
            if (track == null || forkSkipTicket !== ticket) {
                track?.decoder?.close()
                live.pendingSubstitute?.cancel()
                forkSkipPlainly(song, ticket)
                return@launch
            }
            commands += Command.ForkSkip(song, track, fadeSeconds, ticket)
            live.pendingSubstitute?.let { upgradeIncoming(song, track.stream, it) }
        }
    }

    /** The cut, unless something newer has taken over; the failure itself is [load]'s to report. */
    private fun forkSkipPlainly(song: Song, ticket: Any) {
        if (forkSkipTicket === ticket) load(song, playWhenReady = !paused)
    }

    /** On the audio thread: the blend [forkSkipTo] asked for, or the cut when it can't run now. */
    private fun forkStartSkip(command: Command.ForkSkip) {
        forkDropSwap() // fork: see promoteUpcoming
        if (forkSkipTicket !== command.ticket) {
            command.track?.decoder?.close()
            return
        }
        forkLoopYieldToSkip() // fork: ForkDesktopLoopMix
        val outgoing = current
        val incoming = command.track ?: upcoming?.takeIf { it.song.videoId == command.song.videoId }
        if (outgoing == null || incoming == null || paused || fadeRemaining > 0 || outgoing.song.videoId == command.song.videoId) {
            // A deck it brought along is closed; an open one goes with the [load]'s flush.
            command.track?.decoder?.close()
            load(command.song, playWhenReady = !paused)
            return
        }
        if (incoming !== upcoming) {
            upcoming?.decoder?.close()
            upcoming = incoming
        }
        nextDeckOpenId = incoming.song.videoId
        val plan = ForkCrossfadeOnSkip.plan(
            positionMs = (outgoingHeardUs() / 1_000).coerceAtLeast(0L),
            outgoingUs = outgoing.decoder.durationUs,
            incomingUs = incoming.decoder.durationUs,
            seconds = command.fadeSeconds,
        )
        armIncomingBlend(incoming, plan)
        // The blend's halfway switch, made at its first frame: the rest of the engine already knows
        // what that means, [prepareNext] held, seeks landing on this song, position counted on it.
        // No [onCrossfaded]: the caller has moved the queue, and moving it again would step on past
        // a song queued twice in a row. The ticket goes last, so [prepareNext] finds one or the other.
        synchronized(blendLock) {
            displaySwitched = true
            forkSkipTicket = null
        }
        publishTrack(incoming, isPlaying = true, positionUs = incomingHeardUs(incoming))
    }

    // ---- fork: the Automix loop mix, see ForkDesktopLoopMix -------------------------------------
    //
    // Automix's top rung, as on the phone: a pair with clean instrumental bars at the seam loops them
    // while one tempo ramps between the songs. Tried before any blend; a pair it can't do gets the
    // planner's blend exactly as before. Everything here runs on the audio thread, on one frame count:
    // the outgoing song is spliced into the renderer at its loop's sample, the renderer's blocks are
    // written as the song's would be (through the same widener and equaliser), and the incoming deck,
    // seeked ahead of time and lined up to the renderer's own sample, crosses in for the last 25 ms.

    private val forkLoop = ForkDesktopLoopMix(scope)

    /** The loop mix being run, from arming to the incoming song's takeover, and its two decks. */
    private var forkLoopRun: ForkLoopMixSession? = null
    private var forkLoopOut: Track? = null
    private var forkLoopIn: Track? = null

    /** From [maybeStartCrossfade]: whether a loop mix holds this pair's blend; arms it once its decks are built. */
    private fun forkLoopConsider(track: Track, incoming: Track, positionMs: Long, durationMs: Long): Boolean {
        forkLoopRun?.let { run ->
            forkLoopMarker(run.plan.out.loopStart, run.plan.out.loopStart + run.clock.releaseAt, durationMs)
            return true
        }
        val hold = forkLoop.consider(
            ForkLoopPair(
                outSong = track.song,
                inSong = incoming.song,
                outStream = track.stream,
                inStream = incoming.stream,
                outAnalysis = analyzer.analysisFor(track.song.videoId),
                inAnalysis = analyzer.analysisFor(incoming.song.videoId),
                outState = analyzer.stateFor(track.song.videoId),
                inState = analyzer.stateFor(incoming.song.videoId),
                outDurationSec = durationMs / 1000.0,
                inDurationSec = (incoming.decoder.durationUs ?: 0L) / 1e6,
                positionSec = positionMs / 1000.0,
                sampleRate = sink.format.sampleRate,
                channels = sink.format.channels,
                automix = automixEnabled,
                inParty = DesktopListenTogether.state.value.inParty,
                speed = playbackSpeed,
                skipSilence = skipSilenceEnabled,
                deckRate = deckRate(track),
                swapping = forkSwap != null || forkArmedSwap != null || forkWaitingSwap != null,
            ),
        ) ?: return false
        forkLoopMarker(hold.startSec, hold.endSec, durationMs)
        val built = forkLoop.takeBuilt(track.song.videoId, incoming.song.videoId) ?: return true
        if (built.outUrl != track.stream.url || built.inUrl != incoming.stream.url || built.sampleRate != sink.format.sampleRate) {
            forkLoop.fallBack("a deck changed copies while it was built")
            return false
        }
        forkLoopArm(built, track, incoming)
        return true
    }

    /** The scrubber's marker over the loop mix, in place of the blend's [publishTransitionWindow] just set. */
    private fun forkLoopMarker(startSec: Double, endSec: Double, durationMs: Long) {
        if (durationMs <= 0) return
        val window = TransitionWindow(
            start = (startSec * 1_000.0 / durationMs).toFloat().coerceIn(0f, 1f),
            end = (endSec * 1_000.0 / durationMs).toFloat().coerceIn(0f, 1f),
        )
        if (_state.value.transitionWindow != window) _state.update { it.copy(transitionWindow = window) }
    }

    /** The decks are built: the incoming deck is seeked to wait at its handover, and the splice is armed. */
    private fun forkLoopArm(run: ForkLoopMixSession, track: Track, incoming: Track) {
        incoming.dropTempo()
        incoming.decoder.seek(run.inStartUs)
        incoming.startUs = run.inStartUs
        forkLoopRun = run
        forkLoopOut = track
        forkLoopIn = incoming
        // A skip now cuts rather than blending over the mix (beginSkipMix, forkSkipTo).
        blendActive = true
        DesktopTrackLog.log(
            "loop mix armed: '${track.song.title}' -> '${incoming.song.title}', loop at " +
                "${"%.2f".format(run.plan.out.loopStart)}s, incoming waits at ${"%.2f".format(run.inStartUs / 1e6)}s, " +
                "mix ${"%.1f".format(run.clock.elementAt)}s · ${ForkLoopMixRules.describe(run.plan)}",
        )
    }

    /** One step of the armed or running loop mix; false leaves the step to the ordinary path. */
    private fun forkLoopStep(track: Track): Boolean {
        val run = forkLoopRun ?: return false
        val incoming = forkLoopIn
        if (incoming == null || upcoming !== incoming || track !== forkLoopOut) {
            val spliced = run.spliced
            forkLoopBail("the pair changed under it", settle = spliced)
            return spliced
        }
        if (!run.spliced) {
            // Armed seconds ahead, so whatever [ForkLoopMixRules.refusal] turns a pair away for is
            // checked again until the splice: Automix switched off with a crossfade length still set
            // keeps the next deck, and a party joined mid-song doesn't touch it, so neither ended the mix.
            val why = when {
                !automixEnabled -> "Automix is off"
                DesktopListenTogether.state.value.inParty -> "in a Listen Together party"
                playbackSpeed != 1f -> "playback speed is not 1x"
                skipSilenceEnabled -> "skip silence is on"
                deckRate(track) != 1.0 -> "the song is still easing out of its own mix"
                forkSwap != null || forkArmedSwap != null || forkWaitingSwap != null -> "a better copy is crossing in"
                sink.format.channels != 2 || sink.format.sampleRate != run.sampleRate -> "the output changed"
                else -> null
            }
            if (why != null) {
                val engaged = run.engaged
                if (engaged) forkLoopBail(why, settle = true) else forkLoopCancel(why)
                return engaged
            }
            val writeUs = playhead.peek(framesWritten)
            if (!run.spliceNear(writeUs)) return false
            if (!run.engaged && run.mixFrameOf(writeUs) > 0) {
                forkLoopCancel("the song passed its loop before the splice")
                return false
            }
            val song = readCurrent(track)
            if (song == null) {
                forkLoopCancel("the song ended before its loop")
                return false
            }
            val count = currentReadCount
            if (count == 0) return true
            val wasEngaged = run.engaged
            run.splice(song, count, writeUs, framesWritten)
            if (!wasEngaged && run.engaged) {
                val lag = run.spliceLag
                DesktopTrackLog.log(
                    "loop mix: splicing into the decks at ${"%.3f".format(run.plan.out.loopStart)}s, outgoing deck " +
                        (if (lag != null) "%+.2f ms (r=%.3f)".format(lag * 1000.0 / run.sampleRate, run.spliceScore) else "not measured") +
                        " against its song",
                )
            }
            forkLoopWrite(track, song, count)
            publishPosition(track)
            return true
        }
        if (playbackSpeed != 1f) {
            forkLoopBail("the playback speed changed", settle = true)
            return true
        }
        val mf = framesWritten - run.originFrame
        if (mf >= run.handoverEndFrame) {
            forkLoopPromote(run, track, incoming)
            return true
        }
        val frames = run.blockFrames(mf, ForkLoopMixSession.BLOCK_FRAMES)
        val block = run.render(mf, frames)
        if (mf >= run.handoverFrame) {
            if (!run.inAligned) forkLoopLineUp(run, incoming)
            val other = readIncoming(incoming, frames * 2)
            if (other != null) {
                val got = incomingReadCount
                for (i in 0 until frames) {
                    val gain = run.inGain(mf + i)
                    val l = 2 * i
                    if (l < got) block[l] += other[l] * gain
                    if (l + 1 < got) block[l + 1] += other[l + 1] * gain
                }
            }
        }
        forkLoopWrite(track, block, frames * 2)
        forkLoopPublish(run, incoming)
        return true
    }

    /** A loop-mix block goes out as the song's own would: widened, equalised, played. */
    private fun forkLoopWrite(track: Track, block: FloatArray, count: Int) {
        widen(spatial, track, block, count)
        equalise(equalizer, block, count)
        play(track, block, count)
    }

    /**
     * Lines the incoming deck's decoder up on the renderer's incoming deck: reads off and drops the
     * frames between where its stream really landed and the sample the deck is at on the handover, so
     * the two cross on the same audio. The deck's own position is used, not the plan's: its grains may
     * sit a few milliseconds off their knots, and that is where its audio actually is.
     */
    private fun forkLoopLineUp(run: ForkLoopMixSession, incoming: Track) {
        val landed = incoming.decoder.forkLandedUs ?: incoming.startUs
        var drop = run.dropFramesFor(landed)
        if (drop < 0) {
            DesktopTrackLog.log("loop mix: the incoming deck landed ${-drop} frames past its handover; crossing in unaligned")
            drop = 0
        }
        val channels = sink.format.channels.coerceAtLeast(1)
        var left = drop * channels
        while (left > 0) {
            readIncoming(incoming, minOf(left, 4_096L * channels).toInt()) ?: break
            left -= incomingReadCount.coerceAtLeast(1)
        }
        DesktopTrackLog.log(
            "loop mix: '${incoming.song.title}' lined up on its deck at ${"%.3f".format(run.handoverTrackUs / 1e6)}s " +
                "(landed ${"%.3f".format(landed / 1e6)}s, $drop frames dropped)",
        )
    }

    /** Position, the scrubber's beat and the mid-ramp move to the incoming song, from what is heard. */
    private fun forkLoopPublish(run: ForkLoopMixSession, incoming: Track) {
        val heardMf = sink.framesPlayed() - run.originFrame
        if (!run.handedOff && heardMf >= run.handoffFrame) forkLoopHandOff(run, incoming, heardMf)
        val positionMs = run.positionMs(heardMf) ?: (playhead.at(sink.framesPlayed()) / 1_000)
        // A loop wrapping back is a deliberate jump: told as one, as the phone's `onMixPositionJump`.
        val wrapped = run.lastPositionMs >= 0 && positionMs < run.lastPositionMs - 500
        val moved = run.lastPositionMs / 250 != positionMs / 250
        run.lastPositionMs = positionMs
        if (moved || wrapped) {
            val sampledAt = System.nanoTime()
            _state.update {
                it.copy(
                    positionMs = positionMs,
                    positionSampledAtNanos = sampledAt,
                    isPlaying = !paused,
                    mixing = true,
                    seeks = if (wrapped) it.seeks + 1 else it.seeks,
                )
            }
        }
        // The scrubber's beat pulse, on the mix's own beats (they move through the ramp).
        if (!automixEnabled || heardMf < 0) return
        val m = heardMf / sink.format.sampleRate.coerceAtLeast(1).toDouble()
        val (beat, _) = run.clock.beatAt(m)
        val times = run.clock.beatTimes
        if (beat == run.lastBeat || beat !in 0 until times.size - 1) return
        run.lastBeat = beat
        DesktopPlayerSettings.smartMixBlend.value = MixBlend(
            beatMs = ((times[beat + 1] - times[beat]) * 1000).toFloat(),
            beatAnchorNanos = System.nanoTime() - ((m - times[beat]) * 1e9).toLong(),
            playing = !paused,
        )
    }

    /** Mid-ramp, heard: the player and the queue move to the incoming song (the phone's `handOff`). */
    private fun forkLoopHandOff(run: ForkLoopMixSession, incoming: Track, heardMf: Long) {
        run.handedOff = true
        synchronized(blendLock) { displaySwitched = true }
        val positionMs = run.positionMs(heardMf) ?: 0L
        run.lastPositionMs = positionMs
        DesktopTrackLog.log("loop mix: showing '${incoming.song.title}' at ${positionMs / 1000.0}s")
        publishTrack(incoming, isPlaying = !paused, positionUs = positionMs * 1_000)
        _state.update { it.copy(mixing = true) }
        onCrossfaded(incoming.song)
    }

    /** The mix played out: the incoming deck is the song, read from its decoder at the very sample its deck left off. */
    private fun forkLoopPromote(run: ForkLoopMixSession, track: Track, incoming: Track) {
        if (!run.handedOff) forkLoopHandOff(run, incoming, sink.framesPlayed() - run.originFrame)
        forkLoopRun = null
        forkLoopOut = null
        forkLoopIn = null
        if (track !== incoming) track.decoder.close()
        current = incoming
        upcoming = null
        nextSong = null
        nextDeckOpenId = null
        blendActive = false
        // After the release the incoming song runs at rate 1 on both sides of the handover, so the
        // position heard now is the next written frame's less what the sink still holds.
        val rate = sink.format.sampleRate.coerceAtLeast(1)
        val writeUs = run.inTrackUsAfter(framesWritten - run.originFrame)
        val queuedUs = (framesWritten - sink.framesPlayed()).coerceAtLeast(0L) * 1_000_000L / rate
        val heardUs = (writeUs - queuedUs).coerceAtLeast(0L)
        resetPlayhead(incoming, heardUs)
        DesktopTrackLog.log("loop mix done: '${incoming.song.title}' carries on at ${"%.2f".format(heardUs / 1e6)}s")
        publishTrack(incoming, isPlaying = !paused, positionUs = heardUs)
        endBlend()
    }

    /** Before anything of it was heard: the pair takes its blend instead (the phone's `loopFallBack`). */
    private fun forkLoopCancel(reason: String) {
        forkLoop.fallBack(reason)
        forkLoopEnd()
    }

    /**
     * The mix can't go on (a seek, the next song changed, the output reopened, the speed changed): with
     * [settle], onto whichever song is shown at the mix's own position for it (the phone's `loopBail`).
     */
    private fun forkLoopBail(reason: String, settle: Boolean) {
        val run = forkLoopRun ?: return
        val heardMf = if (run.engaged) sink.framesPlayed() - run.originFrame else 0L
        val target = if (settle) run.settleMs(heardMf) else null
        DesktopTrackLog.log("loop mix stops: $reason")
        forkLoop.forget()
        forkLoopEnd()
        DesktopPlayerSettings.smartMixBlend.value = null
        // Past the move to the incoming song, [performSeek] promotes it first.
        if (target != null) performSeek(target)
    }

    private fun forkLoopEnd() {
        val run = forkLoopRun
        val incoming = forkLoopIn
        forkLoopRun = null
        forkLoopOut = null
        forkLoopIn = null
        blendActive = false
        // Not shown yet: its deck goes back to its start, where a blend expects to find it.
        if (run != null && !run.handedOff && incoming != null && incoming === upcoming) {
            incoming.dropTempo()
            incoming.decoder.seek(0L)
            incoming.startUs = 0L
        }
    }

    /**
     * A skip that reached the audio thread while a loop mix is armed or running. [beginSkipMix] and
     * [forkSkipTo] read [blendActive] on the caller's thread, so one pressed just before the mix armed
     * still arrives here as a blend: armed under the mix's decks, the blend ran on the ordinary path
     * until the splice took the step over, both mixes at once. A mix not yet heard gives way (its
     * incoming deck goes back to the start the blend expects); one heard settles first, as for a seek,
     * which past its move to the incoming song promotes that song; the skip then blends from wherever
     * that leaves the player, or cuts when it has no deck left to blend into.
     */
    private fun forkLoopYieldToSkip() {
        val run = forkLoopRun ?: return
        if (run.engaged) forkLoopBail("a skip", settle = true) else forkLoopCancel("a skip")
    }

    /** A better copy of the playing song under a loop mix: ignored once spliced, and the mix dropped before. */
    private fun forkLoopRefusesUpgrade(track: Track): Boolean {
        val run = forkLoopRun ?: return false
        if (run.engaged) {
            track.decoder.close()
            return true
        }
        forkLoopCancel("a better copy of the song arrived")
        return false
    }

    // ---- fork: a better copy that arrives during a mix waits for it, see ForkMixUpgradeHold -----

    private val forkUpgradeHold = ForkMixUpgradeHold<Track>()

    /**
     * Whether a mix is playing: a blend (a skip's too) still running or past its display switch, or a
     * loop mix's decks spliced in. The times [replaceCurrent] used to close a better copy.
     */
    private fun forkMixing(): Boolean = fadeRemaining > 0 || displaySwitched || forkLoopRun?.engaged == true

    /** From [replaceCurrent]: a copy arriving mid-mix, or just after one, is held instead of closed. */
    private fun forkHoldUpgrade(track: Track): Boolean {
        if (!forkUpgradeHold.mustWait(forkMixing(), System.nanoTime())) return false
        forkUpgradeHold.hold(track)?.decoder?.close()
        DesktopTrackLog.log(
            "holding the better copy of '${track.song.title}' " +
                "(${track.stream.format.summary.ifBlank { "another rendition" }}) until the mix settles",
        )
        return true
    }

    /**
     * From [step], every block: the held copy once the mix has settled, for the song playing then. The
     * crossfaded swap takes it where it can (and the step carries on reading the old copy, which it
     * fades out of); otherwise the old cut-over, re-cued to where the song now is, since the copy was
     * opened at the playhead of the moment it was offered. True when the cut-over changed the deck.
     */
    private fun forkInstallHeldUpgrade(playing: Track): Boolean {
        val held = forkUpgradeHold.due(forkMixing(), System.nanoTime()) ?: return false
        val shown = _state.value
        if (held.song.videoId != playing.song.videoId || shown.song?.videoId != held.song.videoId) {
            DesktopTrackLog.log(
                "dropping the held copy of '${held.song.title}': '${shown.song?.title ?: playing.song.title}' is playing now",
            )
            held.decoder.close()
            return false
        }
        // Another copy may have gone in meanwhile (the song's own second look, after the mix).
        if (!DesktopMusicSources.worthSwapping(held.stream.format, shown.streamFormat)) {
            DesktopTrackLog.log("dropping the held copy of '${held.song.title}': what plays is as good")
            held.decoder.close()
            return false
        }
        DesktopTrackLog.log(
            "the mix has settled: '${held.song.title}' upgrades to the held " +
                held.stream.format.summary.ifBlank { "rendition" },
        )
        if (forkQueueSwap(held)) return false
        if (kotlin.math.abs(shown.positionMs - held.startUs / 1_000) >= UPGRADE_RESEEK_THRESHOLD_MS) {
            held.decoder.seek(shown.positionMs * 1_000)
            held.startUs = shown.positionMs * 1_000
        }
        replaceCurrent(held, playWhenReady = true)
        return true
    }

    private fun forkDropHeldUpgrade() {
        forkUpgradeHold.drop()?.decoder?.close()
    }

    // ---- fork: Automix reported as on the phone, see ForkAutomixReport -------------------------

    private val forkReport = ForkAutomixReporter()

    /** The newest plan [publishTransitionWindow] was handed, for the stats line (the phone's `lastSmartPlan`). */
    private var forkLastPlan: TransitionPlan? = null

    /**
     * From [step], every pass, paused too: the mix popup's view and progress for a real blend, and the
     * stats line. Audio thread, where every field it reads is written.
     */
    private fun forkReportAutomix(track: Track?) {
        val incoming = upcoming
        val plan = activePlan
        if (track != null && incoming != null && plan != null && isSmartMixInProgress()) {
            forkReport.showBlend(plan) {
                ForkMixView.build(
                    plan = plan,
                    outSong = track.song,
                    inSong = incoming.song,
                    outAnalysis = analyzer.analysisFor(track.song.videoId),
                    inAnalysis = analyzer.analysisFor(incoming.song.videoId),
                    outStartSec = plan.transitionStart,
                    inStartSec = incoming.startUs / 1e6,
                    incomingRate = incoming.tempoRate,
                    outDurationMs = (track.decoder.durationUs ?: 0L) / 1_000,
                    inDurationMs = (incoming.decoder.durationUs ?: 0L) / 1_000,
                )
            }
            val channels = sink.format.channels.coerceAtLeast(1)
            val queued = ((framesWritten - sink.framesPlayed()).coerceAtLeast(0L) * channels * playbackSpeed).toLong()
            forkReport.progress(
                ForkMixView.heardProgress((transitionTotal - fadeRemaining).toLong(), queued, fadeTotal.toLong()),
            )
        } else {
            forkReport.clearBlend()
        }
        if (forkReport.detailDue(System.nanoTime())) forkReport.publishDetail(forkAutomixDetail(track))
    }

    /** The phone's `describeAutomix` on this engine's decks; null with Automix off, in a party, or nothing playing. */
    private fun forkAutomixDetail(track: Track?): String? {
        if (track == null || !automixEnabled || DesktopListenTogether.state.value.inParty) return null
        val positionSec = _state.value.positionMs / 1000.0
        forkLoopRun?.let { run ->
            val mixSec = if (run.engaged) {
                (sink.framesPlayed() - run.originFrame).toDouble() / sink.format.sampleRate.coerceAtLeast(1)
            } else {
                null
            }
            return ForkAutomixDetail.loopRunning(run.plan, mixSec, run.clock.releaseAt, run.plan.out.loopStart - positionSec)
        }
        // Past the display switch the incoming song is the one shown, and the song after it is next.
        val shown = if (displaySwitched) upcoming?.song ?: track.song else track.song
        val next = if (displaySwitched) deferredNext else upcoming?.song ?: nextSong
        // Mid-blend the phone has already cleared its marker, so its line goes back to the analysis.
        val blending = fadeRemaining > 0 || displaySwitched
        if (!blending && next != null) {
            forkLoop.plannedFor(track.song.videoId, next.videoId)?.let {
                return ForkAutomixDetail.loopPlanned(it, it.out.loopStart - positionSec)
            }
            val plan = forkLastPlan
            if (plan != null && !plan.blocked && _state.value.transitionWindow != null) {
                return ForkAutomixDetail.planned(plan, plan.transitionStart - positionSec, forkLoop.refusal)
            }
        }
        fun label(song: Song?, outgoing: Boolean): String = song?.videoId?.let {
            ForkAutomixDetail.label(analyzer.analysisFor(it), analyzer.stateFor(it), outgoing)
        } ?: "none"
        return ForkAutomixDetail.analysis(label(shown, outgoing = true), label(next, outgoing = false))
    }

    // ---- fork: decks measured from where their streams landed, see ForkCueCorrection ------------

    /** A started or seeked deck to check against its landing on its first block, and the position it was asked for. */
    private var forkCueCheck: Track? = null
    private var forkCueAskUs = 0L

    private fun forkArmCueCheck(track: Track, askedUs: Long) {
        forkCueCheck = track.takeIf { askedUs > 0 }
        forkCueAskUs = askedUs
    }

    /** The first block after a start or a seek: a stream that landed elsewhere is counted from where it landed. */
    private fun forkCheckCue(track: Track) {
        if (forkCueCheck !== track) return
        val landed = track.decoder.forkLandedUs ?: return
        forkCueCheck = null
        val start = ForkCueCorrection.correctedStart(forkCueAskUs, landed) ?: return
        val moved = start - forkCueAskUs
        DesktopTrackLog.log(
            "'${track.song.title}' was asked for ${"%.1f".format(forkCueAskUs / 1e6)}s but its stream starts at " +
                "${"%.1f".format(start / 1e6)}s; measuring from there",
        )
        track.startUs += moved
        resetPlayhead(track, (outgoingHeardUs() + moved).coerceAtLeast(0L))
    }

    /** The incoming deck's first block in a blend: its cue is where its stream landed, not where it was asked. */
    private fun forkCorrectCue(incoming: Track) {
        val start = ForkCueCorrection.correctedStart(incoming.startUs, incoming.decoder.forkLandedUs) ?: return
        DesktopTrackLog.log(
            "'${incoming.song.title}' was cued for ${"%.1f".format(incoming.startUs / 1e6)}s but its stream starts at " +
                "${"%.1f".format(start / 1e6)}s; measuring from there",
        )
        incoming.startUs = start
    }

    /**
     * At the blend's move to the incoming song and at its end: the blend's count of the incoming deck
     * against the deck's own clock — the end of the last block it gave, less what its stretch buffer
     * still holds — and the start moved when they disagree, so the position and the lyrics follow the
     * audio from here.
     */
    private fun forkReconcileIncoming(incoming: Track) {
        if (incomingSourceFrames <= 0.0) return
        val rate = sink.format.sampleRate.takeIf { it > 0 } ?: return
        val channels = sink.format.channels.coerceAtLeast(1)
        val heldFrames = (incoming.tempo?.available ?: 0) / channels
        val heldUs = (heldFrames * 1_000_000.0 / rate * deckRate(incoming)).toLong()
        val drift = ForkCueCorrection.drift(incoming.startUs, incomingElapsedUs(), incoming.decoder.forkClock.takenEndUs, heldUs) ?: return
        DesktopTrackLog.log(
            "'${incoming.song.title}' was counted ${"%.1f".format(drift / 1e6)}s away from the audio its deck handed " +
                "over; measuring from ${"%.1f".format((incoming.startUs - drift) / 1e6)}s instead",
        )
        incoming.startUs = (incoming.startUs - drift).coerceAtLeast(0L)
    }

    private fun closeEverything() {
        forkDropSwap() // fork
        forkDropHeldUpgrade() // fork
        current?.decoder?.close()
        upcoming?.decoder?.close()
        current = null
        upcoming = null
        sink.close()
    }

    // ---- output precision ------------------------------------------------

    @Volatile private var preferFloat = false
    @Volatile private var spatialEnabled = false

    @Volatile private var equalizerEnabled = false

    @Volatile private var equalizerCurve: EqCurve = EqCurve.FLAT

    @Volatile private var equalizerBalance = 0f
    @Volatile private var skipSilenceEnabled = false
    @Volatile private var automixPerformance = AutomixPerformanceMode.BALANCED

    /** Android's "Automix performance": how much CPU background analysis may use. */
    fun setAutomixPerformance(mode: AutomixPerformanceMode) {
        automixPerformance = mode
    }

    /** Android's "Spatial audio". */
    /**
     * Aims the equaliser.
     *
     * The curve is rendered by the caller because working out the make-up attenuation walks the
     * whole response ([EqCurve]), and the audio thread is the one place that must not do that.
     */
    fun setEqualizer(enabled: Boolean, curve: EqCurve, balance: Float) {
        equalizerCurve = curve
        equalizerBalance = balance.coerceIn(-1f, 1f)
        equalizerEnabled = enabled
    }

    fun setSpatialAudio(enabled: Boolean) {
        spatialEnabled = enabled
    }

    /** Android's "Skip silence", on the same terms. */
    fun setSkipSilence(enabled: Boolean) {
        skipSilenceEnabled = enabled
        if (!enabled) silence?.reset()
    }

    private fun precisionBytes(): Int = if (preferFloat) 4 else 2

    /** Android's "Output precision", which is two rungs there and two here. */
    fun setOutputPrecision(mode: String) {
        val wanted = mode.uppercase() == "FLOAT_32"
        if (wanted == preferFloat) return
        preferFloat = wanted
        commands += Command.Reconfigure
    }

    /**
     * The whole signal chain as it stands, for the pipeline readout: what arrived, what decoded it,
     * whether it is being resampled, what is processing it, and what it is being written to.
     */
    internal fun pipeline(): DesktopAudioPipeline {
        val track = current
        val decoded = track?.decoder?.outputFormat
        val out = sink.format
        return DesktopAudioPipeline(
            sourceFormat = track?.stream?.format,
            decoderName = track?.stream?.format?.codec,
            decodedSampleRateHz = decoded?.sampleRate,
            decodedChannels = decoded?.channels,
            outputSampleRateHz = out.sampleRate.takeIf { sink.isOpen },
            outputChannels = out.channels.takeIf { sink.isOpen },
            outputIsFloat = out.isFloat.takeIf { sink.isOpen },
            outputBytesPerSample = out.bytesPerSample.takeIf { sink.isOpen },
            deviceName = sink.deviceName,
            bufferBytes = sink.bufferBytes,
            equalizerEnabled = equalizerEnabled,
            skipSilence = skipSilenceEnabled,
        )
    }

    /** What the device actually accepted, for the settings screen to report. */
    fun outputSummary(): String = if (!sink.isOpen) "Not started" else with(sink.format) {
        val depth = if (isFloat) "32-bit float" else "${bytesPerSample * 8}-bit"
        "$depth · ${sampleRate / 1000.0} kHz · ${if (channels == 2) "stereo" else "$channels ch"}"
    }

    companion object {
        /** How many times a lossy substitute is asked to be beaten before the question is closed. */
        private const val LOSSLESS_FOLLOW_UPS = 2

        /** Avoid a decoder seek for sub-tick drift while an upgraded rendition is opening. */
        private const val UPGRADE_RESEEK_THRESHOLD_MS = 250L

        /** Fork: a deck rate this close to 1 is 1 (from PC-Continued be257dad). */
        private const val FORK_TEMPO_UNITY = 0.0001

        /** Fork: how long a quality upgrade crosses from the old copy to the new; see `forkSwap`. */
        private const val FORK_SWAP_FRAMES_MS = 200L

        /** Fork: how far ahead the better copy is seeked, so its decoder has it ready; see `forkStartWaitingSwap`. */
        private const val FORK_SWAP_LEAD_US = 1_000_000L

        /** Fork: past its point by this with nothing decoded, the better copy is armed again further on. */
        private const val FORK_SWAP_LATE_US = 2_000_000L
        private const val FORK_SWAP_TRIES = 3

        /** Enough of a measurement on the incoming track to cue into it. */
        val MEASURED_ENOUGH_TO_ENTER_ON = setOf(
            TrackAnalysisState.ANALYSED,
            TrackAnalysisState.REFINING,
        )

        /** Where in a blend the player moves to the incoming song: halfway, as on Android. */
        private const val DISPLAY_SWITCH_AT = 0.5f

        /** Jitter tolerated in a republished beat anchor; see [publishBlend]. */
        private const val ANCHOR_TOLERANCE_NANOS = 25_000_000L

        private const val MIX_END_PADDING_FRAMES = 2_048
        private const val DEFAULT_TEMPO_OUTPUT_SAMPLES = 4_096
        private const val TEMPO_STEP_PER_BEAT = 0.0075
        private const val EASE_FALLBACK_SECONDS = 0.5

        /**
         * How far ahead of its transition a held-back addon track is resolved and opened. Android
         * arms 4s ahead from a disk cache; an addon has a network round trip or two and a fresh
         * stream to open, and an incoming track that is not ready by the transition misses it.
         */
        private const val ADDON_ARM_LEAD_MS = 15_000L

        /** How long a better copy waits for the blend into its track to finish: up to a minute. */
        private const val BLEND_WAIT_POLLS = 300
        private const val BLEND_WAIT_POLL_MS = 200L

        const val MAX_CROSSFADE_SECONDS = 12
        const val AUTOMIX_FALLBACK_SECONDS = 6

        // ---- outgoing-track FX for a smart transition the planner gave no echo ----
        // A beat faster than this slices on the 1/8 (half-beat); slower material rolls on the 1/4
        // (whole beat), so the repeat never turns into a machine-gun at high tempi.
        private const val EIGHTH_NOTE_MAX_BEAT_SECONDS = 0.4
        /** Where in the fade the roll freezes and starts repeating, and how loud its send peaks. */
        private const val ROLL_START = 0.55f
        private const val ROLL_SEND = 0.5f
        /** Extra blend time so the roll and the reverb ring out after the outgoing dry is gone. */
        private const val ROLL_TAIL_SECONDS = 0.9
        private const val REVERB_TAIL_SECONDS = 1.4
        /** The reverb opens over the last part of the fade and holds through the tail. */
        private const val REVERB_START = 0.5f
        private const val REVERB_SEND = 0.55f
        /** The texture send widens or grits the outgoing tail over the last stretch of the fade. */
        private const val TEXTURE_START = 0.4f
        private const val TEXTURE_SEND = 0.4f
        private const val TEXTURE_TAIL_SECONDS = 0.6
    }
}
