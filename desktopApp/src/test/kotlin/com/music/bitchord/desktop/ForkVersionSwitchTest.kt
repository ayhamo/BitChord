package com.music.bitchord.desktop

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ForkVersionSwitchTest {

    private fun song(id: String, video: Boolean = false) = Song(videoId = id, title = id, artist = "A", thumbnailUrl = null, isVideo = video)

    @Test
    fun theClockMovesByTheShiftFromTheFrameHeard() {
        val playhead = DesktopPlayhead()
        // 1 ms of source per frame, from 10 s.
        playhead.reset(frame = 0, sourceUs = 10_000_000, usPerFrame = 1_000.0)
        playhead.forkShift(frame = 2_000, deltaUs = 3_250_000)
        assertEquals(11_000_000, playhead.peek(1_000)) // still the old version's time before it
        assertEquals(15_250_000, playhead.peek(2_000)) // 12 s + 3.25 s
        assertEquals(16_250_000, playhead.peek(3_000))
    }

    @Test
    fun aRateChangeAlreadyQueuedMovesWithTheShift() {
        val playhead = DesktopPlayhead()
        playhead.reset(frame = 0, sourceUs = 0, usPerFrame = 1_000.0)
        playhead.change(frame = 5_000, usPerFrame = 2_000.0) // 5 s in, twice as fast
        playhead.forkShift(frame = 2_000, deltaUs = -1_000_000)
        assertEquals(1_000_000, playhead.peek(2_000))
        assertEquals(4_000_000, playhead.peek(5_000))
        assertEquals(6_000_000, playhead.peek(6_000))
    }

    @Test
    fun noShiftLeavesTheClockAlone() {
        val playhead = DesktopPlayhead()
        playhead.reset(frame = 0, sourceUs = 500, usPerFrame = 10.0)
        playhead.forkShift(frame = 10, deltaUs = 0)
        assertEquals(600, playhead.peek(10))
    }

    @Test
    fun theQueueRowIsReplacedInPlace() {
        val video = song("vvvvvvvvvvv", video = true).copy(queueTier = QueueTier.USER_QUEUE, queueEntryId = "e1")
        val audio = song("aaaaaaaaaaa").copy(queueTier = QueueTier.USER_QUEUE, queueEntryId = "e1")
        val queue = DesktopQueue(listOf(song("x"), video, song("y")), index = 1)
        val switched = forkQueueWithVersion(queue, video, audio)
        assertEquals(listOf("x", "aaaaaaaaaaa", "y"), switched.songs.map { it.videoId })
        assertEquals(1, switched.index)
        assertEquals("e1", switched.current?.queueEntryId)
    }

    @Test
    fun aQueueThatMovedOnIsLeftAlone() {
        val video = song("vvvvvvvvvvv", video = true)
        val queue = DesktopQueue(listOf(video, song("y")), index = 1)
        assertSame(queue, forkQueueWithVersion(queue, video, song("aaaaaaaaaaa")))
    }

    @Test
    fun historyKeepsOneEntryForTheSong() {
        val video = song("v", video = true)
        val audio = song("a")
        val history = listOf(video, song("x"), audio)
        assertEquals(listOf("a", "x"), forkHistoryWithVersion(history, video, audio).map { it.videoId })
    }
}
