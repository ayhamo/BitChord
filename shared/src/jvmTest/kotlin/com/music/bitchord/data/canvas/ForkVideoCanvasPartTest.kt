package com.music.bitchord.data.canvas

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Fork: the music video loop's `.part` file is written streamed and capped (audit 2026-11-26, [ForkVideoCanvas.writePart]). */
class ForkVideoCanvasPartTest {

    private fun dir(): File = Files.createTempDirectory("fork-video-part").toFile().apply { deleteOnExit() }

    @Test
    fun theInitSegmentThenTheFragments() {
        val raw = File(dir(), "a.mp4.part")
        ForkVideoCanvas.writePart(raw, byteArrayOf(1, 2, 3, 4), 1, ByteArrayInputStream(byteArrayOf(9, 8)), 10)
        assertContentEquals(byteArrayOf(1, 2, 9, 8), raw.readBytes())
        raw.delete()
    }

    @Test
    fun anInitRangePastTheHeadIsAnIOException() {
        val raw = File(dir(), "b.mp4.part")
        assertFailsWith<IOException> { ForkVideoCanvas.writePart(raw, byteArrayOf(1, 2), 2, ByteArrayInputStream(byteArrayOf(9)), 10) }
        assertFailsWith<IOException> { ForkVideoCanvas.writePart(raw, byteArrayOf(1, 2), -1, ByteArrayInputStream(byteArrayOf(9)), 10) }
        assertFalse(raw.exists())
    }

    @Test
    fun aBodyOverTheCapIsAnIOExceptionAndNoPartIsLeft() {
        val raw = File(dir(), "c.mp4.part")
        // an endless body: the cap must stop it, not the end of the stream
        val endless = object : InputStream() { override fun read(): Int = 7 }
        assertFailsWith<IOException> { ForkVideoCanvas.writePart(raw, byteArrayOf(1, 2), 1, endless, 1_000_000) }
        assertFalse(raw.exists())
    }
}
