package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Fork: Automix reported on the PC as the phone reports it. The shared player (sharedUi) reads three
 * flows from its settings: `automixDetail`, the stats-for-nerds line under the stream stats;
 * `smartMixView`, the mix popup's layout of the blend in flight; and `smartMixProgress`, how far
 * through that blend playback is. On the phone `CrossfadeController` fills them
 * (`publishAutomixDetail`/`describeAutomix`, `publishMixView`/`clearMixView`, and the progress line in
 * its fade tick); the PC's were constant empty flows, so the stats line said only the analysis states
 * and the popup never opened.
 *
 * The rules are the phone's, line for line where the PC has the same thing to say:
 *  - The detail line is null with Automix off or in a party. A loop mix in hand says its stage
 *    ("loop mix in N s", "looping", "letting go") and its loops; a loop mix planned for the pair
 *    says when; otherwise the planned blend, filter mix or crossfade, with how long, the incoming
 *    stretch and why the loop mix was refused; and with nothing planned (or mid-blend, where the
 *    phone has cleared its marker), both songs' analysis.
 *  - The popup's view is set when a blend that is really *mixing* starts (the phone's `isRealMix`,
 *    the PC's `isSmartMixInProgress`: a DJ style, a cue into the incoming song, or a stretch), and
 *    cleared when it ends however it ends. A plain fallback crossfade, a skip's blend and a loop mix
 *    get no view, exactly as on the phone (its loop mix publishes none either).
 *  - The progress is the blend's fraction 0..1 as *heard*: what the sink still holds is taken off
 *    what the blend has written, as the phone reads its player's position rather than its decoder's.
 *
 * The popup's waveforms: the phone decodes both regions 30 s ahead with `MixWaveform`; the PC
 * measures the same three bands during its analysis decode and keeps them (`ForkMixWaves`). The view
 * goes out without them, built on the audio thread, and [ForkAutomixReporter.showBlend] has them read
 * and sliced on a thread of their own, swapped in a few milliseconds later while the view is still
 * the one shown. A song with no kept bands (analysed before they were) keeps empty lanes, which the
 * popup already draws as nothing.
 */

/** The stats-for-nerds line's wording, the phone's `describeAutomix` as pure functions. */
internal object ForkAutomixDetail {
    /** "analysed (124 BPM A minor, model grid 0.82)", or the state when there is no usable analysis. */
    fun label(analysis: TrackAnalysis?, state: TrackAnalysisState?, outgoing: Boolean): String {
        if (analysis == null || state == null) return "none"
        if (state == TrackAnalysisState.ANALYSING) return "analysing…"
        if (state == TrackAnalysisState.WAITING) return "waiting"
        if (state == TrackAnalysisState.FAILED || !analysis.isUsable) return "failed"
        val side = if (outgoing) analysis.tailBeats else analysis.headBeats
        val bpm = side?.bpm ?: analysis.bpm
        val confidence = side?.confidence ?: analysis.beatConfidence
        val key = analysis.key.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val grid = if (side != null) "model grid" else "dsp grid"
        val refining = if (state == TrackAnalysisState.REFINING) ", refining" else ""
        return "analysed (${bpm.roundToInt()} BPM$key, $grid ${"%.2f".format(Locale.ROOT, confidence)}$refining)"
    }

    fun analysis(thisSong: String, nextSong: String): String = "Automix · this song: $thisSong · next: $nextSong"

    /**
     * A loop mix in hand. [mixSeconds] is where in the mix the listener is (0 the outgoing loop's
     * start), null before its decks are spliced; [untilLoopSeconds] is the playhead's distance to the
     * loop for that case.
     */
    fun loopRunning(plan: LoopMix.Plan, mixSeconds: Double?, releaseAt: Double, untilLoopSeconds: Double): String {
        val stage = when {
            mixSeconds == null -> "loop mix in ${ceil(untilLoopSeconds).toInt().coerceAtLeast(0)} s"
            mixSeconds < 0 -> "loop mix in ${ceil(-mixSeconds).toInt().coerceAtLeast(0)} s"
            mixSeconds < releaseAt -> "looping"
            else -> "letting go"
        }
        return "Automix · $stage · ${ForkLoopMixRules.describe(plan)}"
    }

