package com.music.bitchord.fork

import androidx.media3.common.MimeTypes
import com.music.bitchord.fork.ForkUnplayableSkip.Notice
import com.music.bitchord.fork.ForkUnplayableSkip.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: the decisions behind skipping songs no decoder here can play ([ForkUnplayableSkip.step]). */
class ForkUnplayableSkipTest {

    private fun step(state: State, id: String, playWhenReady: Boolean = true, hasNext: Boolean = true, count: Int = 10, next: String? = "next") =
        ForkUnplayableSkip.step(state, id, playWhenReady, hasNext, count) { next }

    @Test
    fun `the first unplayable song is skipped to the next, with its own toast`() {
        val s = step(State(), "a", next = "b")!!
        assertTrue(s.skip)
        assertEquals(Notice.SKIPPED, s.notice)
        assertEquals(State(run = 1, lastSkippedId = "a", expectedId = "b"), s.state)
    }

    @Test
    fun `the song a skip went to carries the run on, and the toast counts them`() {
        val first = step(State(), "a", next = "b")!!
        val second = step(first.state, "b", next = "c")!!
        assertEquals(2, second.state.run)
        assertEquals(Notice.SKIPPED_MANY, second.notice)
    }

    @Test
    fun `any other song starts a new run`() {
        // Seen 2026-11-06: Trilogy after the Starboy run paused on its first song ("run 22 of 11").
        val stale = State(run = 21, lastSkippedId = "x", expectedId = null)
        val s = step(stale, "trilogy-1", next = "trilogy-2")!!
        assertEquals(1, s.state.run)
        assertTrue(s.skip)
        assertEquals(Notice.SKIPPED, s.notice)
    }

    @Test
    fun `a queue with no next song pauses`() {
        val s = step(State(), "last", hasNext = false, next = "never")!!
        assertFalse(s.skip)
        assertEquals(Notice.STOPPED, s.notice)
        assertNull(s.state.expectedId)
    }

    @Test
    fun `as many skips in a row as the queue has songs pauses, repeat included`() {
        var state = State()
        var last: ForkUnplayableSkip.Step? = null
        val ids = listOf("a", "b", "c")
        for ((i, id) in ids.withIndex()) {
            last = step(state, id, count = 3, next = ids.getOrNull(i + 1) ?: "a")!!
            state = last.state
        }
        assertFalse(last!!.skip)
        assertEquals(Notice.NONE_LEFT, last.notice)
        assertEquals(3, last.state.run)
    }

    @Test
    fun `the song a run paused on is left alone until it is played again`() {
        val paused = State(run = 3, lastSkippedId = "c", expectedId = null)
        assertNull(step(paused, "c", playWhenReady = false))
        val pressedPlay = step(paused, "c", playWhenReady = true, hasNext = false)!!
        assertFalse(pressedPlay.skip)
        assertEquals(Notice.STOPPED, pressedPlay.notice)
        assertEquals(1, pressedPlay.state.run)
    }

    @Test
    fun `formats get short names, unknown ones their subtype`() {
        assertEquals("Dolby Atmos (E-AC-3)", ForkUnplayableSkip.describe(MimeTypes.AUDIO_E_AC3_JOC, null))
        assertEquals("Dolby Digital Plus", ForkUnplayableSkip.describe(MimeTypes.AUDIO_E_AC3, null))
        assertEquals("DTS", ForkUnplayableSkip.describe(MimeTypes.AUDIO_DTS_X, null))
        assertEquals("MPEG-H", ForkUnplayableSkip.describe(MimeTypes.AUDIO_MPEGH_MHM1, null))
        assertEquals("ALAC", ForkUnplayableSkip.describe("audio/alac", null))
        assertEquals("ec+3", ForkUnplayableSkip.describe(null, "ec+3"))
        assertEquals("this format", ForkUnplayableSkip.describe(null, null))
    }
}
