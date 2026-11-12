package com.music.bitchord.playback

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ForkMixShelfTest {

    private val rate = 48_000

    private fun tone(hz: Double, frames: Int) = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * hz * (i / 2) / rate)).toFloat() }

    private fun rmsDb(x: FloatArray, from: Int): Double {
        var sum = 0.0
        for (i in from until x.size) sum += x[i].toDouble() * x[i]
        return 20 * log10(sqrt(sum / (x.size - from)))
    }

    @Test
    fun flatLeavesEverySampleUntouched() {
        val shelf = ForkMixShelf()
        val x = tone(100.0, 4_800)
        val copy = x.copyOf()
        shelf.process(x, x.size, 2, rate)
        assertContentEquals(copy, x)
        assertFalse(shelf.isShaping)
    }

    @Test
    fun theLowShelfTurnsTheBassDownAndLeavesTheHighs() {
        val low = tone(40.0, 48_000)
        val high = tone(10_000.0, 48_000)
        val dryLow = rmsDb(low.copyOf(), 24_000)
        val dryHigh = rmsDb(high.copyOf(), 24_000)
        ForkMixShelf().apply { snap(-12f, 0f) }.process(low, low.size, 2, rate)
        ForkMixShelf().apply { snap(-12f, 0f) }.process(high, high.size, 2, rate)
        assertEquals(-12.0, rmsDb(low, 24_000) - dryLow, 0.6)
        assertEquals(0.0, rmsDb(high, 24_000) - dryHigh, 0.2)
    }

    @Test
    fun theHighShelfTurnsTheHighsDown() {
        val high = tone(15_000.0, 48_000)
        val dry = rmsDb(high.copyOf(), 24_000)
        ForkMixShelf().apply { snap(0f, -8f) }.process(high, high.size, 2, rate)
        assertEquals(-8.0, rmsDb(high, 24_000) - dry, 0.6)
    }

    /** A torn-down sink comes back flat: the next song isn't shelved by the last mix. */
    @Test
    fun resetGoesFlatWithNoHistory() {
        val shelf = ForkMixShelf().apply { snap(-12f, -8f) }
        shelf.process(tone(40.0, 4_800), 9_600, 2, rate)
        shelf.reset()
        val x = tone(40.0, 4_800)
        val copy = x.copyOf()
        shelf.process(x, x.size, 2, rate)
        assertContentEquals(copy, x)
        assertFalse(shelf.isShaping)
    }

    /** With float output off the sink hands the filter PCM16: the swap must happen there too. */
    @Test
    fun the16BitPathShelvesInPlaceAndLeavesFlatBlocksAlone() {
        fun pcm(x: FloatArray) = java.nio.ByteBuffer.allocate(x.size * 2).order(java.nio.ByteOrder.nativeOrder()).apply {
            x.forEach { putShort((it * 32768f).toInt().toShort()) }
            flip()
        }
        fun floats(b: java.nio.ByteBuffer) = FloatArray(b.remaining() / 2) { b.getShort(b.position() + it * 2) / 32768f }
        val flat = pcm(tone(40.0, 4_800))
        val before = floats(flat)
        ForkMixShelf().process16(flat, 2, rate)
        assertContentEquals(before, floats(flat))
        val low = pcm(tone(40.0, 48_000))
        val dry = rmsDb(floats(low), 24_000)
        ForkMixShelf().apply { snap(-12f, 0f) }.process16(low, 2, rate)
        assertEquals(0, low.position())
        assertEquals(-12.0, rmsDb(floats(low), 24_000) - dry, 0.6)
    }

    /** The cut's level change on a [hz] tone, settled, with the low-pass at [lowPassHz] and the high-pass at [highPassHz]. */
    private fun cutDb(hz: Double, lowPassHz: Float = ForkMixShelf.OPEN_HZ, highPassHz: Float = ForkMixShelf.OFF_HZ): Double {
        val x = tone(hz, 48_000)
        val dry = rmsDb(x.copyOf(), 24_000)
        ForkMixShelf().apply { snap(0f, 0f, lowPassHz, highPassHz) }.process(x, x.size, 2, rate)
        return rmsDb(x, 24_000) - dry
    }

    /**
     * The extension's lowpass is one Butterworth section: 12 dB an octave, so an octave above the
     * cutoff is down 12.3 dB and two octaves 24.1 (the old 24 dB filters gave 24.1 and 48.2).
     */
    @Test
    fun theLowPassFallsTwelveDbAnOctave() {
        assertEquals(-3.0, cutDb(1_000.0, lowPassHz = 1_000f), 0.3)
        assertEquals(-12.3, cutDb(2_000.0, lowPassHz = 1_000f), 0.5)
        assertEquals(-24.1, cutDb(4_000.0, lowPassHz = 1_000f), 0.6)
        assertEquals(0.0, cutDb(100.0, lowPassHz = 1_000f), 0.1)
    }

    @Test
    fun theHighPassFallsTwelveDbAnOctave() {
        assertEquals(-3.0, cutDb(400.0, highPassHz = 400f), 0.3)
        assertEquals(-12.3, cutDb(200.0, highPassHz = 400f), 0.5)
        assertEquals(-24.1, cutDb(100.0, highPassHz = 400f), 0.6)
        assertEquals(0.0, cutDb(5_000.0, highPassHz = 400f), 0.1)
    }

    /** A cut that opens again glides there and switches off: later blocks pass untouched, nothing held. */
    @Test
    fun anOpenedCutEndsUntouched() {
        val shelf = ForkMixShelf().apply { snap(0f, 0f, 500f, 300f) }
        shelf.process(tone(1_000.0, 4_800), 9_600, 2, rate)
        shelf.open()
        shelf.process(tone(1_000.0, 48_000), 96_000, 2, rate) // a second at 64-frame steps of 5%: long settled
        assertFalse(shelf.isShaping)
        val x = tone(1_000.0, 4_800)
        val copy = x.copyOf()
        shelf.process(x, x.size, 2, rate)
        assertContentEquals(copy, x)
    }

    /**
     * A playing deck that starts a mix whose curve opens on a cut (Advanced AutoDJ's filter mix: the outgoing
     * low-pass at 7 kHz) glides in from open, even after the shelf cut deep for the last mix: no step, no dulling jump.
     */
    @Test
    fun startOpenGlidesInFromOpenWhateverCameBefore() {
        val shelf = ForkMixShelf().apply { snap(-12f, -8f, 300f, 20f) }
        shelf.process(tone(1_000.0, 4_800), 9_600, 2, rate) // the last mix ended deep in its cut
        shelf.startOpen(0f, 0f, 300f, 20f)
        val x = tone(5_000.0, 64)
        val copy = x.copyOf()
        shelf.process(x, x.size, 2, rate)
        // From open, one block's 5% glide toward 300 Hz is still ~16 kHz: a 5 kHz tone passes nearly whole.
        assertEquals(0.0, rmsDb(x, 0) - rmsDb(copy, 0), 1.0)
    }

    /** A cutoff moves by a glide, never a jump: one block after a far aim the cut is still near where it was. */
    @Test
    fun aMovedCutoffGlides() {
        val shelf = ForkMixShelf().apply { snap(-1f, 0f, 20_000f, 20f) } // a shelf on, so the block is processed and the snap lands
        shelf.process(tone(2_000.0, 64), 128, 2, rate)
        shelf.set(-1f, 0f, 200f, 20f)
        val x = tone(2_000.0, 64)
        val copy = x.copyOf()
        shelf.process(x, x.size, 2, rate)
        // One block at 5% of the log distance from 20 kHz toward 200 Hz is still ~16 kHz: a 2 kHz tone barely moves.
        val change = rmsDb(x, 0) - rmsDb(copy, 0)
        assertEquals(0.0, change, 0.5)
    }
}
