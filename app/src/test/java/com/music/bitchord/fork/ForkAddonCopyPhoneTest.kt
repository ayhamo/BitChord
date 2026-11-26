package com.music.bitchord.fork

import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.smart.ForkAddonCopyProgress
import com.music.bitchord.playback.smart.ForkAddonCopyProgress.Stage
import com.music.bitchord.playback.smart.ForkAnalysisProgress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's hooks for the progress row's addon-copy line (editor.md §11 rule 6). */
class ForkAddonCopyPhoneTest {

    @After
    fun reset() {
        ForkAddonCopyProgress.end("song")
    }

    @Test
    fun theLosslessCopyIsTheFormatsCodecOrBitDepth() {
        assertTrue(ForkAddonCopyPhone.lossless(StreamFormat(codec = "flac")))
        assertTrue(ForkAddonCopyPhone.lossless(StreamFormat(codec = "ape")))
        assertTrue(ForkAddonCopyPhone.lossless(StreamFormat(bitDepth = 24)))
        assertFalse(ForkAddonCopyPhone.lossless(StreamFormat(kbps = 320)))
        assertFalse(ForkAddonCopyPhone.lossless(StreamFormat(codec = "opus", kbps = 160)))
        assertFalse(ForkAddonCopyPhone.lossless(null))
    }

    @Test
    fun askingEndsWithItsCallButNotALaterStage(): Unit = runBlocking {
        val seen = ForkAddonCopyPhone.asking("song") { ForkAddonCopyProgress.stageOf("song") }
        assertEquals(Stage.ASKING, seen)
        assertNull(ForkAddonCopyProgress.stageOf("song"))
        runCatching { ForkAddonCopyPhone.asking("song") { error("the race threw") } }
        assertNull("a race that throws ends its line too", ForkAddonCopyProgress.stageOf("song"))
        ForkAddonCopyPhone.asking("song") { ForkAddonCopyPhone.fetching("song", StreamFormat(codec = "flac")) }
        assertEquals(Stage.FETCHING, ForkAddonCopyProgress.stageOf("song"))
    }

    @Test
    fun thePassEndingLeavesATakeOverRunning() {
        ForkAddonCopyPhone.liningUp("song", StreamFormat(codec = "flac"))
        ForkAddonCopyPhone.passEnded("song")
        assertNull("a refused copy's line goes with its pass", ForkAddonCopyProgress.stageOf("song"))
        ForkAddonCopyPhone.liningUp("song", StreamFormat(codec = "flac"))
        ForkAddonCopyPhone.switching("song")
        ForkAddonCopyPhone.passEnded("song")
        assertEquals("Switching to the lossless copy…", ForkAnalysisProgress.rowFor("song", null, false)?.text)
        ForkAddonCopyPhone.switched("song")
        assertNull(ForkAddonCopyProgress.stageOf("song"))
    }

    @Test
    fun aCutIsWatchedUntilTheSwappedCopyPlays() {
        assertFalse(ForkAddonCopyPhone.cutDone(sameSong = true, swapped = true, playing = false, elapsedMs = 400))
        assertTrue(ForkAddonCopyPhone.cutDone(sameSong = true, swapped = true, playing = true, elapsedMs = 900))
        assertTrue("the song moved on", ForkAddonCopyPhone.cutDone(sameSong = false, swapped = false, playing = true, elapsedMs = 0))
        assertFalse(ForkAddonCopyPhone.cutDone(sameSong = true, swapped = false, playing = true, elapsedMs = 500))
        assertTrue(
            "upstream's swap returned early",
            ForkAddonCopyPhone.cutDone(sameSong = true, swapped = false, playing = true, elapsedMs = ForkAddonCopyPhone.NO_SWAP_MS),
        )
        assertFalse(ForkAddonCopyPhone.cutDone(sameSong = true, swapped = true, playing = false, elapsedMs = ForkAddonCopyPhone.CUT_MAX_MS - 1))
        assertTrue(ForkAddonCopyPhone.cutDone(sameSong = true, swapped = true, playing = false, elapsedMs = ForkAddonCopyPhone.CUT_MAX_MS))
    }
}
