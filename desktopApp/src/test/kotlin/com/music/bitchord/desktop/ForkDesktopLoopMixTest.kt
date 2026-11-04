package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkDesktopLoopMixTest {
    private val rate = 8_000

    private fun noise(i: Long): Float {
        var x = i * 6364136223846793005L + 1442695040888963407L
        x = x xor (x ushr 33)
        x *= -0x61c8864680b583ebL
        x = x xor (x ushr 29)
        return ((x and 0xFFFF).toFloat() / 0xFFFF - 0.5f)
    }

    /** The song's audio at track frame [i] (at [rate]): one signal per song, both channels alike. */
    private fun outSong(i: Long) = noise(i)
    private fun inSong(i: Long) = noise(i + 50_000_000)

    private fun pick(start: Double) = LoopMix.Pick(
        loopStart = start,
        loopEnd = start + 8.0,
        bars = 4,
        beatSec = 0.5,
        bpm = 120.0,
        vocal = 0.0,
        checkedBy = LoopMix.Check.MASK,
    )

    /** 120 BPM into 120 BPM: four bars in, two of ramp (no tempo to move), two out. */
    private val plan = LoopMix.Plan(
        out = pick(100.0),
        inn = pick(10.0),
        fold = 1.0,
        enterBars = 4,
        rampBars = 2,
        exitBars = 2,
        totalBars = 8,
    )

    private fun region(from: Double, to: Double, late: Int = 0, song: (Long) -> Float): ForkLoopRegion {
        val first = (from * rate).toLong()
        val n = ((to - from) * rate).toInt()
        val samples = FloatArray(n) { song(first + it - late) }
        return ForkLoopRegion(samples, samples.copyOf(), from)
    }

    private fun session(outLate: Int = 0): ForkLoopMixSession {
        val (outFrom, outTo) = ForkLoopMixSession.outRange(plan)
        val (inFrom, inTo) = ForkLoopMixSession.inRange(plan)
        return assertNotNull(
            ForkLoopMixSession.build(
                plan, rate,
                region(outFrom, outTo, outLate, ::outSong),
                region(inFrom, inTo, song = ::inSong),
                "a", "b", "urlA", "urlB",
            ),
        )
    }

    @Test
    fun `the clock puts the handover where the incoming song carries on`() {
        val run = session()
        // 8 bars of 2 s, then a 2-bar settle: the incoming deck is 4 s past its loop end at 20 s.
        assertEquals(20 * rate.toLong(), run.handoverFrame)
        assertEquals(run.handoverFrame + 200, run.handoverEndFrame)
        assertEquals(21_500_000L, run.inStartUs)
        // At rate 1 the deck sits exactly on its knots: 22 s into the incoming song.
        assertEquals(22_000_000.0, run.inTrackUsAt(run.handoverFrame).toDouble(), 1e6 / rate)
    }

    @Test
    fun `no block straddles the handover's start or end`() {
        val run = session()
        assertEquals(1_024, run.blockFrames(0, 1_024))
        assertEquals(24, run.blockFrames(run.handoverFrame - 24, 1_024))
        assertEquals(200, run.blockFrames(run.handoverFrame, 1_024))
        assertEquals(1_024, run.blockFrames(run.handoverEndFrame, 1_024))
        assertEquals(0f, run.inGain(run.handoverFrame))
        assertEquals(0.5f, run.inGain(run.handoverFrame + 100))
        assertEquals(1f, run.inGain(run.handoverEndFrame))
    }

    /** Plays the outgoing song through the splice in decoder-sized blocks; the output from 0.6 s before the loop. */
    private fun spliceThrough(run: ForkLoopMixSession, untilMf: Long): Pair<Long, FloatArray> {
        val startTrack = (plan.out.loopStart * rate).toLong() - (0.6 * rate).toLong()
        val firstMf = startTrack - (plan.out.loopStart * rate).toLong()
        val frames = (untilMf - firstMf).toInt()
        val out = FloatArray(frames)
        var at = 0
        val writeFrameBase = 1_000_000L
        while (at < frames) {
            val n = minOf(320, frames - at)
            val block = FloatArray(n * 2) { outSong(startTrack + at + it / 2) }
            val writeUs = ((startTrack + at) * 1_000_000L) / rate
            if (run.spliceNear(writeUs) || run.engaged) run.splice(block, n * 2, writeUs, writeFrameBase + at)
            for (i in 0 until n) out[at + i] = block[2 * i]
            at += n
        }
        return firstMf to out
    }

    @Test
    fun `the splice into the outgoing deck is the song's own audio across it`() {
        val run = session()
        val (firstMf, out) = spliceThrough(run, untilMf = 400)
        assertTrue(run.spliced)
        for (i in out.indices) {
            val mf = firstMf + i
            if (mf >= 0) break
            val expected = outSong((plan.out.loopStart * rate).toLong() + mf)
            assertEquals(expected, out[i], 0.02f, "mix frame $mf")
        }
    }

    @Test
    fun `a region decoded late is lined up on the song before the splice`() {
        val run = session(outLate = 5)
        val (firstMf, out) = spliceThrough(run, untilMf = 400)
        assertEquals(5, run.spliceLag)
        assertTrue(run.spliceScore > 0.9)
        for (i in out.indices) {
            val mf = firstMf + i
            if (mf >= 0) break
            if (mf < -run.fadeFrames) continue
            val expected = outSong((plan.out.loopStart * rate).toLong() + mf)
            assertEquals(expected, out[i], 0.02f, "mix frame $mf")
        }
    }

    @Test
    fun `positions loop on the outgoing song, then follow the incoming one`() {
        val run = session()
        assertNull(run.positionMs(-10))
        // 9 s in is beat 18: two beats into the second pass of the 16-beat outgoing loop.
        assertEquals(101_000L, run.positionMs(9L * rate))
        run.handedOff = true
        // The incoming loop at 9 s: the same two beats into its own loop.
        assertEquals(11_000L, run.positionMs(9L * rate))
        // Past the release at 16 s, on from its loop end at rate 1.
        assertEquals(19_000L, run.positionMs(17L * rate))
    }

    @Test
    fun `a stop before the splice settles nowhere, after it on the mix's own position`() {
        val run = session()
        assertNull(run.settleMs(0))
        spliceThrough(run, untilMf = 400)
        assertEquals(101_000L, run.settleMs(9L * rate))
        assertEquals(100_000L, run.settleMs(-50))
        run.handedOff = true
        assertEquals(19_000L, run.settleMs(17L * rate))
    }

    @Test
    fun `the incoming decoder is lined up by dropping frames to its deck's sample`() {
        val run = session()
        val drop = run.dropFramesFor(landedUs = 21_500_000L)
        assertTrue(run.inAligned)
        assertTrue(abs(drop - rate / 2) <= 1, "dropped $drop")
        assertEquals(run.handoverTrackUs + 1_000_000L, run.inTrackUsAfter(run.handoverFrame + rate))
    }

    @Test
    fun `regions that don't hold what their decks play build nothing`() {
        val (outFrom, _) = ForkLoopMixSession.outRange(plan)
        val (inFrom, inTo) = ForkLoopMixSession.inRange(plan)
        val short = region(outFrom, plan.out.loopEnd - 1, song = ::outSong)
        assertNull(ForkLoopMixSession.build(plan, rate, short, region(inFrom, inTo, song = ::inSong), "a", "b", "", ""))
        val late = region(plan.out.loopStart + 0.1, plan.out.loopEnd + 0.5, song = ::outSong)
        assertNull(ForkLoopMixSession.build(plan, rate, late, region(inFrom, inTo, song = ::inSong), "a", "b", "", ""))
    }

    @Test
    fun `the best lag is found either way`() {
        val n = 4_000
        val lagMax = 100
        val region = FloatArray(n + 2 * lagMax) { noise(it.toLong()) }
        for (lag in listOf(-37, 0, 12)) {
            val song = FloatArray(n) { region[lagMax + lag + it] }
            val (found, score) = ForkLoopMixSession.bestLag(song, n, region, lagMax)
            assertEquals(lag, found)
            assertTrue(score > 0.99)
        }
    }

    private fun pair(
        automix: Boolean = true,
        speed: Float = 1f,
        skipSilence: Boolean = false,
        channels: Int = 2,
        deckRate: Double = 1.0,
        swapping: Boolean = false,
        state: TrackAnalysisState = TrackAnalysisState.ANALYSED,
        phraseBars: Int = 0,
    ) = ForkLoopPair(
        outSong = Song("a", "A", "Artist", null),
        inSong = Song("b", "B", "Artist", null),
        outStream = DesktopStream("C:/music/a.flac"),
        inStream = DesktopStream("C:/music/b.flac"),
        outAnalysis = TrackAnalysis(trackId = "a"),
        inAnalysis = TrackAnalysis(trackId = "b"),
        outState = state,
        inState = TrackAnalysisState.ANALYSED,
        outDurationSec = 200.0,
        inDurationSec = 200.0,
        positionSec = 120.0,
        sampleRate = 48_000,
        channels = channels,
        automix = automix,
        inParty = false,
        speed = speed,
        skipSilence = skipSilence,
        deckRate = deckRate,
        swapping = swapping,
        phraseBars = phraseBars,
    )

    @Test
    fun `the loop mix is refused exactly where it can't run`() {
        assertNull(ForkLoopMixRules.refusal(pair()))
        assertNull(ForkLoopMixRules.refusal(pair(state = TrackAnalysisState.REFINING)))
        assertEquals("Automix is off", ForkLoopMixRules.refusal(pair(automix = false)))
        assertEquals("playback speed is not 1x", ForkLoopMixRules.refusal(pair(speed = 1.25f)))
        assertEquals("skip silence is on", ForkLoopMixRules.refusal(pair(skipSilence = true)))
        assertEquals("this output can't take it", ForkLoopMixRules.refusal(pair(channels = 6)))
        assertEquals("the song is still easing out of its own mix", ForkLoopMixRules.refusal(pair(deckRate = 1.02)))
        assertEquals("a better copy is crossing in", ForkLoopMixRules.refusal(pair(swapping = true)))
        assertEquals("waiting on analysis", ForkLoopMixRules.refusal(pair(state = TrackAnalysisState.ANALYSING)))
        assertEquals("a fixed 16-bar overlap is chosen", ForkLoopMixRules.refusal(pair(phraseBars = 16)))
    }
}
