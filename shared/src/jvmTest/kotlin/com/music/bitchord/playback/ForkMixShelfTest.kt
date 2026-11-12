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
}
