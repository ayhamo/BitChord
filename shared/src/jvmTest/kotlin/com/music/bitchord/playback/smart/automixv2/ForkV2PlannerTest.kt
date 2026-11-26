package com.music.bitchord.playback.smart.automixv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's PlannerTest.java, ported. */
class ForkV2PlannerTest {

    companion object {
        /** As Automix plans. */
        const val EARLIEST_LEAD = ForkV2Mix.HAND_LEAD + 2.5

        /** A song with a steady grid from 0.5 s (the same at its end), no energy curve. */
        fun song(id: String, bpm: Double, dur: Double, conf: Double): ForkV2Analysis {
            val a = ForkV2Analysis()
            a.fileKey = id
            a.durationSec = dur
            a.bpm = bpm
            a.beatIntervalSec = 60 / bpm
            a.firstDownbeatSec = 0.5
            a.gridConfidence = conf
            val t = ForkV2Analysis.LocalGrid()
            t.bpm = bpm
            t.beatIntervalSec = 60 / bpm
            t.firstDownbeatSec = 0.5
            t.gridConfidence = conf
            a.tailGrid = t
            a.energy = 0.5
            a.introEndSec = 8.0
            return a
        }

        fun plan(s: ForkV2Planner.Settings, from: ForkV2Analysis?, to: ForkV2Analysis?, pos: Double): ForkV2Planner.Plan =
            ForkV2Planner.plan(s, "a", "b", from, to, from?.durationSec ?: 200.0, pos, pos + EARLIEST_LEAD, null)

        fun flat(duration: Double): ForkV2Planner.Audio = object : ForkV2Planner.Audio {
            override fun duration(): Double = duration

            override fun rms(from: Double, to: Double): Double = 0.5
        }

        fun loopPlan(): ForkV2Planner.LoopPlan? {
            val sung = ForkV2Planner.Sung()
            sung.out = arrayOf()
            sung.`in` = arrayOf()
            return ForkV2Planner.planLoopMix(song("a", 120.0, 200.0, 0.9), flat(200.0), song("b", 120.0, 180.0, 0.9), flat(180.0), 100.0, sung)
        }
    }

    @Test
    fun sameTempoBlendEndsOnTheBarGridBeforeTheEnd() {
        val p = plan(ForkV2Planner.Settings(), song("a", 120.0, 200.0, 0.9), song("b", 120.0, 180.0, 0.9), 60.0)
        assertEquals("blend", p.style)
        assertFalse(p.fallback)
        assertEquals(1.0, p.rate, 0.0)
        assertEquals(1.0, p.outRate, 0.0)
        assertEquals(0, p.rampBeats)
        // auto length: 16 beats capped at 12 s -> 4 bars of 2 s
        assertEquals(4, p.bars)
        assertEquals(8.0, p.durationSec, 1e-9)
        // the last downbeat that leaves a bar after the blend: 200 - 2 - 8 = 190 -> 188.5
        assertEquals(188.5, p.mixOutTrackTime, 1e-9)
        assertEquals(0.0, ((p.mixOutTrackTime - 0.5) / 2) % 1, 1e-9)
        assertTrue(p.mixOutTrackTime + p.durationSec <= 200 - 0.3)
        // the next song arrives so its first full-energy phrase (16.5 s) lands at the blend's end
        assertEquals(8.5, p.mixInTrackTime, 1e-9)
        assertEquals(ForkV2Planner.SHORT_BLEND_KILL_DB, p.swapDb, 0.0)
    }

