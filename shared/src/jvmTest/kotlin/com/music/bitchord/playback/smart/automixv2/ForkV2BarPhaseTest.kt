package com.music.bitchord.playback.smart.automixv2

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** analysis-cases.md "The bar" [all] (dedb2a2d), the extension's test_automix_plan.mjs "Bar phase" (c8dff6a). */
class ForkV2BarPhaseTest {

    private val ib = 0.6

    private fun side(vararg downs: Double) = ForkV2Analysis.BeatSide().apply { downbeats = downs }

    private fun analysis(vararg downs: Double) = ForkV2Analysis().apply {
        bpm = 100.0
        beatIntervalSec = ib
        firstDownbeatSec = 5.999
        durationSec = 200.0
        headBeats = side(*downs)
    }

    @Test
    fun theBarsStartWhereMostModelDownbeatsFallNotAtALoneFirstOne() {
        // SI NO ES CONTIGO's opening as analysed: downbeats 5.999, a pickup at 7.172, then a bar apart from 9.600
        val a = ForkV2BarPhase.apply(analysis(5.999, 7.172, 9.6, 12.0, 14.4, 16.8, 19.2))
        assertTrue(abs(a.firstDownbeatSec - (5.999 + 2 * ib)) < 1e-9, "first downbeat ${a.firstDownbeatSec}")
        val again = a.firstDownbeatSec
        assertEquals(again, ForkV2BarPhase.apply(a).firstDownbeatSec, "phasing again changes nothing")
    }

    @Test
    fun aTieKeepsTheFirstDownbeatsPlace() {
        // places 0, 2, 0, 2
        assertEquals(5.999, ForkV2BarPhase.apply(analysis(6.0, 7.2, 8.4, 9.6)).firstDownbeatSec)
    }

    @Test
    fun theTailGridVotesWithTheTailModelsDownbeatsOnItsOwn() {
        val a = analysis(6.0, 8.4).apply {
            tailGrid = ForkV2Analysis.LocalGrid().apply {
                bpm = 100.0
                beatIntervalSec = ib
                firstDownbeatSec = 150.0
                gridConfidence = 1.0
            }
            tailBeats = side(150.0, 150.6, 153.0, 155.4)
        }
        ForkV2BarPhase.apply(a)
        assertEquals(5.999, a.firstDownbeatSec)
        assertTrue(abs(a.tailGrid!!.firstDownbeatSec - 150.6) < 1e-9, "tail ${a.tailGrid!!.firstDownbeatSec}")
    }

    @Test
    fun anOffGridDownbeatDoesntVoteAndNoDownbeatsChangeNothing() {
        assertEquals(5.999, ForkV2BarPhase.apply(analysis(5.999, 6.9, 7.5, 8.1)).firstDownbeatSec)
        assertEquals(5.999, ForkV2BarPhase.apply(analysis().apply { headBeats = null }).firstDownbeatSec)
    }

    @Test
    fun aSavedAnalysisIsPhasedAsItIsLoaded() {
        val saved = analysis(5.999, 7.172, 9.6, 12.0, 14.4, 16.8, 19.2)
        val text = ForkV2Json.write(saved.toJson())
        val loaded = ForkV2Analysis.fromJsonText(text)
        assertTrue(abs(loaded.firstDownbeatSec - (5.999 + 2 * ib)) < 1e-9, "loaded ${loaded.firstDownbeatSec}")
    }
}
