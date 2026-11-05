package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A yt-dlp URL Google refused mid-track was handed back from the cache until its expiry, so the
 * recovery reopened the same dead stream; [ForkYtDlp.forget] drops it.
 */
class ForkYtDlpCacheTest {

    private val later = System.currentTimeMillis() + 3_600_000L

    @Test
    fun aRefusedUrlIsForgotten() {
        val refused = DesktopStream(url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=refused", sourceId = "youtube")
        ForkYtDlp.cacheForTest("refusedId01|bestaudio", refused, later)
        assertEquals(refused, ForkYtDlp.cachedFor("refusedId01|bestaudio"))

        ForkYtDlp.forget(refused.url)

        assertNull(ForkYtDlp.cachedFor("refusedId01|bestaudio"))
    }

    @Test
    fun forgettingOneUrlKeepsTheOthers() {
        val kept = DesktopStream(url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=kept", sourceId = "youtube")
        ForkYtDlp.cacheForTest("keptId00001|bestaudio", kept, later)

        ForkYtDlp.forget("https://rr1---sn-test.googlevideo.com/videoplayback?id=other")
        ForkYtDlp.forget("")

        assertEquals(kept, ForkYtDlp.cachedFor("keptId00001|bestaudio"))
        ForkYtDlp.forget(kept.url)
    }
}
