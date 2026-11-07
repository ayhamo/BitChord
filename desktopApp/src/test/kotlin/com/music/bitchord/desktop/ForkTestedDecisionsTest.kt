package com.music.bitchord.desktop

import androidx.compose.ui.input.key.Key
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.data.navidrome.NavidromeSettings
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (2026-11-07): the decisions of fork features that had no test — the AppCDS stamp, Top Picks'
 * hourly row, the Library's server rows, the refresh key, the direct-source length check.
 */
class ForkTestedDecisionsTest {

    @AfterTest
    fun reset() {
        ForkLibraryLinks.openStations.value = false
    }

    // ── AppCDS stamp (ForkAppCdsStamp.stampAfter) ─────────────────────────

    private val now = "jvm:21\na.jar:1:2"
    private val retry = now + ForkAppCdsStamp.RETRY

    @Test
    fun `no archive yet, or a stale one deleted, writes the new stamp`() {
        assertEquals(now, ForkAppCdsStamp.stampAfter(kept = null, current = now, existed = false, deleted = false))
        assertEquals(now, ForkAppCdsStamp.stampAfter(kept = "old", current = now, existed = true, deleted = true))
    }

    @Test
    fun `a first failed delete only marks the stamp, so the next start tries again`() {
        assertEquals(retry, ForkAppCdsStamp.stampAfter(kept = "old", current = now, existed = true, deleted = false))
        assertEquals(retry, ForkAppCdsStamp.stampAfter(kept = null, current = now, existed = true, deleted = false))
    }

    @Test
    fun `a second failure in a row takes the archive as in use`() {
        assertEquals(now, ForkAppCdsStamp.stampAfter(kept = retry, current = now, existed = true, deleted = false))
    }

    @Test
    fun `a retry mark from another class path is a first failure again`() {
        val otherRetry = "jvm:21\nb.jar:1:2" + ForkAppCdsStamp.RETRY
        assertEquals(retry, ForkAppCdsStamp.stampAfter(kept = otherRetry, current = now, existed = true, deleted = false))
    }

    // ── Top Picks (ForkTopPicks) ──────────────────────────────────────────

    @Test
    fun `a row lasts one hour of the clock`() {
        assertEquals("2026-11-07T03", ForkTopPicks.slot(LocalDateTime.of(2026, 11, 7, 3, 0)))
        assertEquals("2026-11-07T03", ForkTopPicks.slot(LocalDateTime.of(2026, 11, 7, 3, 59)))
        assertEquals("2026-11-07T04", ForkTopPicks.slot(LocalDateTime.of(2026, 11, 7, 4, 0)))
        assertEquals("2026-11-08T00", ForkTopPicks.slot(LocalDateTime.of(2026, 11, 8, 0, 5)))
    }

    @Test
    fun `nothing is asked within the hour or while building, for the same listener`() {
        assertTrue(ForkTopPicks.upToDate("me", "me", building = false, doneSlot = "h1", slot = "h1"))
        assertTrue(ForkTopPicks.upToDate("me", "me", building = true, doneSlot = null, slot = "h1"))
        assertFalse(ForkTopPicks.upToDate("me", "me", building = false, doneSlot = "h0", slot = "h1"))
        assertFalse(ForkTopPicks.upToDate("me", "me", building = false, doneSlot = null, slot = "h1"))
    }

    @Test
    fun `another listener always loads, even mid-build`() {
        assertFalse(ForkTopPicks.upToDate("other", "me", building = true, doneSlot = "h1", slot = "h1"))
        assertFalse(ForkTopPicks.upToDate(null, "me", building = false, doneSlot = "h1", slot = "h1"))
    }

    @Test
    fun `the moods grid is told apart from a real See all page`() {
        val page = ForkTopPicks.moodsPage("Find Your Mood")
        assertTrue(ForkTopPicks.isMoodsPage(page))
        assertEquals("Find Your Mood", page.title)
        assertFalse(ForkTopPicks.isMoodsPage(page.copy(moreBrowseId = "FEmusic_moods_and_genres")))
        assertFalse(ForkTopPicks.isMoodsPage(null))
    }

    // ── Library rows (ForkLibraryLinks.opens) ─────────────────────────────

