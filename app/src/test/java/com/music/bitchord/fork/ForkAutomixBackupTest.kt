package com.music.bitchord.fork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.music.bitchord.playback.smart.AutomixStyle

/**
 * Fork: a backup carries the Automix style, the phrase length and the Automix overview's picks per
 * pair, each only as the value it is here: an edited or older file can't store a style this build
 * doesn't have or a length Settings doesn't offer. A backup from before Automix v2 replaced Web and
 * Advanced AutoDJ comes in as Automix v2, and its "Shorter blend when keys clash" switch is dropped.
 */
class ForkAutomixBackupTest {

    @Test
    fun `the style travels by its key, and only a known one`() {
        assertEquals("bitchord", ForkSettings.backupValue("automix_style", "bitchord"))
        assertEquals("automix_v2", ForkSettings.backupValue("automix_style", "automix_v2"))
        assertNull(ForkSettings.backupValue("automix_style", "original"))
        assertNull(ForkSettings.backupValue("automix_style", true))
    }

    @Test
    fun `a backup naming Web or Advanced AutoDJ comes in as Automix v2`() {
        assertEquals("automix_v2", ForkSettings.backupValue("automix_style", "original_autodj"))
        assertEquals("automix_v2", ForkSettings.backupValue("automix_style", "advanced_autodj"))
        assertEquals(AutomixStyle.V2, AutomixStyle.fromKey(ForkSettings.backupValue("automix_style", "advanced_autodj") as String))
    }

    @Test
    fun `the removed key-aware switch is not read from a backup`() {
        assertNull(ForkSettings.backupValue("automix_key_aware", true))
    }

    @Test
    fun `the phrase length is one of Auto, 8 and 16`() {
        assertEquals(0, ForkSettings.backupValue("automix_phrase_bars", 0))
        assertEquals(8, ForkSettings.backupValue("automix_phrase_bars", 8))
        assertEquals(16, ForkSettings.backupValue("automix_phrase_bars", 16))
        assertNull(ForkSettings.backupValue("automix_phrase_bars", 32))
        assertNull(ForkSettings.backupValue("automix_phrase_bars", "8"))
    }

    @Test
    fun `the picks travel as their saved text`() {
        val saved = "a->b\tloop\t\nc->d\tfilter\t8"
        assertEquals(saved, ForkSettings.backupValue("automix_pair_picks", saved))
        assertNull(ForkSettings.backupValue("automix_pair_picks", 3))
    }
}
