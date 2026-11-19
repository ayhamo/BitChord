package com.music.bitchord.playback.smart

import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Automix overview's picks per pair ([ForkMixPicks]), how Automix v2's hook in upstream's
 * planner ([ForkV2Automix.planTransition]) uses them, and the saved style's migration
 * ([AutomixStyle.fromKey], [AutomixStyle.migratedKey]).
 */
class ForkMixPicksTest {

    @AfterTest
    fun reset() {
        ForkMixPicks.save = null
        ForkMixPicks.reset()
        ForkMixPicks.style = AutomixStyle.BITCHORD
        ForkV2Automix.forget()
    }

    @Test
    fun theRemovedStylesReadAsAutomixV2AndAreStoredAsIt() {
        assertEquals(AutomixStyle.V2, AutomixStyle.fromKey("original_autodj"))
        assertEquals(AutomixStyle.V2, AutomixStyle.fromKey("advanced_autodj"))
        assertEquals(AutomixStyle.V2, AutomixStyle.fromKey("automix_v2"))
        assertEquals(AutomixStyle.BITCHORD, AutomixStyle.fromKey("bitchord"))
        assertEquals(AutomixStyle.BITCHORD, AutomixStyle.fromKey(null))
        assertEquals(AutomixStyle.BITCHORD, AutomixStyle.fromKey("something else"))
        assertEquals("automix_v2", AutomixStyle.migratedKey("original_autodj"))
        assertEquals("automix_v2", AutomixStyle.migratedKey("advanced_autodj"))
        assertEquals("automix_v2", AutomixStyle.migratedKey("automix_v2"))
        assertEquals("bitchord", AutomixStyle.migratedKey("bitchord"))
        assertNull(AutomixStyle.migratedKey("something else"))
        assertNull(AutomixStyle.migratedKey(null))
        assertEquals(listOf("bitchord", "automix_v2"), AutomixStyle.entries.map { it.key }, "exactly two styles")
    }

    @Test
    fun blendFilterFadeOrALengthAloneTurnTheLoopAwayButOnlyUnderAutomixV2() {
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.BLEND))
        ForkMixPicks.set("a->c", ForkMixPicks.Pick(bars = 8))
        ForkMixPicks.set("a->d", ForkMixPicks.Pick(style = ForkMixPicks.Style.LOOP))
        assertNull(ForkMixPicks.loopRefusal("a", "b"), "BitChord's style ignores picks")
        ForkMixPicks.style = AutomixStyle.V2
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
        assertEquals(ForkMixPicks.FILTER_MAX_BARS, ForkMixPicks.picks.value["p"]!!.bars)
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
        ForkMixPicks.style = AutomixStyle.V2
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
    fun savedPicksKeepStyleAndLengthAndLoadAsSetWouldStoreThem() {
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.BLEND, bars = 16))
        val saved = ForkMixPicks.encode(ForkMixPicks.picks.value)
        ForkMixPicks.reset()
        ForkMixPicks.load(saved + "\nc->d\tloop\t16\ne->f\tfilter\t32")
        assertEquals(ForkMixPicks.Pick(ForkMixPicks.Style.BLEND, 16), ForkMixPicks.picks.value["a->b"])
        assertEquals(ForkMixPicks.Pick(ForkMixPicks.Style.LOOP), ForkMixPicks.picks.value["c->d"])
        assertEquals(ForkMixPicks.Pick(ForkMixPicks.Style.FILTER, ForkMixPicks.FILTER_MAX_BARS), ForkMixPicks.picks.value["e->f"])
    }

    // ---- the hook in upstream's planTransition ----

    private val analysis = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "a", bpm = 120.0, duration = 200.0)

    private fun hook(at: Double, style: AutomixStyle = AutomixStyle.V2, seconds: Long = 200L) = ForkV2Automix.planTransition(
        style, analysis, analysis.copy(trackId = "b"),
        TransitionTrackInfo(id = "a", durationMs = seconds * 1000, title = "A"), TransitionTrackInfo(id = "b", durationMs = 200_000L, title = "B"),
        at, seconds.toDouble(), false,
    )

    /** Upstream's own entry point, the fork's first line included. */
    private fun upstream(style: AutomixStyle) = planTransition(
        analysis = analysis,
        nextAnalysis = analysis.copy(trackId = "b"),
        currentTrack = TransitionTrackInfo(id = "a", durationMs = 200_000L, title = "A"),
        nextTrack = TransitionTrackInfo(id = "b", durationMs = 200_000L, title = "B"),
        currentTime = 20.0,
        duration = 200.0,
        fadeSeconds = 8.0,
        mode = CrossfadeMode.SMART,
        albumSequential = false,
        forkStyle = style,
    )

    @Test
    fun upstreamsPlannerTakesTheHookUnderAutomixV2AndPlansItselfUnderBitChord() {
        ForkMixPicks.style = AutomixStyle.V2
        val v2 = upstream(AutomixStyle.V2)
        assertNotNull(v2.forkV2, "Automix v2 plans the pair")
        assertTrue(v2.reason.contains("automix-v2"), v2.reason)
        ForkV2Automix.forget()
        ForkMixPicks.style = AutomixStyle.BITCHORD
        val own = upstream(AutomixStyle.BITCHORD)
        assertNull(own.forkV2, "BitChord's planner, untouched")
        assertTrue(!own.reason.contains("automix-v2"), own.reason)
    }

    @Test
    fun theHookPlansThePairsPickAndNotesIt() {
        ForkMixPicks.style = AutomixStyle.V2
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        val p = hook(20.0)!!
        assertEquals(TransitionStyle.EQUAL_POWER, p.transitionStyle)
        assertEquals(ForkMixPicks.PICKED_REASON, p.policyReasons.first())
        val noted = ForkMixPicks.planned.value!!
        assertEquals("a->b", noted.pair)
        assertEquals(ForkMixPicks.Style.FADE, noted.style)
        assertEquals("A" to "B", noted.outTitle to noted.inTitle)
    }

    @Test
    fun aPickBeforeTheMixIsArmedReplansItAndOneAfterWaitsForTheNextTime() {
        ForkMixPicks.style = AutomixStyle.V2
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        val picked = hook(20.0)!!
        assertEquals(ForkMixPicks.PICKED_REASON, picked.policyReasons.first())
        ForkMixPicks.set("a->b", ForkMixPicks.Pick())
        val auto = hook(20.25)!!
        assertNotSame(picked.forkV2, auto.forkV2, "not armed yet: planned afresh")
        assertTrue(auto.policyReasons.firstOrNull() != ForkMixPicks.PICKED_REASON)
        assertTrue(!ForkMixPicks.planned.value!!.armed)

        val armedAt = auto.transitionStart - 1.0
        val held = hook(armedAt)!!
        assertTrue(ForkMixPicks.planned.value!!.armed, "the latch holds the plan")
        ForkMixPicks.set("a->b", ForkMixPicks.Pick(style = ForkMixPicks.Style.FADE))
        val after = hook(armedAt + 1.25)!!
        assertSame(held.forkV2, after.forkV2, "the armed mix is not re-planned")
        assertEquals(held.transitionStart, after.transitionStart, 1e-9)
        assertTrue(ForkMixPicks.planned.value!!.armed)
        ForkV2Automix.forget()
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
        ForkMixPicks.style = AutomixStyle.V2
        hook(20.0)
        assertEquals("a->b", ForkMixPicks.planned.value!!.pair)
        assertNull(hook(10.0, seconds = 30L))
        assertNull(ForkMixPicks.planned.value)
    }
}
