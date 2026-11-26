package com.music.bitchord.playback

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Automix v2's echo out on the shelf (editor.md §5): a one-beat delay line fed the beat before the
 * catch with feedback 0.53 is that beat played again and again, each pass 0.53 of the last, under a
 * 10 ms dry cut, for 8 beats, the last 50 ms faded.
 */
class ForkMixShelfEchoTest {

    private val rate = 1000
    private val beat = 0.1 // 100 frames

    private fun near(a: Float, b: Double, what: String) = assertTrue(abs(a - b) < 1e-3, "$what: want $b, got $a")

    @Test
    fun theBeatBeforeTheCatchRepeatsUnderTheDecay() {
        val shelf = ForkMixShelf()
        shelf.echoRecord()
        val before = FloatArray(300) { (it + 1) * 0.0003f } // under the limiter's ceiling with the dry (ForkMixLimiter)
        val heard = before.copyOf()
        shelf.process(heard, heard.size, 1, rate)
        for (i in heard.indices) near(heard[i], before[i].toDouble(), "untouched before the catch, frame $i")

        shelf.echoCatch(beat, 8, 0.53, 0.01)
        val after = FloatArray(900) { 0.5f }
        shelf.process(after, after.size, 1, rate)
        for (n in 0 until 900) {
            val want = if (n >= 800) 0.0 else {
                val dry = maxOf(0.0, 1 - n / 10.0) * 0.5
                val wet = before[200 + n % 100] * 0.53.pow(floor(n / 100.0)) * min(1.0, (800 - n) / 50.0)
                dry + wet
            }
            near(after[n], want, "frame $n after the catch")
        }
    }

    @Test
    fun aCatchWithoutTheWholeBeatOnlyCutsTheDrySound() {
        val shelf = ForkMixShelf()
        shelf.echoRecord()
        shelf.process(FloatArray(40) { 1f }, 40, 1, rate)
        shelf.echoCatch(beat, 8, 0.53, 0.01)
        val after = FloatArray(50) { 1f }
        shelf.process(after, after.size, 1, rate)
        for (n in after.indices) near(after[n], maxOf(0.0, 1 - n / 10.0), "frame $n")
    }

    @Test
    fun aParkPutsTheDrySoundBack() {
        val shelf = ForkMixShelf()
        shelf.echoRecord()
        shelf.process(FloatArray(200) { 1f }, 200, 1, rate)
        shelf.echoCatch(beat, 8, 0.53, 0.01)
        shelf.process(FloatArray(100) { 1f }, 100, 1, rate)
        shelf.echoPark()
        val later = FloatArray(50) { 0.5f }
        shelf.process(later, later.size, 1, rate)
        for (n in later.indices) near(later[n], 0.5, "frame $n after the park")
    }

    @Test
    fun thePcKeepsTheBeatWithoutTouchingTheBlock() {
        val shelf = ForkMixShelf()
        shelf.echoRecord()
        val block = FloatArray(200) { it * 0.001f }
        shelf.listen(block, block.size, 1, rate)
        for (i in block.indices) near(block[i], i * 0.001, "listen leaves frame $i")
        shelf.echoCatch(beat, 8, 0.53, 0.01)
        val after = FloatArray(20) { 0f }
        shelf.process(after, after.size, 1, rate)
        for (n in after.indices) near(after[n], (100 + n) * 0.001, "the kept beat, frame $n")
    }

    @Test
    fun aLoudCatchStaysUnderTheCeilingAndTheRepeatsAfterItAreUntouched() {
        val shelf = ForkMixShelf()
        shelf.echoRecord()
        val before = FloatArray(200) { 0.95f }
        shelf.process(before, before.size, 1, rate)
        shelf.echoCatch(beat, 8, 0.53, 0.01)
        val after = FloatArray(800) { 0.95f }
        shelf.process(after, after.size, 1, rate)
        // the 10 ms overlap would sum to 1.9 (+5.6 dB) unlimited
        for (n in 0 until 10) assertTrue(after[n] <= ForkMixLimiter.CEILING + 1e-6f, "frame $n: ${after[n]} over the ceiling")
        // a 100 ms release, then the repeats as they are: the last pass is 0.95 · 0.53^7
        for (n in 700 until 750) near(after[n], 0.95 * 0.53.pow(7), "repeat frame $n")
    }

    @Test
    fun aParkBeforeANewRecordDropsTheOldTail() {
        val shelf = ForkMixShelf()
        shelf.echoRecord()
        shelf.process(FloatArray(200) { 0.4f }, 200, 1, rate)
        shelf.echoPark()
        shelf.echoRecord() // the PC records every block: the park must still be taken
        shelf.process(FloatArray(40) { 0.2f }, 40, 1, rate)
        shelf.echoCatch(beat, 8, 0.53, 0.01)
        val after = FloatArray(30) { 0.2f }
        shelf.process(after, after.size, 1, rate)
        // 40 frames kept since the park, under the 100-frame beat: the dry cut alone, no repeat of the old tail
        for (n in after.indices) near(after[n], maxOf(0.0, 1 - n / 10.0) * 0.2, "frame $n")
    }

    @Test
    fun aNewBlendEndsTheLastBlendsRing() {
        val shelf = ForkMixShelf()
        shelf.echoRecord()
        shelf.listen(FloatArray(200) { 0.3f }, 200, 1, rate)
        shelf.echoCatch(beat, 8, 0.53, 0.01)
        shelf.process(FloatArray(900) { 0.3f }, 900, 1, rate) // rung out: silent from here
        shelf.echoEndRing() // the next blend arms before any single-deck block
        shelf.echoCatch(beat, 8, 0.53, 0.01)
        val next = FloatArray(5) { 0.5f }
        shelf.process(next, next.size, 1, rate)
        near(next[0], 0.5, "the new blend's dry sound, not the old ring's silence")
    }

    /** A non-finite feedback, cut or beat is no catch: a NaN feedback made every repeat NaN. The next real one still plays. */
    @Test
    fun aNonFiniteCatchIsIgnored() {
        val shelf = ForkMixShelf()
        shelf.echoRecord()
        shelf.process(FloatArray(200) { 0.2f }, 200, 1, rate)
        shelf.echoCatch(beat, 8, Double.NaN, 0.01)
        shelf.echoCatch(Double.POSITIVE_INFINITY, 8, 0.53, 0.01)
        val after = FloatArray(50) { 0.5f }
        shelf.process(after, after.size, 1, rate)
        for (n in after.indices) near(after[n], 0.5, "untouched, frame $n")
        shelf.echoCatch(beat, 8, 0.53, 0.01)
        val caught = FloatArray(50) { 0.5f }
        shelf.process(caught, caught.size, 1, rate)
        near(caught[20], 0.2, "the real catch plays (dry cut, repeat of the kept beat)")
    }
}
