package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automix style's Original / Advanced AutoDJ ([ForkAutoDj], [ForkAutoDjCurves]). The first block
 * ports the YouTube Music extension's own planner tests (`scripts/test_automix_plan.mjs`) case for
 * case; the rest pin what the port adds: tempo only up, the version-2 rules, the plan latch, the
 * hook into upstream's planner, and the curves.
 */
class ForkAutoDjTest {

    @AfterTest
    fun forget() = ForkAutoDj.forget()

    /** The extension's `song()`: a steady grid, an energy curve (here a sample a second). */
    private fun song(
        bpm: Double,
        durationSec: Double,
        conf: Double = 1.0,
        down: Double = 0.4,
        id: String = "s",
        contentEnd: Double? = null,
        energy: (Double) -> Double = { 1.0 },
    ) = ForkAutoDj.Side(
        id = id,
        durationSec = durationSec,
        grid = ForkAutoDj.Grid(60 / bpm, down),
        confidence = conf,
        energy = (0 until durationSec.toInt()).map { EnergySample(it.toDouble(), energy(it.toDouble())) },
        introEndSec = null,
        contentEndSec = contentEnd,
        vocalWindows = emptyList(),
        vocalMask = emptyList(),
        stub = false,
    )

    private fun planOf(cur: ForkAutoDj.Side, next: ForkAutoDj.Side, pos: Double = 20.0, bars: Int = 0, v2: Boolean = false) =
        ForkAutoDj.plan(cur, next.let { ForkAutoDj.Side("b", it.durationSec, it.grid, it.confidence, it.energy, it.introEndSec, it.contentEndSec, it.vocalWindows, it.vocalMask, it.stub) }, pos, pos + 4.5, v2, bars)

    private fun barSec(bpm: Double) = 240 / bpm
    private fun isDownbeat(t: Double, s: ForkAutoDj.Side): Boolean {
        val g = s.grid!!
        val x = ((t - g.downbeatSec) / g.barSec) % 1
        return abs(x) < 1e-6 || abs(x - 1) < 1e-6
    }

    // ---- the extension's planner tests ----

    @Test
    fun upTo16BarsIsA16BarBlendEndingABarBeforeTheSong() {
        val cur = song(92.0, 104.0)
        val p = planOf(cur, song(92.0, 180.0), bars = 16)
        assertEquals(ForkAutoDj.Style.BLEND, p.style)
        assertEquals(16, p.bars)
        assertEquals(16 * barSec(92.0), p.durationSec, 1e-9)
        val end = p.mixOut + p.durationSec
        assertTrue(end <= cur.durationSec - barSec(92.0) + 1e-6, "ends at least a bar before the song")
        assertTrue(end > cur.durationSec - 2 * barSec(92.0), "and within two bars of it")
        assertTrue(isDownbeat(p.mixOut, cur), "starts on a downbeat")
    }

    @Test
    fun upToNBarsAndAWeakGridShortensIt() {
        assertEquals(8, planOf(song(120.0, 200.0), song(120.0, 200.0), bars = 8).bars)
        assertEquals(8, planOf(song(120.0, 200.0, conf = 0.5), song(120.0, 200.0), bars = 16).bars)
        assertEquals(4, planOf(song(120.0, 200.0, conf = 0.3), song(120.0, 200.0), bars = 16).bars)
    }

    @Test
    fun autoIs4BarsAtAnOrdinaryTempo() {
        val p = planOf(song(92.0, 104.0), song(92.0, 180.0))
        assertEquals(4, p.bars)
        assertTrue(p.durationSec <= 12)
    }

