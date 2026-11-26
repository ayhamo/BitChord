package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Morphe phone's beat spans and its Fold (yt music patches Analyzer.java BEAT_WINDOW_SEC, Fold, clip). */
class ForkBeatSpansTest {

    @Test
    fun oneFiftyNineSecondSpanAtEachEndAndAShortSongsHeadIsItsTail() {
        val (head, tail) = ForkBeatSpans.spans(200.0)
        assertEquals(0.0 to 59.0, head)
        assertEquals(141.0 to 200.0, tail)
        val (h, t) = ForkBeatSpans.spans(50.0)
        assertEquals(0.0 to 50.0, h)
        assertNull(t)
        assertNull(ForkBeatSpans.spans(59.0).second)
    }

    @Test
    fun theFoldAveragesTheChannelsAndAt441kTwoFramesAs16Bit() {
        val l = floatArrayOf(1f, 0.5f, -1f, 0f, 0.25f)
        val r = floatArrayOf(0f, 0.5f, -1f, 0f, 0.25f)
        val (mono, rate) = ForkBeatSpans.fold(l, r, 44_100)
        assertEquals(22_050, rate)
        assertEquals(2, mono.size) // the odd frame is dropped, as the phone's accumulator never fills
        assertEquals(ForkBeatSpans.clip(0.5f) / 32768f, mono[0]) // ((0.5 + 0.5) / 2)
        assertEquals(ForkBeatSpans.clip(-0.5f) / 32768f, mono[1])
        val (same, r24) = ForkBeatSpans.fold(floatArrayOf(0.5f), floatArrayOf(0.5f), 24_000)
        assertEquals(24_000, r24)
        assertEquals(ForkBeatSpans.clip(0.5f) / 32768f, same[0])
    }

    @Test
    fun theClipIsTheJavaRound() {
        assertEquals(32767, ForkBeatSpans.clip(1f).toInt())
        assertEquals(-32767, ForkBeatSpans.clip(-1f).toInt())
        assertEquals(-32768, ForkBeatSpans.clip(-2f).toInt())
        assertEquals(16384, ForkBeatSpans.clip(0.5f).toInt()) // 16383.5 rounds half up
    }
}
