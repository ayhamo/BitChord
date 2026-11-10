package com.music.bitchord.data.download

import com.music.bitchord.data.download.ForkAudioSniff.Container
import com.music.bitchord.data.download.ForkAudioSniff.Probe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The PC's copy of the phone's download sniffing (be8c5d66); the same cases as the phone's `DownloadStoreTest`. */
class ForkAudioSniffTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun sniffed(head: ByteArray, contentType: String = "", stated: String? = null, total: Long? = null) =
        ForkAudioSniff.sniff(head, contentType, stated, total)

    private fun containerOf(head: ByteArray, stated: String? = null) = (sniffed(head, stated = stated) as? Probe.File)?.container

    @Test
    fun `a file is named for what its opening bytes are`() {
        assertEquals(Container.FLAC, containerOf("fLaC\u0000\u0000".toByteArray()))
        assertEquals(Container.MP4, containerOf("\u0000\u0000\u0000 ftypM4A ".toByteArray()))
        assertEquals(Container.WAV, containerOf("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()))
        assertEquals(Container.OGG, containerOf("OggS\u0000\u0002".toByteArray()))
        assertEquals(Container.WEBM, containerOf(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01)))
        assertEquals(Container.ADTS, containerOf(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals(Container.MP3, containerOf(bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals(Container.MP3, containerOf("ID3\u0004\u0000".toByteArray()))
        // Behind an ID3 tag only the label can tell: AAC gets the benefit of the doubt.
        assertEquals(Container.ADTS, containerOf("ID3\u0004\u0000".toByteArray(), stated = "aac"))
        assertEquals("m4a", Container.MP4.extension)
        assertEquals("aac", Container.ADTS.extension)
    }

    @Test
    fun `the label does not decide the container`() {
        // An addon's "flac" that is an MP3, and a "mp3" that is a FLAC.
        assertEquals(Container.MP3, containerOf(bytes(0xFF, 0xFB, 0x90, 0x64), stated = "flac"))
        assertEquals(Container.FLAC, containerOf("fLaC".toByteArray(), stated = "mp3"))
    }

    /** The reference addon serves HLS from an extensionless `/dash/{id}` and calls it FLAC. */
    @Test
    fun `a manifest is recognised whatever its URL or label says`() {
        assertEquals(Probe.Manifest(dash = false), sniffed("#EXTM3U\n#EXT-X-VERSION:7\n".toByteArray(), stated = "flac"))
        assertEquals(Probe.Manifest(dash = false), sniffed(bytes(0xEF, 0xBB, 0xBF) + "\n#EXTM3U\n".toByteArray()))
        assertEquals(Probe.Manifest(dash = true), sniffed("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPD xmlns=".toByteArray()))
        assertEquals(Probe.Manifest(dash = false), sniffed("garbage".toByteArray(), contentType = "application/vnd.apple.mpegurl"))
        assertEquals(Probe.Manifest(dash = true), sniffed("garbage".toByteArray(), contentType = "application/dash+xml"))
    }

    @Test
    fun `bytes that are no audio container are not kept`() {
        assertNull(sniffed("<html><body>Not found".toByteArray()))
        assertNull(sniffed(ByteArray(0)))
        assertNull(sniffed("FORM\u0000\u0000\u0000\u0000AIFF".toByteArray()))
        // 0xFF followed by a byte that is neither ADTS nor MPEG audio sync.
        assertNull(sniffed(bytes(0xFF, 0x00)))
    }

    @Test
    fun `the file length travels with the container`() {
        assertEquals(Probe.File(Container.FLAC, 30_000_000), sniffed("fLaC".toByteArray(), total = 30_000_000))
    }

    @Test
    fun `an unstated bitrate is measured from the file's length over the track's`() {
        // 320 kbps for 200 s is 8 MB.
        assertEquals(320, ForkAudioSniff.estimatedKbps(8_000_000, 200))
        assertNull(ForkAudioSniff.estimatedKbps(null, 200))
        assertNull(ForkAudioSniff.estimatedKbps(8_000_000, null))
        assertNull(ForkAudioSniff.estimatedKbps(8_000_000, 0))
        assertNull(ForkAudioSniff.estimatedKbps(10, 200))
    }

    @Test
    fun `the whole length is read from a 206's range or a 200's length`() {
        assertEquals(1234L, ForkAudioSniff.totalBytes(206, "bytes 0-511/1234", null))
        assertEquals(999L, ForkAudioSniff.totalBytes(200, null, "999"))
        // A 206 whose total is unknown ("*") says nothing, and a 206's Content-Length is the window's.
        assertNull(ForkAudioSniff.totalBytes(206, "bytes 0-511/*", "512"))
        assertNull(ForkAudioSniff.totalBytes(200, null, "0"))
    }
}
