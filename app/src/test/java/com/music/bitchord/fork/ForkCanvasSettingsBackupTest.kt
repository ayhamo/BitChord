package com.music.bitchord.fork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The canvas settings (canvas.md §13) and the Best part button travel in the phone's backup, as strings. */
class ForkCanvasSettingsBackupTest {

    @Test
    fun canvasKeysAreStrings() {
        assertEquals("apple,video,spotify", ForkSettings.backupValue("canvas_order", "apple,video,spotify"))
        assertEquals("NONE", ForkSettings.backupValue("canvas_sources_on", "NONE"))
        assertEquals("P1080", ForkSettings.backupValue("canvas_video_quality", "P1080"))
        assertEquals("off", ForkSettings.backupValue("canvas_artist_images", "off"))
        assertEquals("off", ForkSettings.backupValue("best_part_button", "off"))
        assertNull(ForkSettings.backupValue("canvas_order", true))
        assertNull(ForkSettings.backupValue("canvas_video_mode", 3))
    }
}
