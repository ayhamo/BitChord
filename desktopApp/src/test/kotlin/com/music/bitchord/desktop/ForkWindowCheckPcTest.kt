package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The PC's window check plumbing: the offset map on a deck's source, and the fake-lossless refusal. */
class ForkWindowCheckPcTest {

    private class Recorder : DesktopSampleSource {
        val seeks = mutableListOf<Long>()
        override fun readSamples(): FloatArray? = null
        override val sampleCount: Int = 0
        override fun seek(micros: Long): Boolean { seeks += micros; return true }
        override val forkLandedUs: Long? get() = seeks.lastOrNull()
        override fun close() {}
    }

    @Test
    fun aCopyWithoutAnOffsetIsReadAsItIs() {
        val source = Recorder()
        assertSame(source, ForkWindowCheckPc.onYouTubeClock(source, "https://example.test/none.flac"))
    }

    @Test
    fun aMeasuredCopyIsReadOnYouTubesClock() {
        ForkWindowCheckPc.noteOffset("https://example.test/poker.flac", -117_200)
        val source = Recorder()
        val mapped = ForkWindowCheckPc.onYouTubeClock(source, "https://example.test/poker.flac")
        mapped.seek(60_000_000)
        assertEquals(60_000_000 - 117_200, source.seeks.last()) // addon time = YouTube time + offset
        assertEquals(60_000_000, mapped.forkLandedUs) // and back on YouTube's clock
        mapped.seek(50_000)
        assertEquals(0, source.seeks.last()) // before the file's start: its start
    }

    @Test
    fun aLosslessPromiseTheDecoderBreaksIsRefused() {
        val song = Song("oG-4Uvhm4lI", "Poker Face", "Lady Gaga", null)
        val flac = DesktopStream(url = "https://example.test/a", format = DesktopStreamFormat(codec = "flac"))
        assertTrue(ForkWindowCheckPc.fakeLossless(song, flac, DesktopStreamFormat(codec = "aac")))
        assertFalse(ForkWindowCheckPc.fakeLossless(song, flac, DesktopStreamFormat(codec = "flac")))
        assertFalse(ForkWindowCheckPc.fakeLossless(song, flac, null))
        val mp3 = DesktopStream(url = "https://example.test/b", format = DesktopStreamFormat(codec = "mp3", kbps = 320))
        assertFalse(ForkWindowCheckPc.fakeLossless(song, mp3, DesktopStreamFormat(codec = "mp3")))
    }
}
