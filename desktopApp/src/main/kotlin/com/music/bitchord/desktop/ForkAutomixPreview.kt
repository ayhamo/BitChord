package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.ui.player.MixViewRequest
import kotlin.math.round
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: upstream v1.9.1's Automix overview between blends on the PC (0c54d7bf, "automix overlook
 * button": the phone's `CrossfadeController.publishMixPreview` / `expireMixPreview`,
 * `AppSettings.smartMixPreview` / `mixViewOpen`, the player menu's "Automix overview" row).
 *
 * The shared mix popup (sharedUi `MixViewPopup`) shows `smartMixView` during a blend and, opened by
 * hand between blends, `smartMixPreview`: the next transition as the plan stands, the same decks,
 * frame and faders the blend will show. The PC host only recorded `mixViewOpen` and published no
 * preview, so the popup sat on its empty state. Now, as on the phone:
 *  - [planned] is told each Automix plan the engine makes for the playing song and the next one
 *    (`maybeStartCrossfade`, the plan behind the scrubber's marker), and lays it out only while
 *    the popup is up and no blend view is showing; a tick that changes nothing visible (the
 *    phone's key: the pair, the frame to 1/20 s, beats, analysis states, tempos, keys, style, bass
 *    swap) publishes nothing.
 *  - a side still being measured goes out flagged ([MixDeck.analysing]) without tempo or key, and
 *    the popup draws a skeleton for it.
 *  - [expire] lets the preview go once the popup closes, a blend view takes over, or nothing has
 *    planned the pair for [STALE_MS] between blends (queue ran out, a party, Automix off).
 *
 * The PC's: the view is built by [ForkMixView.build], the blend view's own builder (the engine's
 * faders, `DesktopTransitionRide`), entered at the plan's cue and stretched by its rate ratio
 * rather than a running deck's; the waveforms come from the analysis's kept bands
 * ([ForkMixWaves.fillLater]), so a song not measured yet has empty lanes until it is, where the
 * phone decodes them on the spot.
 */
internal class ForkMixPreview(
    private val preview: MutableStateFlow<MixTransitionView?> = DesktopPlayerSettings.smartMixPreview,
    private val open: StateFlow<Boolean> = DesktopPlayerSettings.mixViewOpen,
    private val blendView: StateFlow<MixTransitionView?> = DesktopPlayerSettings.smartMixView,
    private val waves: (MutableStateFlow<MixTransitionView?>, MixTransitionView, String, String) -> Unit =
        { flow, shown, outId, inId -> ForkMixWaves.fillLater(flow, shown, outId, inId) },
) {
    private var key = ""

    @Volatile private var plannedAtMs = 0L

    /** The engine planned [plan] from [outSong] into [inSong]; laid out when someone is looking. */
    fun planned(
        plan: TransitionPlan,
        outSong: Song,
        inSong: Song,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        outAnalysing: Boolean,
        inAnalysing: Boolean,
        outDurationMs: Long,
        inDurationMs: Long,
    ) {
        if (!open.value || blendView.value != null) return
        if (plan.blocked || plan.fadeSeconds <= 0.0) return
        plannedAtMs = System.currentTimeMillis()
        val ratio = plan.incomingPlaybackRate / plan.outgoingPlaybackRate.coerceAtLeast(0.01)
        val beats = ForkMixView.beats(plan)
        val fresh = "${outSong.videoId}|${inSong.videoId}|${round(plan.transitionStart * 20)}|" +
            "${round(plan.fadeSeconds * 20)}|${round(plan.incomingCueTime * 20)}|$beats|$outAnalysing|$inAnalysing|" +
            "${outAnalysis.bpm}|${inAnalysis.bpm}|${outAnalysis.key}|${inAnalysis.key}|${plan.transitionStyle}|${plan.bassSwap}"
        if (fresh == key && preview.value != null) return
        key = fresh
        val built = ForkMixView.build(
            plan = plan,
            outSong = outSong,
            inSong = inSong,
            outAnalysis = outAnalysis,
            inAnalysis = inAnalysis,
            outStartSec = plan.transitionStart,
            inStartSec = plan.incomingCueTime,
            incomingRate = ratio,
            outDurationMs = outDurationMs,
            inDurationMs = inDurationMs,
        )
        val view = built.withDecks(built.outgoing.measuring(outAnalysing), built.incoming.measuring(inAnalysing))
        preview.value = view
        waves(preview, view, outSong.videoId, inSong.videoId)
    }

    /**
     * Each audio step: the preview goes when the popup is closed or a blend view is up, and between
     * blends ([betweenBlends]) when no plan has come for [STALE_MS].
     */
    fun expire(betweenBlends: Boolean) {
        if (preview.value == null) return
        val stale = betweenBlends && System.currentTimeMillis() - plannedAtMs > STALE_MS
        if (open.value && blendView.value == null && !stale) return
        preview.value = null
        key = ""
    }

    /** A deck still being measured: no tempo or key, drawn as a skeleton (the phone's `analysing`). */
    private fun MixDeck.measuring(analysing: Boolean): MixDeck = if (!analysing) this else MixDeck(
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        bpm = 0.0,
        key = "",
        durationMs = durationMs,
        waveStart = waveStart,
        waveEnd = waveEnd,
        low = low,
        mid = mid,
        high = high,
        analysing = true,
        decoding = decoding,
    )

    companion object {
        /** The phone's `MIX_PREVIEW_STALE_MS`. */
        const val STALE_MS = 1_500L
    }
}

/**
 * "Automix overview" in the player's menu (the phone's `SongActionsMenu` row, `MainActivity`'s
 * `onAutomixOverview`): only while Automix is on; closes the menu and asks the shared player for
 * its mix popup, which lays the next transition out on its own ([ForkMixPreview]).
 */
@Composable
internal fun ForkAutomixOverviewRow(onDismiss: () -> Unit) {
    val automix by DesktopPlayerSettings.smartFadeEnabled.collectAsState()
    if (!automix) return
    DesktopMenuRow(Icons.Rounded.GraphicEq, DesktopStrings["automix_overview", "Automix overview"]) {
        onDismiss()
        MixViewRequest.open = true
    }
}
