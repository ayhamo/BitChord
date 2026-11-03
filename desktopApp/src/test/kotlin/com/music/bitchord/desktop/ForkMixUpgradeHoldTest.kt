package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForkMixUpgradeHoldTest {
    private val ms = 1_000_000L
    private fun hold() = ForkMixUpgradeHold<String>(settleNs = 1_500 * ms)

    @Test
    fun `with no mix played a copy goes straight in`() {
        val h = hold()
        assertFalse(h.mustWait(mixing = false, nowNs = 10 * ms))
    }

    @Test
    fun `a copy arriving mid-mix waits until the mix has settled`() {
        val h = hold()
        assertTrue(h.mustWait(mixing = true, nowNs = 1_000 * ms))
        assertNull(h.hold("flac"))
        // Still mixing, then just ended: held.
        assertNull(h.due(mixing = true, nowNs = 5_000 * ms))
        assertNull(h.due(mixing = false, nowNs = 5_100 * ms))
        assertNull(h.due(mixing = false, nowNs = 6_499 * ms))
        // A second and a half after the mix's last moment: out, once.
        assertEquals("flac", h.due(mixing = false, nowNs = 6_500 * ms))
        assertNull(h.due(mixing = false, nowNs = 7_000 * ms))
        assertNull(h.holding)
    }

    @Test
    fun `a copy arriving on the mix's heels waits out the settle too`() {
        val h = hold()
        h.observe(mixing = true, nowNs = 2_000 * ms)
        assertTrue(h.mustWait(mixing = false, nowNs = 2_400 * ms))
        assertFalse(h.mustWait(mixing = false, nowNs = 3_500 * ms))
    }

    @Test
    fun `a second mix before the settle holds it again`() {
        val h = hold()
        h.observe(mixing = true, nowNs = 1_000 * ms)
        h.hold("flac")
        assertNull(h.due(mixing = false, nowNs = 2_000 * ms))
        // The next song is short and its blend starts before the copy went in.
        assertNull(h.due(mixing = true, nowNs = 2_400 * ms))
        assertNull(h.due(mixing = false, nowNs = 3_000 * ms))
        assertEquals("flac", h.due(mixing = false, nowNs = 3_900 * ms))
    }

    @Test
    fun `the newest copy wins and the replaced one is handed back to close`() {
        val h = hold()
        assertNull(h.hold("a"))
        assertEquals("a", h.hold("b"))
        // The same copy twice is not handed back: closing it would close the one held.
        val same = "b"
        assertNull(h.hold(same))
        assertSame(same, h.holding)
    }

    @Test
    fun `drop empties the hold`() {
        val h = hold()
        h.hold("a")
        assertEquals("a", h.drop())
        assertNull(h.drop())
        assertNull(h.due(mixing = false, nowNs = Long.MAX_VALUE / 2))
    }
}
