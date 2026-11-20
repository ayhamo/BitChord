package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ForkAddonFloor] on BitChord's own [StreamFormat] (the rulebook's cases run in [ForkRulebookTest]),
 * and the read-ahead window [ForkReadAheadWindow] both apps hold the next song's `/stream` to.
 */
class ForkAddonFloorTest {

    @Test
    fun aStreamFormatReadsAsTheRulebookReadsAStream() {
        assertEquals(0, ForkAddonFloor.rankOf(StreamFormat(codec = "aac", kbps = 320)))
        assertEquals(1, ForkAddonFloor.rankOf(StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)))
        assertEquals(1, ForkAddonFloor.rankOf(StreamFormat(codec = "flac"))) // depth and rate unstated: CD
        assertEquals(2, ForkAddonFloor.rankOf(StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 48_000)))
        assertEquals(2, ForkAddonFloor.rankOf(StreamFormat(codec = "alac", bitDepth = 16, sampleRateHz = 96_000)))
        assertEquals(0, ForkAddonFloor.rankOf(StreamFormat())) // nothing stated: lossy
    }

    @Test
    fun theFloorOnBitChordsFormats() {
        assertTrue(ForkAddonFloor.passesFloor(StreamFormat(codec = "flac"), 256))
        assertTrue(ForkAddonFloor.passesFloor(StreamFormat(codec = "aac", kbps = 256), null)) // 160 assumed
        assertFalse(ForkAddonFloor.passesFloor(StreamFormat(codec = "aac", kbps = 255), null))
        assertFalse(ForkAddonFloor.passesFloor(StreamFormat(codec = "mp3", kbps = 320), 256))
        // An inferred figure (the answer said HIGH) is read like a stated one.
        assertTrue(ForkAddonFloor.passesFloor(StreamFormat(codec = "mp3", kbps = 320, kbpsInferred = true), 160))
        assertFalse(ForkAddonFloor.passesFloor(StreamFormat(codec = "opus"), 160))
        // Atmos is the mix the listener chose, not a rung (phone and BitChord).
        assertTrue(ForkAddonFloor.passesFloor(StreamFormat(codec = "eac3-joc", kbps = 768), 160))
        assertNull(ForkAddonFloor.refusal(StreamFormat(codec = "eac3-joc"), 160))
        assertEquals("lossy, bitrate unknown", ForkAddonFloor.refusal(StreamFormat(codec = "opus"), 160))
    }

    @Test
    fun thePassTakesOnlyWhatItAskedFor() {
        val lossless = ForkAddonFloor.Pass(aboveOnly = false, minRank = 1)
        val hiRes = ForkAddonFloor.Pass(aboveOnly = true, minRank = 2)
        val mp3 = StreamFormat(codec = "mp3", kbps = 320)
        val cd = StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)
        val hr = StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 96_000)
        assertFalse(ForkAddonFloor.passAccepts(lossless, StreamFormat(codec = "aac", kbps = 512), mp3)) // lossy, however high
        assertTrue(ForkAddonFloor.passAccepts(lossless, cd, mp3))
        assertFalse(ForkAddonFloor.passAccepts(hiRes, cd, cd)) // the same rank is no upgrade
        assertTrue(ForkAddonFloor.passAccepts(hiRes, hr, cd))
        assertFalse(ForkAddonFloor.passAccepts(lossless, hr, StreamFormat(codec = "eac3-joc"))) // never away from Atmos
    }

    @Test
    fun theReadAheadWindowIs25sBeforeTheTransition() {
        assertEquals(25_000L, ForkReadAheadWindow.LEAD_MS)
        assertTrue(ForkReadAheadWindow.due(ForkReadAheadWindow.remainingMs(boundaryMs = 200_000, positionMs = 175_000)))
        assertFalse(ForkReadAheadWindow.due(ForkReadAheadWindow.remainingMs(boundaryMs = 200_000, positionMs = 174_999)))
        // The planned mix-out, not the song's end, is the boundary the caller passes.
        assertFalse(ForkReadAheadWindow.due(ForkReadAheadWindow.remainingMs(boundaryMs = 180_000, positionMs = 150_000)))
        // Real time: at double speed 40 s of song is 20 s away.
        assertTrue(ForkReadAheadWindow.due(ForkReadAheadWindow.remainingMs(boundaryMs = 200_000, positionMs = 160_000, speed = 2f)))
        assertFalse(ForkReadAheadWindow.due(ForkReadAheadWindow.remainingMs(boundaryMs = 200_000, positionMs = 190_000, speed = 0.1f)))
    }

    @Test
    fun onlyThePlayingSongAndTheOpenedNextOneAreAdmitted() {
        var open = ForkReadAheadWindow.Open().playing(setOf("a"))
        assertTrue(open.admits("a"))
        assertFalse(open.admits("b")) // the next song, prepared early: held
        assertFalse(open.admits("c")) // a song skipped past: never asked
        open = open.opening("b")
        assertTrue(open.admits("b"))
        open = open.playing(setOf("a", "b")) // blending in
        assertTrue(open.admits("b"))
        open = open.playing(setOf("b"))
        assertTrue(open.admits("b"))
        assertFalse(open.admits("a"))
        // A skip away before the window: the old next one is held again.
        open = ForkReadAheadWindow.Open(setOf("a"), next = "b").playing(setOf("x"))
        assertFalse(open.admits("b"))
        assertEquals(open, open.opening("x")) // the playing song is already in
    }
}
