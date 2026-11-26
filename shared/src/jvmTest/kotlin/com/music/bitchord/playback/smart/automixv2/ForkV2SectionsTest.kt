package com.music.bitchord.playback.smart.automixv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForkV2SectionsTest {

    private val sr = 1000.0

    /** [seconds] of a square wave at [level] per section. */
    private fun song(vararg sections: Pair<Double, Float>): FloatArray {
        val out = ArrayList<Float>()
        for ((sec, level) in sections) repeat((sec * sr).toInt()) { out += if (it % 2 == 0) level else -level }
        return out.toFloatArray()
    }

    @Test
    fun theIntroEndsAtTheFirstTwoLoudWindowsAndTheOutroAfterTheLastTwo() {
        // 120 bpm: 4 bars = 8 s. 24 s quiet, 72 s loud, 24 s quiet: windows 0-2 quiet, 3-11 loud, 12-14 quiet
        val x = song(24.0 to 0.2f, 72.0 to 1f, 24.0 to 0.2f)
        val p = ForkV2Sections.power(x, sr)!!
        assertEquals(0.05, p.step, 1e-12)
        val (intro, outro) = ForkV2Sections.introOutro(p, 0.5, x.size / sr)!!
        assertEquals(24.0, intro, 1e-9)
        assertEquals(96.0, outro, 1e-9) // (10 + 2) windows of 8 s
    }

    @Test
    fun theIntroIsAtMostAThirdInAndTheOutroAtLeastHalfway() {
        val x = song(60.0 to 0.1f, 16.0 to 1f, 44.0 to 0.1f)
        val (intro, outro) = ForkV2Sections.introOutro(ForkV2Sections.power(x, sr), 0.5, 120.0)!!
        // windows 7 (56-64 s, half loud: 0.71) to 9 (72-80 s, half loud) are loud
        assertEquals(40.0, intro, 1e-9) // 56 s, capped at a third
        assertEquals(80.0, outro, 1e-9) // after windows 8 and 9
        val (_, early) = ForkV2Sections.introOutro(ForkV2Sections.power(song(16.0 to 1f, 104.0 to 0.1f), sr), 0.5, 120.0)!!
        assertEquals(60.0, early, 1e-9) // 16 s, at least halfway
    }

    @Test
    fun noGridMeansFourSecondWindowsAndNoCurveMeansNoAnswer() {
        val x = song(8.0 to 0.1f, 40.0 to 1f)
        val (intro, _) = ForkV2Sections.introOutro(ForkV2Sections.power(x, sr), Double.NaN, 48.0)!!
        assertEquals(8.0, intro, 1e-9)
        assertNull(ForkV2Sections.introOutro(null, 0.5, 120.0))
    }

    @Test
    fun theEvidenceKeepsThePowerCurveAndTheMergeSetsTheIntro() {
        val ev = ForkV2Analysis()
        ev.durationSec = 120.0
        ev.powerCurve = ForkV2Sections.power(song(24.0 to 0.2f, 72.0 to 1f, 24.0 to 0.2f), sr)
        val back = ForkV2Analysis.fromJsonText(ForkV2Json.write(ev.toJson()))
        assertEquals(ev.powerCurve!!.values.size, back.powerCurve!!.values.size)
        val a = ForkV2Analysis()
        a.beatIntervalSec = 0.5
        a.introEndSec = 3.0
        ForkV2Automix.merge(a, back)
        assertEquals(24.0, a.introEndSec, 1e-9)
        assertEquals(96.0, a.outroStartSec, 1e-9)
    }
}
