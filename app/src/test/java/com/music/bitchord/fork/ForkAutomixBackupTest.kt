package com.music.bitchord.fork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fork: a backup carries the Automix style, "Shorter blend when keys clash", the phrase length and
 * the Automix overview's picks per pair, each only as the value it is here: an edited or older file
 * can't store a style this build doesn't have or a length Settings doesn't offer.
 */
class ForkAutomixBackupTest {

    @Test
    fun `the style travels by its key, and only a known one`() {
        assertEquals("original_autodj", ForkSettings.backupValue("automix_style", "original_autodj"))
        assertEquals("advanced_autodj", ForkSettings.backupValue("automix_style", "advanced_autodj"))
        assertEquals("bitchord", ForkSettings.backupValue("automix_style", "bitchord"))
        assertNull(ForkSettings.backupValue("automix_style", "original"))
        assertNull(ForkSettings.backupValue("automix_style", true))
    }

    @Test
    fun `key-aware is a switch`() {
        assertEquals(true, ForkSettings.backupValue("automix_key_aware", true))
        assertNull(ForkSettings.backupValue("automix_key_aware", 1))
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
