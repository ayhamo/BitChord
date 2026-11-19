package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.automixv2.ForkV2EditorGeometry as G
import com.music.bitchord.playback.smart.automixv2.ForkV2PlannerTest.Companion.song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's arithmetic, checked against the extension's automix.js windowLimits / editorGrid and the overview's drag. */
class ForkV2EditorGeometryTest {

    private fun blend(bars: Int, beatSec: Double, ramp: Double = 0.0): ForkV2Planner.Plan {
        val p = ForkV2Planner.Plan()
        p.style = "blend"
        p.bars = bars
        p.beatSec = beatSec
        p.rate = 1.0
        p.outRate = 1.0
        p.rampSongSec = ramp
        p.durationSec = bars * 4 * beatSec
        return p
    }

    @Test
    fun blendLimitsEndBeforeTheMusicAndTenSecondsBeforeB() {
        val a = song("a", 120.0, 200.0, 0.9)
        val b = song("b", 120.0, 180.0, 0.9)
        val l = G.windowLimits(blend(8, 0.5, ramp = 2.0), a, b, 60.0)
        // 8 bars of 0.5 s beats: 16 s on both sides
        assertEquals(62.0, l.outLo, 1e-9) // the earliest plus the tempo ramp
        assertEquals(200.0 - 0.3 - 16.0, l.outHi, 1e-9)
        assertEquals(0.0, l.inLo, 0.0)
        assertEquals(180.0 - 10 - 16.0, l.inHi, 1e-9)
    }

    @Test
    fun aSongWhoseMusicEndsEarlyHoldsTheMixOutThere() {
        val a = song("a", 120.0, 200.0, 0.9)
        a.contentEndSec = 190.0
        val l = G.windowLimits(blend(4, 0.5), a, song("b", 120.0, 180.0, 0.9), 10.0)
        assertEquals(190.0 - 8.0, l.outHi, 1e-9)
    }

    @Test
    fun aFadeIsLimitedByItsSecondsAndAGlideByItsIncomingSpan() {
        val f = ForkV2Planner.Plan()
        f.style = "crossfade"
        f.durationSec = 12.0
        val l = G.windowLimits(f, song("a", 120.0, 200.0, 0.9), song("b", 120.0, 100.0, 0.9), 20.0)
        assertEquals(20.0, l.outLo, 0.0)
        assertEquals(200.0 - 0.3 - 12.0, l.outHi, 1e-9)
        assertEquals(100.0 - 10 - 12.0, l.inHi, 1e-9)
        val g = blend(4, 0.5)
        val gl = ForkV2Planner.Glide(0.95, 16, 0.5)
        g.glide = gl
        val lg = G.windowLimits(g, song("a", 120.0, 200.0, 0.9), song("b", 120.0, 100.0, 0.9), 20.0)
        assertEquals(100.0 - 10 - 8.0 / 0.95, lg.inHi, 1e-9)
    }

    @Test
    fun limitsNeverCrossAndNothingKnownIsZero() {
        val l = G.windowLimits(blend(32, 0.5), song("a", 120.0, 40.0, 0.9), null, 30.0)
        assertEquals(l.outLo, l.outHi, 0.0)
        assertEquals(0.0, l.inHi, 0.0)
    }

    @Test
    fun theOutgoingGridIsTheTailGrid() {
        val a = song("a", 120.0, 200.0, 0.9)
        a.tailGrid!!.firstDownbeatSec = 0.75
        a.tailGrid!!.beatIntervalSec = 0.48
        a.tailGrid!!.bpm = 125.0
        val out = G.editorGrid(a, true)!!
        assertEquals(0.75, out.downbeatSec, 0.0)
        assertEquals(0.48, out.beatSec, 0.0)
        val inn = G.editorGrid(a, false)!!
        assertEquals(0.5, inn.downbeatSec, 0.0)
        assertNull(G.editorGrid(null, true))
    }

