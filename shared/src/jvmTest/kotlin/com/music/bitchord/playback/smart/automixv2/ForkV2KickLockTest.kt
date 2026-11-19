package com.music.bitchord.playback.smart.automixv2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Automix v2's kick lock: [ForkV2KickLock.kickLock] on two synthetic songs whose kicks sit a known
 * distance apart, and [ForkV2Automix.kickLocked], which moves the plan's mix-in by what an app
 * measured (the PC; the phone sets no [ForkV2Automix.kickLock]).
 */
class ForkV2KickLockTest {

    /** A rate whose kept side is a whole number of kHz, so [ForkV2KickLock.kickOnsets]' 1 ms hop is exact (as the PC decodes). */
    private val rate = 12_000.0

    @AfterTest
    fun tearDown() {
        ForkV2Automix.kickLock = null
    }

    private fun blend(): ForkV2Planner.Plan = ForkV2Planner.Plan().apply {
        style = "blend"
        beatSec = 0.5
        mixOutTrackTime = 100.0
        mixInTrackTime = 20.0
        durationSec = 16.0
    }

    /** A song second [from] to [to] with a 50 Hz kick (40 ms decay) on every beat of [beatSec] from [first]. */
    private fun kicks(from: Double, to: Double, first: Double, beatSec: Double): ForkV2KickLock.Side {
        val n = ((to - from) * rate).toInt()
        val x = FloatArray(n)
        var k = Math.floor((from - first) / beatSec)
        while (true) {
            val at = first + k * beatSec
            if (at > to) break
            val a = ((at - from) * rate).toInt()
            for (i in 0 until (0.2 * rate).toInt()) {
                val j = a + i
                if (j < 0 || j >= n) continue
                val t = i / rate
                x[j] += (0.8 * sin(2 * PI * 50 * t) * exp(-t / 0.04)).toFloat()
            }
            k++
        }
        return ForkV2KickLock.kickSource(arrayOf(x), rate, from, 0.0, Double.POSITIVE_INFINITY)
    }

    @Test
    fun `a next song whose kicks come 25 ms late is cued 25 ms later`() {
        val p = blend()
        val out = kicks(90.0, 130.0, 100.0, 0.5)
        val inn = kicks(10.0, 50.0, 20.025, 0.5)
        val r = assertNotNull(ForkV2KickLock.kickLock(p, out, inn))
        assertNull(r.none, r.toString())
        assertTrue(abs(r.shift - 0.025) < 0.003, r.toString())
        assertTrue(r.score >= ForkV2KickLock.MIN_MATCH && r.gain >= ForkV2KickLock.MIN_GAIN, r.toString())
    }

    @Test
    fun `kicks that already line up are left alone`() {
        val p = blend()
        val r = assertNotNull(ForkV2KickLock.kickLock(p, kicks(90.0, 130.0, 100.0, 0.5), kicks(10.0, 50.0, 20.0, 0.5)))
        assertNotNull(r.none, r.toString())
    }

    @Test
    fun `the plan's mix-in moves by the measured shift`() {
        val measured = ForkV2KickLock.Result().apply { shift = 0.021; score = 0.9; gain = 0.5 }
        var asked = 0
        ForkV2Automix.kickLock = { fromId, toId, _, _ ->
            asked++
            assertEquals(Pair("a", "b"), Pair(fromId, toId))
            measured
        }
        val p = blend()
        val q = ForkV2Automix.kickLocked(p, "a", "b", 60.0)
        assertEquals(20.021, q.mixInTrackTime, 1e-9)
        assertSame(measured, q.kick)
        assertEquals(20.0, p.mixInTrackTime, "the planner's plan is not changed in place")
        assertEquals(1, asked)
    }

    @Test
    fun `nothing moves without a measurement, a refusal, a placed mix-in or a cue before the song`() {
        val p = blend()
        assertSame(p, ForkV2Automix.kickLocked(p, "a", "b", 60.0), "no app measures it (the phone)")
        ForkV2Automix.kickLock = { _, _, _, _ -> null }
        assertSame(p, ForkV2Automix.kickLocked(p, "a", "b", 60.0), "not measured yet")
        ForkV2Automix.kickLock = { _, _, _, _ -> ForkV2KickLock.Result().apply { none = "no clear kick" } }
        assertSame(p, ForkV2Automix.kickLocked(p, "a", "b", 60.0), "a refusal")
        ForkV2Automix.kickLock = { _, _, _, _ -> ForkV2KickLock.Result().apply { shift = -0.03 } }
        val placed = blend().apply { windowIn = true }
        assertSame(placed, ForkV2Automix.kickLocked(placed, "a", "b", 60.0), "a mix-in placed in the editor")
        val atStart = blend().apply { mixInTrackTime = 0.01 }
        assertSame(atStart, ForkV2Automix.kickLocked(atStart, "a", "b", 60.0), "a cue before the song's start")
        val fade = blend().apply { style = "crossfade" }
        assertSame(fade, ForkV2Automix.kickLocked(fade, "a", "b", 60.0), "not a blend")
    }
}
