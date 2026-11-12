package com.music.bitchord.desktop

import com.music.bitchord.desktop.ForkChannelLayout.BL
import com.music.bitchord.desktop.ForkChannelLayout.BR
import com.music.bitchord.desktop.ForkChannelLayout.FC
import com.music.bitchord.desktop.ForkChannelLayout.FL
import com.music.bitchord.desktop.ForkChannelLayout.FR
import com.music.bitchord.desktop.ForkChannelLayout.LFE
import com.music.bitchord.desktop.ForkChannelLayout.MIX_LEVEL
import com.music.bitchord.desktop.ForkChannelLayout.STEREO
import com.music.bitchord.desktop.ForkChannelLayout.SURROUND_5_1
import com.music.bitchord.desktop.ForkChannelLayout.SURROUND_7_1
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Multichannel output (ForkMultichannel): the decision, the channel maps, mixing decks of two layouts, the decoder's output. */
class ForkMultichannelAudioTest {

    private val scratch = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = scratch.forEach { it.delete() }

    private fun format(channels: Int, mask: Long = 0L) = DesktopPcmFormat(48_000, channels, 4, isFloat = true, forkChannelMask = mask)

    // ---- the decision (format fallback) ----

    @Test
    fun `off is stereo, whatever the device takes`() {
        val layout = ForkChannelLayout.decide(enabled = false, wasapi = true, mix = 8 to SURROUND_7_1, refusal = null)
        assertEquals(2, layout.channels)
        assertEquals(0L, layout.mask)
        assertFalse(layout.active)
        assertTrue("This output takes 7.1" in layout.reason, layout.reason)
    }

    @Test
    fun `on, a 7_1 mix format is sent as 7_1`() {
        val layout = ForkChannelLayout.decide(enabled = true, wasapi = true, mix = 8 to SURROUND_7_1, refusal = null)
        assertEquals(8, layout.channels)
        assertEquals(SURROUND_7_1, layout.mask)
        assertTrue(layout.active)
    }

    @Test
    fun `on, the device's own 5_1 mask is kept, and a missing one becomes Windows' 5_1`() {
        val back = FL or FR or FC or LFE or BL or BR
        assertEquals(back, ForkChannelLayout.decide(true, true, 6 to back, null).mask)
        assertEquals(SURROUND_5_1, ForkChannelLayout.decide(true, true, 6 to 0L, null).mask)
    }

    @Test
    fun `on, it falls back to stereo and says why`() {
        val noWasapi = ForkChannelLayout.decide(true, wasapi = false, mix = 8 to SURROUND_7_1, refusal = null)
        val stereoDevice = ForkChannelLayout.decide(true, true, 2 to STEREO, null)
        val unknown = ForkChannelLayout.decide(true, true, null, null)
        val refused = ForkChannelLayout.decide(true, true, 8 to SURROUND_7_1, "Starting WASAPI shared mode failed (0x88890008)")
        val odd = ForkChannelLayout.decide(true, true, 5 to 0L, null)
        val wide = ForkChannelLayout.decide(true, true, 12 to 0L, null)
        for (layout in listOf(noWasapi, stereoDevice, unknown, refused, odd, wide)) {
            assertEquals(2, layout.channels, layout.reason)
            assertTrue(layout.reason.startsWith("Folding to stereo"), layout.reason)
        }
        assertTrue("Dolby Atmos for Headphones" in stereoDevice.reason)
        assertTrue("0x88890008" in refused.reason)
    }

    @Test
    fun `on, a spatial renderer gets a 7_1 bed at its object rate, but never when off or refused`() {
        val spatial = ForkChannelLayout.decide(true, true, 2 to STEREO, null, spatialRate = 48_000)
        assertEquals(8, spatial.channels)
        assertEquals(SURROUND_7_1, spatial.mask)
        assertEquals(48_000, spatial.spatialRate)
        assertTrue("Spatial Sound" in spatial.reason, spatial.reason)
        assertEquals(2, ForkChannelLayout.decide(false, true, 2 to STEREO, null, spatialRate = 48_000).channels)
        assertEquals(2, ForkChannelLayout.decide(true, true, 2 to STEREO, "refused", spatialRate = 48_000).channels)
        assertEquals(0, ForkChannelLayout.decide(true, true, 8 to SURROUND_7_1, null).spatialRate)
    }

