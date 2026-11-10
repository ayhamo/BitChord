package com.music.bitchord

import com.music.bitchord.data.download.ForkAudioSniff
import com.music.bitchord.download.Downloader
import com.music.bitchord.download.Downloads
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PC names its downloads with `ForkAudioSniff`, a copy of the phone's `Downloader.sniff`
 * (upstream be8c5d66) kept in shared code so the upstream file stays as it is. This feeds both the
 * same bytes: an upstream change to the phone's rules fails here, and the copy is brought along,
 * instead of the two apps naming the same file differently.
 */
class ForkAudioSniffDriftTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val heads: List<ByteArray> = listOf(
        "fLaC\u0000\u0000".toByteArray(),
        "\u0000\u0000\u0000 ftypM4A ".toByteArray(),
        "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(),
        "OggS\u0000\u0002".toByteArray(),
        bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01),
        bytes(0xFF, 0xF1, 0x50, 0x80),
        bytes(0xFF, 0xFB, 0x90, 0x64),
        bytes(0xFF, 0x00),
        "ID3\u0004\u0000".toByteArray(),
        "#EXTM3U\n".toByteArray(),
        bytes(0xEF, 0xBB, 0xBF) + "\n#EXTM3U\n".toByteArray(),
        "<?xml version=\"1.0\"?>\n<MPD xmlns=".toByteArray(),
        "<html>Not found".toByteArray(),
        "FORM\u0000\u0000\u0000\u0000AIFF".toByteArray(),
        ByteArray(0),
    )

    /** The phone's answer in the shared copy's terms, so the two can be compared as values. */
    private fun phone(head: ByteArray, type: String, stated: String?, total: Long?): String? =
        when (val probe = Downloader.sniff(head, type, stated, total)) {
            null -> null
            is Downloader.Probe.Manifest -> "manifest dash=${probe.dash}"
            is Downloader.Probe.File -> with(probe.container) { "file $name $extension $mimeType $codec ${probe.totalBytes}" }
        }

    private fun shared(head: ByteArray, type: String, stated: String?, total: Long?): String? =
        when (val probe = ForkAudioSniff.sniff(head, type, stated, total)) {
            null -> null
            is ForkAudioSniff.Probe.Manifest -> "manifest dash=${probe.dash}"
            is ForkAudioSniff.Probe.File -> with(probe.container) { "file $name $extension $mimeType $codec ${probe.totalBytes}" }
        }

    @Test
    fun `the PC's copy reads every opening as the phone does`() {
        for (head in heads) {
            for (type in listOf("", "audio/flac", "application/vnd.apple.mpegurl", "application/dash+xml")) {
                for (stated in listOf(null, "aac", "flac", "mp3")) {
                    assertEquals(
                        "head=${head.joinToString(",")} type=$type stated=$stated",
                        phone(head, type, stated, 1234),
                        shared(head, type, stated, 1234),
                    )
                }
            }
        }
    }

    @Test
    fun `the containers are the phone's`() {
        assertEquals(
            Downloader.Container.entries.map { "${it.name} ${it.extension} ${it.mimeType} ${it.codec}" },
            ForkAudioSniff.Container.entries.map { "${it.name} ${it.extension} ${it.mimeType} ${it.codec}" },
        )
    }

    @Test
    fun `an unstated bitrate is measured as the phone does`() {
        for (total in listOf(null, 10L, 8_000_000L, 120_000_000L)) {
            for (duration in listOf(null, 0, 1, 200, 3_600)) {
                assertEquals(Downloads.estimatedKbps(total, duration), ForkAudioSniff.estimatedKbps(total, duration))
            }
        }
    }
}
