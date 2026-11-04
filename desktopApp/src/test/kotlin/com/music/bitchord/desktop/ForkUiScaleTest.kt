package com.music.bitchord.desktop

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

/** From Desktop Revived 8782585b's `DesktopUiScaleSettingsTest`. */
class ForkUiScaleTest {
    @Test
    fun `normalizes values to the presets`() {
        assertEquals(listOf(80, 90, 100, 110, 125), ForkUiScale.options)
        assertEquals(100, ForkUiScale.normalizePercent(100))
        assertEquals(100, ForkUiScale.normalizePercent(103))
        assertEquals(110, ForkUiScale.normalizePercent(107))
        assertEquals(80, ForkUiScale.normalizePercent(-10))
        assertEquals(125, ForkUiScale.normalizePercent(1_000))
    }

    @Test
    fun `reads the saved scale and falls back for a broken value`() {
        val preferences = Preferences.userRoot().node("com.music.bitchord.desktop.test.ui_scale_${UUID.randomUUID()}")
        try {
            assertEquals(100, ForkUiScale.readPercent(preferences))
            preferences.putInt(ForkUiScale.KEY_UI_SCALE_PERCENT, 125)
            assertEquals(125, ForkUiScale.readPercent(preferences))
            preferences.put(ForkUiScale.KEY_UI_SCALE_PERCENT, "broken")
            assertEquals(100, ForkUiScale.readPercent(preferences))
        } finally {
            preferences.removeNode()
        }
    }
}
