package com.music.bitchord.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForkWindowModeTest {

    @Test
    fun `the popup reported as floating does not end a native full screen`() {
        // F11 from a maximized window: the borderless popup has no WS_MAXIMIZE, AWT says Floating.
        assertNull(adoptPlacement(WindowPlacement.Floating, WindowPlacement.Maximized, fullScreen = true, nativeFullscreen = true))
        assertNull(adoptPlacement(WindowPlacement.Maximized, WindowPlacement.Floating, fullScreen = true, nativeFullscreen = true))
    }

    @Test
    fun `without the native full screen the window's word is taken as before`() {
        assertEquals(
            AdoptedPlacement(WindowPlacement.Floating, fullScreen = false),
            adoptPlacement(WindowPlacement.Floating, WindowPlacement.Maximized, fullScreen = true, nativeFullscreen = false),
        )
        assertEquals(
            AdoptedPlacement(WindowPlacement.Maximized, fullScreen = true),
            adoptPlacement(WindowPlacement.Maximized, WindowPlacement.Floating, fullScreen = true, nativeFullscreen = false),
        )
        assertEquals(
            AdoptedPlacement(WindowPlacement.Maximized, fullScreen = false),
            adoptPlacement(WindowPlacement.Maximized, WindowPlacement.Floating, fullScreen = false, nativeFullscreen = false),
        )
        assertNull(adoptPlacement(WindowPlacement.Maximized, WindowPlacement.Maximized, fullScreen = false, nativeFullscreen = false))
    }
}
