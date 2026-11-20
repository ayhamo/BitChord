package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Fork: the phone's quality upgrade without the audible cut.
 *
 * Upstream's [PlaybackService] `swapIn` replaces the playing item with the addon's copy, seeks and
 * prepares. The audition before it fills the cache, so what is left is a source rebuilt from disk,
 * a decoder stood up and an AudioTrack flushed: measured upstream at about a second, and heard as
 * the song cutting out the moment the lossless copy arrives. The PC fork removed the same cut by
 * mixing the two copies in its own engine (`desktopApp/.../DesktopAudioEngine.kt` `forkQueueSwap`,
 * a short equal-power cross). The phone has no mixer of its own; it has two ExoPlayers.
 *
 * Why a *cover* and not the version switch's hand-over. The phone already crossfades between two
 * cuts of a song (`smoothSwapCurrentTrackVersion`): it loads the other cut on the standby player,
 * fades, and moves the session onto the standby (`adoptPlayerForVersionSwap`). Reused for an
 * upgrade that hand-over is wrong in three places: it books the adopted player's song as a new
 * one (`onTrackBecameCurrent`: loudness read again, the upgrade verdict ended, history touched,
 * the exact double booking upstream's `swappingMediaId` exists to stop); the decoder and format
 * the stats show come from a listener the standby never had; and everything upstream does after
 * the swap (`watchUpgrade`, `lookForBetterCopy(player)`, the swap seam log) holds the player the
 * session was on. So the session never moves here. The standby plays the copy that is *already*
 * playing, from the cache, lined up with it; the session player fades under it ([COVER_IN_MS]) and
 * then takes upstream's swap muted; once it plays the new copy it is lined up on the cover and
 * faded back in over [LAND_FADE_MS] (the version switch's length). Upstream's swap runs unchanged,
 * on the same player, with the same bookkeeping; only what is heard across it changes.
 *
 * The fades are equal gain (out + in = 1), not the version switch's equal power: both fades are
 * between copies of one recording at the same point (the first between the same copy twice), whose
 * sum is coherent, so equal power would swell by up to 3 dB in the middle.
 *
 * Position: the copies are lined up on the players' clocks plus the offset the window check
 * measured between them ([ForkWindowCheckPhone], `land`'s shiftMs), which also keeps a different
 * cut out before the swap. A copy the check could not read (HLS/DASH) is lined up on the clocks
 * alone, as before; one offset by a few hundred ms is heard as a short doubling in the fade.
 *
 * Every case [ForkUpgradeCoverPlan.decide] turns down, and every failure on the way (the cover not
 * ready in time, a skip, a seek, a pause, the new copy not playing in time), leaves upstream's swap
 * as it was: a cut. Nothing here can stop a swap upstream would have made.
 */
internal class ForkUpgradeCover(
    private val scope: CoroutineScope,
    private val active: () -> ExoPlayer?,
    private val standby: () -> ExoPlayer?,
    /** An Automix/crossfade transition or a version switch holds the standby player. */
    private val transitioning: () -> Boolean,
    /** Where the next transition out of this song starts (CrossfadeController.plannedTransitionFor). */
    private val plannedTransition: (String) -> Long?,
    /** A Cast or watch speaker plays instead of this phone. */
    private val onSpeaker: () -> Boolean,
    /** Puts the standby's loudness stage on [String]'s gain (spareLoudness().track). */
    private val trackStandbyLoudness: (String) -> Unit,
) {

    /**
     * True from the moment a cover is raised until it is taken down. Read by CrossfadeController
     * through its `versionSwapActive` hook, so Automix neither arms nor blends a skip on the
     * standby player this holds; the plan keeps a cover clear of a planned transition anyway
     * ([ForkUpgradeCoverPlan.TRANSITION_MARGIN_MS]).
     */
    @Volatile
    var busy: Boolean = false
        private set

    /**
     * Before upstream's swap: decides, and if covering, starts the playing copy on the standby,
     * lines it up, and fades the session player out under it. Null means "cut as upstream does";
     * nothing is left changed then.
     */
    suspend fun raise(mediaId: String): Cover? = withContext(Dispatchers.Main) { raiseOnMain(mediaId) }

    private suspend fun raiseOnMain(mediaId: String): Cover? {
        val p = active() ?: return null
        val s = standby()
        val item = p.currentMediaItem
        val verdict = if (busy) {
            ForkUpgradeCoverPlan.Verdict.Cut("a cover is already up")
        } else {
            ForkUpgradeCoverPlan.decide(
                ForkUpgradeCoverPlan.Inputs(
                    hasStandby = s != null && s !== p,
                    sameSong = item?.mediaId == mediaId,
                    playing = p.isPlaying && p.playWhenReady,
                    inParty = ListenTogether.state.value.inParty,
                    exclusiveOutput = BitPerfectOutput.ready.value || BitPerfectOutput.status.value.active,
                    onSpeaker = onSpeaker(),
                    transitioning = transitioning(),
                    speedMatches = abs(p.playbackParameters.speed - AppSettings.playbackSpeed.value) <
                        ForkUpgradeCoverPlan.SPEED_EPSILON,
                    playingUpgradedCopy = ForkUpgradeCoverPlan.isUpgradedCopy(
                        item?.localConfiguration?.uri?.getQueryParameter(QualityUpgrade.MARKER),
                    ),
                    positionMs = p.currentPosition,
                    plannedTransitionMs = plannedTransition(mediaId),
                ),
            )
        }
        if (verdict is ForkUpgradeCoverPlan.Verdict.Cut || s == null || item == null) {
            val why = (verdict as? ForkUpgradeCoverPlan.Verdict.Cut)?.reason ?: "nothing to cover with"
            TrackLog.d(TAG, "upgrade cover: none ($why); the swap cuts as before", about = mediaId)
            return null
        }

        busy = true
        var raised = false
        try {
            val stillOn = { p === active() && p.currentMediaItem?.mediaId == mediaId && p.playWhenReady }
            val coverOk = { s.playerError == null && s.playbackState != Player.STATE_IDLE && s.playbackState != Player.STATE_ENDED }

            s.stop()
            s.clearMediaItems()
            s.volume = 0f
            s.skipSilenceEnabled = p.skipSilenceEnabled
            s.repeatMode = Player.REPEAT_MODE_OFF
            s.shuffleModeEnabled = false
            s.playbackParameters = p.playbackParameters
            trackStandbyLoudness(mediaId)
            // The item as it plays, same URI: the same cache entry, read from disk. Not the
            // upgraded one: a marked URI resolves to the parked stream, and that is the copy the
            // session player is about to take.
            s.setMediaItem(item, p.currentPosition)
            s.playWhenReady = false
            s.prepare()
            if (!await(ForkUpgradeCoverPlan.RAISE_READY_TIMEOUT_MS, { stillOn() && s.playerError == null }) {
                    s.playbackState == Player.STATE_READY
                }
            ) {
                return fail(mediaId, "the playing copy did not come up on the standby")
            }
            s.play()
            if (!await(ForkUpgradeCoverPlan.START_TIMEOUT_MS, { stillOn() && coverOk() }) { s.isPlaying }) {
                return fail(mediaId, "the standby did not start")
            }
            // Running now, still silent: put it where the session player is *now*. A near seek
            // inside what it already buffered, as the version switch does, so the drift from the
            // waits above collapses to this seek's own resume time.
            s.seekTo(p.currentPosition)
            if (!await(ForkUpgradeCoverPlan.RESYNC_TIMEOUT_MS, { stillOn() && coverOk() }) {
                    s.playbackState == Player.STATE_READY && s.isPlaying
                }
            ) {
                return fail(mediaId, "the standby did not line up")
            }
            val apart = abs(s.currentPosition - p.currentPosition)
            if (apart > ForkUpgradeCoverPlan.ALIGN_TOLERANCE_MS) {
                return fail(mediaId, "the copies sit ${apart}ms apart")
            }
            if (!fade(ForkUpgradeCoverPlan.COVER_IN_MS, { stillOn() && coverOk() }) { inGain ->
                    s.volume = inGain
                    p.volume = 1f - inGain
                }
            ) {
                return fail(mediaId, "the song moved while the cover faded in")
            }
            p.volume = 0f
            s.volume = 1f
            raised = true
            TrackLog.d(TAG, "upgrade cover: up at ${p.currentPosition}ms (${apart}ms apart)", about = mediaId)
            return Cover(mediaId, p, s)
        } finally {
            if (!raised) withContext(NonCancellable) { takeDown(p, s) }
        }
    }

    /** A raised cover; [land] after upstream's swap, or it is taken down on its own. */
    inner class Cover internal constructor(
        private val mediaId: String,
        private val p: ExoPlayer,
        private val s: ExoPlayer,
    ) {
        private var landed = false

        /**
         * Upstream's swap returns early in places (a transition caught at the last moment, the
         * queue moved on, the parked stream dropped) and then never reaches [land]. Its block runs
         * on the main thread without suspending, so this, queued behind it, finds out right after.
         */
        private val deadline: Job = scope.launch {
            delay(ForkUpgradeCoverPlan.LAND_DEADLINE_MS)
            if (!landed) {
                landed = true
                TrackLog.d(TAG, "upgrade cover: the swap did not happen; fading back", about = mediaId)
                lower()
            }
        }

        /**
         * Called right after upstream's `replaceMediaItem`/`seekTo`/`prepare` on [p]. [shiftMs] is the
         * new copy's time less the old one's at the same moment (the window check's offsets).
         */
        fun land(upgradedUri: String, shiftMs: Long = 0L) {
            if (landed) return
            landed = true
            deadline.cancel()
            p.volume = 0f
            scope.launch { runLand(upgradedUri, shiftMs) }
        }

        private suspend fun runLand(upgradedUri: String, shiftMs: Long) {
            var userSeek = false
            var ownSeek = false
            val listener = object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (!ownSeek && reason == Player.DISCONTINUITY_REASON_SEEK) userSeek = true
                }
            }
            p.addListener(listener)
            try {
                val ok = {
                    !userSeek && p === active() && p.playWhenReady &&
                        p.currentMediaItem?.mediaId == mediaId &&
                        p.currentMediaItem?.localConfiguration?.uri?.toString() == upgradedUri &&
                        s.playerError == null && s.playbackState != Player.STATE_IDLE &&
                        s.playbackState != Player.STATE_ENDED
                }
                val startedAt = SystemClock.elapsedRealtime()
                // Muted the whole time, whatever else writes its volume: the cover is what is heard.
                if (!await(ForkUpgradeCoverPlan.LAND_READY_TIMEOUT_MS, { p.volume = 0f; ok() }) {
                        p.playbackState == Player.STATE_READY && p.isPlaying
                    }
                ) {
                    TrackLog.d(TAG, "upgrade cover: the new copy did not play in time; cutting over", about = mediaId)
                    return
                }
                val readyMs = SystemClock.elapsedRealtime() - startedAt
                // Upstream seeked to where the song was when the swap began; the cover has played on
                // since. A near seek into the copy the audition warmed, while it is still silent.
                ownSeek = true
                p.seekTo(p.currentMediaItemIndex, (s.currentPosition + shiftMs).coerceAtLeast(0L))
                ownSeek = false
                if (!await(ForkUpgradeCoverPlan.RESYNC_TIMEOUT_MS, { p.volume = 0f; ok() }) {
                        p.playbackState == Player.STATE_READY && p.isPlaying
                    }
                ) {
                    TrackLog.d(TAG, "upgrade cover: the new copy did not line up; cutting over", about = mediaId)
                    return
                }
                val apart = abs(s.currentPosition + shiftMs - p.currentPosition)
                if (!fade(ForkUpgradeCoverPlan.LAND_FADE_MS, ok) { inGain ->
                        p.volume = inGain
                        s.volume = 1f - inGain
                    }
                ) {
                    TrackLog.d(TAG, "upgrade cover: interrupted while fading back", about = mediaId)
                    return
                }
                TrackLog.d(
                    TAG,
                    "upgrade cover: faded onto the new copy at ${p.currentPosition}ms " +
                        "(ready ${readyMs}ms after the swap, ${apart}ms apart); the swap seam above was muted",
                    about = mediaId,
                )
            } finally {
                p.removeListener(listener)
                withContext(NonCancellable) { takeDown(p, s) }
            }
        }

        /** The swap did not happen: the session player still plays the old copy, muted, so fade it back. */
        private suspend fun lower() {
            try {
                val ok = {
                    p === active() && p.playWhenReady && p.currentMediaItem?.mediaId == mediaId &&
                        s.playerError == null && s.playbackState == Player.STATE_READY
                }
                fade(ForkUpgradeCoverPlan.COVER_IN_MS, ok) { inGain ->
                    p.volume = inGain
                    s.volume = 1f - inGain
                }
            } finally {
                withContext(NonCancellable) { takeDown(p, s) }
            }
        }
    }

    /** Standby stopped and emptied, session player at full volume, the hold released. */
    private fun takeDown(p: ExoPlayer, s: ExoPlayer) {
        s.stop()
        s.clearMediaItems()
        s.volume = 1f
        if (p === active()) p.volume = 1f
        busy = false
    }

    private fun fail(mediaId: String, why: String): Cover? {
        TrackLog.d(TAG, "upgrade cover: none ($why); the swap cuts as before", about = mediaId)
        return null
    }

    /** Waits for [done]; false on timeout or as soon as [ok] fails. */
    private suspend fun await(timeoutMs: Long, ok: () -> Boolean, done: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (!ok()) return false
            if (done()) return true
            if (SystemClock.elapsedRealtime() >= until) return false
            delay(ForkUpgradeCoverPlan.TICK_MS)
        }
    }

    /** Runs a fade over [lengthMs], [apply] given the incoming gain; false as soon as [ok] fails. */
    private suspend fun fade(lengthMs: Long, ok: () -> Boolean, apply: (Float) -> Unit): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (!ok()) return false
            val progress = ForkUpgradeCoverPlan.progress(SystemClock.elapsedRealtime() - start, lengthMs)
            apply(ForkUpgradeCoverPlan.inGain(progress))
            if (progress >= 1f) return true
            delay(ForkUpgradeCoverPlan.TICK_MS)
        }
    }

    private companion object {
        const val TAG = "BitChord"
    }
}

