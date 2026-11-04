package com.music.bitchord.desktop

import com.music.bitchord.desktop.ForkWindowState.Bounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForkWindowStateTest {

    private val main = Bounds(0, 0, 1920, 1040) // 1080p, taskbar off the bottom
    private val right = Bounds(1920, 0, 2560, 1400)
    private val left = Bounds(-1280, 200, 1280, 984)

    private fun place(saved: Bounds?, vararg screens: Bounds) = ForkWindowState.place(saved, screens.toList(), 900, 600)

    @Test
    fun `nothing saved opens at the defaults`() {
        assertNull(place(null, main))
        assertNull(place(Bounds(10, 10, 0, 0), main))
        assertNull(place(Bounds(10, 10, 1200, 800)))
    }

    @Test
    fun `a window that fits stays where it was`() {
        assertEquals(Bounds(100, 80, 1220, 780), place(Bounds(100, 80, 1220, 780), main, right))
        assertEquals(Bounds(2100, 300, 1400, 900), place(Bounds(2100, 300, 1400, 900), main, right))
        assertEquals(Bounds(-1200, 250, 1000, 700), place(Bounds(-1200, 250, 1000, 700), main, left))
    }

    @Test
    fun `a window on a monitor that is gone opens centred on the main one`() {
        assertEquals(Bounds(350, 130, 1220, 780), place(Bounds(2100, 300, 1220, 780), main))
        assertEquals(Bounds(350, 130, 1220, 780), place(Bounds(-1200, 250, 1220, 780), main))
    }

    @Test
    fun `a window mostly off its screen is pulled back onto it`() {
        // Hanging off the right edge of the main screen with only its left part showing.
        assertEquals(Bounds(700, 260, 1220, 780), place(Bounds(1700, 900, 1220, 780), main))
        // Above the top: the caption strip is brought back on screen.
        assertEquals(Bounds(100, 0, 1220, 780), place(Bounds(100, -300, 1220, 780), main))
    }

    @Test
    fun `a sliver on a screen does not count as on it`() {
        // 50 px of the window on the main screen: not enough to grab, so centred.
        assertEquals(Bounds(350, 130, 1220, 780), place(Bounds(1870, 100, 1220, 780), main))
    }

    @Test
    fun `the screen holding most of the window wins`() {
        // Straddling the main and right screens, mostly on the right one.
        assertEquals(Bounds(1920, 100, 1220, 780), place(Bounds(1800, 100, 1220, 780), main, right))
    }

    @Test
    fun `too big for its screen is shrunk to it, never under the minimum`() {
        assertEquals(Bounds(0, 0, 1920, 1040), place(Bounds(-10, -10, 2600, 1500), main))
        assertEquals(Bounds(200, 100, 900, 600), place(Bounds(200, 100, 400, 300), main))
        // A screen smaller than the minimum: the screen wins.
        val tiny = Bounds(0, 0, 800, 560)
        assertEquals(Bounds(0, 0, 800, 560), place(Bounds(50, 50, 1220, 780), tiny))
    }
}
