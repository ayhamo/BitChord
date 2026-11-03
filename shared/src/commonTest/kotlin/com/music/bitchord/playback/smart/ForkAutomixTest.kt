package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForkAutomixTest {

    // Opens at 100 BPM, ends at 128 BPM: the analysis states the ending's tempo, as TrackAnalyzer does.
    private val head = BeatSide(beats = listOf(0.5, 1.1), downbeats = listOf(0.5, 2.9, 5.3), bpm = 100.0, confidence = 0.4)
    private val tail = BeatSide(beats = listOf(180.0, 180.47), downbeats = listOf(180.0, 181.875), bpm = 128.0, confidence = 0.9)
    private val song = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        bpm = 128.0,
        beatInterval = 60 / 128.0,
        beatConfidence = 0.9,
        downbeats = listOf(0.5, 2.9, 5.3, 180.0, 181.875),
        firstBeat = 0.5,
        headBeats = head,
        tailBeats = tail,
    )

    @Test
    fun incomingTakesTheOpeningsTempo() {
        val incoming = ForkAutomix.incoming(song)
        assertEquals(100.0, incoming.bpm)
        assertEquals(0.6, incoming.beatInterval, 1e-9)
        assertEquals(0.4, incoming.beatConfidence)
    }

    @Test
    fun outgoingKeepsTheEndingsTempo() {
        assertSame(song, ForkAutomix.outgoing(song))
    }

    @Test
    fun withoutTheModelsGridsNothingChanges() {
        val dspOnly = song.copy(headBeats = null, tailBeats = null)
        assertSame(dspOnly, ForkAutomix.incoming(dspOnly))
        assertFalse(ForkAutomix.worthStoring(dspOnly))
        assertTrue(ForkAutomix.worthStoring(song))
    }

    @Test
    fun theFallbackGridCountsBarsFromTheStretchItLoopsIn() {
        // Outgoing: the ending's beat, counted from the ending's downbeat, not from 0.5 s.
        val (outBeat, outAnchor) = assertNotNull(ForkAutomix.grid(song, song.tailBeats, near = 170.0))
        assertEquals(60 / 128.0, outBeat, 1e-9)
        assertEquals(180.0, outAnchor)
        // Incoming: the opening's beat and first bar line.
        val (inBeat, inAnchor) = assertNotNull(ForkAutomix.grid(song, song.headBeats, near = 0.0))
        assertEquals(0.6, inBeat, 1e-9)
        assertEquals(0.5, inAnchor)
    }

    @Test
    fun theLoopMixGainsAreTrimmedWhereTheyOverlapPastFullScale() {
        // The loop mix's own curves: enterEnd 8 s, rampEnd 24 s, releaseAt 32 s.
        val gains = ForkAutomix.trimmedGains(
            outTimes = doubleArrayOf(-0.025, 0.0, 8.0, 24.0, 32.0),
            outGains = floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
            inTimes = doubleArrayOf(0.0, 8.0, 24.0),
            inGains = floatArrayOf(0f, 0.55f, 1f),
        )
        assertEquals(gains.outTimes.toList(), gains.inTimes.toList())
        for (k in gains.outTimes.indices) {
            val sum = gains.outGains[k] + gains.inGains[k]
            // Never past sqrt(1.6), about 1.26, where it used to reach 1.6; untouched at or under 1.
            assertTrue(sum <= 1.27f, "sum $sum at ${gains.outTimes[k]}")
        }
        val atZero = gains.outTimes.toList().indexOf(0.0)
        assertEquals(1f, gains.outGains[atZero], 1e-6f)
        assertEquals(0f, gains.inGains[atZero], 1e-6f)
        val atRelease = gains.outTimes.toList().indexOf(32.0)
        assertEquals(0f, gains.outGains[atRelease], 1e-6f)
        assertEquals(1f, gains.inGains[atRelease], 1e-6f)
    }

    @Test
    fun aWeakGridIsBelowTheLoopFloor() {
        val weak = song.copy(headBeats = head.copy(confidence = 0.1))
        assertTrue(ForkAutomix.confidence(weak, weak.headBeats) < ForkAutomix.MIN_LOOP_CONFIDENCE)
        assertTrue(ForkAutomix.confidence(song, song.tailBeats) >= ForkAutomix.MIN_LOOP_CONFIDENCE)
    }
}
