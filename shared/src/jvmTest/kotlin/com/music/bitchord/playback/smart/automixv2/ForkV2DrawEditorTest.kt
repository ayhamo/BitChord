package com.music.bitchord.playback.smart.automixv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The drawing editor's rules (editor.md §10 rule 6; automix-overview.js), without the spec mounted. */
class ForkV2DrawEditorTest {
    private val E = ForkV2DrawEditor

    private fun pts(vararg p: Pair<Number, Number>): Array<DoubleArray> = Array(p.size) { doubleArrayOf(p[it].first.toDouble(), p[it].second.toDouble()) }
    private fun str(a: Array<DoubleArray>): String = a.joinToString { "[${it[0]}, ${it[1]}]" }

    @Test
    fun heightsAndValuesAreInverse() {
        for (key in ForkV2Drawing.CURVES.keys) for (n in listOf(0.0, 0.25, 0.5, 1.0)) assertEquals(n, E.norm(key, E.value(key, n)), 1e-9, key)
        assertEquals(1.0, E.norm("bassDb", 0.0), 1e-9)
        assertEquals(0.0, E.norm("bassDb", -18.0), 1e-9)
        assertEquals(1.0, E.norm("lowpassHz", 20000.0), 1e-9)
        assertEquals(1.0, E.norm("highpassHz", 20.0), 1e-9)
    }

    @Test
    fun pointsSnapToAQuarterBeatOrTenMs() {
        assertEquals(2.25, E.pointAt("gain", 2.2, 0.5, "beat", false)[0], 0.0)
        assertEquals(2.2, E.pointAt("gain", 2.2, 0.5, "beat", true)[0], 0.0)
        assertEquals(1.23, E.pointAt("gain", 1.2349, 0.5, "sec", false)[0], 0.0)
        assertEquals(0.0, E.pointAt("gain", -1.0, 2.0, "beat", false)[0], 0.0)
        assertEquals(1.0, E.pointAt("gain", 1.0, 2.0, "beat", false)[1], 0.0)
        assertEquals(20000.0, E.pointAt("lowpassHz", 1.0, 1.0, "beat", false)[1], 0.0)
    }

    @Test
    fun aStepButNeverAThirdPointAtOneX() {
        val p = pts(0 to 1, 4 to 1, 8 to 0)
        val (two, i) = E.add(p, doubleArrayOf(4.0, 0.0))!!
        assertEquals(2, i)
        assertEquals("[0.0, 1.0], [4.0, 1.0], [4.0, 0.0], [8.0, 0.0]", str(two))
        assertNull(E.add(two, doubleArrayOf(4.0, 0.5)))
        // dragged onto its neighbour's x it makes a step; past it, it is held
        val moved = E.move(p, 1, doubleArrayOf(9.0, 0.3))
        assertEquals("[0.0, 1.0], [8.0, 0.3], [8.0, 0.0]", str(moved))
        // a step's neighbour can't come onto it: held 0.001 short
        val held = E.move(two, 3, doubleArrayOf(2.0, 0.0))
        assertEquals(4.001, held[3][0], 1e-9)
    }

    @Test
    fun aVolumeDeckKeepsItsLastPoint() {
        assertNull(E.delete("gain", pts(0 to 1), 0))
        assertEquals(0, E.delete("bassDb", pts(0 to 0), 0)!!.size)
        assertEquals("[0.0, 1.0]", str(E.delete("gain", pts(0 to 1, 4 to 0), 1)!!))
    }

    @Test
    fun repeatCopiesTheSpanAlongTheWindowOnBothDecks() {
        val d = ForkV2Drawing("volume", "beat", mapOf("gain" to pts(0 to 1, 2 to 1, 2 to 0, 3 to 0, 3 to 1, 12 to 1)), mapOf("gain" to pts(0 to 0, 16 to 1)))
        val r = E.repeat(d, "gain", 2.0, 2.0, 16.0)!!
        val out = r.points("gain", true)
        // 0 and the part [2, 4) at 2, 4, ..., 14; the point at 12 is replaced by its copy's span
        assertEquals(1 + 4 * 7, out.size, str(out))
        assertEquals(14.0, out[out.size - 4][0], 1e-9)
        assertEquals(15.0, out.last()[0], 1e-9)
        // the next song's deck has no point in the span: unchanged
        assertEquals("[0.0, 0.0], [16.0, 1.0]", str(r.points("gain", false)))
        // past 128 points: refused
        assertNull(E.repeat(d, "gain", 2.0, 0.25, 200.0))
    }

    @Test
    fun aTinyRepeatLengthIsRefusedAtOnce() {
        // 0.0001 beats over 32: 320 000 copies before the old check; refused as soon as it passes 128
        val d = ForkV2Drawing("volume", "beat", mapOf("gain" to pts(0 to 1, 2 to 0, 32 to 1)), mapOf("gain" to pts(0 to 0, 32 to 1)))
        val started = System.nanoTime()
        assertNull(E.repeat(d, "gain", 2.0, 0.0001, 32.0))
        assertTrue(System.nanoTime() - started < 1_000_000_000L, "repeat took over a second")
    }

    @Test
    fun drawStartsFromTheShapeAndPinsTheLength() {
        val ov = ForkV2MixOverride().also { it.preset = "slam" }
        val c = ForkV2DrawEditor.Ctx(0.5, null, "beat")
        val d = E.startDrawing("volume", ov, 4.0, c)
        assertEquals("[0.0, 1.0], [4.0, 1.0], [4.0, 0.0]", str(d.points("gain", true)))
        // an EQ shape with nothing on a curve: that curve starts flat and open
        val eq = E.startDrawing("eq", ForkV2MixOverride().also { it.preset = "slam" }, 4.0, c)
        assertEquals("[0.0, 0.0]", str(eq.points("highDb", true)))
        val saved = E.withDrawing(ov, "volume", d, 2)!!
        assertEquals("custom", saved.preset)
        assertEquals(2, saved.bars)
        assertEquals("none", saved.eq)
        assertNotNull(saved.drawing("volume"))
        // undo: Slam back, and the length the edit set goes with it
        val back = E.undone(saved, ForkV2DrawEditor.Step("a>b", ov, 0, 2))!!
        assertEquals("slam", back.preset)
        assertEquals(0, back.bars)
        assertTrue(back.drawings.isEmpty())
        // a length picked by hand since stays
        val picked = ForkV2EditorGeometry.edited(saved, false) { bars = 8 }
        assertEquals(8, E.undone(picked, ForkV2DrawEditor.Step("a>b", ov, 0, 2))!!.bars)
    }
}
