package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.metrolist.innertubex.extraction.StreamResolveException
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.ui.player.MusicVideoQualityUi
import java.awt.EventQueue
import java.awt.Frame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * Fork: a music video's picture on the PC — the phone's MusicVideoEngine, decoded with the
 * desktop's own FFmpeg canvas decoder. Ported from Desktop Revived (Bntagaaa/BitChord-Desktop-Revived
 * `1.9.1`: DesktopMusicVideo.kt and DesktopVideoBeta.kt, 6adad8f7 / 9b2c72c9 / c7ca573f), but drawn
 * by the phone's own player (`sharedUi`'s MusicVideoLayout, through [DesktopPlayerHost]), not their UI.
 *
 * - Opt-in ("Music videos (beta)" under Settings > Appearance > Animated canvas), off by default.
 * - Only a YouTube music video ([ForkVideoRules.eligible]): its video-only stream from the shared
 *   [InnerTubeXResolver.extractVideo], asked plainly and then as age-restricted, a refused client
 *   skipped for the next one (three at most), a height that would not decode stepped down once —
 *   the phone's rules. The audio is untouched: the sources never stand in for a video row, so the
 *   sound is that video's own.
 * - Decoded at the stream's own size, which the resolver capped at the chosen height (Auto 720 on
 *   the PC, see [ForkVideoRules.AUTO_HEIGHT]); read a megabyte window at a time with the next ones
 *   in flight ([ForkVideoIo]).
 * - Held to the engine's audio clock by [ForkVideoPacer]: waits, catch-up, drops, seeks.
 * - Each frame is copied once, into a Skia image, and an image is closed shortly after it is
 *   replaced on screen (the clip memory work in DesktopCanvasVideo, `forkHandedOut`).
 * - Decodes only while a player surface shows it and the window is on screen; four seconds after
 *   the last one goes, the decoder is closed (the phone's release grace).
 * - A new song, a seek, a skip or a crossfade's midpoint: frames carry their song and a generation,
 *   so nothing from the last one reaches the screen; a picture that stops keeping up hands the
 *   sleeve back to the still art ([ForkVideoPacer.stalled]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object ForkMusicVideo {

    const val TITLE = "Music videos (beta)"
    const val SUBTITLE = "Plays a music video's picture in the player, kept to the song. Uses more CPU, memory and data"

    private const val KEY_ON = "fork_music_video"
    private const val KEY_QUALITY = "fork_music_video_quality"

    /** How long the picture outlives its last surface: the player closed and reopened. */
    private const val RELEASE_GRACE_MS = 4_000L

    /** A googlevideo URL lives about six hours; a resolved stream is reused well inside that. */
    private const val STREAM_TTL_MS = 60 * 60 * 1000L
    private const val MAX_STREAMS = 8

    /** How many refused clients a picture is worth before it is given up — the audio's three. */
    private const val MAX_REFUSED_CLIENTS = 3

    /** Reopens after a read that broke off mid-video, before the picture is given up for this play. */
    private const val MAX_REOPENS = 2

    /** A replaced frame is closed once it has been off screen this long. */
    private const val RETIRE_NS = 100_000_000L

    private val ESCALATED_REASONS = setOf(
        StreamResolveException.Reason.AGE_RESTRICTED,
        StreamResolveException.Reason.NO_PLAYABLE_STREAM,
    )

    private val persistence by lazy { DesktopPersistence() }

    // ---- the switch -------------------------------------------------------

    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ON, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * What [DesktopPlayerHost.musicVideoSupported] answers. Snapshot state, so the player, which
     * reads it while composing, follows the switch at once rather than at the next song.
     */
    var supported by mutableStateOf(_enabled.value)
        private set

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        supported = on
        persistence.saveBoolean(KEY_ON, on)
    }

    // ---- what the window hands over --------------------------------------

    /** The listener's playback speed; the clock runs on at it between readings. */
    @Volatile var speed: Float = 1f

    private val audio = MutableStateFlow<StateFlow<DesktopPlaybackState>?>(null)

    /** The engine's state, whose clock the picture follows. Called by the window. */
    fun attach(state: StateFlow<DesktopPlaybackState>) {
        audio.value = state
        start()
    }

    // ---- what the player reads -------------------------------------------

    private val _aspect = MutableStateFlow(16f / 9f)
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    private val _quality = MutableStateFlow(
        MusicVideoQualityUi(held = ForkVideoRules.storedHeight(persistence.int(KEY_QUALITY, 0))),
    )
    val quality: StateFlow<MusicVideoQualityUi> = _quality.asStateFlow()

    /** Holds the picture at [height] from now on, or Auto with null; the playing one reopens at it. */
    fun setQuality(height: Int?) {
        val held = height?.let(ForkVideoRules::storedHeight)
        persistence.saveInt(KEY_QUALITY, held ?: 0)
        running?.videoId?.let {
            stepDown.remove(it)
            slow.remove(it)
        }
        _quality.update { it.copy(held = held) }
    }

    /** The frame on screen. Written on the AWT event thread only, where Compose draws it. */
    internal val shown = mutableStateOf<ForkVideoFrame?>(null)

    private val _stalled = MutableStateFlow(false)

    /** The picture has stopped keeping up; the sleeve shows the still art until it has caught up. */
    internal val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    private val surfaces = MutableStateFlow(0)

    internal fun claim() = surfaces.update { it + 1 }
    internal fun release() = surfaces.update { (it - 1).coerceAtLeast(0) }

    // ---- control: one thread, so the job bookkeeping needs no locks -------

    private val control = Executors.newSingleThreadExecutor { Thread(it, "music-video").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + control)
    private val started = AtomicBoolean(false)

    private data class Target(val videoId: String, val cap: Int)

    private class Resolved(val stream: InnerTubeXResolver.VideoStream, val cap: Int, val at: Long)

    private var job: Job? = null
    @Volatile private var running: Target? = null
    private var grace: Job? = null
    private val watched = MutableStateFlow(false)
    private val generation = AtomicLong(0)

    /** Bumped when a verdict below changes what the playing song should get. */
    private val revision = MutableStateFlow(0)
    private val noPicture = ConcurrentHashMap.newKeySet<String>()
    private val ageGated = ConcurrentHashMap.newKeySet<String>()
    private val stepDown = ConcurrentHashMap.newKeySet<String>()
    private val slow = ConcurrentHashMap.newKeySet<String>()
    private val refused = ConcurrentHashMap<String, MutableSet<String>>()
    private val streams = LinkedHashMap<String, Resolved>()

    private fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Signing in is what opens an age gate: every "no picture" verdict and every URL minted
            // for the old session is owed a fresh ask (the phone's init).
            InnerTubeXResolver.sessionGeneration.drop(1).collect {
                noPicture.clear()
                refused.clear()
                synchronized(streams) { streams.clear() }
                revision.update { it + 1 }
            }
        }
        val songs = audio.filterNotNull().flatMapLatest { it }
            .map { it.song }
            .distinctUntilChanged { a, b -> a?.videoId == b?.videoId && a?.isVideo == b?.isVideo }
        val targets = combine(songs, enabled, quality.map { it.held }.distinctUntilChanged(), revision) { song, on, held, _ ->
            if (!on || !ForkVideoRules.eligible(song) || song!!.videoId in noPicture) {
                null
            } else {
                val id = song.videoId
                Target(id, ForkVideoRules.capFor(held, id in stepDown, id in slow))
            }
        }.distinctUntilChanged()
        val onScreen = combine(surfaces, DesktopWindowVisibility.visible, windowShown()) { count, visible, shown ->
            count > 0 && visible && shown
        }.distinctUntilChanged()
        scope.launch {
            combine(targets, onScreen) { target, on -> target to on }.collect { (target, on) -> steer(target, on) }
        }
    }

    /** Whether any of the app's windows is on screen and not minimised; asked once a second. */
    private fun windowShown() = flow {
        while (true) {
            emit(runCatching { Frame.getFrames().any { it.isShowing && (it.extendedState and Frame.ICONIFIED) == 0 } }.getOrDefault(true))
            delay(1_000)
        }
    }.distinctUntilChanged()

    /** Starts, keeps, pauses or stops the picture. On [control]. */
    private fun steer(target: Target?, onScreen: Boolean) {
        watched.value = onScreen
        if (target == null) {
            stop()
            return
        }
        if (running != target) {
            stop()
            if (onScreen) launch(target)
            return
        }
        if (onScreen) {
            grace?.cancel()
            grace = null
            if (job?.isActive != true) launch(target)
        } else if (grace == null) {
            // Off screen: the job waits where it is, and lets go of the decoder after the grace.
            grace = scope.launch {
                delay(RELEASE_GRACE_MS)
                grace = null
                stop()
            }
        }
    }

    private fun launch(target: Target) {
        val gen = generation.incrementAndGet()
        running = target
        val state = audio.value ?: return
        job = scope.launch(Dispatchers.IO) { play(target, gen, state) }
        prefetchNext(target.cap)
    }

    /**
     * Lets go of the picture at once. The job is cancelled, not joined: a decoder blocked in a read
     * finishes in its own time and closes itself, while its frames, carrying the old generation,
     * are turned away.
     */
    private fun stop() {
        grace?.cancel()
        grace = null
        job?.cancel()
        job = null
        running = null
        generation.incrementAndGet()
        _stalled.value = false
        _quality.update { it.copy(heights = emptyList(), activeHeight = null) }
        clearFrame()
    }

    // ---- one play of one picture -----------------------------------------

    private sealed interface Outcome {
        data object Finished : Outcome
        data object OpenFailed : Outcome
        data object Broke : Outcome
        data object Slow : Outcome
        data class Refused(val profile: String) : Outcome
    }

    private suspend fun play(target: Target, gen: Long, audioState: StateFlow<DesktopPlaybackState>) {
        val id = target.videoId
        var reopens = 0
        while (currentCoroutineContextActive()) {
            val stream = obtain(target) ?: return
            if (generation.get() != gen) return
            when (val outcome = decode(target, gen, stream, audioState)) {
                Outcome.Finished -> return
                is Outcome.Refused -> {
                    forget(id)
                    val skip = refused.getOrPut(id) { ConcurrentHashMap.newKeySet() }
                    if (skip.add(outcome.profile) && skip.size <= MAX_REFUSED_CLIENTS) {
                        DesktopTrackLog.log("music video: ${outcome.profile} refused $id; asking the next client")
                        InnerTubeXResolver.onVideoRefused()
                        continue
                    }
                    giveUp(id, "every client refused it")
                    return
                }
                Outcome.OpenFailed -> {
                    forget(id)
                    // Above 1080p YouTube serves VP9 and AV1: one more try on the H.264 ladder.
                    if (target.cap > ForkVideoRules.STEP_DOWN_HEIGHT && stepDown.add(id)) {
                        DesktopTrackLog.log("music video: ${target.cap}p would not open; trying ${ForkVideoRules.STEP_DOWN_HEIGHT}p")
                        revision.update { it + 1 }
                        return
                    }
                    giveUp(id, "the picture would not open")
                    return
                }
                Outcome.Broke -> {
                    forget(id)
                    if (++reopens > MAX_REOPENS) {
                        DesktopTrackLog.log("music video: the picture of $id kept breaking off; still art for the rest of this play")
                        return
                    }
                    DesktopTrackLog.log("music video: the picture of $id broke off; reopening")
                }
                Outcome.Slow -> {
                    DesktopTrackLog.log("music video: most frames of $id came too late at ${target.cap}p; Auto goes to ${ForkVideoRules.SLOW_HEIGHT}p")
                    slow.add(id)
                    revision.update { it + 1 }
                    return
                }
            }
        }
    }

    private suspend fun currentCoroutineContextActive() = kotlinx.coroutines.currentCoroutineContext().isActive

    private fun giveUp(id: String, why: String) {
        DesktopTrackLog.log("music video: no picture for $id ($why)")
        noPicture.add(id)
        revision.update { it + 1 }
    }

    private fun forget(id: String) = synchronized(streams) { streams.remove(id) }

    /** The stream for [target], from the cache or asked for; null (and remembered) when there is none. */
    private suspend fun obtain(target: Target): InnerTubeXResolver.VideoStream? {
        val id = target.videoId
        cached(id, target.cap)?.let { return it }
        val stream = try {
            resolve(id, target.cap)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            DesktopTrackLog.log("music video: no picture for $id (${e.reason}): ${e.message}")
            // Settled until something changes it: the age gate by a sign-in, the rest not at all.
            // A network failure is asked again on the next song or surface.
            if (e.reason != StreamResolveException.Reason.NETWORK) giveUp(id, e.reason.toString())
            return null
        } catch (e: Exception) {
            DesktopTrackLog.log("music video: couldn't resolve the picture of $id: ${e.message}")
            return null
        }
        // A catalogue track's "video" is its cover held still: nothing to show.
        if (stream == null || stream.aspect < ForkVideoRules.MIN_VIDEO_ASPECT) {
            giveUp(id, if (stream == null) "no video-only stream" else "a still cover, ${stream.width}x${stream.height}")
            return null
        }
        synchronized(streams) {
            streams[id] = Resolved(stream, target.cap, System.currentTimeMillis())
            while (streams.size > MAX_STREAMS) streams.remove(streams.keys.first())
        }
        return stream
    }

    private fun cached(id: String, cap: Int): InnerTubeXResolver.VideoStream? = synchronized(streams) {
        val hit = streams[id] ?: return null
        if (hit.cap != cap || System.currentTimeMillis() - hit.at > STREAM_TTL_MS) {
            streams.remove(id)
            return null
        }
        hit.stream
    }

    /** Asked plainly, then as age-restricted, which InnerTubeX needs told (the phone's resolve). */
    private suspend fun resolve(id: String, cap: Int): InnerTubeXResolver.VideoStream? = withContext(Dispatchers.IO) {
        val skip = refused[id].orEmpty().toSet()
        if (id !in ageGated) {
            val plain = try {
                InnerTubeXResolver.extractVideo(id, cap, skip)
            } catch (e: StreamResolveException) {
                if (e.reason !in ESCALATED_REASONS) throw e
                null
            }
            if (plain != null) return@withContext plain
            DesktopTrackLog.log("music video: nothing for $id asked plainly; asking as age-restricted")
        }
        InnerTubeXResolver.extractVideo(id, cap, skip, ageRestricted = true)?.also { ageGated.add(id) }
    }

    /** The next song's stream, asked for while this one plays, so a skip to it doesn't wait on it. */
    private fun prefetchNext(cap: Int) {
        scope.launch(Dispatchers.IO) {
            delay(5_000)
            val next = runCatching { ForkDebugBridge.queue().upcoming.firstOrNull() }.getOrNull()
            if (!ForkVideoRules.eligible(next)) return@launch
            val id = next!!.videoId
            if (id in noPicture || cached(id, cap) != null) return@launch
            val stream = runCatching { resolve(id, cap) }.getOrNull() ?: return@launch
            if (stream.aspect < ForkVideoRules.MIN_VIDEO_ASPECT) return@launch
            synchronized(streams) {
                streams[id] = Resolved(stream, cap, System.currentTimeMillis())
                while (streams.size > MAX_STREAMS) streams.remove(streams.keys.first())
            }
        }
    }

    private suspend fun decode(
        target: Target,
        gen: Long,
        stream: InnerTubeXResolver.VideoStream,
        audioState: StateFlow<DesktopPlaybackState>,
    ): Outcome {
        val id = target.videoId
        val io = ForkVideoIo(stream.url, stream.headers)
        val decoder = DesktopCanvasDecoder(
            forkMaxEdge = ForkVideoRules.decodeEdge(stream.width, stream.height, target.cap),
            forkFrameThreads = true,
            forkIo = io,
        )
        val ended = AtomicBoolean(false)
        var watcher: Job? = null
        // A cancel (stop, skip, the switch off) ends a read blocked on a window at once: open and
        // forkNext sit in the reader's 25 s waits, which a cancel doesn't interrupt, and each play
        // left behind held its decoder and windows until they timed out.
        val unblock = kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion { io.reader.close() }
        try {
            if (decoder.open(stream.url).isFailure) {
                return io.reader.refusedWith?.let { Outcome.Refused(stream.profileId) } ?: Outcome.OpenFailed
            }
            if (generation.get() != gen) return Outcome.Finished
            val width = decoder.width
            val height = decoder.height
            DesktopTrackLog.log("music video: $id at ${width}x$height ${stream.mimeType} via ${stream.profileId} (asked ${target.cap}p)")
            _aspect.value = width.toFloat() / height.coerceAtLeast(1)
            _quality.update { it.copy(heights = stream.heights, activeHeight = height) }
            val pacer = ForkVideoPacer(frameMs = decoder.frameIntervalMillis)
            watcher = scope.launch(Dispatchers.Default) {
                while (isActive && generation.get() == gen) {
                    delay(250)
                    val state = audioState.value
                    val stalled = state.song?.videoId == id && pacer.stalled(
                        ForkVideoClock.at(state, speed, System.nanoTime()),
                        ForkVideoClock.running(state),
                        ended.get(),
                        System.nanoTime(),
                    )
                    if (generation.get() == gen) _stalled.value = stalled
                }
            }
            val pixels = ByteArray(width * height * 4)
            val info = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)
            var lastPts = -1L
            while (currentCoroutineContextActive() && generation.get() == gen) {
                if (!watched.value) {
                    watched.first { it }
                    continue
                }
                val state = audioState.value
                if (state.song?.videoId != id) {
                    delay(30)
                    continue
                }
                val now = System.nanoTime()
                val clock = ForkVideoClock.at(state, speed, now)
                pacer.owedSeek(state, clock, speed, now)?.let { to ->
                    ended.set(false)
                    if (!decoder.forkSeek(to)) return Outcome.Broke
                }
                val running = ForkVideoClock.running(state)
                if (ended.get() || (!running && pacer.shownSinceSeek)) {
                    // Paused on its frame, or past the picture's end: nothing to decode until the
                    // audio moves on (plays, seeks, or changes song).
                    audioState.first {
                        it.seeks != state.seeks || it.song?.videoId != id ||
                            (!ended.get() && ForkVideoClock.running(it) != running)
                    }
                    continue
                }
                var step: ForkVideoPacer.Step = ForkVideoPacer.Step.Drop
                val frame = decoder.forkNext(pixels) { pts ->
                    val at = System.nanoTime()
                    step = pacer.decide(pts, ForkVideoClock.at(audioState.value, speed, at), running, speed, at)
                    step is ForkVideoPacer.Step.Show
                }
                if (frame == null) {
                    if (io.reader.refusedWith != null) return Outcome.Refused(stream.profileId)
                    // A read that broke off well before the end (an expired URL, the network), not
                    // the video's own end, which can come a little before the audio's.
                    val duration = state.durationMs
                    if (duration > 0 && lastPts in 0 until duration - 10_000 && clock < duration - 10_000) return Outcome.Broke
                    ended.set(true)
                    continue
                }
                lastPts = maxOf(lastPts, frame.ptsMs)
                when (val decided = step) {
                    is ForkVideoPacer.Step.Seek -> if (!decoder.forkSeek(decided.toMs)) return Outcome.Broke
                    ForkVideoPacer.Step.Drop -> Unit
                    is ForkVideoPacer.Step.Show -> {
                        if (decided.waitMs > 0) delay(decided.waitMs)
                        val latest = audioState.value
                        // A seek, a pause or a new song while it waited: this frame is not the one.
                        if (latest.song?.videoId != id || latest.seeks != state.seeks ||
                            (running && !ForkVideoClock.running(latest))
                        ) continue
                        offer(ForkVideoFrame(id, gen, Image.makeRaster(info, pixels, width * 4)))
                        pacer.shown(frame.ptsMs)
                    }
                }
                if (_quality.value.held == null && pacer.slow && target.cap > ForkVideoRules.SLOW_HEIGHT) return Outcome.Slow
            }
            return Outcome.Finished
        } finally {
            unblock?.dispose()
            watcher?.cancel()
            withContext(NonCancellable) { decoder.close() }
        }
    }

    // ---- frames to the screen --------------------------------------------

    private val pending = AtomicReference<ForkVideoFrame?>(null)
    private val drainQueued = AtomicBoolean(false)
    private val retired = ArrayDeque<Pair<ForkVideoFrame, Long>>() // event thread only
    private var sweepQueued = false // event thread only

    /**
     * Hands a frame to the event thread. Only the newest waits there: one the screen never got to is
     * closed here, so a busy UI holds two frames at most, not a queue of them.
     */
    private fun offer(frame: ForkVideoFrame) {
        if (frame.generation != generation.get()) {
            frame.close()
            return
        }
        pending.getAndSet(frame)?.close()
        if (drainQueued.compareAndSet(false, true)) EventQueue.invokeLater(::drain)
    }

    private fun drain() {
        drainQueued.set(false)
        val frame = pending.getAndSet(null) ?: return
        if (frame.generation != generation.get()) {
            frame.close()
            return
        }
        replace(frame)
    }

    private fun clearFrame() {
        pending.getAndSet(null)?.close()
        EventQueue.invokeLater { replace(null) }
    }

    /**
     * Puts [frame] up and retires the one it replaces. Compose draws on this thread and reads
     * [shown] as it draws, so a frame off screen is never drawn again; it is closed a moment later
     * all the same, for a layer still holding it (Skia keeps the pixels alive for anything that
     * has them, closing only lets go of ours).
     */
    private fun replace(frame: ForkVideoFrame?) {
        val old = shown.value
        if (old === frame) return
        shown.value = frame
        if (old != null) retired.addLast(old to System.nanoTime())
        sweep()
    }

    private fun sweep() {
        val now = System.nanoTime()
        while (retired.isNotEmpty() && now - retired.first().second >= RETIRE_NS) retired.removeFirst().first.close()
        if (retired.isNotEmpty() && !sweepQueued) {
            sweepQueued = true
            javax.swing.Timer((RETIRE_NS / 1_000_000).toInt() + 20) {
                sweepQueued = false
                sweep()
            }.apply { isRepeats = false }.start()
        }
    }
}

