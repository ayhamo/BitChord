package com.music.bitchord.playback.smart

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Automix overview's picks per pair ([ForkMixPicks]) and how the AutoDJ planner uses them. */
class ForkMixPicksTest {

    @AfterTest
    fun reset() {
        ForkMixPicks.save = null
        ForkMixPicks.reset()
        ForkMixPicks.style = AutomixStyle.BITCHORD
        ForkAutoDj.forget()
    }

    private fun song(bpm: Double, durationSec: Double, id: String, key: String = "", conf: Double = 1.0) = ForkAutoDj.Side(
        id = id,
        durationSec = durationSec,
        grid = ForkAutoDj.Grid(60 / bpm, 0.4),
        confidence = conf,
        energy = (0 until durationSec.toInt()).map { EnergySample(it.toDouble(), 1.0) },
        introEndSec = null,
        contentEndSec = null,
        vocalWindows = emptyList(),
        vocalMask = emptyList(),
        stub = false,
        key = key,
        keyConfidence = if (key.isEmpty()) 0.0 else 0.9,
    )

    private fun plan(pick: ForkMixPicks.Pick?, cur: ForkAutoDj.Side = song(120.0, 200.0, "a"), next: ForkAutoDj.Side = song(120.0, 200.0, "b"), keyAware: Boolean = false) =
        ForkAutoDj.plan(cur, next, 20.0, 24.5, true, 0, keyAware = keyAware, pick = pick)

    @Test
    fun aPickedLengthIsTheBlendsLength() {
        assertEquals(32, plan(ForkMixPicks.Pick(bars = 32)).bars)
        assertEquals(4, plan(ForkMixPicks.Pick(style = ForkMixPicks.Style.BLEND, bars = 4)).bars)
    }

    @Test
    fun aPickedLengthStillGivesWayToAWeakGrid() {
        assertEquals(8, plan(ForkMixPicks.Pick(bars = 32), cur = song(120.0, 200.0, "a", conf = 0.5)).bars)
    }

    @Test
    fun aPickedLengthBeatsTheKeyClashShortBlend() {
        val cur = song(120.0, 200.0, "a", key = "C major")
        val clash = song(120.0, 200.0, "b", key = "F♯ major")
        assertEquals(2, plan(null, cur, clash, keyAware = true).bars)
        assertEquals(16, plan(ForkMixPicks.Pick(bars = 16), cur, clash, keyAware = true).bars)
    }

