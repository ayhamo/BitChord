package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.math.abs

/** Fork: one decoded frame from [DesktopCanvasDecoder.forkNext]: its time, and whether it was converted. */
internal data class ForkDecodedFrame(val ptsMs: Long, val kept: Boolean)

/**
 * Fork: the audio clock a music video follows, read the way the lyrics read it.
 *
 * The engine publishes its position (taken off the sink, so already what is heard) every 250 ms of
 * audio, stamped with when it was true. Between readings it runs on at the listener's speed; not
 * while paused or while a seek's audio has yet to arrive (`awaitingAudio`), and never more than a
 * second past the last reading — a stalled engine is not a clock running on.
 * From Desktop Revived's `videoClockPosition` (DesktopMusicVideo.kt, 9b2c72c9), bounded.
 */
internal object ForkVideoClock {
    const val MAX_EXTRAPOLATE_MS = 1_000.0

    fun at(state: DesktopPlaybackState, speed: Float, nowNs: Long): Long {
        if (!state.isPlaying || state.awaitingAudio || state.positionSampledAtNanos == 0L) return state.positionMs
        val elapsedMs = ((nowNs - state.positionSampledAtNanos).coerceAtLeast(0L) / 1_000_000.0)
            .coerceAtMost(MAX_EXTRAPOLATE_MS)
        val at = state.positionMs + (elapsedMs * speed).toLong()
        return if (state.durationMs > 0) at.coerceAtMost(state.durationMs) else at
    }

    /** Whether the picture should be advancing: playing, and the audio actually reaching the ears. */
    fun running(state: DesktopPlaybackState): Boolean = state.isPlaying && !state.awaitingAudio
}

/**
 * Fork: holds a music video's frames to the audio clock — the phone's MusicVideoEngine.sync in
 * frame-by-frame terms.
 *
 * The phone's picture is a second ExoPlayer with a clock of its own, so it is steered: its rate
 * nudged up to 10 % for a small drift, a seek for one past 450 ms. Here frames are pulled from
 * FFmpeg one by one and each is put up when the audio reaches its time, so the picture has no
 * clock to drift: a frame ahead waits for the audio, a frame a little behind goes up at once (the
 * picture runs fast until it has caught up — the nudge), one more than [dropLateMs] behind is
 * decoded and dropped unconverted, and past [seekBehindMs] (or [seekAheadMs] ahead, after a jump
 * back) the decoder seeks.
 *
 * A seek lands on the keyframe before its target, often seconds before it, so after one the frames
 * read as far behind: they are decoded through, never answered with another seek, for up to
 * [catchUpLimitMs] (Desktop Revived's "FFmpeg may seek to an earlier keyframe and otherwise seek
 * forever", c7ca573f). The audio's own jumps come from its seek count, and from its clock moving
 * further than the time gone by allows (a jump nothing counted).
 *
 * Also keeps the count [slow] is read from: under Auto, a machine that drops most frames is moved
 * to a smaller picture.
 *
 * Not thread-safe; the decoding loop owns it. [lastShownPts], [catching] and [seekAtNs] are read by
 * the stall watcher.
 */