    @Test
    fun fixedBarsAreHonouredAndConfidenceLimitsThem() {
        val s = ForkV2Planner.Settings()
        s.bars = 16
        var p = plan(s, song("a", 120.0, 200.0, 0.9), song("b", 120.0, 180.0, 0.9), 60.0)
        assertEquals(16, p.bars)
        assertEquals(ForkV2Planner.KILL_DB, p.swapDb, 0.0)
        p = plan(s, song("a", 120.0, 200.0, 0.5), song("b", 120.0, 180.0, 0.9), 60.0)
        assertEquals(8, p.bars) // confidence 0.45-0.6: at most 8
        p = plan(s, song("a", 120.0, 200.0, 0.3), song("b", 120.0, 180.0, 0.9), 60.0)
        assertEquals(4, p.bars) // under 0.45: at most 4
    }

    @Test
    fun slowerNextSongSpeedsThePlayingSongUpOverARamp() {
        // 120 into 126: the playing song goes up 5% in 0.75% steps per beat
        val p = plan(ForkV2Planner.Settings(), song("a", 120.0, 200.0, 0.9), song("b", 126.0, 180.0, 0.9), 60.0)
        assertEquals("blend", p.style)
        assertEquals(1.0, p.rate, 0.0)
        assertEquals(1.05, p.outRate, 1e-9)
        assertEquals(7, p.rampBeats)
        assertEquals(7 * 0.5, p.rampSongSec, 1e-9)
        assertEquals(126.0, p.bpm, 1e-9)
    }

    @Test
    fun temposTooFarApartGiveAFilterMix() {
        val p = plan(ForkV2Planner.Settings(), song("a", 120.0, 200.0, 0.9), song("b", 90.0, 180.0, 0.9), 60.0)
        assertEquals("filter", p.style)
        assertTrue(p.reason!!.contains("apart"), p.reason)
        assertEquals(1.0, p.rate, 0.0)
        assertEquals(0.5, p.mixInTrackTime, 1e-9) // on its own first downbeat
    }

    @Test
    fun halfTimeFoldsBeforeStretching() {
        assertEquals(1.0, ForkV2Planner.foldRate(2.0), 1e-12)
        assertEquals(1.0, ForkV2Planner.foldRate(0.5), 1e-12)
        assertEquals(1.1, ForkV2Planner.foldRate(1.1), 1e-12)
        assertTrue(ForkV2Planner.stretchAllowed(1.25))
        assertFalse(ForkV2Planner.stretchAllowed(1.26))
        assertTrue(ForkV2Planner.stretchAllowed(0.9))
        assertFalse(ForkV2Planner.stretchAllowed(0.89))
    }

    @Test
    fun unanalysedSongsFallBackToACrossfade() {
        val p = ForkV2Planner.plan(ForkV2Planner.Settings(), "a", "b", null, null, 200.0, 60.0, 60 + EARLIEST_LEAD, null)
        assertEquals("crossfade", p.style)
        assertTrue(p.fallback)
        assertEquals(200 - 0.3 - ForkV2Planner.FALLBACK_SEC, p.mixOutTrackTime, 1e-9)
        assertEquals(ForkV2Planner.FALLBACK_SEC, p.durationSec, 1e-9)
    }

    @Test
    fun noGridAtTheEndFallsBack() {
        val a = song("a", 120.0, 200.0, 0.9)
        a.tailGrid = null // known: no beat at its end
        val p = plan(ForkV2Planner.Settings(), a, song("b", 120.0, 180.0, 0.9), 60.0)
        assertTrue(p.fallback)
        assertTrue(p.reason!!.contains("end"), p.reason)
    }

    @Test
    fun tooLateForABlendGivesShorterOrNoMix() {
        val p = plan(ForkV2Planner.Settings(), song("a", 120.0, 200.0, 0.9), song("b", 120.0, 180.0, 0.9), 195.0)
        assertTrue(p.style == "crossfade" || p.mixOutTrackTime + p.durationSec <= 200)
        assertTrue(p.durationSec >= 0)
    }

