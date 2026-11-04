package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkLyricsHoldTest {

    @Test
    fun heldWhileAnyLineIsPressed() {
        val hold = ForkLyricsHold()
        assertFalse(hold.held)
        hold.press()
        hold.press()
        assertTrue(hold.held)
        hold.release()
        assertTrue(hold.held, "a second finger is still down")
        hold.release()
        assertFalse(hold.held)
    }

    @Test
    fun extraReleaseNeverGoesNegative() {
        val hold = ForkLyricsHold()
        hold.release()
        hold.press()
        assertTrue(hold.held, "a stray release before the press must not swallow it")
        hold.release()
        assertFalse(hold.held)
    }
}