    @Test
    fun `a device mask is used only when it is sane`() {
        assertEquals(SURROUND_7_1, ForkChannelLayout.deviceMask(8, SURROUND_7_1))
        assertEquals(SURROUND_7_1, ForkChannelLayout.deviceMask(8, 0x3FL)) // 6 bits for 8 channels
        assertEquals(SURROUND_5_1, ForkChannelLayout.deviceMask(6, 1L shl 20 or 0x1FL)) // a height bit
        assertEquals(null, ForkChannelLayout.deviceMask(2, STEREO))
        assertEquals(null, ForkChannelLayout.deviceMask(9, 0x1FFL))
    }

    // ---- channel mapping (pass-through, padding, fold-down) ----

    @Test
    fun `stereo onto 7_1 lands on the front left and right only`() {
        val adapter = ForkChannelAdapter()
        val count = adapter.adapt(floatArrayOf(0.5f, -0.25f, 0.1f, 0.2f), 4, format(2), format(8, SURROUND_7_1))
        assertEquals(16, count)
        assertContentEquals(
            floatArrayOf(0.5f, -0.25f, 0f, 0f, 0f, 0f, 0f, 0f, 0.1f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0f),
            adapter.output.copyOf(count),
        )
    }

    @Test
    fun `5_1 onto 7_1 keeps every speaker where it is`() {
        // FL FR FC LFE SL SR → FL FR FC LFE BL BR SL SR
        val adapter = ForkChannelAdapter()
        val count = adapter.adapt(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 6, format(6, SURROUND_5_1), format(8, SURROUND_7_1))
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 0f, 0f, 5f, 6f), adapter.output.copyOf(count))
        // 5.1 with back speakers: BL BR stay backs
        val back = FL or FR or FC or LFE or BL or BR
        val again = adapter.adapt(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 6, format(6, back), format(8, SURROUND_7_1))
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 0f, 0f), adapter.output.copyOf(again))
    }

    @Test
    fun `7_1 onto Windows 5_1 puts the backs on the sides`() {
        val adapter = ForkChannelAdapter()
        val count = adapter.adapt(floatArrayOf(1f, 2f, 3f, 4f, 0.5f, 0.25f, 5f, 6f), 8, format(8, SURROUND_7_1), format(6, SURROUND_5_1))
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5.5f, 6.25f), adapter.output.copyOf(count))
    }

    @Test
    fun `5_1 folds to stereo as swresample does, LFE dropped`() {
        val adapter = ForkChannelAdapter()
        val count = adapter.adapt(floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f, 0.4f, 0.5f), 6, format(6, SURROUND_5_1), format(2))
        assertEquals(2, count)
        assertEquals(0.1f + MIX_LEVEL * 0.3f + MIX_LEVEL * 0.4f, adapter.output[0], 1e-6f)
        assertEquals(0.2f + MIX_LEVEL * 0.3f + MIX_LEVEL * 0.5f, adapter.output[1], 1e-6f)
    }

    @Test
    fun `same layouts need no mapping`() {
        assertFalse(ForkChannelLayout.differs(format(2), format(2)))
        assertFalse(ForkChannelLayout.differs(format(8, SURROUND_7_1), format(8)))
        assertTrue(ForkChannelLayout.differs(format(2), format(8, SURROUND_7_1)))
        assertTrue(ForkChannelLayout.differs(format(6, SURROUND_5_1), format(6, FL or FR or FC or LFE or BL or BR)))
    }

    @Test
    fun `the decoder pads stereo sources and maps multichannel ones`() {
        val deck = format(8, SURROUND_7_1)
        assertEquals(2, ForkChannelLayout.decodeFormat(deck, sourceChannels = 2).channels)
        assertEquals(2, ForkChannelLayout.decodeFormat(deck, sourceChannels = 1).channels)
        assertEquals(deck, ForkChannelLayout.decodeFormat(deck, sourceChannels = 6))
        // A stereo deck is left exactly as upstream asked for it.
        assertEquals(format(2), ForkChannelLayout.decodeFormat(format(2), sourceChannels = 6))
    }

    // ---- mixing a stereo deck with a 6-channel one ----

    @Test
    fun `a stereo deck and a 5_1 deck mix on a 5_1 output without garbling`() {
        val out = format(6, SURROUND_5_1)
        val stereoDeck = ForkChannelAdapter()
        val frames = 64
        val stereo = FloatArray(frames * 2) { if (it % 2 == 0) 0.5f else -0.5f }
        val surround = FloatArray(frames * 6) { (it % 6 + 1) * 0.1f }
        // As readSource does: the 5.1 deck already matches the output, the stereo one is mapped.
        assertFalse(ForkChannelLayout.differs(out, out))
        assertTrue(ForkChannelLayout.differs(format(2), out))
        val a = stereoDeck.adapt(stereo, stereo.size, format(2), out)
        assertEquals(surround.size, a) // the same frames from each deck, so a crossfade lines up sample for sample
        val mixed = FloatArray(a) { stereoDeck.output[it] * 0.5f + surround[it] * 0.5f }
        for (frame in 0 until frames) {
            val at = frame * 6
            assertEquals(0.25f + 0.05f, mixed[at], 1e-6f) // FL: both decks
            assertEquals(-0.25f + 0.1f, mixed[at + 1], 1e-6f) // FR: both decks
            for (ch in 2 until 6) assertEquals((ch + 1) * 0.05f, mixed[at + ch], 1e-6f) // the rest: the 5.1 deck only
        }
    }

    @Test
    fun `a 5_1 deck reaching a stereo output is folded, not read as six interleaved stereo frames`() {
        val adapter = ForkChannelAdapter()
        val count = adapter.adapt(FloatArray(6 * 10) { 0.1f }, 60, format(6, SURROUND_5_1), format(2))
        assertEquals(20, count) // ten frames in, ten out
    }

    @Test
    fun `meters read the stereo fold of a multichannel block and stereo as it is`() {
        val view = ForkChannelAdapter()
        val stereo = floatArrayOf(0.1f, 0.2f)
        assertSame(stereo, view.stereoView(stereo, 2, format(2)))
        assertEquals(2, view.viewCount)
        val padded = floatArrayOf(0.1f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0f)
        val folded = view.stereoView(padded, 8, format(8, SURROUND_7_1))
        assertEquals(2, view.viewCount)
        assertContentEquals(floatArrayOf(0.1f, 0.2f), folded.copyOf(2))
    }

    // ---- the pipeline panel's words ----

    @Test
    fun `the channel path says where channels go`() {
        assertEquals("Stereo throughout", ForkChannelLayout.path(2, 2, 2, 2, 2))
        assertEquals("5.1 → Stereo (folded down)", ForkChannelLayout.path(6, 2, 2, 2, null))
        assertEquals("Stereo → 7.1 (stereo on the front left/right)", ForkChannelLayout.path(2, 8, 8, 8, 8))
        assertEquals("5.1 → 7.1", ForkChannelLayout.path(6, 8, 8, 8, 8))
        assertEquals("5.1 → Stereo (folded down)", ForkChannelLayout.path(6, 6, 6, 2, 2))
        assertEquals("7.1 throughout (Windows mixes it to stereo)", ForkChannelLayout.path(8, 8, 8, 8, 2))
        assertEquals(null, ForkChannelLayout.path(null, null, null, null, null))
    }

    @Test
    fun `layout names`() {
        assertEquals("5.1", ForkChannelLayout.name(6, SURROUND_5_1))
        assertEquals("7.1", ForkChannelLayout.name(8))
        assertEquals("Stereo", ForkChannelLayout.name(2))
        assertEquals("4 ch", ForkChannelLayout.name(4))
    }

    // ---- through FFmpeg: what the decoder hands the engine ----

    @Test
    fun `FFmpeg places a 5_1 file on a 7_1 deck speaker for speaker`() {
        val values = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val frame = firstFrame(wav(6, SURROUND_5_1, values), format(8, SURROUND_7_1))
        assertEquals(8, frame.size)
        val expected = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0f, 0f, 0.5f, 0.6f)
        for (i in expected.indices) assertEquals(expected[i], frame[i], 1e-3f, "channel $i of ${frame.toList()}")
    }

    @Test
    fun `a stereo file on a 7_1 deck is padded onto the front pair`() {
        val frame = firstFrame(wav(2, STEREO, floatArrayOf(0.25f, -0.5f)), format(8, SURROUND_7_1))
        val expected = floatArrayOf(0.25f, -0.5f, 0f, 0f, 0f, 0f, 0f, 0f)
        for (i in expected.indices) assertEquals(expected[i], frame[i], 1e-3f, "channel $i of ${frame.toList()}")
    }

    @Test
    fun `a 5_1 file on a stereo deck is FFmpeg's fold, which the app's own fold matches`() {
        val values = floatArrayOf(0.1f, 0.2f, 0.15f, 0.4f, 0.05f, 0.1f)
        val frame = firstFrame(wav(6, SURROUND_5_1, values), format(2))
        assertEquals(2, frame.size)
        val app = ForkChannelAdapter()
        app.adapt(values, 6, format(6, SURROUND_5_1), format(2))
        // ForkDownmixMakeup may lift FFmpeg's fold with the Atmos setting on; compare the ratio of the two sides then.
        val gain = frame[0] / app.output[0]
        assertTrue(abs(gain - 1f) < 1e-2f || abs(gain - ForkDownmixMakeup.GAIN) < 2e-2f, "FFmpeg ${frame.toList()} vs app ${app.output.copyOf(2).toList()}")
        assertEquals(app.output[1] * gain, frame[1], 2e-3f)
    }

    /** The first decoded frame of [file] on a deck of [deck]. */
    private fun firstFrame(file: File, deck: DesktopPcmFormat): FloatArray {
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(url = file.absolutePath, requested = deck).getOrThrow()
            assertEquals(deck.channels, decoder.outputFormat.channels)
            val block = assertNotNull(decoder.readSamples())
            assertTrue(decoder.sampleCount >= deck.channels)
            // A few frames in, clear of any edge the resampler might shape.
            val at = deck.channels * 8
            return block.copyOfRange(at, at + deck.channels)
        } finally {
            decoder.close()
        }
    }

    /** A WAVE_FORMAT_EXTENSIBLE file of [channels] holding [values] in every frame, so FFmpeg reads the speaker [mask]. */
    private fun wav(channels: Int, mask: Long, values: FloatArray, frames: Int = 4_800, rate: Int = 48_000): File {
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { values.forEach { data.putShort((it * 32_767f).toInt().toShort()) } }
        val fmt = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0xFFFE.toShort())
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
            putShort(22)
            putShort(16)
            putInt(mask.toInt())
            put(byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        }
        val body = ByteArrayOutputStream()
        fun chunk(id: String, bytes: ByteArray) {
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            body.write(bytes)
        }
        chunk("fmt ", fmt.array())
        chunk("data", data.array())
        val riff = ByteArrayOutputStream()
        riff.write("RIFF".toByteArray(Charsets.US_ASCII))
        riff.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + body.size()).array())
        riff.write("WAVE".toByteArray(Charsets.US_ASCII))
        riff.write(body.toByteArray())
        return File.createTempFile("bitchord-multichannel", ".wav").also {
            scratch += it
            it.writeBytes(riff.toByteArray())
        }
    }
}
