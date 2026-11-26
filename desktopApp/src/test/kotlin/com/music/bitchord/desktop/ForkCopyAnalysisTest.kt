package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkCopyAnalysisTest {

    private val song = Song("weaRBsTrSkM", "Vaina Loca", "Ozuna", null)
    private val flac = DesktopStream("https://addon.example/vl.flac", sourceId = "addon")

    @Test
    fun anAddOnsCopyHasItsOwnKeyAndYouTubeOrAFileKeepTheSongs() {
        assertTrue(ForkCopyAnalysis.isCopy(song, flac))
        assertEquals("weaRBsTrSkM@addon", ForkCopyAnalysis.key(song, flac))
        assertEquals("weaRBsTrSkM", ForkCopyAnalysis.songOf("weaRBsTrSkM@addon"))
        assertEquals("weaRBsTrSkM", ForkCopyAnalysis.key(song, DesktopStream("https://yt.example/a", sourceId = "youtube")))
        assertEquals("weaRBsTrSkM", ForkCopyAnalysis.key(song, DesktopStream("C:/music/vl.flac")))
        assertEquals("weaRBsTrSkM", ForkCopyAnalysis.key(song, null))
        assertFalse(ForkCopyAnalysis.isCopy(song, DesktopStream("https://x.example/a")))
    }

    @Test
    fun theCopysAnalysisIsPlannedWithOnlyOnceItIsUsable() {
        assertEquals("weaRBsTrSkM" to false, ForkCopyAnalysis.planKey(song, flac) { false })
        assertEquals("weaRBsTrSkM@addon" to true, ForkCopyAnalysis.planKey(song, flac) { it == "weaRBsTrSkM@addon" })
        assertEquals("weaRBsTrSkM" to false, ForkCopyAnalysis.planKey(song, null) { true })
    }

    @Test
    fun aCopysPassIsFocusedWithItsSong() {
        val q = ForkAnalysisQueue<Int>()
        q.focus("weaRBsTrSkM", "vNQjKyEMJxk")
        assertTrue(q.isRelevant("weaRBsTrSkM@addon"))
        assertFalse(q.isRelevant("other@addon"))
    }
}
