package com.music.bitchord.ui.screens

import com.music.bitchord.data.ForkSettingsBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fork's own Settings pages (owner, 2026-11-19): Navidrome has a page of its own beside Fork
 * settings, both listed last on the main page, and only their chevron rows carry the "Fork" badge.
 */
class ForkSettingsPagesTest {

    @Test
    fun theForkPagesComeLastNavidromeFirst() {
        assertEquals(
            listOf(SettingsCategory.NAVIDROME, SettingsCategory.FORK),
            SettingsCategory.entries.takeLast(2),
        )
    }

    @Test
    fun onlyTheForkPagesAreBadged() {
        assertEquals(ForkSettingsBadge.TEXT, SettingsCategory.NAVIDROME.forkBadge())
        assertEquals(ForkSettingsBadge.TEXT, SettingsCategory.FORK.forkBadge())
        SettingsCategory.entries
            .filter { it != SettingsCategory.NAVIDROME && it != SettingsCategory.FORK }
            .forEach { assertNull("$it is upstream's", it.forkBadge()) }
    }

    @Test
    fun theBadgeReadsFork() {
        assertEquals("Fork", ForkSettingsBadge.TEXT)
    }
}