    @Test
    fun auto8BarsOnlyWhenBothSidesAreCalmAndItFitsIn16s() {
        val calmEnd = { d: Double -> { t: Double -> if (t > d - 30) 0.3 else 1.0 } }
        val calmStart = { t: Double -> if (t < 30) 0.3 else 1.0 }
        assertEquals(8, planOf(song(128.0, 200.0, energy = calmEnd(200.0)), song(128.0, 200.0, energy = calmStart)).bars)
        assertEquals(4, planOf(song(128.0, 200.0, energy = calmEnd(200.0)), song(128.0, 200.0)).bars, "only one side calm")
        assertEquals(4, planOf(song(100.0, 200.0, energy = calmEnd(200.0)), song(100.0, 200.0, energy = calmStart)).bars, "8 bars at 100 BPM is over 16 s")
    }

    @Test
    fun autoNeverTakesMoreThan40PercentOfAShortNextSong() {
        assertEquals(4, planOf(song(140.0, 200.0), song(140.0, 15.0)).bars)
    }

    @Test
    fun temposTooFarApartAreAFilterMixEndingWithTheSong() {
        val cur = song(120.0, 200.0)
        val p = planOf(cur, song(80.0, 200.0))
        assertEquals(ForkAutoDj.Style.FILTER, p.style)
        val end = p.mixOut + p.durationSec
        assertTrue(end <= cur.durationSec - 0.3 && end > cur.durationSec - 2 * barSec(120.0) - 1e-6)
    }

    @Test
    fun noGridIsThe12sCrossfadeEndingWithTheSong() {
        val p = planOf(song(120.0, 200.0, conf = 0.0), song(120.0, 200.0), bars = 16)
        assertEquals(ForkAutoDj.Style.CROSSFADE, p.style)
        assertEquals(200 - 0.3, p.mixOut + p.durationSec, 1e-6)
    }

    @Test
    fun noAnalysisYetPutsThePlaceholderCrossfadeAtTheEnd() {
        val p = ForkAutoDj.plan(ForkAutoDj.stub("a", 150.0), ForkAutoDj.stub("b", 0.0), 10.0, 14.5, v2 = false)
        assertEquals(ForkAutoDj.Style.CROSSFADE, p.style)
        assertEquals(150 - 0.3 - 12, p.mixOut, 1e-6)
    }

    @Test
    fun neverMoreThan12sOfMusicSkippedAfterABlend() {
        for (bars in listOf(0, 4, 8, 16, 32)) {
            var bpm = 60.0
            while (bpm <= 200.0) {
                val cur = song(bpm, 240.0)
                val p = planOf(cur, song(bpm, 240.0), bars = bars)
                val left = cur.durationSec - (p.mixOut + p.durationSec)
                assertTrue(left >= 0 && left <= 12, "$bars bars at $bpm BPM leaves $left s")
                bpm += 7
            }
        }
    }

    @Test
    fun seekedPastTheNaturalPointTakesTheNextPhraseAfterTheEarliestStart() {
        val cur = song(120.0, 200.0)
        val natural = planOf(cur, song(120.0, 200.0), bars = 8).mixOut
        val p = planOf(cur, song(120.0, 200.0), pos = natural + 1, bars = 8)
        assertTrue(p.mixOut >= natural + 1 + 4.5 - 1e-6)
    }

    // ---- tempo only ever moves up ----

    @Test
    fun aFasterNextSongBringsThePlayingOneUpBeforeTheBlend() {
        val p = planOf(song(120.0, 200.0), song(126.0, 200.0), bars = 8)
        assertEquals(ForkAutoDj.Style.BLEND, p.style)
        assertEquals(1.0, p.rate)
        assertEquals(1.05, p.outRate, 1e-9)
        assertEquals(7, p.rampBeats) // 5% in 0.75% steps
        assertEquals(p.outSpan / 1.05, p.durationSec, 1e-9)
        val plan = ForkAutoDj.toTransitionPlan(p, 20.0, AutomixStyle.ORIGINAL)
        val beat = 0.5
        assertNull(ForkAutoDj.outgoingRampRate(plan, p.mixOut - 7 * beat - 0.01))
        assertEquals(1 + 0.05 / 7, ForkAutoDj.outgoingRampRate(plan, p.mixOut - 7 * beat + 0.01)!!, 1e-4)
        assertEquals(1.05, ForkAutoDj.outgoingRampRate(plan, p.mixOut)!!, 1e-4)
        assertEquals(p.durationSec, ForkAutoDj.outputSeconds(plan), 1e-9)
    }

