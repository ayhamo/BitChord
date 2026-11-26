package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.ForkAddonCopyProgress
import com.music.bitchord.playback.smart.ForkAddonCopyProgress.Stage
import com.music.bitchord.playback.smart.ForkAnalysisProgress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The PC's hooks for the progress row's addon-copy line (editor.md §11 rule 6), the take-over's watch above all. */
class ForkAddonCopyPcTest {

    private val flac = DesktopStream(url = "https://addon/x.flac", format = DesktopStreamFormat(codec = "flac", bitDepth = 24), sourceId = "addon-1")
    private val ms = 1_000_000L

    @AfterTest
    fun reset() {
        ForkAddonCopyPc.reset()
        ForkAddonCopyProgress.end("song")
    }

    private fun line() = ForkAnalysisProgress.rowFor("song", null, false)?.text

    @Test
    fun aCopyCrossedInIsHeardOnceItIsNoLongerPending() {
        ForkAddonCopyPc.switching("song", flac)
        assertEquals("Switching to the lossless copy…", line())
        ForkAddonCopyPc.engineStep(null, null, null, nowNs = 0)
        assertEquals(Stage.SWITCHING, ForkAddonCopyProgress.stageOf("song"), "not handed to the audio thread yet: still opening")
        ForkAddonCopyPc.queued("song", nowNs = 0)
        ForkAddonCopyPc.opened("song")
        ForkAddonCopyPc.engineStep(null, null, "song", nowNs = 1 * ms)
        ForkAddonCopyPc.engineStep(null, "song", null, nowNs = 20 * ms)
        ForkAddonCopyPc.engineStep("song", null, null, nowNs = 1_200 * ms)
        ForkAddonCopyPc.engineStep("song", null, null, nowNs = 5_000 * ms)
        assertEquals("Switching to the lossless copy…", line(), "crossing in, however long it takes")
        ForkAddonCopyPc.engineStep(null, null, null, nowNs = 5_020 * ms)
        assertNull(line(), "crossed in: heard")
    }

    @Test
    fun aCopyNeverPendingEndsAfterTheDrain() {
        ForkAddonCopyPc.switching("song", flac)
        ForkAddonCopyPc.queued("song", nowNs = 0)
        ForkAddonCopyPc.engineStep(null, null, null, nowNs = 100 * ms)
        assertEquals(Stage.SWITCHING, ForkAddonCopyProgress.stageOf("song"))
        ForkAddonCopyPc.engineStep(null, null, null, nowNs = ForkAddonCopyPc.UNSEEN_MS * ms)
        assertNull(ForkAddonCopyProgress.stageOf("song"), "cut over, held through a mix, or closed by a transition")
    }

    @Test
    fun aCopyThatNeverReachedTheAudioThreadEndsWithItsOpen() {
        ForkAddonCopyPc.switching("song", flac)
        ForkAddonCopyPc.opened("song")
        assertNull(ForkAddonCopyProgress.stageOf("song"))
    }

    @Test
    fun youTubesOwnCopyIsNoAddonsCopy() {
        ForkAddonCopyPc.switching("song", flac.copy(sourceId = "youtube"))
        assertNull(ForkAddonCopyProgress.stageOf("song"))
    }

    @Test
    fun anAssembledRowGoesBackToAskingAndThePassLeavesTheTakeOver() {
        ForkAddonCopyPc.asking("song")
        ForkAddonCopyPc.fetching("song", flac.copy(format = DesktopStreamFormat(codec = "aac")))
        assertEquals("Getting the addon's copy…", line())
        ForkAddonCopyPc.assembled("song")
        assertEquals("Asking your addons…", line())
        ForkAddonCopyPc.asked("song")
        assertNull(line())
        ForkAddonCopyPc.liningUp("song", flac)
        assertEquals("Lining up the lossless copy with YouTube's…", line())
        ForkAddonCopyPc.switching("song", flac)
        ForkAddonCopyPc.passEnded("song")
        ForkAddonCopyPc.asked("song")
        assertEquals("Switching to the lossless copy…", line())
    }

    @Test
    fun losslessIsTheCodecOrABitDepth() {
        assertTrue(ForkAddonCopyPc.lossless(DesktopStreamFormat(codec = "pcm_s24le")))
        assertTrue(ForkAddonCopyPc.lossless(DesktopStreamFormat(codec = "audio/flac")))
        assertTrue(ForkAddonCopyPc.lossless(DesktopStreamFormat(bitDepth = 16)))
        assertFalse(ForkAddonCopyPc.lossless(DesktopStreamFormat(codec = "opus", kbps = 160)))
        assertFalse(ForkAddonCopyPc.lossless(null))
    }
}