/** Fork: [ForkUpgradeCover]'s decisions and timings, plain Kotlin for unit tests. */
internal object ForkUpgradeCoverPlan {

    /** The cover fading in over the same copy: short, as the two are the same audio. */
    const val COVER_IN_MS = 150L

    /** The cover fading out over the new copy: the version switch's 550 ms. */
    const val LAND_FADE_MS = 550L

    /** The playing copy coming up on the standby from the cache; past this, cut as before. */
    const val RAISE_READY_TIMEOUT_MS = 2_500L

    /** The standby starting once ready (the version switch's 600 ms). */
    const val START_TIMEOUT_MS = 600L

    /** A near seek inside a buffer settling (the version switch allows 300). */
    const val RESYNC_TIMEOUT_MS = 300L

    /**
     * How long the cover plays while the session player rebuilds its source. Generous because the
     * cover is music, not silence; bounded so a copy that never plays still ends in upstream's cut,
     * and so a cover stays well inside [TRANSITION_MARGIN_MS].
     */
    const val LAND_READY_TIMEOUT_MS = 10_000L

    /** Upstream's swap block runs without suspending; a cover not landed by this was not swapped. */
    const val LAND_DEADLINE_MS = 1_000L

    /** Further apart than this after lining up, the cover would be heard as an echo. */
    const val ALIGN_TOLERANCE_MS = 120L

