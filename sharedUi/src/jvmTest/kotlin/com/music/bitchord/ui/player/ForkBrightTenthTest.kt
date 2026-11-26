package com.music.bitchord.ui.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ForkBrightTenth]: a near-white cover's bright tenth ends at mid grey under the scrim; a dark one keeps the base. */
class ForkBrightTenthTest {

    private fun grey(l: Int) = (0xFF shl 24) or (l shl 16) or (l shl 8) or l

    @Test
    fun theBrightTenthIsThe90thPercentile() {
        val px = IntArray(100) { if (it < 85) grey(20) else grey(250) }
        assertEquals(250.0, ForkBrightTenth.p90Luma(px))
        assertEquals(20.0, ForkBrightTenth.p90Luma(IntArray(100) { if (it < 95) grey(20) else grey(250) }))
        assertEquals(0.0, ForkBrightTenth.p90Luma(IntArray(0)))
    }

    @Test
    fun aWhiteSleeveIsBroughtDownToMidGrey() {
        val a = ForkBrightTenth.alpha(0.18f, 255.0)
        assertTrue(a > 0.18f)
        assertTrue(abs(255.0 * (1 - a) - ForkBrightTenth.TARGET_LUMA) < 0.5, "white under the scrim lands on 128")
    }

    /**
     * A stand-in for "Fortnight" (Taylor Swift): a black-and-white photo, mostly a white-to-pale-grey
     * room, with dark figures in about a fifth of it. Upstream's mesh scrim alone (6% at its top) left
     * its bright tenth near 230, under white text; with the hook it lands on 128.
     */
    @Test
    fun aFortnightLikeSleeveUnderTheMeshScrimLandsOnMidGrey() {
        val px = IntArray(32 * 32) { i ->
            when {
                i % 5 == 0 -> grey(25 + i % 30) // the figures
                i % 3 == 0 -> grey(200 + i % 20) // pale grey walls
                else -> grey(235 + i % 15) // the white room
            }
        }
        val p90 = ForkBrightTenth.p90Luma(px)
        assertTrue(p90 > 240.0, "bright tenth $p90")
        assertTrue(p90 * (1 - 0.06) > 200.0, "the mesh scrim alone leaves it light")
        val extra = ForkBrightTenth.extra(0.06f, p90)
        val shown = p90 * (1 - 0.06) * (1 - extra)
        assertTrue(abs(shown - ForkBrightTenth.TARGET_LUMA) < 0.5, "under both: $shown")
        assertEquals(0f, ForkBrightTenth.extra(0.06f, 120.0), "a dark sleeve gets nothing more")
    }

    @Test
    fun aDarkOrMidCoverKeepsTheBaseScrim() {
        assertEquals(0.18f, ForkBrightTenth.alpha(0.18f, 100.0))
        assertEquals(0.18f, ForkBrightTenth.alpha(0.18f, 150.0), "150 under 18% is 123, already under 128")
        assertEquals(0.55f, ForkBrightTenth.alpha(0.55f, 255.0), "the lyrics' shade already holds white at 115")
        assertEquals(0.18f, ForkBrightTenth.alpha(0.18f, 0.0), "no pixels read: the base")
    }
}