    @Test
    fun aDragSnapsToDownbeatsOrBeatsAndIsHeldAtItsLimits() {
        val grid = G.EditorGrid(0.5, 0.5, 120.0)
        // 101.3 s: the nearest downbeat is 100.5 (bar 50), the nearest beat 101.5
        val d = G.dragTo(101.3, 0.0, 300.0, grid, snap = true, beats = false, out = true)
        assertEquals(100.5, d.pos.sec, 1e-9)
        assertEquals(50, d.pos.bar)
        assertNull(d.limit)
        val b = G.dragTo(101.3, 0.0, 300.0, grid, snap = true, beats = true, out = true)
        assertEquals(101.5, b.pos.sec, 1e-9)
        assertEquals(2, b.pos.beat)
        // past A's earliest: held there, and a snap that lands before it moves one bar inside
        val held = G.dragTo(40.0, 60.2, 300.0, grid, snap = true, beats = false, out = true)
        assertEquals("A has already played up to there", held.limit)
        assertTrue(held.pos.sec >= 60.2 - 1e-6)
        assertEquals(60.5, held.pos.sec, 1e-9)
        val end = G.dragTo(400.0, 0.0, 170.1, grid, snap = true, beats = false, out = false)
        assertEquals("the window has to end 10 s before B's end", end.limit)
        assertTrue(end.pos.sec <= 170.1 + 1e-6)
        assertEquals("that is B's start", G.dragTo(-3.0, 0.0, 100.0, grid, true, false, out = false).limit)
    }

    @Test
    fun beatMatchOffDoesNotSnapAndNoGridKeepsTheSeconds() {
        val grid = G.EditorGrid(0.5, 0.5, 120.0)
        val d = G.dragTo(101.3, 0.0, 300.0, grid, snap = false, beats = false, out = true)
        assertEquals(101.3, d.pos.sec, 1e-9)
        // the beat at or before it (101.0 = bar 50, beat 1) plus 300 ms as a nudge
        assertEquals(50, d.pos.bar)
        assertEquals(1, d.pos.beat)
        assertEquals(300, d.pos.nudgeMs)
        val none = G.dragTo(101.3456, 0.0, 300.0, null, snap = true, beats = false, out = true)
        assertEquals(101.346, none.pos.sec, 1e-9)
        assertEquals(0, none.pos.bar)
    }

    @Test
    fun theSongMovesWithThePointer() {
        // dragging right by a tenth of the width brings what was left of the frame under it
        assertEquals(97.0, G.dragWant(100.0, 50.0, 500.0, 30.0), 1e-9)
        assertEquals(100.0, G.dragWant(100.0, 50.0, 0.0, 30.0), 0.0)
    }

    @Test
    fun theFirstWindowEditSavesTheOtherSideWhereThePlanHasIt() {
        val grid = G.EditorGrid(0.5, 0.5, 120.0)
        val pos = ForkV2MixOverride.Position(80, 0, 0, 160.5)
        val (o, i) = G.windowWith(null, true, pos, 150.0, 33.27, grid, grid)
        assertEquals(160.5, o.sec, 0.0)
        assertEquals(33.27, i.sec, 1e-9)
        assertEquals(270, i.nudgeMs) // 33.0 is a beat; the rest is a nudge, not a snap
        val ov = ForkV2MixOverride()
        ov.`in` = ForkV2MixOverride.Position(4, 0, 0, 8.5)
        val (_, kept) = G.windowWith(ov, true, pos, 150.0, 33.27, grid, grid)
        assertEquals(8.5, kept.sec, 0.0)
    }

    @Test
    fun nudgesStepTenMsAndStopAtTwoSeconds() {
        val p = G.nudged(ForkV2MixOverride.Position(10, 0, 0, 20.5), 1)!!
        assertEquals(10, p.nudgeMs)
        assertEquals(20.51, p.sec, 1e-9)
        assertEquals(-10, G.nudged(ForkV2MixOverride.Position(10, 0, 0, 20.5), -1)!!.nudgeMs)
        assertNull(G.nudged(ForkV2MixOverride.Position(10, 0, 2000, 22.5), 1))
    }