    /** A loop mix planned for the pair, [untilLoopSeconds] ahead. */
    fun loopPlanned(plan: LoopMix.Plan, untilLoopSeconds: Double): String =
        "Automix · loop mix in ${ceil(untilLoopSeconds.coerceAtLeast(0.0)).toInt()} s · ${ForkLoopMixRules.describe(plan)}"

    /** The planned blend, [untilSeconds] ahead; [loopRefusal] is why the pair gets no loop mix. */
    fun planned(plan: TransitionPlan, untilSeconds: Double, loopRefusal: String?): String {
        val inSeconds = untilSeconds.coerceAtLeast(0.0)
        val whenText = if (inSeconds > 0) "in ${ceil(inSeconds).toInt()} s" else "now"
        val bars = ForkMixView.bars(ForkMixView.beats(plan))
        val length = if (bars > 0) "$bars bars" else "${"%.1f".format(Locale.ROOT, plan.fadeSeconds)} s"
        val why = plan.policyReasons.firstOrNull()?.let { " · $it" }.orEmpty()
        val noLoop = loopRefusal?.let { " · no loop: $it" }.orEmpty()
        return when (plan.transitionStyle) {
            TransitionStyle.EQUAL_POWER -> "Automix · crossfading $whenText · ${"%.0f".format(Locale.ROOT, plan.fadeSeconds)} s$why$noLoop"
            TransitionStyle.DJ_FILTER -> "Automix · filter mix $whenText · $length$why$noLoop"
            else -> {
                val rate = plan.incomingPlaybackRate
                val stretch = if (abs(rate - 1) < 0.0005) "same tempo" else "next at ${"%.3f".format(Locale.ROOT, rate)}×"
                "Automix · mixing $whenText · $length · $stretch$noLoop"
            }
        }
    }
}

/** The mix popup's layout of a PC blend, the phone's `publishMixView` with the PC's plan and decks. */
internal object ForkMixView {
    /** Context shown either side of the overlap, as a share of it (phone: `MIX_VIEW_CONTEXT`). */
    const val CONTEXT = 0.24

    /** Fader samples over the blend (phone: `MIX_VIEW_FADER_POINTS`). */
    const val FADER_POINTS = 64

    /**
     * The overlap counted on the outgoing grid, what the popup's chip is made from; 0 when the grid
     * is not trusted (the chip then shows seconds). Not [TransitionPlan.transitionBeats]: that is
     * `adaptiveOverlap`'s first ask, which `mixPlan` then replaces for a same-beat blend (the
     * overlap runs to the incoming intro's drop, clamped and rounded to whole bars), so a blend the
     * engine logged as "9.8s, DJ_BLEND, 8 beats" ran about 16 beats and the popup said 4 Bars.
     */
    fun beats(plan: TransitionPlan): Int =
        if (plan.beatSeconds > 0) (plan.fadeSeconds.coerceAtLeast(0.5) / plan.beatSeconds).roundToInt() else 0

    /** [beats] as the popup's chip words it (MixViewPopup: `(beats / 4f).roundToInt().coerceAtLeast(1)`); 0 for none. */
    fun bars(beats: Int): Int = if (beats <= 0) 0 else (beats / 4f).roundToInt().coerceAtLeast(1)

    /** The engine's "transition into" line: the bars the popup shows, and the planner's first ask when it differs. */
    fun logLength(plan: TransitionPlan): String {
        val beats = beats(plan)
        val asked = plan.transitionBeats.takeIf { it > 0 && it != beats }?.let { " (planner asked $it beats)" }.orEmpty()
        return when {
            beats > 0 -> ", ${bars(beats)} bars / $beats beats$asked"
            plan.transitionBeats > 0 -> ", no trusted grid$asked"
            else -> ""
        }
    }

