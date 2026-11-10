package com.music.bitchord.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForkLyricsUnfocusedTest {

    @Test fun switchOffLeavesTheLifecycleInCharge() {
        for (visible in listOf(true, false)) for (minimized in listOf(true, false)) {
            assertNull(ForkLyricsUnfocused.onScreen(enabled = false, windowVisible = visible, windowMinimized = minimized))
        }
    }

    @Test fun switchOnKeepsAShownWindowMoving() {
        assertEquals(true, ForkLyricsUnfocused.onScreen(enabled = true, windowVisible = true, windowMinimized = false))
    }

    @Test fun switchOnNeverRunsMinimizedOrInTheTray() {
        assertEquals(false, ForkLyricsUnfocused.onScreen(enabled = true, windowVisible = true, windowMinimized = true))
        assertEquals(false, ForkLyricsUnfocused.onScreen(enabled = true, windowVisible = false, windowMinimized = false))
        assertEquals(false, ForkLyricsUnfocused.onScreen(enabled = true, windowVisible = false, windowMinimized = true))
    }
}
