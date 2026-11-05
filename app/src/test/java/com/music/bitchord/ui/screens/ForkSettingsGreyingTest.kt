package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fork settings page greys its dependent rows on the same conditions as their home rows. */
class ForkSettingsGreyingTest {

    @Test
    fun crossfadeOnSkipNeedsACrossfadeOrAutomix() {
        assertFalse(forkCrossfadeOnSkipAvailable(crossfadeSeconds = 0, smartFade = false))
        assertTrue(forkCrossfadeOnSkipAvailable(crossfadeSeconds = 4, smartFade = false))
        assertTrue(forkCrossfadeOnSkipAvailable(crossfadeSeconds = 0, smartFade = true))
    }

    @Test
    fun navidromeRowsNeedAServerAndAUser() {
        assertTrue(forkNoNavidromeServer(url = "", user = ""))
        assertTrue(forkNoNavidromeServer(url = "https://music.example.com", user = " "))
        assertTrue(forkNoNavidromeServer(url = "", user = "me"))
        assertFalse(forkNoNavidromeServer(url = "https://music.example.com", user = "me"))
    }
}
