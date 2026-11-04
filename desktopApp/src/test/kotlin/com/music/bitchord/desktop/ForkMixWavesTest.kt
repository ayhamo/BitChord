package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class ForkMixWavesTest {
    private val rate = 44_100.0

    private fun tone(hz: Double, seconds: Double, level: Float = 0.5f) =
        FloatArray((seconds * rate).toInt()) { (sin(2 * PI * hz * it / rate) * level).toFloat() }

    @Test
    fun `a bass tone lands in the lows and a hi-hat tone in the highs`() {
        val bass = assertNotNull(ForkMixWaveMath.measure(tone(50.0, 2.0), rate))
        assertEquals(80, bass.bins)
        // Past the filters' settling, the bass is nearly all in the low band.
        assertTrue(bass.low[40] > 0.4f, "low ${bass.low[40]}")
        assertTrue(bass.high[40] < bass.low[40] * 0.1f, "high ${bass.high[40]}")
        val hat = assertNotNull(ForkMixWaveMath.measure(tone(9_000.0, 2.0), rate))
        assertTrue(hat.high[40] > 0.35f, "high ${hat.high[40]}")
        assertTrue(hat.low[40] < 0.01f, "low ${hat.low[40]}")
        assertNull(ForkMixWaveMath.measure(FloatArray(0), rate))
        assertNull(ForkMixWaveMath.measure(tone(50.0, 1.0), 0.0))
    }

    @Test
    fun `a slice sits on whole columns and is normalised on its own`() {
        val loud = tone(50.0, 4.0, level = 0.8f)
        val quiet = tone(50.0, 4.0, level = 0.1f)
        val peaks = assertNotNull(ForkMixWaveMath.measure(loud + quiet, rate))
        val slice = assertNotNull(ForkMixWaveMath.slice(peaks, 5.01, 7.0))
        assertEquals(5.0, slice.start, 1e-9)
        assertEquals(7.0, slice.end, 1e-9)
        assertEquals(80, slice.low.size)
        // Only the quiet half is in it, so it is scaled up to the ceiling as the phone's region would be.
        assertTrue(slice.low.drop(4).all { abs(it - ForkMixWaveMath.LOW_CEILING) < 0.08f }, slice.low.joinToString())
        // Clamped to the song; under two columns is nothing.
        val early = assertNotNull(ForkMixWaveMath.slice(peaks, -3.0, 1.0))
        assertEquals(0.0, early.start)
        assertEquals(320, assertNotNull(ForkMixWaveMath.slice(peaks, 0.0, 99.0)).low.size)
        assertNull(ForkMixWaveMath.slice(peaks, 9.0, 12.0))
    }

    @Test
    fun `normalising follows the phone, a transient does not set the scale`() {
        val band = FloatArray(100) { 0.2f }.also { it[50] = 5f }
        ForkMixWaveMath.normalize(band, 0.5f)
        assertEquals(0.5f, band[0], 1e-6f)
        assertEquals(0.54f, band[50], 1e-6f)
        val silent = FloatArray(10)
        ForkMixWaveMath.normalize(silent, 1f)
        assertTrue(silent.all { it == 0f })
    }

    @Test
    fun `packed peaks come back within a percent of the band's top`() {
        val peaks = assertNotNull(ForkMixWaveMath.measure(tone(50.0, 1.0) + tone(5_000.0, 1.0, 0.05f), rate))
        val back = assertNotNull(ForkMixWaveMath.decode(ForkMixWaveMath.encode(peaks)))
        assertEquals(peaks.bins, back.bins)
        assertEquals(peaks.binsPerSecond, back.binsPerSecond)
        for ((a, b) in listOf(peaks.low to back.low, peaks.mid to back.mid, peaks.high to back.high)) {
            val top = a.maxOrNull() ?: 0f
            for (i in a.indices) assertTrue(abs(a[i] - b[i]) <= top * 0.01f + 1e-7f, "$i: ${a[i]} vs ${b[i]}")
        }
        assertNull(ForkMixWaveMath.decode(ByteArray(3)))
        assertNull(ForkMixWaveMath.decode(ByteArray(64)))
    }

    @Test
    fun `the store keeps a successful pass's peaks and reads them back from disk`() {
        val folder = Files.createTempDirectory("mixwaves").toFile()
        try {
            val store = ForkMixWaveStore { folder }
            store.keep("nothing-measured")
            assertNull(store.peaksFor("nothing-measured"))
            store.measured(tone(50.0, 1.0), rate)
            store.keep("song")
            // Taken by the keep: a second song does not get the first one's.
            store.keep("other")
            assertNull(store.peaksFor("other"))
            val fresh = ForkMixWaveStore { folder }
            assertEquals(40, assertNotNull(fresh.peaksFor("song")).bins)
            assertNull(fresh.peaksFor(""))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `the view gets each deck's slice, padded as the phone pads, and a missing song keeps empty lanes`() {
        val view = view()
        val peaks = assertNotNull(ForkMixWaveMath.measure(tone(50.0, 60.0), rate))
        val filled = ForkMixWaveMath.withWaves(view, peaks, null)
        // The outgoing deck shows 40 - 1.92 .. 48 + 1.92 s; with 3 s either side.
        assertEquals(35.075, filled.outgoing.waveStart, 0.03)
        assertEquals(52.925, filled.outgoing.waveEnd, 0.03)
        assertEquals(filled.outgoing.low.size, ((filled.outgoing.waveEnd - filled.outgoing.waveStart) * 40).roundToInt())
        assertEquals(0, filled.incoming.low.size)
        assertEquals(view.incoming.waveStart, filled.incoming.waveStart)
        assertEquals(view.outStart, filled.outStart)
        assertSame(view.fadeOut, filled.fadeOut)
    }

    @Test
    fun `the reporter hands the ids over, and a late fill does not bring back a cleared view`() {
        val flow = MutableStateFlow<MixTransitionView?>(null)
        val calls = ArrayList<Triple<MixTransitionView, String, String>>()
        val reporter = ForkAutomixReporter(MutableStateFlow(null), flow, MutableStateFlow(0f)) { _, shown, outId, inId ->
            calls += Triple(shown, outId, inId)
        }
        val plan = TransitionPlan(transitionStyle = TransitionStyle.DJ_BLEND, fadeSeconds = 8.0)
        reporter.showBlend(plan, "a", "b") { view() }
        val shown = assertNotNull(flow.value)
        assertEquals(listOf(Triple(shown, "a", "b")), calls)
        reporter.showBlend(plan, "a", "b") { view() }
        assertEquals(1, calls.size)
        // Without ids (the old call) nothing is asked for.
        reporter.showBlend(plan.copy()) { view() }
        assertEquals(1, calls.size)
        // The fill swaps only the view it was given.
        reporter.clearBlend()
        val peaks = ForkMixWaveMath.measure(tone(50.0, 60.0), rate)
        flow.compareAndSet(shown, ForkMixWaveMath.withWaves(shown, peaks, peaks))
        assertNull(flow.value)
    }

    private fun view(): MixTransitionView {
        val ready = TrackAnalysis(status = TrackAnalysis.STATUS_READY, bpm = 120.0)
        val plan = TransitionPlan(transitionStyle = TransitionStyle.DJ_BLEND, fadeSeconds = 8.0, beatSeconds = 0.5)
        return ForkMixView.build(plan, song("a"), song("b"), ready, ready, 40.0, 10.0, 1.0, 60_000, 60_000)
    }

    private fun song(id: String) = Song(videoId = id, title = "song $id", artist = "artist", thumbnailUrl = null)
}