    @Test
    fun aSlowerNextSongIsSpedUpAndThePlayingOneLeftAlone() {
        val p = planOf(song(126.0, 200.0), song(120.0, 200.0), bars = 8)
        assertEquals(1.05, p.rate, 1e-9)
        assertEquals(1.0, p.outRate)
        assertEquals(0, p.rampBeats)
        assertNull(ForkAutoDj.outgoingRampRate(ForkAutoDj.toTransitionPlan(p, 20.0, AutomixStyle.ORIGINAL), p.mixOut))
    }

    @Test
    fun halfTimeIsFoldedNotStretched() {
        val p = planOf(song(140.0, 200.0), song(70.0, 200.0), bars = 8)
        assertEquals(ForkAutoDj.Style.BLEND, p.style)
        assertEquals(1.0, p.rate, 1e-9)
        assertEquals(1.0, p.outRate, 1e-9)
    }

    // ---- version 2 ----

    @Test
    fun advancedEndsTheBlendWhereTheMusicEnds() {
        val v1 = planOf(song(120.0, 200.0, contentEnd = 185.0), song(120.0, 200.0), bars = 8)
        val v2 = planOf(song(120.0, 200.0, contentEnd = 185.0), song(120.0, 200.0), bars = 8, v2 = true)
        assertTrue(v1.mixOut + v1.outSpan > 185.0)
        assertTrue(v2.mixOut + v2.outSpan <= 185.0 - barSec(120.0) + 1e-6, "a bar before the music ends")
        // Never more than 20 s cut.
        val far = planOf(song(120.0, 200.0, contentEnd = 100.0), song(120.0, 200.0), bars = 8, v2 = true)
        val farEnd = far.mixOut + far.outSpan
        assertTrue(farEnd > 180.0 - 2 * barSec(120.0) - 1e-6 && farEnd <= 180.0 - barSec(120.0) + 1e-6, "cut at 20 s: ends $farEnd")
    }

    @Test
    fun advancedNeverCuesPastAQuarterOfTheNextSong() {
        // The next song's first full phrase is late (a long intro), on a 60 s song.
        val next = song(120.0, 60.0).let { ForkAutoDj.Side("b", 60.0, it.grid, 1.0, it.energy, 40.0, null, emptyList(), emptyList(), false) }
        val v1 = ForkAutoDj.plan(song(120.0, 200.0), next, 20.0, 24.5, false, 8)
        val v2 = ForkAutoDj.plan(song(120.0, 200.0), next, 20.0, 24.5, true, 8)
        assertTrue(v1.mixIn > 15.0)
        assertTrue(v2.mixIn <= 15.0 + 1e-6, "cue ${v2.mixIn}")
        assertTrue(isDownbeat(v2.mixIn, next), "still on a bar line")
    }

    @Test
    fun advancedMovesABlendOffTwoSingersAtOnce() {
        val cur = song(120.0, 200.0, id = "a")
        val next = song(120.0, 200.0, id = "b")
        val plain = ForkAutoDj.plan(cur, next, 20.0, 24.5, true, 8)
        // The playing song sung all through its blend; the next song only from its cue on, for 8 bars.
        val sungOut = listOf(LoopMix.Span(plain.mixOut - 30, plain.mixOut + plain.outSpan))
        val sungIn = listOf(LoopMix.Span(plain.mixIn, plain.mixIn + plain.outSpan))
        val moved = ForkAutoDj.plan(cur, next, 20.0, 24.5, true, 8, sungOut, sungIn)
        assertNotNull(moved.rerouted)
        assertTrue(moved.clash <= 0.5 * 1.0 + 1e-9, "clash ${moved.clash}")
        assertTrue(moved.mixOut >= plain.mixOut - ForkAutoDj.MAX_DISCARD_SEC - 1e-6)
        // Web AutoDJ never moves it.
        val v1 = ForkAutoDj.plan(cur, next, 20.0, 24.5, false, 8, sungOut, sungIn)
        assertEquals(plain.mixOut, v1.mixOut, 1e-9)
        assertNull(v1.rerouted)
    }

