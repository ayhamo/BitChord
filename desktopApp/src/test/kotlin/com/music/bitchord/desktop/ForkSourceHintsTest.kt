package com.music.bitchord.desktop

import com.music.bitchord.desktop.ForkSourceHints.Health
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: the phone's Sources lines on the PC (ForkSourceHints.kt). */
class ForkSourceHintsTest {

    private val addon = DesktopSourceConfig(id = "a", kind = DesktopSourceKind.ADDON, baseUrl = "https://addon.example")
    private val jio = DesktopSourceConfig(id = "jiosaavn", kind = DesktopSourceKind.JIOSAAVN)
    private val youtube = DesktopSourceConfig(id = "youtube", kind = DesktopSourceKind.YOUTUBE)

    @Test
    fun `an unreachable source says so with its reason`() {
        assertEquals("Can’t reach it right now · timeout", ForkSourceHints.statusLine(addon, Health.Unreachable("timeout")))
    }

    @Test
    fun `a rejected source shows its reason and is the only red one`() {
        assertEquals("No manifest at that URL", ForkSourceHints.statusLine(addon, Health.Rejected("No manifest at that URL")))
        assertTrue(ForkSourceHints.rejected(Health.Rejected("x"), skippedByQuality = false))
        assertFalse(ForkSourceHints.rejected(Health.Unreachable("x"), skippedByQuality = false))
        assertFalse(ForkSourceHints.rejected(Health.Rejected("x"), skippedByQuality = true))
    }

    @Test
    fun `an answering addon names itself then its kind's labels`() {
        assertEquals("Mine v2 · FLAC · Lossless · Hi-Res", ForkSourceHints.statusLine(addon, Health.Ok("Mine v2")))
        assertEquals("FLAC · Lossless · Hi-Res", ForkSourceHints.statusLine(addon, Health.Ok(null)))
    }

    @Test
    fun `before the probe answers, the editor's test and then checking`() {
        assertEquals("Addon ready", ForkSourceHints.statusLine(addon, null, tested = "Addon ready"))
        assertEquals("Checking…", ForkSourceHints.statusLine(addon, null))
    }

    @Test
    fun `JioSaavn carries the mismatch warning`() {
        assertEquals("May match the wrong version of a song. Enable at your own risk.", ForkSourceHints.statusLine(jio, null))
    }

    @Test
    fun `a source the ceiling leaves out says so first`() {
        val line = ForkSourceHints.line(addon, Health.Ok("Mine"), null, DesktopAudioQuality.HIGH, skippedByQuality = true)
        assertEquals("Not used on this computer · quality is set to High", line)
    }

    @Test
    fun `the footer warns when nothing switched on can serve lossless`() {
        val footer = ForkSourceHints.footer(listOf(jio, youtube), DesktopAudioQuality.LOSSLESS)
        assertTrue(footer.endsWith("No enabled source can provide lossless audio. Add an addon to play lossless files without transcoding."))
        val withAddon = ForkSourceHints.footer(listOf(addon, jio, youtube), DesktopAudioQuality.LOSSLESS)
        assertFalse(withAddon.contains("No enabled source"))
        val switchedOff = ForkSourceHints.footer(listOf(addon.copy(enabled = false), youtube), DesktopAudioQuality.LOSSLESS)
        assertTrue(switchedOff.contains("No enabled source"))
    }

    @Test
    fun `below lossless the footer speaks of the cap instead`() {
        val footer = ForkSourceHints.footer(listOf(jio, youtube), DesktopAudioQuality.HIGH)
        assertTrue(footer.contains("transcoded"))
        assertFalse(footer.contains("No enabled source"))
    }

    @Test
    fun `failures split as the phone splits them`() {
        assertTrue(ForkSourceHints.addonFailure(DesktopAddonException("No manifest at that URL")) is Health.Rejected)
        assertTrue(ForkSourceHints.addonFailure(DesktopAddonUnavailable("HTTP 503")) is Health.Unreachable)
        assertTrue(ForkSourceHints.addonFailure(java.io.IOException("Connection refused")) is Health.Unreachable)
        assertTrue(ForkSourceHints.moduleFailure(IllegalStateException(ForkSourceHints.NO_MODULES)) is Health.Rejected)
        assertTrue(ForkSourceHints.moduleFailure(IllegalStateException("Module index returned HTTP 502")) is Health.Unreachable)
    }

    @Test
    fun `only sources with a server are probed, and only their addresses key it`() {
        val key = ForkSourceHints.probeKey(listOf(addon, jio, youtube))
        assertEquals("a@https://addon.example", key)
        assertEquals(key, ForkSourceHints.probeKey(listOf(addon.copy(enabled = false), jio)))
    }
}