    @Test
    fun anEditMergesAsTheOverviewsPick() {
        // a migrated Blend pick: picking a preset drops its family, the length stays
        val migrated = ForkV2MixOverride.fromJson(mapOf("style" to "filter", "bars" to 8))!!
        assertEquals("filter", migrated.family)
        val smooth = G.edited(migrated, presetPicked = true) { preset = "smooth" }!!
        assertNull(smooth.family)
        assertEquals("smooth", smooth.preset)
        assertEquals(8, smooth.bars)
        // a lane edit is Custom, starting from the preset's lanes
        val lanes = G.lanesNow(smooth)
        val custom = G.edited(smooth, presetPicked = true) { preset = "custom"; volume = lanes[0]; eq = lanes[1]; effect = "lpBoth" }!!
        assertEquals("custom", custom.preset)
        assertEquals("smooth", custom.volume)
        assertEquals("centerBassSwap", custom.eq)
        assertEquals("lpBoth", custom.effect)
        // another preset drops the custom lanes
        assertNull(G.edited(custom, true) { preset = "rise" }!!.volume)
        // Loop has no length, window or beat match
        val withWindow = G.edited(smooth, false) { beatMatch = "sync"; out = ForkV2MixOverride.Position(1, 0, 0, 2.5) }!!
        val loop = G.edited(withWindow, true) { preset = "loop" }!!
        assertEquals(0, loop.bars)
        assertNull(loop.out)
        assertNull(loop.beatMatch)
        // all Auto: nothing left to save
        assertNull(G.edited(G.edited(null, true) { preset = "slam" }, true) { preset = "auto" })
        assertNull(G.edited(null, false) { beatMatch = "auto" })
    }

    @Test
    fun theLanesAFirstChangeStartsFrom() {
        assertEquals(listOf("smooth", "centerBassSwap", "none"), G.lanesNow(null).toList())
        val rise = ForkV2MixOverride()
        rise.preset = "rise"
        assertEquals(listOf("overlap", "endBassSwap", "lpInHpOut"), G.lanesNow(rise).toList())
    }

    @Test
    fun autosNameSaysWhatThePlannerChose() {
        val p = ForkV2Planner.Plan()
        p.style = "crossfade"
        p.handoff = true
        assertEquals("Auto · Hand-over", G.autoName(p, false))
        p.handoff = false
        p.bridge = true
        assertEquals("Auto · Bridge", G.autoName(p, false))
        p.bridge = false
        assertEquals("Auto · Fade", G.autoName(p, false))
        p.style = "blend"
        assertEquals("Auto · Blend", G.autoName(p, false))
        p.shape = "dropin"
        assertEquals("Auto · Drop-in", G.autoName(p, false))
        p.shape = "muffle"
        assertEquals("Auto · Muffle", G.autoName(p, false))
        p.shape = null
        p.glide = ForkV2Planner.Glide(0.95, 16, 0.5)
        assertEquals("Auto · Glide", G.autoName(p, false))
        p.style = "filter"
        assertEquals("Auto · Filter", G.autoName(p, false))
        assertEquals("Auto · Loop", G.autoName(p, true))
        val ov = ForkV2MixOverride()
        ov.preset = "melt"
        assertEquals("Melt", G.presetName(ov, p, false))
    }