    /**
     * No cover this close to a planned Automix/crossfade start: the cover holds the standby player
     * that transition needs, for up to [LAND_READY_TIMEOUT_MS] plus the fades.
     */
    const val TRANSITION_MARGIN_MS = 20_000L

    const val SPEED_EPSILON = 0.01f

    const val TICK_MS = 16L

    data class Inputs(
        val hasStandby: Boolean,
        val sameSong: Boolean,
        val playing: Boolean,
        val inParty: Boolean,
        val exclusiveOutput: Boolean,
        val onSpeaker: Boolean,
        val transitioning: Boolean,
        val speedMatches: Boolean,
        val playingUpgradedCopy: Boolean,
        val positionMs: Long,
        val plannedTransitionMs: Long?,
    )

    sealed interface Verdict {
        data object Cover : Verdict
        data class Cut(val reason: String) : Verdict
    }

    /**
     * Whether to cover the swap, or let upstream cut. Each "cut" case:
     *  - no standby, or the song changed: nothing to cover with, or nothing to cover;
     *  - paused: a cut while paused is not heard;
     *  - Listen Together: party sync seeks the session player and expects one stream (as Automix
     *    stays off there);
     *  - bit-perfect output ready or active (USB direct, the built-in DAC's direct mode): it carries
     *    one stream to the DAC and mutes the rest, and the lossless copy is the one it would take, so
     *    two players can't be faded there (as CrossfadeController plays gapless there);
     *  - a Cast/watch speaker: the phone's players are not what is heard;
     *  - a transition or version switch holds the standby;
     *  - the session player's speed is not the listener's (an Automix stretch easing out): the cover
     *    would run at another rate;
     *  - the playing copy is itself an upgrade (`q=hifi…`, a lossy first pass): a marked URI
     *    resolves to the stream parked for the *new* swap (QualityUpgrade.forcedStream keys on the
     *    id), so the cover would read the wrong file into the old copy's cache entry;
     *  - a planned transition within [TRANSITION_MARGIN_MS].
     */
    fun decide(i: Inputs): Verdict = when {
        !i.hasStandby -> Verdict.Cut("no standby player")
        !i.sameSong -> Verdict.Cut("the song changed")
        !i.playing -> Verdict.Cut("paused, so the cut is not heard")
        i.inParty -> Verdict.Cut("in a Listen Together party")
        i.exclusiveOutput -> Verdict.Cut("bit-perfect output carries one stream")
        i.onSpeaker -> Verdict.Cut("playing on a speaker")
        i.transitioning -> Verdict.Cut("a transition or version switch holds the standby player")
        !i.speedMatches -> Verdict.Cut("the player's speed is easing")
        i.playingUpgradedCopy -> Verdict.Cut("the playing copy is itself an upgrade")
        i.plannedTransitionMs != null && i.plannedTransitionMs - i.positionMs < TRANSITION_MARGIN_MS ->
            Verdict.Cut("a transition starts in ${i.plannedTransitionMs - i.positionMs}ms")
        else -> Verdict.Cover
    }

    /** A `q` marker that [QualityUpgrade.forcedStream] would serve the parked stream for. */
    fun isUpgradedCopy(marker: String?): Boolean = marker?.startsWith("hifi") == true

    fun progress(elapsedMs: Long, lengthMs: Long): Float =
        if (lengthMs <= 0) 1f else (elapsedMs.toFloat() / lengthMs).coerceIn(0f, 1f)

    /** Equal gain: the outgoing copy gets 1 minus this, so coherent copies sum to the original. */
    fun inGain(progress: Float): Float = progress.coerceIn(0f, 1f)
}
