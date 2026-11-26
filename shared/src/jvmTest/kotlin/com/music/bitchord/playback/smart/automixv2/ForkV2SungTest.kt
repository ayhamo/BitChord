package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.ForkAnalysisProgress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * versions.md "Where a song is sung", "No effect three times in a row" and "Analysis length", and
 * editor.md §11's row (spec fef76a9), on made-up songs. The real pairs are ForkV2SpecFixturesTest's.
 */
class ForkV2SungTest {

    @AfterTest
    fun reset() {
        ForkV2Moves.clear()
        ForkV2Sung.clear()
        ForkAnalysisProgress.clear()
        com.music.bitchord.playback.smart.ForkMixPicks.style = com.music.bitchord.playback.smart.AutomixStyle.BITCHORD
    }

    @Test
    fun aLineIsSungAboutAsLongAsItsWordsTake() {
        val spans = ForkV2Sung.sungSpansFromLines(
            listOf(
                // plain LRC: 4 words, next line 20 s on: 4 x 0.35 + 0.5 = 1.9 s
                ForkV2Sung.LineShape(10.0, Double.NaN, 4, null),
                // its own end comes first
                ForkV2Sung.LineShape(30.0, 30.8, 10, null),
                // word times: first start to last end + 0.3
                ForkV2Sung.LineShape(40.0, 45.0, 2, arrayOf(doubleArrayOf(40.2, 40.6), doubleArrayOf(40.7, 41.0))),
                // the next line's start caps it
                ForkV2Sung.LineShape(50.0, Double.NaN, 30, null),
                ForkV2Sung.LineShape(52.0, Double.NaN, 30, null),
            ),
        )
        assertEquals(5, spans.size)
        assertEquals(11.9, spans[0][1], 1e-9)
        assertEquals(30.8, spans[1][1], 1e-9)
        assertEquals(40.2, spans[2][0], 1e-9)
        assertEquals(41.3, spans[2][1], 1e-9)
        assertEquals(52.0, spans[3][1], 1e-9)
        // and 10 s at most
        assertEquals(62.0, spans[4][1], 1e-9)
    }

    @Test
    fun theMaskCarvesABreathAcrossALineChange() {
        val a = ForkV2Analysis()
        a.fileKey = "a"
        a.beatIntervalSec = 0.5
        val w = ForkV2Analysis.VocalWindow()
        w.startSec = 0.0
        w.fps = 50.0
        // sung everywhere but 9.7..10.3 s (over half a beat each side of the line change at 10 s)
        w.curve = FloatArray(1000) { i -> if (i / 50.0 >= 9.7 && i / 50.0 < 10.3) 0.1f else 0.9f }
        a.vocals = mutableListOf(w)
        val carved = ForkV2Sung.carveSung(arrayOf(doubleArrayOf(5.0, 10.0), doubleArrayOf(10.0, 15.0)), a)!!
        assertEquals(2, carved.size, "the two lines join, then the beat-long breath is cut out")
        assertTrue(carved[0][1] in 9.65..9.75 && carved[1][0] in 10.25..10.35)
        // a breath shorter than a beat stays sung
        w.curve = FloatArray(1000) { i -> if (i / 50.0 >= 9.9 && i / 50.0 < 10.1) 0.1f else 0.9f }
        assertEquals(1, ForkV2Sung.carveSung(arrayOf(doubleArrayOf(5.0, 10.0), doubleArrayOf(10.0, 15.0)), a)!!.size)
        // no mask: the lyrics stand
        a.vocals = mutableListOf()
        assertEquals(2, ForkV2Sung.carveSung(arrayOf(doubleArrayOf(5.0, 10.0), doubleArrayOf(10.0, 15.0)), a)!!.size)
    }

    @Test
    fun aCopyOfTheAnalysisIsNotCarvedAgain() {
        // the planner copies the analysis on every plan (asOutgoing, musicEnd): the carve is kept on
        // the spans, the mask and the beat, never the copy (the extension's c42bb5f: 77 % of the PC's
        // main thread re-walking the mask)
        val a = ForkV2Analysis()
        a.fileKey = "a"
        a.beatIntervalSec = 0.5
        val w = ForkV2Analysis.VocalWindow()
        w.startSec = 0.0
        w.fps = 50.0
        w.curve = FloatArray(1000) { i -> if (i / 50.0 >= 9.7 && i / 50.0 < 10.3) 0.1f else 0.9f }
        a.vocals = mutableListOf(w)
        val spans = arrayOf(doubleArrayOf(5.0, 10.0), doubleArrayOf(10.0, 15.0))
        val first = ForkV2Sung.carveSung(spans, a)!!
        val before = ForkV2Sung.carves
        val again = ForkV2Sung.carveSung(spans, a.copy())!!
        assertEquals(before, ForkV2Sung.carves, "a copy of the same analysis hits the kept carve")
        assertEquals(first.map { it.toList() }, again.map { it.toList() })
        // the answer handed out is the caller's to change
        again[0][0] = -1.0
        assertEquals(first[0][0], ForkV2Sung.carveSung(spans, a)!![0][0])
        // another beat is another carve
        val other = a.copy().also { it.beatIntervalSec = 0.25 }
        ForkV2Sung.carveSung(spans, other)
        assertEquals(before + 1, ForkV2Sung.carves)
    }