    /**
     * [outStartSec] is where the blend opens on the outgoing song, [inStartSec] where the incoming
     * one was cued, [incomingRate] the stretch its deck runs at (its timeline covers the overlap that
     * many times over), the durations in ms (0 falls back to the analysis's).
     */
    fun build(
        plan: TransitionPlan,
        outSong: Song,
        inSong: Song,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        outStartSec: Double,
        inStartSec: Double,
        incomingRate: Double,
        outDurationMs: Long,
        inDurationMs: Long,
    ): MixTransitionView {
        val span = plan.fadeSeconds.coerceAtLeast(0.5)
        val outStart = outStartSec
        val outEnd = outStart + span
        val ratio = incomingRate.takeIf { it > 0.0 } ?: 1.0
        val inStart = inStartSec
        val inEnd = inStart + span * ratio
        val context = span * CONTEXT
        val beatSeconds = plan.beatSeconds
        val beats = beats(plan)
        val gains = FloatArray(2)
        val fadeOut = FloatArray(FADER_POINTS)
        val fadeIn = FloatArray(FADER_POINTS)
        for (i in 0 until FADER_POINTS) {
            val p = i / (FADER_POINTS - 1f)
            DesktopTransitionRide.gains(plan, p, gains)
            // With an echo out the fader stays up for the repeats; what is heard is the echo's level,
            // the same thing the engine's headroom trim reads (the phone's `outgoingLevel`).
            val out = if (plan.echoSeconds > 0.0) DesktopTransitionRide.echoLevel(plan, p) else gains[0]
            fadeOut[i] = out.coerceIn(0f, 1f)
            fadeIn[i] = gains[1].coerceIn(0f, 1f)
        }
        fun deck(song: Song, analysis: TrackAnalysis, durationMs: Long, from: Double, to: Double) = MixDeck(
            title = song.title,
            artist = song.artist,
            artworkUrl = song.thumbnailUrl,
            bpm = analysis.bpm,
            key = analysis.key,
            durationMs = durationMs.takeIf { it > 0L } ?: (analysis.duration * 1000).roundToLong(),
            waveStart = from,
            waveEnd = to,
        )
        return MixTransitionView(
            outgoing = deck(outSong, outAnalysis, outDurationMs, outStart - context, outEnd + context),
            incoming = deck(inSong, inAnalysis, inDurationMs, inStart - context * ratio, inEnd + context * ratio),
            outStart = outStart,
            outEnd = outEnd,
            inStart = inStart,
            inEnd = inEnd,
            beats = beats,
            beatMarks = beatMarks(outAnalysis.downbeats, outStart, outEnd, beatSeconds),
            fadeOut = fadeOut,
            fadeIn = fadeIn,
            bassSwapAt = bassSwapAt(plan),
        )
    }

    /** The grid's beats inside [outStart]..[outEnd] as fractions of it, phased on the last downbeat before it. */
    fun beatMarks(downbeats: List<Double>, outStart: Double, outEnd: Double, beatSeconds: Double): FloatArray {
        if (beatSeconds <= 0) return FloatArray(0)
        val span = outEnd - outStart
        if (span <= 0) return FloatArray(0)
        val anchor = downbeats.lastOrNull { it <= outStart } ?: outStart
        val first = anchor + ceil((outStart - anchor) / beatSeconds) * beatSeconds
        return generateSequence(first) { it + beatSeconds }
            .takeWhile { it <= outEnd + 1e-6 }
            .map { ((it - outStart) / span).toFloat() }
            .toList().toFloatArray()
    }