    @Test
    fun theHeaderNamesLengthTempoAndSeconds() {
        val p = blend(8, 0.5)
        p.bpm = 120.0
        p.outBpm = 120.0
        p.inBpm = 120.0
        assertEquals("Auto · Blend · 8 bars · 120 BPM · 16.0 s", G.headline("Auto · Blend", p, false))
        p.inBpm = 124.0
        assertEquals("Auto · Blend · 8 bars · 120→124 BPM · 16.0 s", G.headline("Auto · Blend", p, false))
        val f = ForkV2Planner.Plan()
        f.style = "crossfade"
        f.durationSec = 12.04
        assertEquals("Auto · Fade · 12.0 s", G.headline("Auto · Fade", f, false))
        assertEquals("Auto · Loop", G.headline("Auto · Loop", p, true))
    }

    @Test
    fun theReadoutAndClocks() {
        val out = ForkV2MixOverride.Position(80, 0, 0, 192.4)
        val inn = ForkV2MixOverride.Position(16, 0, 0, 32.4)
        assertEquals("A mixes out at 3:12.4 (bar 81) · B enters at 0:32.4 (bar 17)", G.readout(out, inn))
        assertEquals("0:05.0", G.fmtSec(5.0))
        assertEquals("1:00.0", G.fmtSec(59.97))
        assertEquals("Mix in 1:05", G.mixClock(-65.2, 16.0))
        assertEquals("Mixing · 0:04 / 0:16", G.mixClock(4.0, 16.0))
        assertEquals("", G.mixClock(17.0, 16.0))
        assertEquals("", G.mixClock(Double.NaN, 16.0))
    }

    @Test
    fun curvesHoldOutsideTheirPointsAndHzIsGeometric() {
        val gain = arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(10.0, 0.0))
        assertEquals(1.0, G.curveAt(gain, -3.0, false, 0.5), 0.0)
        assertEquals(0.5, G.curveAt(gain, 5.0, false, 0.5), 1e-9)
        assertEquals(0.0, G.curveAt(gain, 30.0, false, 0.5), 0.0)
        val lp = arrayOf(doubleArrayOf(0.0, 20000.0), doubleArrayOf(10.0, 200.0))
        assertEquals(2000.0, G.curveAt(lp, 5.0, true, 20000.0), 1e-6)
        assertEquals(7.0, G.curveAt(null, 1.0, false, 7.0), 0.0)
        assertEquals(1.0, G.dbHeight(0.0), 0.0)
        assertEquals(0.0, G.dbHeight(-18.0), 0.0)
        assertEquals(1.0, G.lowpassHeight(20000.0), 1e-9)
        assertEquals(1.0, G.highpassHeight(20.0), 1e-9)
    }

    @Test
    fun theBadgeMarksAnyOverride() {
        assertFalse(G.badge(null))
        assertTrue(G.badge(ForkV2MixOverride.fromJson(mapOf("style" to "blend"))))
        assertNotNull(ForkV2MixOverride.fromJson(mapOf("style" to "blend")))
    }

    @Test
    fun theUnusedLengthAndTheUnusedWindowAreSaid() {
        // a length asked for where the planner hands over (no beat grid at the next song)
        val a = song("a", 120.0, 200.0, 0.9)
        val b = song("b", 120.0, 180.0, 0.1)
        val s = ForkV2Automix.settings(0)
        val ov = ForkV2MixOverride()
        ov.bars = 16
        val p = ForkV2Planner.plan(s, "a", "b", a, b, 200.0, 60.0, 60.0 + ForkV2Automix.LATCH_LEAD_SEC, null, ov)
        assertTrue(p.handoff)
        assertEquals("16 bars not used: ${p.reason}", p.warnings!!.single())
        // a pick that forced the filter mix keeps its own window
        val f = ForkV2MixOverride.fromJson(mapOf("style" to "filter"))!!
        f.out = ForkV2MixOverride.Position(100, 0, 0, 150.5)
        val pf = ForkV2Planner.plan(s, "a", "b", a, song("b", 120.0, 180.0, 0.9), 200.0, 60.0, 64.0, null, f)
        assertEquals("filter", pf.style)
        assertEquals("a Filter mix places itself: the saved window isn't used (pick a preset to use it)", pf.warnings!!.first())
    }
}