    @Test
    fun `the Navidrome row opens the page as it is`() {
        assertTrue(ForkLibraryLinks.opens(NavidromeSettings.BROWSE_ID))
        assertFalse(ForkLibraryLinks.openStations.value)
    }

    @Test
    fun `the Internet radio row opens the page on its stations`() {
        assertTrue(ForkLibraryLinks.opens(NavidromeRadio.PAGE_ID))
        assertTrue(ForkLibraryLinks.openStations.value)
    }

    @Test
    fun `any other row is left to the app`() {
        assertFalse(ForkLibraryLinks.opens("local:downloads"))
        assertFalse(ForkLibraryLinks.opens(""))
        assertFalse(ForkLibraryLinks.openStations.value)
    }

    // ── Refresh key (ForkLibraryRefresh.isRefreshKey) ─────────────────────

    @Test
    fun `F5 refreshes whatever is held`() {
        assertTrue(ForkLibraryRefresh.isRefreshKey(Key.F5, ctrl = false, meta = false, mac = false))
        assertTrue(ForkLibraryRefresh.isRefreshKey(Key.F5, ctrl = true, meta = false, alt = true, mac = false))
    }

    @Test
    fun `Ctrl+R on Windows and Linux, Cmd+R on a Mac`() {
        assertTrue(ForkLibraryRefresh.isRefreshKey(Key.R, ctrl = true, meta = false, mac = false))
        assertFalse(ForkLibraryRefresh.isRefreshKey(Key.R, ctrl = false, meta = true, mac = false))
        assertTrue(ForkLibraryRefresh.isRefreshKey(Key.R, ctrl = false, meta = true, mac = true))
        assertFalse(ForkLibraryRefresh.isRefreshKey(Key.R, ctrl = true, meta = false, mac = true))
        assertFalse(ForkLibraryRefresh.isRefreshKey(Key.R, ctrl = false, meta = false, mac = false))
    }

    @Test
    fun `AltGr+R types a letter and is not a refresh`() {
        // Windows reports AltGr as Ctrl+Alt.
        assertFalse(ForkLibraryRefresh.isRefreshKey(Key.R, ctrl = true, meta = false, alt = true, mac = false))
    }

    // ── Direct source length (ForkDirectSourceCheck.tooShort) ─────────────

    private val flac = DesktopStreamFormat(codec = "flac")

    @Test
    fun `a 30 second lossless preview of a 200 second song is too short`() {
        val preview = 30L * 200_000 / 8
        assertTrue(ForkDirectSourceCheck.tooShort(preview, flac, expectedSec = 200, slackSec = 5))
    }

    @Test
    fun `the whole lossless file is not`() {
        val whole = 200L * 200_000 / 8
        assertFalse(ForkDirectSourceCheck.tooShort(whole, flac, expectedSec = 200, slackSec = 5))
    }

    @Test
    fun `lossy files are judged at 64 kbps at most, a stated lower bitrate is believed`() {
        val at64For200s = 200L * 64_000 / 8
        assertFalse(ForkDirectSourceCheck.tooShort(at64For200s, DesktopStreamFormat(codec = "mp3", kbps = 320), 200, 5))
        assertFalse(ForkDirectSourceCheck.tooShort(at64For200s, DesktopStreamFormat(codec = "mp3"), 200, 5))
        val at32For200s = 200L * 32_000 / 8
        assertTrue(ForkDirectSourceCheck.tooShort(at32For200s, DesktopStreamFormat(codec = "mp3"), 200, 5))
        assertFalse(ForkDirectSourceCheck.tooShort(at32For200s, DesktopStreamFormat(codec = "mp3", kbps = 32), 200, 5))
    }

    @Test
    fun `the slack and missing numbers never call a file short`() {
        val at64For196s = 196L * 64_000 / 8
        assertFalse(ForkDirectSourceCheck.tooShort(at64For196s, DesktopStreamFormat(codec = "aac"), 200, 5))
        assertFalse(ForkDirectSourceCheck.tooShort(0, flac, 200, 5))
        assertFalse(ForkDirectSourceCheck.tooShort(1000, flac, 0, 5))

    }
}
