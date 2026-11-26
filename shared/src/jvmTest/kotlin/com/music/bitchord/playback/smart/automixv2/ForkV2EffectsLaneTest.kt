package com.music.bitchord.playback.smart.automixv2

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Effects and Looping lanes, the new shapes and song speed in an override (editor.md §3, §12, §13). */
class ForkV2EffectsLaneTest {

    private fun custom(vararg lanes: Pair<String, Any?>, speed: Map<String, Any?>? = null) = ForkV2MixOverride.fromJson(
        buildMap {
            put("v", 1)
            put("preset", "custom")
            put("lanes", mapOf("volume" to "overlap", "eq" to "centerBassSwap", "effect" to "none") + lanes.toMap())
            if (speed != null) put("speed", speed)
        },
    )!!

    @Test
    fun noneIsNeverStoredAndMissingIsNone() {
        val o = custom()
        assertContentEquals(arrayOf<String?>("overlap", "centerBassSwap", "none", "none", "none"), o.lanes())
        @Suppress("UNCHECKED_CAST")
        val lanes = o.toJson()["lanes"] as Map<String, Any?>
        assertFalse("fx" in lanes)
        assertFalse("looping" in lanes)
        val n = custom("fx" to "none", "looping" to "none")
        assertNull(n.fx)
        assertNull(n.looping)
    }

    @Test
    fun effectsLoopingAndSpeedRoundTrip() {
        val o = custom("fx" to "echo34OutEnd", "looping" to "loop4", speed = mapOf("out" to 1.05, "in" to 0.95))
        assertEquals("echo34OutEnd", o.fx)
        assertEquals("loop4", o.looping)
        assertEquals(1.05, o.speedOut)
        assertEquals(0.95, o.speedIn)
        val back = ForkV2MixOverride.fromJson(o.toJson())!!
        assertEquals(o.toJson(), back.toJson())
        // a speed outside 0.5..2 is none
        assertEquals(1.0, ForkV2MixOverride.speedOf(3.0))
        assertEquals(1.0, ForkV2MixOverride.speedOf("x"))
    }

    @Test
    fun aMixesSpeedGoesToTheSongItBringsInFirst() {
        val ab = custom(speed = mapOf("in" to 1.1))
        val bc = custom(speed = mapOf("out" to 0.9))
        val of = { a: String, b: String -> if (a == "A" && b == "B") ab else if (a == "B" && b == "C") bc else null }
        assertEquals(1.1, ForkV2MixOverride.songSpeed("B", "A", "C", of))
        assertEquals(0.9, ForkV2MixOverride.songSpeed("B", null, "C", of))
        assertEquals(1.0, ForkV2MixOverride.songSpeed("C", "B", null, of))
    }

    @Test
    fun anEffectsShapeSendsTheOutgoingDeckWithItsTime() {
        val c = ForkV2MixOverride.presetCurves(custom("fx" to "echoHalfOutEnd"), 16.0, 0.5)!!
        val send = assertNotNull(c.out["echoSend"])
        assertEquals(0.0, G.curveAt(send, 8.0 - 0.01, false, 0.0), 1e-6)
        assertEquals(0.5, G.curveAt(send, 16.0, false, 0.0), 1e-6)
        assertNull(c.`in`["echoSend"])
        val t = assertNotNull(c.fxTime)
        assertEquals(0.25, t[0], 1e-9) // ½ beat of 0.5 s
        assertEquals(0.25, t[1], 1e-9) // the delay's default ½ beat
        val r = ForkV2MixOverride.presetCurves(custom("fx" to "reverbOutCenter"), 16.0, 0.5)!!
        assertEquals(0.5, G.curveAt(r.out["reverbSend"]!!, 8.0, false, 0.0), 1e-6)
    }

    @Test
    fun spotifysOneBarCutOutsAndBassFadeOut() {
        // 16 s of 0.5 s beats: one bar before the end is 14 s (editor.md §3, 17e67dbd)
        val f = ForkV2MixOverride.presetCurves(custom("volume" to "fadeIn1BarCutOut"), 16.0, 0.5)!!
        assertEquals(1.0, G.curveAt(f.out["gain"]!!, 13.9, false, 0.0), 1e-6)
        assertEquals(0.0, G.curveAt(f.out["gain"]!!, 15.0, false, 0.0), 1e-6)
        assertEquals(0.5, G.curveAt(f.`in`["gain"]!!, 4.0, false, 0.0), 1e-6)
        assertEquals(1.0, G.curveAt(f.`in`["gain"]!!, 8.0, false, 0.0), 1e-6)
        val o = ForkV2MixOverride.presetCurves(custom("volume" to "overlap1BarCutOut"), 16.0, 0.5)!!
        assertEquals(1.0, G.curveAt(o.`in`["gain"]!!, 1.0, false, 0.0), 1e-6)
        assertEquals(0.0, G.curveAt(o.out["gain"]!!, 15.0, false, 0.0), 1e-6)
        // a short mix: never before the middle
        val s = ForkV2MixOverride.presetCurves(custom("volume" to "overlap1BarCutOut"), 2.0, 0.5)!!
        assertEquals(1.0, G.curveAt(s.out["gain"]!!, 0.99, false, 0.0), 1e-6)
        val b = ForkV2MixOverride.presetCurves(custom("eq" to "bassFadeOut"), 16.0, 0.5)!!
        val bass = b.out["bassDb"]!!
        assertEquals(17, bass.size)
        val cut = G.curveAt(bass, 8.0, false, 0.0)
        assertTrue(cut < -10)
        assertEquals(cut / 2, G.curveAt(bass, 4.0, false, 0.0), 1e-6)
        assertEquals(cut, G.curveAt(bass, 16.0, false, 0.0), 1e-6)
        assertNull(b.`in`["bassDb"])
    }

    @Test
    fun theNewShapesPlay() {
        for (v in ForkV2MixOverride.VOLUMES) assertNotNull(ForkV2MixOverride.presetCurves(custom("volume" to v), 16.0, 0.5), v)
        for (e in ForkV2MixOverride.EQS) assertNotNull(ForkV2MixOverride.presetCurves(custom("eq" to e), 16.0, 0.5), e)
        for (f in ForkV2MixOverride.EFFECTS) assertNotNull(ForkV2MixOverride.presetCurves(custom("effect" to f), 16.0, 0.5), f)
        val n = ForkV2MixOverride.presetCurves(custom("effect" to "noiseOutEnd"), 16.0, 0.5)!!
        assertEquals(1.0, G.curveAt(n.out["noise"]!!, 16.0, false, 0.0), 1e-6)
        val fifo = ForkV2MixOverride.presetCurves(custom("volume" to "fadeInFadeOut"), 16.0, 0.5)!!
        assertEquals(1.0, G.curveAt(fifo.`in`["gain"]!!, 8.0, false, 0.0), 1e-6)
    }

    @Test
    fun theLoopingLaneCarriesItsBeats() {
        val c = ForkV2MixOverride.presetCurves(custom("looping" to "loop8"), 16.0, 0.5)!!
        assertEquals(8, c.loopBeats)
        assertEquals(0, ForkV2MixOverride.presetCurves(custom(), 16.0, 0.5)!!.loopBeats)
        assertTrue(ForkV2MixOverride.LOOPINGS.all { it == "none" || ForkV2MixOverride.LOOP_BEATS[it]!! > 0 })
    }

    private object G {
        fun curveAt(pts: Array<DoubleArray>, t: Double, geo: Boolean, rest: Double) = ForkV2EditorGeometry.curveAt(pts, t, geo, rest)
    }
}
