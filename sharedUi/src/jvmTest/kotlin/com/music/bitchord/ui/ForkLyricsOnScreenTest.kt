package com.music.bitchord.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkLyricsOnScreenTest {

    @Test fun withoutAPlatformAnswerTheLifecycleDecides() {
        // The phone never provides one, and the PC provides none while its switch is off.
        assertTrue(resolveForkLyricsOnScreen(platform = null, lifecycleForeground = true))
        assertFalse(resolveForkLyricsOnScreen(platform = null, lifecycleForeground = false))
    }

    @Test fun aPlatformAnswerOverridesTheLifecycleBothWays() {
        // An unfocused but shown PC window: lifecycle STARTED, lyrics still on screen.
        assertTrue(resolveForkLyricsOnScreen(platform = true, lifecycleForeground = false))
        // Minimized or in the tray with the switch on: nothing to draw for, even if RESUMED.
        assertFalse(resolveForkLyricsOnScreen(platform = false, lifecycleForeground = true))
    }
}