    // ---- into upstream's planner ----

    // ---- key-aware length (the extension's automixKeyAware) ----

    private fun keyed(s: ForkAutoDj.Side, key: String, conf: Double = 0.9, id: String = s.id) =
        ForkAutoDj.Side(id, s.durationSec, s.grid, s.confidence, s.energy, s.introEndSec, s.contentEndSec, s.vocalWindows, s.vocalMask, s.stub, key, conf)

    @Test
    fun camelotNumbersMatchTheWheel() {
        assertEquals(8 to 'B', ForkAutoDj.camelot("C major"))
        assertEquals(8 to 'A', ForkAutoDj.camelot("A minor"))
        assertEquals(9 to 'B', ForkAutoDj.camelot("G major"))
        assertEquals(10 to 'A', ForkAutoDj.camelot("B minor"))
        assertEquals(3 to 'B', ForkAutoDj.camelot("C♯ major"))
        assertEquals(3 to 'B', ForkAutoDj.camelot("Db major"))
        assertEquals(11 to 'A', ForkAutoDj.camelot("F#m"))
        assertNull(ForkAutoDj.camelot(""))
    }

    @Test
    fun clashingKeysGetAShortBlendOnlyInAdvancedWithTheToggleOn() {
        val cur = keyed(song(120.0, 200.0), "C major")
        val clash = keyed(song(120.0, 200.0, id = "b"), "F♯ major")
        // 120 BPM: 2 bars are 4 s, not under 4 s, so 2 bars.
        val on = ForkAutoDj.plan(cur, clash, 20.0, 24.5, true, 16, keyAware = true)
        assertEquals(2, on.bars)
        assertEquals("C major → F♯ major", on.keyClash)
        assertEquals(16, ForkAutoDj.plan(cur, clash, 20.0, 24.5, true, 16, keyAware = false).bars)
        assertEquals(16, ForkAutoDj.plan(cur, clash, 20.0, 24.5, false, 16, keyAware = true).bars, "Web AutoDJ ignores it")
        val fifth = keyed(song(120.0, 200.0, id = "b"), "G major")
        assertNull(ForkAutoDj.plan(cur, fifth, 20.0, 24.5, true, 16, keyAware = true).keyClash, "one step round the wheel is compatible")
        val unsure = keyed(song(120.0, 200.0, id = "b"), "F♯ major", conf = 0.3)
        assertEquals(16, ForkAutoDj.plan(cur, unsure, 20.0, 24.5, true, 16, keyAware = true).bars, "an unsure key doesn't clash")
    }

    @Test
    fun aShortClashBlendUnderSixSecondsFrom140BpmIsFourBars() {
        val cur = keyed(song(150.0, 200.0), "C major")
        val next = keyed(song(150.0, 200.0, id = "b"), "F♯ major")
        // 150 BPM: 2 bars are 3.2 s, under the 6 s floor from 140 BPM.
        assertEquals(4, ForkAutoDj.plan(cur, next, 20.0, 24.5, true, 16, keyAware = true).bars)
    }

    private fun analysis(bpm: Double, duration: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        duration = duration,
        bpm = bpm,
        beatInterval = 60 / bpm,
        beatConfidence = 0.9,
        downbeats = generateSequence(0.4) { it + 240 / bpm }.takeWhile { it < duration }.toList(),
        firstBeat = 0.4,
        energyCurve = (0 until duration.toInt()).map { EnergySample(it.toDouble(), 1.0) },
    )

