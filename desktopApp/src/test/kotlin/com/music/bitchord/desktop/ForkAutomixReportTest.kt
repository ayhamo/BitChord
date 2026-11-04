package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class ForkAutomixReportTest {
    private val ready = TrackAnalysis(status = TrackAnalysis.STATUS_READY, bpm = 124.4, beatConfidence = 0.5, key = "A minor")

    @Test
    fun `the analysis labels read as the phone's`() {
        assertEquals("none", ForkAutomixDetail.label(null, null, outgoing = true))
        assertEquals("waiting", ForkAutomixDetail.label(ready, TrackAnalysisState.WAITING, outgoing = true))
        assertEquals("analysing…", ForkAutomixDetail.label(ready, TrackAnalysisState.ANALYSING, outgoing = true))
        assertEquals("failed", ForkAutomixDetail.label(TrackAnalysis(), TrackAnalysisState.ANALYSED, outgoing = true))
        assertEquals(
            "analysed (124 BPM A minor, dsp grid 0.50)",
            ForkAutomixDetail.label(ready, TrackAnalysisState.ANALYSED, outgoing = true),
        )
        // The model's grid over the side that mixes: the tail going out, the head coming in.
        val sided = ready.copy(tailBeats = BeatSide(emptyList(), emptyList(), bpm = 128.0, confidence = 0.9))
        assertEquals(
            "analysed (128 BPM A minor, model grid 0.90, refining)",
            ForkAutomixDetail.label(sided, TrackAnalysisState.REFINING, outgoing = true),
        )
        assertEquals(
            "analysed (124 BPM A minor, dsp grid 0.50)",
            ForkAutomixDetail.label(sided, TrackAnalysisState.ANALYSED, outgoing = false),
        )
    }

    @Test
    fun `the planned mix says what kind, when and how long`() {
        val fade = TransitionPlan(transitionStyle = TransitionStyle.EQUAL_POWER, fadeSeconds = 8.0)
        assertEquals("Automix · crossfading in 13 s · 8 s", ForkAutomixDetail.planned(fade, 12.3, null))
        val blend = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            fadeSeconds = 8.0,
            beatSeconds = 0.5,
            incomingPlaybackRate = 1.02,
        )
        assertEquals(
            "Automix · mixing now · 4 bars · next at 1.020× · no loop: waiting on lyrics",
            ForkAutomixDetail.planned(blend, -1.0, "waiting on lyrics"),
        )
        assertEquals(
            "Automix · mixing in 2 s · 4 bars · same tempo",
            ForkAutomixDetail.planned(blend.copy(incomingPlaybackRate = 1.0), 1.5, null),
        )
        val filter = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_FILTER,
            fadeSeconds = 6.0,
            policyReasons = listOf("vocals collide"),
        )
        assertEquals("Automix · filter mix in 5 s · 6.0 s · vocals collide", ForkAutomixDetail.planned(filter, 5.0, null))
    }

    @Test
    fun `the engine's line counts the bars the popup shows, not the planner's first ask`() {
        // The owner's case: 9.8 s at ~98 BPM logged "8 beats" while the popup said 4 Bars.
        val plan = TransitionPlan(transitionStyle = TransitionStyle.DJ_BLEND, fadeSeconds = 9.8, beatSeconds = 60 / 98.0, transitionBeats = 8)
        assertEquals(16, ForkMixView.beats(plan))
        assertEquals(4, ForkMixView.bars(ForkMixView.beats(plan)))
        assertEquals(", 4 bars / 16 beats (planner asked 8 beats)", ForkMixView.logLength(plan))
        assertEquals(", 4 bars / 16 beats", ForkMixView.logLength(plan.copy(transitionBeats = 16)))
        assertEquals(", no trusted grid (planner asked 8 beats)", ForkMixView.logLength(plan.copy(beatSeconds = 0.0)))
        assertEquals("", ForkMixView.logLength(plan.copy(beatSeconds = 0.0, transitionBeats = 0)))
        assertEquals(1, ForkMixView.bars(2))
        assertEquals(0, ForkMixView.bars(0))
    }

    @Test
    fun `beat marks are phased on the downbeat before the blend`() {
        val marks = ForkMixView.beatMarks(listOf(95.0, 99.25, 101.0), outStart = 100.0, outEnd = 104.0, beatSeconds = 0.5)
        // Anchored at 99.25: beats at 100.25, 100.75, … 103.75.
        assertEquals(8, marks.size)
        assertEquals(0.0625f, marks.first(), 1e-6f)
        assertEquals(0.9375f, marks.last(), 1e-6f)
        assertEquals(0, ForkMixView.beatMarks(listOf(99.0), 100.0, 104.0, beatSeconds = 0.0).size)
    }

    @Test
    fun `the view lays the blend out on both timelines`() {
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 100.0,
            fadeSeconds = 8.0,
            beatSeconds = 0.5,
            transitionBeats = 16,
            bassSwap = true,
        )
        val view = ForkMixView.build(
            plan = plan,
            outSong = song("a"),
            inSong = song("b"),
            outAnalysis = ready.copy(downbeats = listOf(98.0)),
            inAnalysis = ready.copy(duration = 200.0),
            outStartSec = 100.0,
            inStartSec = 30.0,
            incomingRate = 1.05,
            outDurationMs = 240_000,
            inDurationMs = 0,
        )
        assertEquals(100.0, view.outStart)
        assertEquals(108.0, view.outEnd)
        assertEquals(30.0, view.inStart)
        assertEquals(30.0 + 8.0 * 1.05, view.inEnd, 1e-9)
        assertEquals(16, view.beats)
        assertEquals(17, view.beatMarks.size)
        assertEquals(240_000L, view.outgoing.durationMs)
        // No decoder length: the analysis's.
        assertEquals(200_000L, view.incoming.durationMs)
        assertEquals(100.0 - 8.0 * ForkMixView.CONTEXT, view.outgoing.waveStart, 1e-9)
        assertEquals("song a", view.outgoing.title)
        // The faders run the whole way, and the low end changes hands on the planned beat.
        assertEquals(ForkMixView.FADER_POINTS, view.fadeOut.size)
        assertEquals(1f, view.fadeOut.first(), 1e-4f)
        assertEquals(0f, view.fadeIn.first(), 1e-4f)
        assertEquals(1f, view.fadeIn.last(), 1e-4f)
        assertEquals(0.6875f, view.bassSwapAt, 1e-6f)
        assertEquals(0, view.outgoing.low.size)
    }

    @Test
    fun `a plain crossfade has no bass mark`() {
        assertEquals(-1f, ForkMixView.bassSwapAt(TransitionPlan(transitionStyle = TransitionStyle.EQUAL_POWER)))
    }

    @Test
    fun `progress is what has been heard, held at the ends`() {
        assertEquals(0f, ForkMixView.heardProgress(writtenSamples = 1_000, queuedSamples = 4_000, fadeSamples = 100_000))
        assertEquals(0.5f, ForkMixView.heardProgress(writtenSamples = 54_000, queuedSamples = 4_000, fadeSamples = 100_000), 1e-6f)
        // An echo's tail runs on past the fade.
        assertEquals(1f, ForkMixView.heardProgress(writtenSamples = 150_000, queuedSamples = 4_000, fadeSamples = 100_000))
        assertEquals(0f, ForkMixView.heardProgress(writtenSamples = 10, queuedSamples = 0, fadeSamples = 0))
    }

    @Test
    fun `the reporter shows a blend once, moves its progress, and clears it`() {
        val detail = MutableStateFlow<String?>(null)
        val view = MutableStateFlow<MixTransitionView?>(null)
        val progress = MutableStateFlow(0.7f)
        val reporter = ForkAutomixReporter(detail, view, progress)
        val plan = TransitionPlan(transitionStyle = TransitionStyle.DJ_BLEND, fadeSeconds = 8.0)
        var builds = 0
        val build = {
            builds++
            ForkMixView.build(plan, song("a"), song("b"), ready, ready, 0.0, 0.0, 1.0, 1, 1)
        }
        // Nothing shown: progress is not touched.
        reporter.progress(0.3f)
        assertEquals(0.7f, progress.value)
        reporter.showBlend(plan, build)
        val shown = assertNotNull(view.value)
        assertEquals(0f, progress.value)
        reporter.showBlend(plan, build)
        assertEquals(1, builds)
        assertSame(shown, view.value)
        reporter.progress(0.001f)
        assertEquals(0f, progress.value)
        reporter.progress(0.25f)
        assertEquals(0.25f, progress.value)
        reporter.progress(1.4f)
        assertEquals(1f, progress.value)
        reporter.clearBlend()
        assertNull(view.value)
        // The next blend's plan is a new one.
        reporter.showBlend(plan.copy(), build)
        assertEquals(2, builds)
    }

    @Test
    fun `the line is looked at every quarter second and only written when it changes`() {
        val detail = MutableStateFlow<String?>("old")
        val reporter = ForkAutomixReporter(detail, MutableStateFlow(null), MutableStateFlow(0f))
        val ms = 1_000_000L
        assertTrue(reporter.detailDue(0L))
        assertEquals(false, reporter.detailDue(100 * ms))
        assertTrue(reporter.detailDue(250 * ms))
        reporter.publishDetail(null)
        assertNull(detail.value)
    }

    private fun song(id: String) = Song(videoId = id, title = "song $id", artist = "artist", thumbnailUrl = null)
}