    @Test
    fun filterAndFadeArePlannedAsPicked() {
        val filter = plan(ForkMixPicks.Pick(style = ForkMixPicks.Style.FILTER, bars = 32))
        assertEquals(ForkAutoDj.Style.FILTER, filter.style)
        assertEquals(ForkAutoDj.FILTER_MAX_BARS, filter.bars)
        assertEquals(ForkMixPicks.PICKED_REASON, filter.reason)
        val fade = plan(ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        assertEquals(ForkAutoDj.Style.CROSSFADE, fade.style)
        assertEquals(ForkAutoDj.FALLBACK_SEC, fade.durationSec, 1e-9)
    }

    @Test
    fun blendFilterFadeOrALengthAloneTurnTheLoopAwayButOnlyUnderTheAutoDjStyles() {
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.BLEND))
        ForkMixPicks.set("a->c", ForkMixPicks.Pick(bars = 8))
        ForkMixPicks.set("a->d", ForkMixPicks.Pick(style = ForkMixPicks.Style.LOOP))
        assertNull(ForkMixPicks.loopRefusal("a", "b"), "BitChord's style ignores picks")
        ForkMixPicks.style = AutomixStyle.ORIGINAL
        assertEquals(ForkMixPicks.PICKED_REASON, ForkMixPicks.loopRefusal("a", "b"))
        assertEquals(ForkMixPicks.PICKED_REASON, ForkMixPicks.loopRefusal("a", "c"))
        assertNull(ForkMixPicks.loopRefusal("a", "d"))
        assertNull(ForkMixPicks.loopRefusal("x", "y"))
    }

    @Test
    fun loopAndFadeDropTheLengthFilterIsCappedAndAutoClearsThePick() {
        ForkMixPicks.set("p", ForkMixPicks.Pick(style = ForkMixPicks.Style.LOOP, bars = 16))
        assertNull(ForkMixPicks.picks.value["p"]!!.bars)
        ForkMixPicks.set("p", ForkMixPicks.Pick(style = ForkMixPicks.Style.FILTER, bars = 32))
        assertEquals(8, ForkMixPicks.picks.value["p"]!!.bars)
        ForkMixPicks.set("p", ForkMixPicks.Pick())
        assertTrue("p" !in ForkMixPicks.picks.value)
    }

    @Test
    fun keepsTheLast300AndSavesAndLoadsThem() {
        var saved = ""
        ForkMixPicks.save = { saved = it }
        for (i in 0 until 305) ForkMixPicks.set("s$i->t", ForkMixPicks.Pick(bars = 4))
        assertEquals(300, ForkMixPicks.picks.value.size)
        assertTrue("s0->t" !in ForkMixPicks.picks.value && "s304->t" in ForkMixPicks.picks.value)
        ForkMixPicks.set("s5->t", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        ForkMixPicks.load(null)
        assertTrue(ForkMixPicks.picks.value.isEmpty())
        ForkMixPicks.load(saved + "\nbad line\nq->r\t\t7")
        assertEquals(300, ForkMixPicks.picks.value.size)
        assertEquals(ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE), ForkMixPicks.picks.value["s5->t"])
        assertEquals("s5->t", ForkMixPicks.picks.value.keys.last(), "a changed pick is the newest")
    }

    @Test
    fun lettingTheLoopBackInShowsWhatTheLoopMixLastDecided() {
        ForkMixPicks.style = AutomixStyle.ORIGINAL
        ForkMixPicks.noteLoop("a->b", null, "60 loops sung")
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(bars = 16))
        ForkMixPicks.noteLoop("a->b", null, ForkMixPicks.PICKED_REASON)
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.LOOP))
        assertEquals(ForkMixPicks.Loop("a->b", null, "60 loops sung"), ForkMixPicks.loop.value)
        ForkMixPicks.noteLoop("c->d", 90.0, null)
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        ForkMixPicks.noteLoop("a->b", null, ForkMixPicks.PICKED_REASON)
        ForkMixPicks.set("a->b", ForkMixPicks.Pick())
        assertEquals("waiting on the loop mix", ForkMixPicks.loop.value!!.why, "another pair's verdict isn't this one's")
    }

    @Test
    fun picksWaitWhileTheOverviewStillDrawsTheFinishedPair() {
        val next = ForkMixPicks.Planned("b->c", ForkMixPicks.Style.BLEND, 8, null, 100.0, 10.0, "Song B", "Song C")
        assertTrue(next.isShownAs("Song B", "Song C"))
        assertTrue(!next.isShownAs("Song A", "Song B"), "the finished A -> B mix still on screen")
        assertTrue(next.copy(outTitle = "", inTitle = "").isShownAs("Song A", "Song B"), "no titles: nothing to tell apart")
    }

    @Test
    fun theHookPlansThePairsPickAndNotesIt() {
        ForkMixPicks.style = AutomixStyle.ADVANCED
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        val analysis = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "a", bpm = 120.0, duration = 200.0)
        val p = ForkAutoDj.planTransition(
            AutomixStyle.ADVANCED, analysis, analysis.copy(trackId = "b"),
            TransitionTrackInfo(id = "a", durationMs = 200_000L, title = "A"), TransitionTrackInfo(id = "b", durationMs = 200_000L, title = "B"),
            20.0, 200.0, false,
        )!!
        assertEquals(TransitionStyle.EQUAL_POWER, p.transitionStyle)
        assertEquals(ForkMixPicks.PICKED_REASON, p.policyReasons.first())
        val noted = ForkMixPicks.planned.value!!
        assertEquals("a->b", noted.pair)
        assertEquals(ForkMixPicks.Style.FADE, noted.style)
        assertEquals("A" to "B", noted.outTitle to noted.inTitle)
    }

    private val analysis = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "a", bpm = 120.0, duration = 200.0)

    private fun hook(at: Double, style: AutomixStyle = AutomixStyle.ADVANCED, seconds: Long = 200L) = ForkAutoDj.planTransition(
        style, analysis, analysis.copy(trackId = "b"),
        TransitionTrackInfo(id = "a", durationMs = seconds * 1000), TransitionTrackInfo(id = "b", durationMs = 200_000L),
        at, seconds.toDouble(), false,
    )

    @Test
    fun aPickBeforeTheMixIsArmedReplansItAndOneAfterWaitsForTheNextTime() {
        // Songs with no beat grid: a Fade pick is "picked in the overview", Auto is the planner's own fade.
        ForkMixPicks.style = AutomixStyle.ADVANCED
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        assertEquals(ForkMixPicks.PICKED_REASON, hook(20.0)!!.policyReasons.first())
        ForkMixPicks.set("a->b", ForkMixPicks.Pick())
        val auto = hook(21.0)!!
        assertTrue(auto.policyReasons.first() != ForkMixPicks.PICKED_REASON, "not armed yet: planned afresh")
        assertTrue(!ForkMixPicks.planned.value!!.armed)

        val armedAt = auto.transitionStart - 1.0
        val held = hook(armedAt)!!
        assertTrue(ForkMixPicks.planned.value!!.armed, "the latch holds the plan")
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        val after = hook(armedAt + 0.25)!!
        assertEquals(held.policyReasons, after.policyReasons, "the armed mix is not re-planned")
        assertEquals(held.transitionStart, after.transitionStart, 1e-9)
        assertTrue(ForkMixPicks.planned.value!!.armed)
        ForkAutoDj.forget()
        assertEquals(ForkMixPicks.PICKED_REASON, hook(20.0)!!.policyReasons.first(), "kept for the next time")
    }

    @Test
    fun picksDoNothingUnderBitChordsStyle() {
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        assertNull(hook(20.0, style = AutomixStyle.BITCHORD))
        assertNull(ForkMixPicks.planned.value)
        assertNull(ForkMixPicks.pickFor("a", "b"))
    }

    @Test
    fun aSongTooShortToPlanLeavesNoStalePair() {
        ForkMixPicks.style = AutomixStyle.ORIGINAL
        hook(20.0)
        assertEquals("a->b", ForkMixPicks.planned.value!!.pair)
        assertNull(hook(10.0, style = AutomixStyle.ORIGINAL, seconds = 30L))
        assertNull(ForkMixPicks.planned.value)
    }

    @Test
    fun savedPicksKeepStyleAndLengthAndLoadAsSetWouldStoreThem() {
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.BLEND, bars = 16))
        val saved = ForkMixPicks.encode(ForkMixPicks.picks.value)
        ForkMixPicks.reset()
        ForkMixPicks.load(saved + "\nc->d\tloop\t16\ne->f\tfilter\t32")
        assertEquals(ForkMixPicks.Pick(ForkMixPicks.Style.BLEND, 16), ForkMixPicks.picks.value["a->b"])
        assertEquals(ForkMixPicks.Pick(ForkMixPicks.Style.LOOP), ForkMixPicks.picks.value["c->d"])
        assertEquals(ForkMixPicks.Pick(ForkMixPicks.Style.FILTER, ForkAutoDj.FILTER_MAX_BARS), ForkMixPicks.picks.value["e->f"])
    }
}