    private val a = analysis(120.0, 200.0)
    private val b = analysis(120.0, 200.0)
    private val ta = TransitionTrackInfo("a", 200_000, "Song A", "Artist", "Album A")
    private val tb = TransitionTrackInfo("b", 200_000, "Song B", "Artist", "Album B")

    private fun upstream(style: AutomixStyle, pos: Double, mode: CrossfadeMode = CrossfadeMode.SMART, next: TrackAnalysis = b) = planTransition(
        analysis = a, nextAnalysis = next, currentTrack = ta, nextTrack = tb,
        currentTime = pos, duration = 200.0, mode = mode, advanced = true, forkStyle = style,
    )

    @Test
    fun bitChordStyleLeavesUpstreamsPlanAsItWas() {
        val before = planTransition(analysis = a, nextAnalysis = b, currentTrack = ta, nextTrack = tb, currentTime = 20.0, duration = 200.0, mode = CrossfadeMode.SMART, advanced = true)
        assertEquals(before, upstream(AutomixStyle.BITCHORD, 20.0))
        assertNull(before.forkAutoDj)
    }

    @Test
    fun autoDjOnlyTakesAutomixNotACrossfade() {
        assertNotNull(upstream(AutomixStyle.ORIGINAL, 20.0).forkAutoDj)
        assertNull(upstream(AutomixStyle.ORIGINAL, 20.0, mode = CrossfadeMode.STANDARD).forkAutoDj)
        val adv = upstream(AutomixStyle.ADVANCED, 20.0)
        assertTrue(adv.forkAutoDj!!.v2)
        assertTrue(adv.reason.contains("advanced-autodj"))
    }

    @Test
    fun anAlbumPlayedThroughIsLeftToUpstream() {
        val plan = planTransition(
            analysis = a, nextAnalysis = b, currentTrack = ta, nextTrack = tb, currentTime = 20.0, duration = 200.0,
            mode = CrossfadeMode.SMART, albumSequential = true, forkStyle = AutomixStyle.ORIGINAL,
        )
        assertNull(plan.forkAutoDj)
    }

    @Test
    fun thePlanIsHeldOnceItIsCloseEnoughToArm() {
        val early = upstream(AutomixStyle.ORIGINAL, 20.0)
        val armAt = early.transitionStart - ForkAutoDj.LEAD_SEC
        val held = upstream(AutomixStyle.ORIGINAL, armAt)
        // A refined analysis of the next song no longer moves it...
        val refined = upstream(AutomixStyle.ORIGINAL, armAt + 1, next = analysis(124.0, 200.0))
        assertEquals(held.transitionStart, refined.transitionStart)
        assertEquals(held.incomingPlaybackRate, refined.incomingPlaybackRate)
        // ...and it starts at its start.
        assertTrue(!held.shouldStart)
        assertTrue(upstream(AutomixStyle.ORIGINAL, held.transitionStart).shouldStart)
    }

    // ---- the curves ----

    private fun shape(style: ForkAutoDj.Style, v2: Boolean = false, clash: Double = 0.0) =
        ForkAutoDj.Shape(v2, style, clash, beats = 32.0, swapDb = -12.0, barFraction = 0.125, outputSeconds = 16.0, rampBeats = 0)

    private fun at(s: ForkAutoDj.Shape, p: Float) = ForkAutoDjCurves.Frame().also { ForkAutoDjCurves.at(s, p, it) }

    @Test
    fun originalFadersAreEqualPower() {
        for (style in ForkAutoDj.Style.entries) {
            val s = shape(style)
            assertEquals(1f, at(s, 0f).gainOut, 1e-6f)
            assertEquals(0f, at(s, 0f).gainIn, 1e-6f)
            assertEquals(1f, at(s, 1f).gainIn, 1e-6f)
            assertEquals(cos(PI / 4).toFloat(), at(s, 0.5f).gainOut, 1e-5f)
            val mid = at(s, 0.3f)
            assertEquals(1f, mid.gainIn * mid.gainIn + mid.gainOut * mid.gainOut, 1e-5f)
        }
    }

