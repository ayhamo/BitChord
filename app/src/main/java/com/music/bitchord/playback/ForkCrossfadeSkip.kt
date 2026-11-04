package com.music.bitchord.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: crossfade on manual skip, for [CrossfadeController] (fork-only).
 *
 * Next / Previous run the controller's own arm → blend → handoff, cued now
 * instead of at the track's end: [crossfadeSkip] has [CrossfadeController.begin]
 * load the chosen item rather than the next one. The controller keeps one-line
 * `// fork` hooks into this; it lends what this needs of its private state as
 * the lambdas below, so none of its own lines had to change visibility.
 *
 * Runs on the playback thread, as the controller does.
 */
internal class ForkCrossfadeSkip(
    /** Nothing in flight (`Phase.IDLE`). */
    private val idle: () -> Boolean,
    /** The standby is loading the incoming track (`Phase.ARMING`). */
    private val arming: () -> Boolean,
    /** Whether the session has moved to the incoming player yet. */
    private val handedOff: () -> Boolean,
    /** The player the session was on when the transition began, while one runs. */
    private val outgoing: () -> ExoPlayer?,
    /** The session's player now. */
    private val active: () -> ExoPlayer,
    /** The crossfade slider's length, 0 when off. */
    private val configuredFadeMs: () -> Long,
    private val versionSwapActive: () -> Boolean,
    /** Automix's own fade length when the slider is off (`DEFAULT_SMART_FALLBACK_SECONDS`). */
    private val smartFallbackMs: Long,
    /** Arms a blend of [Long] ms into item [Int], cued now; false when it can't. */
    private val begin: (fadeMs: Long, toIndex: Int) -> Boolean,
    private val bail: () -> Unit,
) {

    /**
     * Where a manual skip is headed while [crossfadeSkip] arms it, or
     * [C.INDEX_UNSET]. A skip that never becomes audible — the incoming track
     * not ready in time, a pause, an error — still has to happen, so the
     * controller's `bail()` carries it out plainly ([take]).
     */
    private var skipTarget = C.INDEX_UNSET

    /** The song a skip is headed to, by id: "Play next" or a row moved above it while arming shifts its index. */
    private var skipTargetId: String? = null

    /**
     * A skip [take] carries out plainly, a moment after the blend gave up: the song it is headed
     * to (by index on [carryPlayer] and by id), and the posted seek. See [take].
     */
    private var carryIndex = C.INDEX_UNSET
    private var carryId: String? = null
    private var carryPlayer: ExoPlayer? = null
    private var carryTask: Runnable? = null
    private var carryHandler: android.os.Handler? = null

    /** Whether the session's mask ([ForkSkipMaskPlayer]) has been told this skip is showing, and not since told otherwise. */
    private var maskShown = false

    /**
     * Called when the session's player starts or stops reporting the chosen song while a skip
     * arms ([pendingSkipIndex]); the mask passes it on to the session's listeners (fork, L10).
     */
    var onMaskChanged: () -> Unit = {}

    /**
     * Whether the blend in flight was started by Next / Previous rather than a
     * track running out. Read by the service at the handoff, so a skipped song
     * is not booked as finished (scrobbles, "sleep after this song"). A skip also
     * hands over at once (`driveFade`), so the screen shows the chosen song as soon
     * as it plays (its audio still blends). Safe: mid-song, the outgoing player
     * isn't reading the next file.
     */
    fun isSkipBlend(): Boolean = skipTarget != C.INDEX_UNSET

    /**
     * The song a manual skip is headed to while it is still arming (its track loading, the session
     * still on the old player), else [C.INDEX_UNSET]. The session reports it as current meanwhile:
     * Media3's controller shows the next song the moment Next is pressed, and a session still
     * answering with the old one flipped the screen back until the handoff (fork).
     */
    fun pendingSkipIndex(): Int = when {
        carryIndex != C.INDEX_UNSET -> lookUp(carryPlayer, carryIndex, carryId)
        !handedOff() && !idle() -> skipIndex()
        else -> C.INDEX_UNSET
    }

    /** [skipTarget], looked up again by [skipTargetId] when the queue moved under it. */
    private fun skipIndex(): Int = lookUp(outgoing(), skipTarget, skipTargetId)

    /** [index] on [player] if the song [id] is still there, else wherever it moved, else [C.INDEX_UNSET]. */
    private fun lookUp(player: Player?, index: Int, id: String?): Int {
        val out = player ?: return index
        if (index == C.INDEX_UNSET || id == null) return index
        if (index in 0 until out.mediaItemCount && out.getMediaItemAt(index).mediaId == id) return index
        return (0 until out.mediaItemCount).firstOrNull { out.getMediaItemAt(it).mediaId == id } ?: C.INDEX_UNSET
    }

    /** Whether a manual skip is blending under a setting that allows it: the slider, or Automix's fallback. */
    fun fadeOn(): Boolean =
        skipTarget != C.INDEX_UNSET && (configuredFadeMs() > 0L || AppSettings.smartFadeEnabled.value)

    /**
     * Next / Previous as a blend into [toIndex] rather than a cut (fork-only).
     *
     * With a crossfade length set, or with Automix on (then a plain fade, see below): nothing in
     * flight, playing, no party, no version swap. The fade runs the same arm →
     * blend → handoff as a track running out, except it is cued now: `begin`
     * loads [toIndex] instead of the next item and the fade point is "as soon
     * as the incoming track is ready". Returns false when it didn't take the
     * skip; the caller then skips the upstream way.
     */
    fun crossfadeSkip(toIndex: Int): Boolean {
        // A second press while the first skip is still arming: carry the first
        // one out now (see [take]) and let the caller skip on from there plainly.
        // [toIndex] was read before that seek, so it is stale. Likewise while a
        // given-up skip waits to be carried out.
        if (carryTask != null) {
            runCarry()
            return false
        }
        if (arming() && skipTarget != C.INDEX_UNSET) {
            bail()
            // Now, not [CARRY_DELAY_MS] on: the caller's `onSkipRequested()` drops a carry still
            // waiting, and its plain skip then started from the old song, so Next twice moved one
            // song. The first skip's song is skipped past at once, so its cache entry doesn't matter.
            runCarry()
            return false
        }
        if (!com.music.bitchord.fork.ForkSettings.crossfadeOnSkip.value) return false
        // One stream to the DAC: no blend, as a song running out (planAutoTransition).
        if (BitPerfectOutput.status.value.active) return false
        if (!idle()) return false
        if (versionSwapActive() || ListenTogether.state.value.inParty) return false
        val out = active()
        if (!out.isPlaying || toIndex == out.currentMediaItemIndex) return false
        // Direct Source Stream can take longer to answer than a skip waits to arm: the old song would
        // play on under the new title, then cut to silence. Its own way to skip wins (a server's song doesn't use it).
        if (DirectSourceGate.isActive() && toIndex in 0 until out.mediaItemCount && !com.music.bitchord.data.navidrome.NavidromeSettings.isNavidromeId(out.getMediaItemAt(toIndex).mediaId)) return false
        if (toIndex !in 0 until out.mediaItemCount) return false
        // With Automix on, a skip blends too (there is no time to analyse it, so a plain fade of the
        // slider's length, or Automix's own fallback with the slider off); songs running out are
        // still Automix's, planned at the end as before.
        val fade = configuredFadeMs().takeIf { it > 0L }
            ?: if (AppSettings.smartFadeEnabled.value) smartFallbackMs else 0L
        if (fade <= 0L) return false
        skipTarget = toIndex
        skipTargetId = out.getMediaItemAt(toIndex).mediaId
        if (!begin(fade, toIndex)) {
            skipTarget = C.INDEX_UNSET
            return false
        }
        // The session now reports the chosen song (see [pendingSkipIndex]): tell its listeners,
        // or the notification, Android Auto and the widget showed the old one until the handoff.
        maskShown = true
        onMaskChanged()
        return true
    }

    /**
     * Forgets the skip. [ended]: the controller's `finish()`, after which the session stops
     * reporting the chosen song; when that happens with no seek or handoff to say so (a skip
     * dropped while arming, with nothing to carry out), the mask's listeners are told. Every
     * other caller is answering a seek, a transition or a skip about to be made, whose own event
     * reaches them, and which supersedes a skip waiting to be carried out ([take]).
     */
    fun drop(ended: Boolean = false) {
        val announce = ended && maskShown && carryIndex == C.INDEX_UNSET
        skipTarget = C.INDEX_UNSET
        maskShown = false
        if (!ended) cancelCarry()
        if (announce) onMaskChanged()
    }

    /** A new queue or a jump replaces a pending skip. */
    fun dropOn(transitionReason: Int) {
        if (transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
            transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
        ) drop()
    }

    /** A skip gives a slow stream only a few seconds; then it happens plainly (see [take]). */
    fun armTimeoutMs(targetIndex: Int, upstreamMs: Long): Long =
        if (targetIndex != C.INDEX_UNSET) SKIP_ARM_TIMEOUT_MS else upstreamMs

    /** A standby whose stream failed will never get ready: a skip bails now rather than hold the old song, and the new title at 0:00. */
    fun failed(into: Player): Boolean = isSkipBlend() && into.playerError != null

    /**
     * What `bail()` does after `finish()` (which forgets it) when nothing was audible yet: a
     * manual skip that never got to blend still happens.
     *
     * A moment later rather than at once, with the old song muted meanwhile and the session still
     * reporting the chosen one ([pendingSkipIndex]): `finish()` has just retired the standby,
     * whose loader lets go of the song's cache entry asynchronously (and holds it for a whole
     * stream resolve, the slow case the skip's timeout is for). The session player's cache
     * source finding the entry still locked streams the whole song past the cache, so it played
     * but wasn't kept, its data fetched twice (read from `AudioCache`'s
     * `FLAG_IGNORE_CACHE_ON_ERROR` path, not measured).
     */
    fun take(): () -> Unit {
        val skip = skipIndex()
        val out = outgoing()
        if (skip == C.INDEX_UNSET || out == null || skip !in 0 until out.mediaItemCount) return {}
        // Set now, so the `finish()` in between doesn't announce the mask going off.
        carryIndex = skip
        carryId = skipTargetId
        carryPlayer = out
        return {
            if (carryPlayer === out) {
                out.volume = 0f
                val handler = android.os.Handler(out.applicationLooper)
                val task = Runnable { runCarry() }
                carryHandler = handler
                carryTask = task
                handler.postDelayed(task, CARRY_DELAY_MS)
            }
        }
    }

    /** Seeks to the skip [take] is carrying out, now; false when there is none. */
    private fun runCarry(): Boolean {
        val out = carryPlayer ?: return false
        val skip = lookUp(out, carryIndex, carryId)
        cancelCarry()
        if (skip !in 0 until out.mediaItemCount) return false
        Log.d(TAG, "skip carried out plainly")
        out.seekTo(skip, 0L)
        return true
    }

    /** Forgets a skip waiting to be carried out, the old song audible again. */
    private fun cancelCarry() {
        carryTask?.let { carryHandler?.removeCallbacks(it) }
        if (carryTask != null) carryPlayer?.volume = 1f
        carryTask = null
        carryHandler = null
        carryPlayer = null
        carryIndex = C.INDEX_UNSET
        carryId = null
    }

    /**
     * Carries a skip that is still arming out plainly, at [positionMs] in the chosen song (fork,
     * L10). The session shows that song at 0:00 while the skip arms ([pendingSkipIndex]), so a
     * seek bar dragged meanwhile means a place in it; passed on as is, the seek went to the old
     * song still playing underneath, whose position jump then dropped the skip: Next, drag, and
     * back in the previous song. Returns false when no skip is arming.
     */
    fun carryOut(positionMs: Long): Boolean {
        val skip = pendingSkipIndex()
        val out = carryPlayer ?: outgoing()
        if (skip == C.INDEX_UNSET || out == null) return false
        drop()
        bail()
        out.seekTo(skip, positionMs.coerceAtLeast(0L))
        return true
    }

    /**
     * At a skip's handoff: the queue the incoming player carries is a copy taken when the skip
     * armed, and the controller only carries a pure append across (`reconcileQueue`). A skip arms
     * on every Next for up to [SKIP_ARM_TIMEOUT_MS], and an edit in that window ("Play next", a
     * row removed or dragged, shuffle switched) went to the outgoing player, which fires no
     * transition: after the handoff an inserted row was gone and the last one doubled, a removed
     * row came back. So the incoming player takes the outgoing one's queue around the song it is
     * playing (removing and adding around the current item doesn't touch its playback).
     *
     * Returns false when the song the skip was headed for was removed meanwhile: the caller
     * bails, and the old song plays on, as with the row gone it would have.
     */
    fun mirrorQueue(out: Player, into: Player): Boolean {
        if (!isSkipBlend()) return true
        val target = skipIndex()
        if (target == C.INDEX_UNSET) {
            Log.d(TAG, "skip target removed while arming: not handing off")
            return false
        }
        maskShown = false // the handoff's own events say what the session plays now
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        val current = into.currentMediaItemIndex
        if (sameAround(items, target, into, current)) return true
        if (into.mediaItemCount > current + 1) into.removeMediaItems(current + 1, into.mediaItemCount)
        if (current > 0) into.removeMediaItems(0, current)
        if (target > 0) into.addMediaItems(0, items.subList(0, target))
        if (target + 1 < items.size) into.addMediaItems(items.subList(target + 1, items.size))
        into.shuffleModeEnabled = out.shuffleModeEnabled
        Log.d(TAG, "skip handoff: queue edited while arming, ${items.size} item(s) carried across")
        return true
    }

    private fun sameAround(items: List<MediaItem>, target: Int, into: Player, current: Int): Boolean =
        target == current && into.mediaItemCount == items.size &&
            items.indices.all { it == current || into.getMediaItemAt(it) == items[it] }

    companion object {
        private const val TAG = "BitChordCrossfade"
        const val SKIP_ARM_TIMEOUT_MS = 3_000L

        /** Longer than the retired standby takes to let go of a cache entry; see [take]. */
        const val CARRY_DELAY_MS = 250L
    }
}