    @Test
    fun theLiveSpansPreferTheSongsOwnLines() {
        val upstream = arrayOf(doubleArrayOf(1.0, 2.0))
        assertTrue(ForkV2Sung.liveSung("a", "b", upstream, null).out === upstream, "not asked yet: upstream's stand in")
        ForkV2Sung.remember("a", null)
        assertNull(ForkV2Sung.liveSung("a", "b", upstream, null).out, "asked, no lyrics: the mask decides")
        ForkV2Sung.remember("a", arrayOf(doubleArrayOf(3.0, 4.0)))
        assertEquals(3.0, ForkV2Sung.liveSung("a", "b", upstream, null).out!![0][0])
    }

    @Test
    fun anEffectHeardTwiceRunningIsAvoided() {
        val p = ForkV2Planner.Plan()
        p.style = "filter"
        ForkV2Moves.heard(p)
        assertNull(ForkV2Moves.avoidedMove())
        ForkV2Moves.heard(p)
        assertEquals("filter", ForkV2Moves.avoidedMove())
        val b = ForkV2Planner.Plan()
        b.style = "blend"
        ForkV2Moves.heard(b)
        assertNull(ForkV2Moves.avoidedMove(), "a blend in between")
        b.shape = "echo"
        ForkV2Moves.heard(b)
        ForkV2Moves.heard(b)
        assertEquals("echo", ForkV2Moves.avoidedMove())
        val c = ForkV2Planner.Plan()
        c.style = "blend"
        ForkV2Moves.heard(c)
        ForkV2Moves.heard(c)
        assertNull(ForkV2Moves.avoidedMove(), "the blend may repeat")
        repeat(40) { ForkV2Moves.heard(c) }
        assertEquals(ForkV2Moves.RECENT_MOVES, ForkV2Moves.recentMoves().size)
    }

    @Test
    fun aSongOverEightMinutesIsTooLongNotFailed() {
        // analysis-cases.json too-long-481 (check tooLong); Automix v2's rule only
        assertFalse(ForkAnalysisProgress.tooLong("long", 481.0), "BitChord's style leaves upstream's analyser alone")
        com.music.bitchord.playback.smart.ForkMixPicks.style = com.music.bitchord.playback.smart.AutomixStyle.V2
        assertTrue(ForkAnalysisProgress.tooLong("long", 481.0))
        assertTrue(ForkAnalysisProgress.tooLong("long", 0.0), "never retried")
        assertFalse(ForkAnalysisProgress.tooLong("short", 480.0))
        val row = ForkAnalysisProgress.rowFor("long", null, false)!!
        assertEquals("This song is over 8 minutes: not analysed, a plain crossfade", row.text)
        assertFalse(row.text.contains("failed"))
    }

    @Test
    fun theRowShowsThisSongBeforeTheNext() {
        ForkAnalysisProgress.stage("next", ForkAnalysisProgress.Stage.MODELS)
        assertEquals("Analysing the next song: finding the beats and the vocals · 35%", ForkAnalysisProgress.rowFor("cur", "next", true)!!.text)
        ForkAnalysisProgress.queued("cur")
        val r = ForkAnalysisProgress.rowFor("cur", "next", true)!!
        assertEquals("Analysing this song: waiting its turn · 0%", r.text)
        assertEquals(0f, r.fill)
        ForkAnalysisProgress.stage("cur", ForkAnalysisProgress.Stage.AUDIO, refining = true)
        assertTrue(ForkAnalysisProgress.rowFor("cur", "next", false)!!.text.startsWith("Refining this song: getting the audio"))
        ForkAnalysisProgress.failed("cur", IllegalStateException("no YouTube copy"))
        ForkAnalysisProgress.ended("cur", false)
        ForkAnalysisProgress.ended("next", true)
        assertEquals("Analysis of this song failed: no YouTube copy", ForkAnalysisProgress.rowFor("cur", "next", true)!!.text)
        ForkAnalysisProgress.ended("cur", true)
        val loop = ForkAnalysisProgress.rowFor("cur", "next", true)!!
        assertEquals("Looking for a loop mix…", loop.text)
        assertTrue(loop.fill!!.isNaN())
        assertNull(ForkAnalysisProgress.rowFor("cur", "next", false))
    }

    @Test
    fun aPassTheListenerCalledOffIsStoppedNeverFailed() {
        // what both analysers now pass for a skipped-past song: its throw counted as retrying, then ended stopped
        ForkAnalysisProgress.queued("gone")
        ForkAnalysisProgress.failed("gone", IllegalStateException("decoder released"), retrying = true)
        ForkAnalysisProgress.ended("gone", null, stopped = true)
        val row = ForkAnalysisProgress.rowFor("gone", null, false)!!
        assertFalse(row.text.contains("failed"), row.text)
        assertTrue(row.text.contains("stopped"), row.text)
    }

    @Test
    fun twoLookupsLandingAtOnceAreBothRemembered() {
        repeat(200) { round ->
            ForkV2Sung.clear()
            val go = java.util.concurrent.CountDownLatch(1)
            val a = kotlin.concurrent.thread { go.await(); ForkV2Sung.remember("out$round", null) }
            val b = kotlin.concurrent.thread { go.await(); ForkV2Sung.remember("in$round", arrayOf(doubleArrayOf(1.0, 2.0))) }
            go.countDown()
            a.join()
            b.join()
            assertTrue(ForkV2Sung.known("out$round") && ForkV2Sung.known("in$round"), "round $round lost a song")
        }
    }
}
