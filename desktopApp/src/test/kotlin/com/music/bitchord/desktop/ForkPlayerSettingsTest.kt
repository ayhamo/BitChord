package com.music.bitchord.desktop

import com.music.bitchord.data.settings.AudioQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: the shared player's settings on the PC (ForkPlayerSettings) and its quality-upgrade mark (ForkQualityUpgrade). */
class ForkPlayerSettingsTest {

    @Test
    fun aChosenTranslationLanguageIsSentAsChosen() {
        assertEquals("zh-TW", ForkPlayerSettings.translationTarget("zh-TW", "en"))
        assertEquals("pt-BR", ForkPlayerSettings.translationTarget("pt-BR", "de"))
    }

    @Test
    fun blankFollowsTheAppLanguageNotTheSystems() {
        assertEquals("de", ForkPlayerSettings.translationTarget("", "de"))
        assertEquals("pt", ForkPlayerSettings.translationTarget("", "pt-BR"))
        // Chinese keeps its script, as the shared player maps it.
        assertEquals("zh-TW", ForkPlayerSettings.translationTarget("", "zh-Hant"))
        assertEquals("zh-CN", ForkPlayerSettings.translationTarget("", "zh-Hans"))
        assertEquals("en", ForkPlayerSettings.translationTarget("", ""))
    }

    @Test
    fun theCeilingMapsRungForRung() {
        DesktopAudioQuality.entries.forEach {
            assertEquals(it.name, ForkPlayerSettings.audioQualityOf(it).name)
        }
        assertEquals(AudioQuality.HIGH, ForkPlayerSettings.audioQualityOf(DesktopAudioQuality.HIGH))
    }

    private val opus = DesktopStreamFormat(codec = "opus", kbps = 160, sampleRateHz = 48_000)
    private val flac = DesktopStreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16)

    private fun run(vararg states: Triple<String?, String?, DesktopStreamFormat?>): ForkQualityUpgrade.Mark =
        states.fold(ForkQualityUpgrade.Mark()) { mark, (song, source, format) ->
            ForkQualityUpgrade.step(mark, song, source, format)
        }

    @Test
    fun aSongOpenedOnALosslessCopyIsNotMarked() {
        assertFalse(run(Triple("a", null, null), Triple("a", "qobuz", flac)).upgraded)
    }

    @Test
    fun aBetterCopySwappedInMidSongIsMarked() {
        val mark = run(Triple("a", null, null), Triple("a", "youtube", opus), Triple("a", "qobuz", flac))
        assertTrue(mark.upgraded)
        // Read again (a recomposition), and with the measured format arriving: still marked.
        assertTrue(ForkQualityUpgrade.step(mark, "a", "qobuz", flac).upgraded)
        assertTrue(ForkQualityUpgrade.step(mark, "a", "qobuz", flac.copy(channels = 2)).upgraded)
    }

    @Test
    fun revertingAndUpgradingAgainThroughAReload() {
        val upgraded = run(Triple("a", "youtube", opus), Triple("a", "qobuz", flac))
        // "Revert to original" reloads: no source while it opens, then YouTube's.
        val reverted = run(Triple("a", "youtube", opus), Triple("a", "qobuz", flac), Triple("a", null, null), Triple("a", "youtube", opus))
        assertTrue(upgraded.upgraded)
        assertFalse(reverted.upgraded)
        assertTrue(ForkQualityUpgrade.step(ForkQualityUpgrade.step(reverted, "a", null, null), "a", "qobuz", flac).upgraded)
    }

    @Test
    fun theNextSongStartsUnmarked() {
        assertFalse(run(Triple("a", "youtube", opus), Triple("a", "qobuz", flac), Triple("b", "qobuz", flac)).upgraded)
    }

    @Test
    fun aSourceChangeThatIsNoBetterIsNotMarked() {
        // A fallback to another lossless copy of the same quality is not an upgrade.
        assertFalse(run(Triple("a", "qobuz", flac), Triple("a", "tidal", flac)).upgraded)
        assertFalse(run(Triple("a", "qobuz", flac), Triple("a", "youtube", opus)).upgraded)
    }
}