/**
 * Fork: when each song's synced lyrics were asked for, for the loop mix. A lookup still running
 * after [FORK_LYRICS_WAIT_MS] no longer holds the loop mix back: the song is cleared by the vocal
 * mask instead ([standIn]), as one with no synced lyrics is. Waiting on slow lyrics providers is
 * one of the ways a pair ended up with the plain blend. [spans] is the controller's own map of
 * the lyrics that did land.
 */
internal class ForkLyricsWait<T : Any>(
    private val spans: Map<String, T>,
    private val standIn: T,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val askedAt = ConcurrentHashMap<String, Long>()

    /** Called as the lookup for [id] starts. */
    fun asked(id: String) {
        askedAt[id] = now()
    }

    /** [id]'s lyrics, or the stand-in once they've been asked for longer than [FORK_LYRICS_WAIT_MS], else null (still waiting). */
    fun spansFor(id: String): T? = spans[id]
        ?: askedAt[id]?.takeIf { now() - it >= FORK_LYRICS_WAIT_MS }?.let { standIn }

    /** For the plan's key: whether each side used the stand-in, so the pair plans again once real lyrics land. */
    fun keyPart(out: T?, into: T?): String = "|${out === standIn}${into === standIn}"

    companion object {
        /** How long a loop mix waits on a song's synced lyrics. */
        const val FORK_LYRICS_WAIT_MS = 8_000L
    }
}
