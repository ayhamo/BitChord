package com.music.bitchord.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkBlurredBackdropTest {

    private fun red(p: Int) = p shr 16 and 0xFF

    @Test
    fun `strip is the lower 40 percent, 48 px wide, at the cover's shape`() {
        // 160 × 160: the strip is 64 tall, so 48 × 19 (64 × 48 / 160).
        val pixels = forkBackdropPixels(160, 160) { _, _ -> 0x123456 }
        assertEquals(48 * 19, pixels.size)
        // Opaque, and a flat colour stays that colour through the blur.
        assertTrue(pixels.all { it == (0xFF shl 24 or 0x123456) })
    }

    @Test
    fun `only the lower part of the cover is sampled`() {
        // White above 60 %, black below: the strip shows none of the white.
        val pixels = forkBackdropPixels(100, 100) { _, y -> if (y < 60) 0xFFFFFF else 0x000000 }
        assertTrue(pixels.all { it and 0xFFFFFF == 0 })
    }

    @Test
    fun `a sharp edge comes out soft, and the edges are clamped not darkened`() {
        // Left half black, right half white.
        val pixels = forkBackdropPixels(96, 96) { x, _ -> if (x < 48) 0x000000 else 0xFFFFFF }
        val row = IntArray(48) { red(pixels[it]) }
        assertEquals(0, row.first(), "the far left stays black (clamped, no wrap-around)")
        assertEquals(255, row.last(), "the far right stays white (clamped, no dark border)")
        val middle = row[23]
        assertTrue(middle in 1..254, "the edge is blurred, was $middle")
        assertTrue(row.toList().zipWithNext().all { (a, b) -> a <= b }, "the ramp never goes back")
    }

    @Test
    fun `a tiny or very wide picture still gives a strip`() {
        val tiny = forkBackdropPixels(1, 1) { _, _ -> 0xFF0000 }
        assertEquals(48 * 48, tiny.size) // one pixel, square: 48 × 48
        assertTrue(tiny.all { red(it) == 255 })
        // 1000 × 10: a 4-pixel strip would scale to 0 rows; at least 4 are kept.
        assertEquals(48 * 4, forkBackdropPixels(1000, 10) { _, _ -> 0 }.size)
    }
}
