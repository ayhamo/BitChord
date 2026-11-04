package com.music.bitchord.desktop

import com.music.bitchord.ui.player.FORK_PAUSE_SHRINK_SCALE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fork: Layout 3's copy of upstream's pause shrink must match upstream's own (private) value, read
 * by reflection, so upstream's NowPlayingScreen.kt needs no edit to share it (ForkWidePlayer.kt).
 */
class ForkWidePlayerShrinkTest {
    @Test
    fun copyMatchesUpstreamsPauseShrink() {
        val field = Class.forName("com.music.bitchord.ui.player.NowPlayingScreenKt")
            .getDeclaredField("ARTWORK_PAUSE_SHRINK_SCALE")
            .apply { isAccessible = true }
        assertEquals(field.getFloat(null), FORK_PAUSE_SHRINK_SCALE, 0f)
    }
}