    /** Where the low end changes hands, 0..1 of the blend, or -1 (the phone's `bassSwapAt`). */
    fun bassSwapAt(plan: TransitionPlan): Float = when {
        plan.transitionStyle == TransitionStyle.DJ_FILTER && plan.filterVariant == FilterTransitionVariant.BASS_HANDOFF ->
            DesktopTransitionRide.filterSwapAt(plan).toFloat()
        plan.transitionStyle == TransitionStyle.DJ_BLEND && plan.bassSwap -> DesktopTransitionRide.handoffAt(plan)
        else -> -1f
    }

    /**
     * How far through the blend the listener is, 0..1: [writtenSamples] of the outgoing song blended so
     * far (interleaved, the engine's `transitionTotal - fadeRemaining`), less [queuedSamples] the sink
     * has not played yet, over the fade's [fadeSamples]. The extended tail an echo or reverb rings
     * through runs past 1 and is held there.
     */
    fun heardProgress(writtenSamples: Long, queuedSamples: Long, fadeSamples: Long): Float {
        if (fadeSamples <= 0) return 0f
        return ((writtenSamples - queuedSamples).toDouble() / fadeSamples).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Writes the three flows, each only when it changes: the view once per blend (keyed on the plan the
 * engine runs it from), the progress when it has moved visibly, the line at most every
 * [DETAIL_EVERY_MS] (the engine asks on every audio step; the phone's line moves with its ticker).
 * The flows are parameters so the tests do not touch [DesktopPlayerSettings], whose first use reads
 * the saved settings; [waves] likewise, the waveform filler ([ForkMixWaves.fillLater]).
 */
internal class ForkAutomixReporter(
    private val detail: MutableStateFlow<String?> = DesktopPlayerSettings.automixDetail,
    private val view: MutableStateFlow<MixTransitionView?> = DesktopPlayerSettings.smartMixView,
    private val progress: MutableStateFlow<Float> = DesktopPlayerSettings.smartMixProgress,
    private val waves: (MutableStateFlow<MixTransitionView?>, MixTransitionView, String, String) -> Unit =
        { flow, shown, outId, inId -> ForkMixWaves.fillLater(flow, shown, outId, inId) },
) {
    private var viewKey: Any? = null
    private var nextDetailNs = Long.MIN_VALUE

    /** A real mix is playing on [key]: its view, built once, and progress back to 0 for it. */
    fun showBlend(key: Any, build: () -> MixTransitionView) = showBlend(key, null, null, build)

    /**
     * As above, and the waveforms of [outId] and [inId] (the songs' analysis ids) filled in off this
     * thread once read. Only the hand-off is made here: the engine calls this on the audio thread.
     */
    fun showBlend(key: Any, outId: String?, inId: String?, build: () -> MixTransitionView) {
        if (viewKey === key) return
        viewKey = key
        progress.value = 0f
        val built = build()
        view.value = built
        if (outId != null && inId != null) waves(view, built, outId, inId)
    }

    /** Progress through the blend shown; ignored with none shown. */
    fun progress(value: Float) {
        if (viewKey == null) return
        val p = value.coerceIn(0f, 1f)
        if (abs(p - progress.value) >= PROGRESS_STEP || (p == 1f && progress.value != 1f)) progress.value = p
    }

    /** No real mix playing: the popup's view goes (the popup holds its last frame until dismissed). */
    fun clearBlend() {
        if (viewKey == null && view.value == null) return
        viewKey = null
        view.value = null
    }

    /** Whether the line is due for another look at [nowNs]. */
    fun detailDue(nowNs: Long): Boolean {
        if (nextDetailNs != Long.MIN_VALUE && nowNs < nextDetailNs) return false
        nextDetailNs = nowNs + DETAIL_EVERY_MS * 1_000_000L
        return true
    }

    fun publishDetail(text: String?) {
        if (detail.value != text) detail.value = text
    }

    companion object {
        const val DETAIL_EVERY_MS = 250L

        /** Under a pixel of the popup's playhead: not worth a recomposition. */
        const val PROGRESS_STEP = 0.002f
    }
}
