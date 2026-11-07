package com.music.bitchord.fork

import com.music.bitchord.ui.player.ForkCoverFade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork: the phone's "Cover fade" switch. The player reads `ForkCoverFade.enabled` itself, so the
 * setting has to be that very flow (not a copy kept in step), and a backup carries
 * it as a switch and nothing else. The fade's own logic is tested with the PC's
 * (`ForkPlayerEffectsTest`), as both run the same sharedUi code.
 */
class ForkCoverFadeSettingTest {

    private val before = ForkCoverFade.enabled.value

    @After
    fun putBack() {
        ForkCoverFade.enabled.value = before
    }

    @Test
    fun `the setting is the flow the player reads`() {
        assertEquals(ForkCoverFade.enabled.value, ForkSettings.coverFade.value)
        ForkCoverFade.enabled.value = true
        assertTrue(ForkSettings.coverFade.value)
        ForkCoverFade.enabled.value = false
        assertEquals(false, ForkSettings.coverFade.value)
    }

    @Test
    fun `a backup carries it as a switch only`() {
        assertEquals(true, ForkSettings.backupValue("cover_fade", true))
        assertEquals(false, ForkSettings.backupValue("cover_fade", false))
        assertNull(ForkSettings.backupValue("cover_fade", 1))
        assertNull(ForkSettings.backupValue("cover_fade", "true"))
    }
}
