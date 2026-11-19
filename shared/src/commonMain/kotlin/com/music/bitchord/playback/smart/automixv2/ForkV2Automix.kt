package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.AutomixStyle
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.ForkMixPicks
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.isSpokenOrLiveForTest
import kotlin.concurrent.Volatile
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fork: Automix v2 ([AutomixStyle.V2]) inside upstream's planner. Its one way in is
 * [planTransition], the first line of upstream's `planTransition` (a SMART plan only); it returns
 * null for BitChord's own style, so upstream's planner runs exactly as it did.
 *
 * What it does with a pair, the way the extension's crossfade.js asks automix.js for a plan:
 * - Both songs' [TrackAnalysis] are read as the port's [ForkV2Analysis] ([ForkV2FromTrackAnalysis]),
 *   with the whole-song evidence each app measured while analysing ([evidence]: sub-bass ratio, bass
 *   and loudness curves, pitch profiles and the key from [ForkV2KeyStrength]). Without evidence the
 *   key's confidence is 0, as the phone app sets it for the native analyser's key: that analyser's
 *   "confidence" is a 0-0.1 best-to-second gap and would never be trusted anyway.
 * - [ForkV2Planner.plan] with version 3 (Automix v2), the app's phrase length as "Up to N bars"
 *   (0: Auto), `earliest` the playhead plus [ForkV2Mix.HAND_LEAD] + 0.5 s, no sung spans (the
 *   extension's blend planner takes none; the loop search does, see [ForkV2Loop]), and the
 *   overview's pick for the pair migrated as {style, bars} ([ForkV2MixOverride.fromJson]).
 * - The plan's [ForkV2Mix] (the deck schedule) becomes a [ForkV2Transition], which the players
 *   read through [TransitionPlan.forkV2].
 *
 * It is planned afresh at most once a second (the PC asks on every audio block) and whenever the
 * pair, the analyses, the evidence, the phrase length or the pick change; and it is held from the
 * hand lead before the outgoing ramp (or the mix-out) to the end of the blend, so arming, the ramp
 * and the blend all see one plan however the playhead and a refining analysis move.
 *
 * Times are song seconds unless named mix (the [ForkV2Mix] clock) or output seconds.
 */
object ForkV2Automix {

    /** Shorter songs are left to upstream (its own rule for jingles and skits). */
    const val MIN_DURATION_SEC = 45.0

    /** The plan is held from this long before its ramp (the extension's hand lead and the 0.5 s it plans ahead by). */
    const val LATCH_LEAD_SEC = ForkV2Mix.HAND_LEAD + 0.5

    /**
     * A song's Automix v2 evidence by track id, or null when it was never measured. Set by each app
     * (`ForkV2EvidenceStore.install()`); read on every plan, so it must be cheap (an in-memory map).
     */
    @Volatile
    var evidence: ((String) -> ForkV2Analysis?)? = null

    /**
     * Told the id of a song with a usable analysis and no [evidence] (one analysed before Automix v2),
     * so each app can measure it once in the background (`ForkV2EvidenceBackfill`). Set with
     * [evidence]; must be cheap, as it is called on every plan of such a pair.
     */
    @Volatile
    var evidenceMissing: ((String) -> Unit)? = null

    // ---- the analyses as the port reads them ----

    private class Mapped(val source: TrackAnalysis, val ev: ForkV2Analysis?, val analysis: ForkV2Analysis)

    /** The last few mappings, so a tick re-planning the same pair doesn't map both songs again. */
    @Volatile
    private var mapped: List<Mapped> = emptyList()

    /**
     * [t] as the port's analysis, with its evidence merged in; [durationSec] (when known) replaces a
     * missing duration. The returned object is shared between calls: callers copy before changing it
     * (the planner does).
     */
    fun analysisOf(t: TrackAnalysis, id: String, durationSec: Double = 0.0): ForkV2Analysis {
        val evId = id.ifBlank { t.trackId }
        val ev = evidence?.invoke(evId)
        if (ev == null && t.isUsable) evidenceMissing?.invoke(evId)
        mapped.firstOrNull { it.source === t && it.ev === ev }?.let { return withDuration(it.analysis, durationSec) }
        val a = ForkV2FromTrackAnalysis.map(t)
        if (a.fileKey.isNullOrBlank()) a.fileKey = id
        merge(a, ev)
        mapped = (listOf(Mapped(t, ev, a)) + mapped).take(6)
        return withDuration(a, durationSec)
    }

    private fun withDuration(a: ForkV2Analysis, durationSec: Double): ForkV2Analysis {
        if (a.durationSec > 0 || !(durationSec > 0)) return a
        val c = a.copy()
        c.durationSec = durationSec
        return c
    }

    /** The evidence fields into [a]; the key only when [ForkV2KeyStrength] named one the grid table reads. */
    internal fun merge(a: ForkV2Analysis, ev: ForkV2Analysis?) {
        // The native analyser's key confidence is a 0-0.1 gap, never the 0.5-0.9 correlation v2's rules read.
        a.keyConfidence = 0.0
        if (ev == null) return
        a.subBassRatio = ev.subBassRatio
        a.bassCurve = ev.bassCurve
        a.loudnessCurve = ev.loudnessCurve
        a.chroma = ev.chroma
        a.chromaCurve = ev.chromaCurve
        val key = ev.keyName
        if (key != null && ev.keyConfidence > 0) {
            val probe = ForkV2Analysis()
            if (ForkV2BeatGrid.camelot(key, probe)) {
                a.keyName = key
                a.camelotNum = probe.camelotNum
                a.camelotLetter = probe.camelotLetter
                a.keyConfidence = ev.keyConfidence
            }
        }
    }

    // ---- planning ----

    /** Settings for Automix v2 with the app's phrase length (8 or 16 bars: "Up to N bars"; else Auto). */
    fun settings(phraseBars: Int): ForkV2Planner.Settings {
        val s = ForkV2Planner.Settings()
        s.version = 3
        s.bars = if (phraseBars == 8 || phraseBars == 16) phraseBars else 0
        return s
    }

    /**
     * The pair's override from the editor's store ([ForkV2OverrideStore], editor.md §1); null for none,
     * or while BitChord's own style is in force (its overview shows the edits greyed, they don't apply).
     */
    fun overrideFor(fromId: String, toId: String): ForkV2MixOverride? =
        if (ForkMixPicks.active) ForkV2OverrideStore.get(fromId, toId) else null

    /**
     * The blend as Automix v2 plans it, and its deck schedule. [from] / [to] null: not analysed yet
     * (the planner's 12 s crossfade). The core of [planTransition], also called by [ForkV2Loop] and the
     * tests.
     */
    fun planBlend(
        phraseBars: Int,
        fromId: String,
        toId: String,
        from: ForkV2Analysis?,
        to: ForkV2Analysis?,
        durationSec: Double,
        pos: Double,
        ov: ForkV2MixOverride?,
    ): ForkV2Transition {
        val earliest = pos + LATCH_LEAD_SEC
        val planned = ForkV2Planner.plan(settings(phraseBars), fromId, toId, from, to, durationSec, pos, earliest, null, ov)
        val p = kickLocked(planned, fromId, toId, pos)
        return ForkV2Transition(p, ForkV2Mix.blend(p, fromId, toId, durationSec))
    }

    /**
     * Automix v2's kick lock for a pair, measured by the app from both songs' decoded audio
     * ([ForkV2KickLock.kickLock] over [ForkV2KickLock.kickSource] sides), or null while it isn't
     * (not measured yet, or an app that can't play it). Called on every plan of a beatmatched blend
     * with the playhead [pos], so it must answer from memory and start any measuring elsewhere.
     * Only the PC sets it (`ForkDesktopKickLock`): its decks are one sample clock, so a few ms of cue
     * are heard as a few ms. The phone's two ExoPlayers start each other only to within their sinks'
     * latency, which is coarser than the shift, so it leaves this null.
     */
    @Volatile
    var kickLock: ((fromId: String, toId: String, plan: ForkV2Planner.Plan, pos: Double) -> ForkV2KickLock.Result?)? = null

    /**
     * [p] with the kick lock applied, as the phone app's `Automix.kickLocked` does before `Mix.blend`:
     * a copy whose mix-in (the incoming deck's cue, and so its whole clock in the deck schedule) moves
     * by the measured shift, with the result kept in [ForkV2Planner.Plan.kick] for the note. [p]
     * itself for anything else: not a blend the lock may move ([ForkV2Planner.kickLockAllowed]: a
     * mix-in placed in the editor stays), no beat, no measurement yet, or a measurement that found
     * nothing to move ([ForkV2KickLock.Result.none]).
     */
    internal fun kickLocked(p: ForkV2Planner.Plan, fromId: String, toId: String, pos: Double): ForkV2Planner.Plan {
        if (p.fallback || !ForkV2Planner.kickLockAllowed(p) || !(p.beatSec > 0) || !(p.durationSec > 0)) return p
        val k = kickLock?.invoke(fromId, toId, p, pos) ?: return p
        // A cue before the song's first sample can't be played (the cue is clamped to 0 and the deck
        // schedule would run that much ahead of it): the plan stays as it was.
        if (k.none != null || !k.shift.isFinite() || p.mixInTrackTime + k.shift < 0) return p
        val q = p.copy()
        q.mixInTrackTime += k.shift
        q.kick = k
        return q
    }

    private class Latch(val key: String, val plan: TransitionPlan, val armAt: Double, val end: Double)

    private class Memo(val key: String, val second: Long, val plan: TransitionPlan)

    @Volatile
    private var latch: Latch? = null

    @Volatile
    private var memo: Memo? = null

    /** The latest blend planned for "fromId->toId", for the loop-or-blend choice ([ForkV2Loop]). */
    @Volatile
    private var lastBlend: Pair<String, ForkV2Transition>? = null

    fun lastBlendFor(fromId: String, toId: String): ForkV2Transition? =
        lastBlend?.takeIf { it.first == ForkMixPicks.pair(fromId, toId) }?.second

    /**
     * Upstream's `planTransition` under [style]: null for [AutomixStyle.BITCHORD], an album played
     * through, a song under [MIN_DURATION_SEC], or a spoken or live track (upstream decides those).
     */
    fun planTransition(
        style: AutomixStyle,
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        currentTrack: TransitionTrackInfo?,
        nextTrack: TransitionTrackInfo?,
        currentTime: Double,
        duration: Double,
        albumSequential: Boolean,
        phraseBars: Int = 0,
    ): TransitionPlan? {
        if (style != AutomixStyle.V2 || albumSequential) return null
        val length = max(duration.takeIf { it.isFinite() } ?: 0.0, (currentTrack?.durationMs ?: 0L) / 1000.0)
        if (length < MIN_DURATION_SEC || spokenOrLive(currentTrack) || spokenOrLive(nextTrack)) {
            ForkMixPicks.notePlannedNothing()
            return null
        }
        val pos = max(0.0, currentTime.takeIf { it.isFinite() } ?: 0.0)
        val curId = currentTrack?.id ?: analysis.trackId
        val nextId = nextTrack?.id ?: nextAnalysis.trackId
        val pair = ForkMixPicks.pair(curId, nextId)
        val bars = if (phraseBars == 8 || phraseBars == 16) phraseBars else 0
        // The pick is not in the latch's key: once armed, the held plan is the one the standby deck is
        // cued for and the ramp runs on, so a pick made now is for the next time (the overview says so).
        val key = "$curId|$nextId|$length|$bars"
        latch?.takeIf { it.key == key && pos >= it.armAt - 0.5 && pos < it.end }?.let { held ->
            notePlanned(pair, held.plan.forkV2!!, pos, currentTrack, nextTrack, armed = true)
            return held.plan.copy(shouldStart = pos >= held.plan.transitionStart, reason = tag(held.plan.forkV2!!, pos >= held.plan.transitionStart))
        }
        val nextLength = max(nextAnalysis.duration.takeIf { it.isFinite() } ?: 0.0, (nextTrack?.durationMs ?: 0L) / 1000.0)
        val from = if (ready(analysis, currentTrack)) analysisOf(analysis, curId, length) else null
        val to = if (ready(nextAnalysis, nextTrack)) analysisOf(nextAnalysis, nextId, nextLength) else null
        val ov = overrideFor(curId, nextId)
        // The store's change counter too, so no memo outlives a write to the store (a restored backup
        // re-installs it without any one pair's JSON telling): an edit is planned at once, unless the
        // latch above already holds the armed plan (editor.md §8, "for the next time").
        val ovKey = ov?.let { ForkV2Json.write(it.toJson()) + it.migrated }.orEmpty() + "#" + ForkV2OverrideStore.changes.value
        val memoKey = "$key|${from?.let { mapKey(analysis) }}|${to?.let { mapKey(nextAnalysis) }}|$ovKey"
        val second = floor(pos).toLong()
        memo?.takeIf { it.key == memoKey && it.second == second }?.let { m ->
            return m.plan.copy(shouldStart = pos >= m.plan.transitionStart, reason = tag(m.plan.forkV2!!, pos >= m.plan.transitionStart))
        }
        val transition = planBlend(bars, curId, nextId, from, to, length, pos, ov)
        val plan = toTransitionPlan(transition, pos)
        memo = Memo(memoKey, second, plan)
        lastBlend = pair to transition
        ForkV2EditorFeed.note(curId, nextId, transition.plan, from, to, length, pos + LATCH_LEAD_SEC) // the transition editor's plan
        val p = transition.plan
        val outSpan = transition.mix.out.unwrappedAt(transition.mix.t0 + transition.mix.D) - p.mixOutTrackTime
        latch = Latch(key, plan, p.mixOutTrackTime - p.rampSongSec - LATCH_LEAD_SEC, p.mixOutTrackTime + max(outSpan, plan.fadeSeconds))
        notePlanned(pair, transition, pos, currentTrack, nextTrack, armed = false)
        return plan
    }

    /** Identity of the mapped analysis and its evidence, for the memo's key. */
    private fun mapKey(t: TrackAnalysis): String =
        mapped.firstOrNull { it.source === t }?.let { "${it.analysis.hashCode()}" } ?: "-"

    /** Drops the held plan and the memo (tests, and a style change). */
    fun forget() {
        latch = null
        memo = null
        lastBlend = null
        mapped = emptyList()
    }

    private fun spokenOrLive(track: TransitionTrackInfo?): Boolean =
        track != null && isSpokenOrLiveForTest(track.title, track.artist, track.album)

    private fun ready(analysis: TrackAnalysis, track: TransitionTrackInfo?): Boolean =
        analysis.isUsable && (analysis.trackId.isBlank() || track?.id.isNullOrBlank() || analysis.trackId == track?.id)

    private fun notePlanned(pair: String, t: ForkV2Transition, pos: Double, cur: TransitionTrackInfo?, next: TransitionTrackInfo?, armed: Boolean) {
        val p = t.plan
        val style = when (p.style) {
            "blend" -> ForkMixPicks.Style.BLEND
            "filter" -> ForkMixPicks.Style.FILTER
            else -> ForkMixPicks.Style.FADE
        }
        ForkMixPicks.notePlanned(
            pair = pair,
            style = style,
            bars = p.bars,
            reason = p.reason,
            mixOut = p.mixOutTrackTime,
            rampSongSec = p.rampSongSec,
            positionSec = pos,
            outTitle = cur?.title.orEmpty(),
            inTitle = next?.title.orEmpty(),
            armed = armed,
        )
    }

    private fun tag(t: ForkV2Transition, started: Boolean): String {
        val p = t.plan
        val kind = when {
            p.handoff -> "handover"
            p.bridge -> "bridge"
            p.shape != null -> p.shape!!
            p.glide != null -> "${p.style}-glide"
            else -> p.style
        }
        return (if (started) "" else "before-") + "automix-v2-" + kind
    }

    /**
     * [t] as upstream's [TransitionPlan]. The fields the players time the blend by are set so their own
     * arithmetic lands on the plan:
     * - [TransitionPlan.transitionStart] is the mix-out; the outgoing ramp runs before it.
     * - [TransitionPlan.fadeSeconds] is in outgoing song seconds, as upstream's: the phone multiplies it by
     *   incoming / outgoing rate to get the incoming media time the blend lasts, and the PC reads the
     *   output length from [ForkV2Transition.outputSeconds] instead ([fadeSeconds] below). A glide has
     *   no single rate, so its fade is the incoming song's span over its first rate: the phone's
     *   product then is exactly that span.
     * - Rates: the incoming player starts at [ForkV2Transition.incomingStartRate]; the outgoing rate is
     *   the constant speed-up a blend ramps to (1 under a glide: those rates come per beat from
     *   [ForkV2Transition.frameAt]).
     * - [TransitionPlan.phaseLock] only for a constant-rate blend: under a glide the rates are the plan's.
     */
    fun toTransitionPlan(t: ForkV2Transition, pos: Double): TransitionPlan {
        val p = t.plan
        val transitionStyle = when (p.style) {
            "blend" -> TransitionStyle.DJ_BLEND
            "filter" -> TransitionStyle.DJ_FILTER
            else -> TransitionStyle.EQUAL_POWER
        }
        val crossfade = p.style == "crossfade"
        val outRate = if (p.style == "blend" && !t.glides && p.outRate > 0) p.outRate else 1.0
        val inRate = t.incomingStartRate
        val fade = fadeSeconds(t)
        val beats = if (crossfade) 0 else p.bars * 4
        val started = pos >= p.mixOutTrackTime
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            reason = tag(t, started),
            transitionStart = p.mixOutTrackTime,
            transitionEnd = p.mixOutTrackTime + fade,
            fadeSeconds = fade,
            transitionStyle = transitionStyle,
            incomingCueTime = max(0.0, p.mixInTrackTime),
            incomingHandoffTime = max(0.0, p.mixInTrackTime) + t.incomingSongSeconds,
            incomingPlaybackRate = round4(inRate),
            outgoingPlaybackRate = round4(outRate),
            handoffDuration = fade,
            transitionBeats = beats,
            bassSwap = !crossfade,
            bassSwapFraction = if (p.swapAt > 0 && p.swapAt < 1) p.swapAt else 0.5,
            filterSweep = if (p.style == "filter") 1.0 else 0.0,
            filterVariant = FilterTransitionVariant.SWEEP,
            vocalOverlap = p.clash.coerceIn(0.0, 1.0),
            outgoingBpm = if (p.outBpm > 0) p.outBpm else 0.0,
            incomingBpm = if (p.inBpm > 0) p.inBpm else 0.0,
            beatSeconds = if (crossfade || !(p.beatSec > 0)) 0.0 else p.beatSec,
            phaseLock = p.style == "blend" && !t.glides,
            echoSeconds = 0.0,
            policyReasons = listOfNotNull(p.reason, p.rerouted, p.keyClash?.let { "keys clash ($it)" }, p.swapMoved?.let { "bass swap $it" }) + p.warnings.orEmpty(),
            forkV2 = t,
        )
    }

    /** Outgoing song seconds as upstream's fade (see [toTransitionPlan]). */
    internal fun fadeSeconds(t: ForkV2Transition): Double {
        val p = t.plan
        if (!(t.outputSeconds > 0)) return 0.0
        val outRate = if (p.style == "blend" && !t.glides && p.outRate > 0) p.outRate else 1.0
        return t.incomingSongSeconds * outRate / t.incomingStartRate
    }

    private fun round4(rate: Double) = (rate * 10000).roundToInt() / 10000.0

    // ---- what the players read ----

    /** Output seconds the mix lasts ([ForkV2Transition.outputSeconds]); [TransitionPlan.fadeSeconds] for any other plan. */
    fun outputSeconds(plan: TransitionPlan): Double = plan.forkV2?.outputSeconds ?: plan.fadeSeconds

    /** [ForkV2Transition.outgoingRampRate] of [plan]'s Automix v2 blend at song second [pos]; null otherwise. */
    fun outgoingRampRate(plan: TransitionPlan, pos: Double): Double? = plan.forkV2?.outgoingRampRate(pos)

    /** [ForkV2Transition.easeBack] of [plan]; null for no Automix v2 blend (the player's own ease) or nothing to ease. */
    fun easeBack(plan: TransitionPlan?): Pair<Int, Double>? = plan?.forkV2?.easeBack()

    /** The rate the incoming player is at when the blend ends: 1 after a glide, else [fallback] (its stretch). */
    fun incomingRateAtEnd(plan: TransitionPlan?, fallback: Double): Double = plan?.forkV2?.takeIf { it.glides }?.incomingEndRate ?: fallback
}