internal class ForkVideoPacer(
    private val frameMs: Long = 33L,
    private val seekBehindMs: Long = 1_500L,
    private val seekAheadMs: Long = 1_000L,
    private val dropLateMs: Long = 80L,
    private val seekLeadMs: Long = 300L,
    private val catchUpLimitMs: Long = 5_000L,
    private val jumpMs: Long = 1_500L,
    private val slowWindowMs: Long = 5_000L,
) {
    sealed interface Step {
        /** Put the frame up after [waitMs] (real time). */
        data class Show(val waitMs: Long) : Step
        /** Decoded, not shown. */
        data object Drop : Step
        /** Seek the decoder to [toMs] and decode on from there. */
        data class Seek(val toMs: Long) : Step
    }

    private var lastSeeks: Int? = null
    private var lastSong: String? = null
    private var prevClock = -1L
    private var prevNs = 0L

    @Volatile var catching = false
        private set
    @Volatile var seekAtNs = 0L
        private set
    @Volatile var lastShownPts = -1L
        private set

    /** Whether a frame has gone up since the last seek; a paused picture then waits. */
    var shownSinceSeek = false
        private set

    private var windowStartNs = 0L
    private var decoded = 0
    private var dropped = 0

    /** Whether, over the last window of steady playing, more than half the frames came too late. */
    var slow = false
        private set

    /**
     * A seek the audio is owed before the next frame — its seek count moved, its song is new to
     * this pacer, or its clock jumped — as the target, else null.
     */
    fun owedSeek(state: DesktopPlaybackState, clockMs: Long, speed: Float, nowNs: Long): Long? {
        val running = ForkVideoClock.running(state)
        val expected = prevClock + if (running) ((nowNs - prevNs) / 1_000_000.0 * speed).toLong() else 0L
        val jumped = prevClock >= 0 && abs(clockMs - expected) > jumpMs
        val first = lastSeeks == null || lastSong != state.song?.videoId
        val moved = state.seeks != lastSeeks
        prevClock = clockMs
        prevNs = nowNs
        lastSeeks = state.seeks
        lastSong = state.song?.videoId
        if (first && clockMs < seekBehindMs) {
            // A song from its start: the decoder is there already, no request needed.
            beginSeek(nowNs)
            return null
        }
        if (!first && !moved && !jumped) return null
        beginSeek(nowNs)
        return if (running) clockMs + seekLeadMs else clockMs
    }

    /** What to do with a frame at [ptsMs] decoded when the audio is at [clockMs]. */
    fun decide(ptsMs: Long, clockMs: Long, running: Boolean, speed: Float, nowNs: Long): Step {
        if (ptsMs < 0) return Step.Show(frameMs) // no time on the frame: paced at the stream's rate
        val catchingUp = catching && (nowNs - seekAtNs) / 1_000_000 < catchUpLimitMs
        val lag = clockMs - ptsMs
        if (!running) {
            // Paused, or a seek's audio still on its way: the frame at the clock, exactly.
            if (-lag > seekAheadMs && !catchingUp) return seek(clockMs, nowNs)
            if (lag > seekBehindMs && !catchingUp) return seek(clockMs, nowNs)
            return if (lag > frameMs) Step.Drop else Step.Show(0)
        }
        if (-lag > seekAheadMs && !catchingUp) return seek(clockMs + seekLeadMs, nowNs)
        if (lag > seekBehindMs && !catchingUp) return seek(clockMs + seekLeadMs, nowNs)
        count(nowNs, catchingUp, dropping = lag > dropLateMs)
        if (lag > dropLateMs) return Step.Drop
        val wait = if (lag >= 0) 0L else (-lag / speed.coerceAtLeast(0.1f)).toLong()
        return Step.Show(wait.coerceAtMost(seekAheadMs))
    }

    /** A frame at [ptsMs] went up. */
    fun shown(ptsMs: Long) {
        catching = false
        shownSinceSeek = true
        lastShownPts = ptsMs
    }

    /**
     * Whether the picture has stopped keeping up and the still art should come back: a seek still
     * catching up after [CATCH_UP_SHOWN_MS], or a running clock [STALL_MS] past the last frame.
     */
    fun stalled(clockMs: Long, running: Boolean, ended: Boolean, nowNs: Long): Boolean {
        if (!running || ended) return false
        if (catching) return (nowNs - seekAtNs) / 1_000_000 > CATCH_UP_SHOWN_MS
        return lastShownPts >= 0 && clockMs - lastShownPts > STALL_MS
    }

    private fun seek(toMs: Long, nowNs: Long): Step {
        beginSeek(nowNs)
        return Step.Seek(toMs.coerceAtLeast(0L))
    }

    private fun beginSeek(nowNs: Long) {
        catching = true
        seekAtNs = nowNs
        shownSinceSeek = false
    }

    private fun count(nowNs: Long, catchingUp: Boolean, dropping: Boolean) {
        if (catchingUp) return
        if (decoded == 0) windowStartNs = nowNs
        decoded++
        if (dropping) dropped++
        if ((nowNs - windowStartNs) / 1_000_000 >= slowWindowMs) {
            slow = decoded >= MIN_FRAMES_FOR_SLOW && dropped * 2 > decoded
            decoded = 0
            dropped = 0
        }
    }

    companion object {
        /** A seek's catch-up shorter than this keeps the last frame up; longer shows the still art. */
        const val CATCH_UP_SHOWN_MS = 700L

        /** The running audio this far past the last frame: the picture has stalled (a slow network). */
        const val STALL_MS = 2_500L

        private const val MIN_FRAMES_FOR_SLOW = 30
    }
}

/** Fork: which songs have a picture to play, and at what height — the phone's rules, PC numbers. */
internal object ForkVideoRules {

    /**
     * Where Auto opens. The phone's is 1080 (the top of YouTube's H.264 ladder), decoded in
     * hardware; here every frame is decoded, converted and uploaded in software, so Auto stays at
     * 720 (under half the pixels) and 1080 and up are a pick in the quality list.
     */
    const val AUTO_HEIGHT = 720

    /** Where a held height above this that would not decode (VP9/AV1 above 1080p) is retried. */
    const val STEP_DOWN_HEIGHT = 1080

    /** Where Auto goes on a machine that drops most frames ([ForkVideoPacer.slow]). */
    const val SLOW_HEIGHT = 480

    /** Narrower than this is a cover held still, not a picture worth showing (the phone's 1.2). */
    const val MIN_VIDEO_ASPECT = 1.2f

    /**
     * A music video from YouTube: not a server song, a station, a local file or a catalogue
     * track — only those have a video-only stream to ask for. The phone shows a picture for
     * `song.isVideo` alone; the PC's other libraries are ruled out by their ids as well.
     */
    fun eligible(song: Song?): Boolean =
        song != null && song.isVideo && song.localUri.isNullOrBlank() &&
            DesktopSearchClient.isVideoId(song.videoId) &&
            !com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(song.videoId)

    /** The height asked for: the held one (stepped down if it failed), else Auto (lower if slow). */
    fun capFor(held: Int?, steppedDown: Boolean, slow: Boolean): Int = when {
        held == null -> if (slow) SLOW_HEIGHT else AUTO_HEIGHT
        steppedDown -> minOf(held, STEP_DOWN_HEIGHT)
        else -> held
    }

    /** A stored height, or null (Auto) for anything a quality list would not offer. */
    fun storedHeight(value: Int): Int? = value.takeIf { it in 144..4320 }

    /**
     * The longer edge to decode at: the stream's own (the resolver already capped its height at
     * what was asked), never above what was asked for at 16:9 if the stream says nothing.
     */
    fun decodeEdge(streamWidth: Int, streamHeight: Int, cap: Int): Int {
        val own = maxOf(streamWidth, streamHeight)
        return if (own > 0) own else (cap * 16 + 8) / 9
    }
}