    @Test
    fun advancedAutoDjPlanIsMarkedAndBuildsAMix() {
        val s = ForkV2Planner.Settings()
        s.version = 2
        val sung = ForkV2Planner.Sung()
        sung.out = arrayOf()
        sung.`in` = arrayOf()
        val p = ForkV2Planner.plan(s, "a", "b", song("a", 120.0, 200.0, 0.9), song("b", 120.0, 180.0, 0.9), 200.0, 60.0, 60 + EARLIEST_LEAD, sung)
        assertEquals("blend", p.style)
        assertTrue(p.v2)
        assertEquals(0.0, p.clash, 0.0)
        val m = ForkV2Mix.blend(p, "a", "b", 200.0)
        assertTrue(m.v2)
        assertEquals(1.0, m.out.gain.at(m.t0), 1e-3)
        assertEquals(1.0, m.`in`.gain.at(m.t0 + m.D + 0.01), 1e-9)
    }

    @Test
    fun keyClashRule() {
        val a = ForkV2Analysis()
        val b = ForkV2Analysis()
        a.camelotNum = 8
        a.camelotLetter = 'A'
        a.keyConfidence = 0.8
        b.camelotNum = 9
        b.camelotLetter = 'A'
        b.keyConfidence = 0.8
        assertNull(ForkV2Planner.keysClash(a, b)) // neighbours
        b.camelotNum = 7
        assertNull(ForkV2Planner.keysClash(a, b))
        b.camelotNum = 8
        b.camelotLetter = 'B'
        assertNull(ForkV2Planner.keysClash(a, b)) // relative major
        a.camelotNum = 12
        a.camelotLetter = 'B'
        b.camelotNum = 1
        assertNull(ForkV2Planner.keysClash(a, b)) // 12 and 1 are neighbours on the wheel
        b.camelotNum = 3
        assertNotNull(ForkV2Planner.keysClash(a, b))
        b.keyConfidence = 0.4
        assertNull(ForkV2Planner.keysClash(a, b)) // a weak key never clashes
    }

    @Test
    fun vocalOverlapFromLyricSpans() {
        val a = song("a", 120.0, 200.0, 0.9)
        val b = song("b", 120.0, 180.0, 0.9)
        val sung = ForkV2Planner.Sung()
        sung.out = arrayOf(doubleArrayOf(0.0, 1000.0))
        sung.`in` = arrayOf(doubleArrayOf(0.0, 1000.0))
        assertEquals(1.0, ForkV2Planner.vocalOverlap(a, b, 100.0, 8.0, 0.0, 1.0, sung), 1e-9)
        sung.`in` = arrayOf()
        assertEquals(0.0, ForkV2Planner.vocalOverlap(a, b, 100.0, 8.0, 0.0, 1.0, sung), 1e-9)
        // neither lyrics nor a mask: unknown
        assertTrue(ForkV2Planner.vocalOverlap(a, b, 100.0, 8.0, 0.0, 1.0, ForkV2Planner.Sung()).isNaN())
    }

    @Test
    fun maskStatsNeedsCoverage() {
        val w = ForkV2Analysis.VocalWindow()
        w.startSec = 10.0
        w.fps = 10.0
        w.curve = FloatArray(100) // 10 s to 20 s
        for (i in 50 until 60) w.curve[i] = 1f // sung 15-16 s
        val m = ForkV2Planner.maskStats(listOf(w), 12.0, 18.0)
        assertNotNull(m)
        assertEquals(10 / 60.0, m[0], 1e-9)
        assertEquals(1.0, m[1], 1e-9)
        assertNull(ForkV2Planner.maskStats(listOf(w), 0.0, 12.0)) // covers 2 of 12 s
    }

    @Test
    fun loopMixPicksTheLastCleanLoopOutAndTheFirstIn() {
        val lp = loopPlan()
        assertNotNull(lp)
        assertNull(lp.none)
        assertEquals(190.5, lp.out!!.loopStart, 1e-9)
        assertEquals(198.5, lp.out!!.loopEnd, 1e-9)
        assertEquals(0.5, lp.`in`!!.loopStart, 1e-9)
        assertEquals(8.5, lp.`in`!!.loopEnd, 1e-9)
        assertEquals(1.0, lp.fold, 0.0)
        assertEquals(ForkV2Planner.MIN_RAMP_BARS, lp.rampBars)
        assertEquals(8, lp.totalBars)
        assertEquals("lyrics", lp.out!!.checkedBy)
    }