/** Fork: one frame of a music video, its song, and the play it belongs to. */
internal class ForkVideoFrame(val videoId: String, val generation: Long, val image: Image) {
    fun close() = image.close()
}

/**
 * Fork: [DesktopPlayerHost.MusicVideo] — the playing music video's picture, fitted into these bounds
 * ([fit]) or cropped to fill them, as the phone's AndroidMusicVideo: nothing until a frame of
 * [videoId] has landed (reported through [onRenderedChanged], so the player keeps its still art or
 * spinner under it), then a 320 ms fade in.
 */
@Composable
internal fun ForkMusicVideoSurface(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) {
    DisposableEffect(Unit) {
        ForkMusicVideo.claim()
        onDispose { ForkMusicVideo.release() }
    }
    val stalled by ForkMusicVideo.stalled.collectAsState()
    // Derived, so the surface recomposes when a frame of this video first lands, not on every frame.
    val hasFrame by remember(videoId) { derivedStateOf { ForkMusicVideo.shown.value?.videoId == videoId } }
    val rendered = hasFrame && !stalled
    val report by rememberUpdatedState(onRenderedChanged)
    LaunchedEffect(rendered) { report(rendered) }
    DisposableEffect(Unit) { onDispose { report(false) } }
    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "forkMusicVideoAlpha",
    )
    Canvas(modifier.clipToBounds().graphicsLayer { this.alpha = alpha }) {
        val frame = ForkMusicVideo.shown.value?.takeIf { it.videoId == videoId } ?: return@Canvas
        val image = frame.image
        val imageWidth = image.width.toFloat()
        val imageHeight = image.height.toFloat()
        if (imageWidth <= 0f || imageHeight <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val aspect = imageWidth / imageHeight
        // The picture at its own shape: letterboxed into the bounds, or overflowing and cropped.
        val wider = size.width / size.height > aspect
        val (width, height) = if (wider == fit) size.height * aspect to size.height else size.width to size.width / aspect
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        drawIntoCanvas {
            it.nativeCanvas.drawImageRect(
                image,
                Rect.makeWH(imageWidth, imageHeight),
                Rect.makeXYWH(left, top, width, height),
                SamplingMode.LINEAR,
                null,
                true,
            )
        }
    }
}

/** Fork: the switch, in Settings > Appearance under the animated canvas rows. */
@Composable
internal fun ForkMusicVideoSwitch() {
    val on by ForkMusicVideo.enabled.collectAsState()
    SettingsToggle(ForkMusicVideo.TITLE, ForkMusicVideo.SUBTITLE, on, ForkMusicVideo::setEnabled)
}

