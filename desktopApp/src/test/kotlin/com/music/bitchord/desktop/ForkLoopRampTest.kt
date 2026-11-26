package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The owner's baseline flaw 2 (research/automix-baseline-2026-11-08.md: "an audio issue while the tempo moved"): the
 * PC's loop deck through a 94 -> 100 BPM ramp, beat by beat as the mix clock lays it out, makes no click. A pure tone
 * through the WSOLA stretch: however the grains are nudged, a sum of windowed copies of one sine never moves faster
 * than the sine itself, so any sample-to-sample step past its steepest slope is a discontinuity.
 */
class ForkLoopRampTest {

    @Test
    fun aTempoRampMakesNoClick() {
        val sr = 48_000
        val hz = 220.0
        val seconds = 12
        val left = FloatArray(sr * seconds) { (0.5 * sin(2 * PI * hz * it / sr)).toFloat() }
        val right = left.copyOf()
        // 4 bars at 94, a 3-bar ramp to 100 (each beat at its midpoint tempo), 2 bars at 100: the Nov 8 mix's shape
        val beatSrc = 60.0 / 94
        val tempos = (0 until 36).map { k ->
            when {
                k < 16 -> 94.0
                k >= 28 -> 100.0
                else -> 94.0 + 6.0 * (k - 16 + 0.5) / 12
            }
        }
        val knotFrames = DoubleArray(tempos.size + 1)
        val knotPositions = DoubleArray(tempos.size + 1)
        for (k in tempos.indices) {
            knotFrames[k + 1] = knotFrames[k] + 60.0 / tempos[k] * sr
            knotPositions[k + 1] = knotPositions[k] + beatSrc * sr
        }
        val deck = ForkLoopDeck(
            left = left, right = right, regionStart = 0.0,
            loopStart = 0L, loopEnd = left.size.toLong(), releaseAt = 1e18,
            knotFrames = knotFrames, knotPositions = knotPositions,
            runOn = false, stopAt = 60.0, preroll = 0.0, level = 1f, sampleRate = sr,
        )
        val frames = knotFrames.last().toInt()
        val outL = FloatArray(frames)
        val outR = FloatArray(frames)
        var at = 0
        while (at < frames) {
            val n = minOf(480, frames - at)
            val l = FloatArray(n)
            val r = FloatArray(n)
            deck.renderInto(at.toLong(), n, l, r)
            l.copyInto(outL, at)
            r.copyInto(outR, at)
            at += n
        }
        val steepest = 0.5 * 2 * PI * hz / sr
        var worst = 0.0
        var worstAt = 0
        // from one grain in (the deck's first grain fades in from silence) to the last knot
        for (i in sr / 10 until frames) {
            val step = abs(outL[i] - outL[i - 1]).toDouble()
            if (step > worst) { worst = step; worstAt = i }
        }
        assertTrue(worst <= steepest * 1.05, "a click at ${"%.3f".format(worstAt.toDouble() / sr)} s: step $worst, the tone's steepest $steepest")
    }
}