    @Test
    fun loopMixSkipsSungBarsAndMusicVideos() {
        val sung = ForkV2Planner.Sung()
        sung.out = arrayOf(doubleArrayOf(186.0, 199.0)) // the last loops are sung
        sung.`in` = arrayOf()
        val lp = ForkV2Planner.planLoopMix(song("a", 120.0, 200.0, 0.9), flat(200.0), song("b", 120.0, 180.0, 0.9), flat(180.0), 100.0, sung)
        assertNotNull(lp)
        assertTrue(lp.out!!.loopEnd <= 186)

        val video = song("a", 120.0, 200.0, 0.9)
        video.musicVideo = true
        assertNull(ForkV2Planner.planLoopMix(video, flat(200.0), song("b", 120.0, 180.0, 0.9), flat(180.0), 100.0, sung))
    }

    @Test
    fun loopScheduleKeepsBeatsInOrder() {
        val lp = loopPlan()!!
        val x = ForkV2Planner.loopSchedule(lp, 0.0)
        assertEquals(32, x.beats)
        assertEquals(x.beats + x.settleBeats + 1, x.beatTimes.size)
        for (k in 1 until x.beatTimes.size) assertTrue(x.beatTimes[k] > x.beatTimes[k - 1])
        assertEquals(16.0, x.releaseAt, 1e-9) // 32 beats at 120 BPM
        assertEquals(20.0, x.elementAt, 1e-9)
        assertTrue(x.enterEnd < x.rampEnd && x.rampEnd <= x.releaseAt)
    }

    @Test
    fun tempoRampIsMonotonic() {
        var last = ForkV2Planner.tempo(0, 16, 24, 120.0, 128.0)
        assertEquals(120.0, last, 0.0)
        for (k in 1 until 40) {
            val t = ForkV2Planner.tempo(k, 16, 24, 120.0, 128.0)
            assertTrue(t >= last)
            last = t
        }
        assertEquals(128.0, last, 0.0)
    }

    @Test
    fun jroundIsJavasMathRound() {
        assertEquals(3L, jround(2.5))
        assertEquals(-2L, jround(-2.5))
        assertEquals(0L, jround(0.49999999999999994))
        assertEquals(0L, jround(Double.NaN))
        assertEquals(Math.round(1234.5678 * 1e3), jround(1234.5678 * 1e3))
    }

    @Test
    fun aSavedMixInIntoASongOfUnknownLengthIsNotNan() {
        val next = song("b", 120.0, Double.NaN, 0.9)
        assertEquals(0.0, ForkV2Planner.inRoom(next, 8.0))
        assertEquals(0.0, ForkV2Planner.clampWindow(30.0, 0.0, ForkV2Planner.inRoom(next, 8.0), "the mix-in", null, null))
        assertEquals(200.0 - 0.3 - 8.0, ForkV2Planner.inRoom(song("c", 120.0, 200.0, 0.9), 8.0), 1e-9) // ends 0.3 s before B does (editor.md §6, 2026-11-26)
    }

    @Test
    fun loopsOnASideWithNoTempoFollowTheGridAndAZeroBarEnds() {
        val side = ForkV2Analysis.BeatSide()
        side.bpm = 0.0
        side.downbeats = DoubleArray(12) { 0.5 + 2.0 * it }
        val loops = ForkV2Planner.loopsBetween(side, 0.5, 2.0, 0.5, 16.5, 4)
        assertTrue(loops.isNotEmpty())
        loops.forEach { assertEquals(8.0, it[1] - it[0], 1e-9, "a loop is 4 bars long, not ended on the first downbeat") }
        assertTrue(ForkV2Planner.loopsBetween(null, 0.0, 0.0, 0.0, 10.0, 4).isEmpty())
    }