    @Test
    fun originalBlendSwapsTheBassOverTheMiddleBarAndCrossesTheHighs() {
        val s = shape(ForkAutoDj.Style.BLEND)
        val start = at(s, 0.2f)
        assertEquals(-12f, start.inLowDb, 1e-5f)
        assertEquals(0f, start.outLowDb, 1e-5f)
        val swapped = at(s, 0.6f)
        assertEquals(0f, swapped.inLowDb, 1e-5f)
        assertEquals(-12f, swapped.outLowDb, 1e-5f)
        val middle = at(s, 0.5f)
        assertEquals(-6f, middle.inLowDb, 1e-4f)
        assertEquals(-6f, middle.outLowDb, 1e-4f)
        assertEquals(0f, at(s, 0f).outHighDb, 1e-6f)
        assertEquals(-8f, at(s, 0f).inHighDb, 1e-6f)
        assertEquals(-8f, at(s, 1f).outHighDb, 1e-6f)
        assertEquals(0f, at(s, 1f).inHighDb, 1e-6f)
        // No cut filters on a version-1 blend.
        assertEquals(ForkAutoDjCurves.OPEN_HZ.toFloat(), start.outLowPassHz)
        assertEquals(ForkAutoDjCurves.OFF_HZ.toFloat(), start.inHighPassHz)
    }

    @Test
    fun originalFilterMixSweepsBothSides() {
        val s = shape(ForkAutoDj.Style.FILTER)
        assertEquals(20f, at(s, 0f).outHighPassHz, 1e-3f)
        assertEquals(1800f, at(s, 1f).outHighPassHz, 1e-1f)
        assertEquals(350f, at(s, 0f).inLowPassHz, 1e-2f)
        assertEquals(20_000f, at(s, 1f).inLowPassHz, 1f)
        assertEquals(sqrt(20.0 * 1800).toFloat(), at(s, 0.5f).outHighPassHz, 0.5f)
    }

    @Test
    fun crossfadeIsFadersOnly() {
        val f = at(shape(ForkAutoDj.Style.CROSSFADE, v2 = true), 0.4f)
        assertEquals(0f, f.inLowDb); assertEquals(0f, f.outHighDb)
        assertEquals(ForkAutoDjCurves.OPEN_HZ.toFloat(), f.outLowPassHz)
        assertEquals(ForkAutoDjCurves.OFF_HZ.toFloat(), f.inHighPassHz)
    }

    @Test
    fun advancedHandsTheBassOverAtTheMiddleBarLine() {
        val s = shape(ForkAutoDj.Style.BLEND, v2 = true)
        val before = at(s, 0.40f)
        val after = at(s, 0.51f)
        assertEquals(20f, before.outHighPassHz, 1e-3f)
        assertEquals(200f, after.outHighPassHz, 1e-2f)
        assertTrue(after.inHighPassHz <= 20.5f)
        // The next song's fader full by the swap; the playing one only falls after it.
        assertEquals(1f, at(s, 0.5f).gainIn, 1e-5f)
        assertEquals(1f, at(s, 0.3f).gainOut, 1e-5f)
        assertEquals(0f, at(s, 1f).gainOut, 1e-5f)
        // Its trim is the players' headroom trim.
        assertEquals(1f / sqrt(2f), ForkAutoDjCurves.trim(1f, 1f), 1e-6f)
    }

    @Test
    fun advancedFadersMoveToTheVocalSafePairWhenBothSing() {
        val s = shape(ForkAutoDj.Style.BLEND, v2 = true, clash = 1.0)
        val f = at(s, 0.5f)
        assertEquals(kotlin.math.sin(0.5.pow(1.65) * PI / 2).toFloat(), f.gainIn, 1e-5f)
        assertEquals(cos(0.5.pow(0.65) * PI / 2).toFloat(), f.gainOut, 1e-5f)
    }
}