    @Test
    fun aWholeSongIsNotWalkedAgainForTheSameCurve() {
        val a = song("usual", 120.0, 200.0, 0.9)
        val c = ForkV2Analysis.Curve()
        c.step = 0.1
        c.values = DoubleArray(2000) { i -> if (i % 50 < 40) -12.0 else -30.0 }
        a.loudnessCurve = c
        val first = ForkV2Planner.usualLevel(a)
        val walks = ForkV2Planner.wholeWalks
        assertEquals(first, ForkV2Planner.usualLevel(a), "the same answer, from the cache")
        assertEquals(first, ForkV2Planner.usualLevel(a.copy()), "a copy of the analysis shares the curve")
        // the trim's whole-song loudness and the outro search's seconds come from the same walk
        val whole = ForkV2Planner.regionLoudness(a, 0.0, a.durationSec)
        assertEquals(whole, ForkV2Planner.wholeOf(a)!!.loudness, 1e-12)
        assertNotNull(ForkV2Planner.loudnessTrim(a, 150.0, 10.0, a.copy(), 20.0, 10.0))
        assertEquals(walks, ForkV2Planner.wholeWalks, "no new walk over the song")
        val secs = ForkV2Planner.wholeOf(a)!!.secs
        assertEquals(200, secs.size)
        assertEquals(ForkV2Planner.ampMean(a, 37.0, 38.0), secs[37], 1e-12)
        val quieter = ForkV2Analysis.Curve()
        quieter.step = 0.1
        quieter.values = DoubleArray(2000) { -40.0 }
        a.loudnessCurve = quieter
        assertTrue(ForkV2Planner.usualLevel(a) < first, "another curve is walked")
        assertEquals(walks + 1, ForkV2Planner.wholeWalks)
    }

    @Test
    fun theChromaProfileReadsOnlyTheWindowAndGivesTheSameAnswer() {
        val a = song("chroma", 120.0, 200.0, 0.9)
        val c = ForkV2Analysis.ChromaCurve()
        c.start = 0.25
        c.step = 0.5
        c.values = Array(400) { i -> if (i % 7 == 3) null else DoubleArray(12) { k -> ((i * 31 + k * 7) % 13) / 13.0 } }
        c.weights = DoubleArray(400) { i -> 0.5 + (i % 5) / 10.0 }
        a.chromaCurve = c
        // the old way: every frame, kept by its time
        fun whole(from: Double, to: Double): Array<DoubleArray>? {
            val profile = DoubleArray(12)
            var weight = 0.0
            var frames = 0
            for (i in c.values.indices) {
                val t = c.start + i * c.step
                val f = c.values[i]
                if (t < from || t > to || f == null) continue
                for (k in 0 until 12) profile[k] += f[k] * c.weights[i]
                weight += c.weights[i]
                frames++
            }
            return if (frames > 0) arrayOf(profile, doubleArrayOf(weight / frames)) else null
        }
        val windows = listOf(0.0 to 10.0, 0.25 to 0.25, 0.75 to 1.75, 37.3 to 61.9, 150.0 to 199.75, 190.0 to 260.0, -20.0 to 3.0, 250.0 to 300.0, -5.0 to -1.0)
        for ((from, to) in windows) {
            val want = whole(from, to)
            val got = ForkV2Planner.chromaProfile(a, from, to)
            if (want == null) { assertNull(got, "[$from, $to]"); continue }
            assertNotNull(got, "[$from, $to]")
            for (k in 0 until 12) assertEquals(want[0][k], got[0][k], 1e-12, "[$from, $to] pitch $k")
            assertEquals(want[1][0], got[1][0], 1e-12, "[$from, $to] weight")
        }
    }
}
