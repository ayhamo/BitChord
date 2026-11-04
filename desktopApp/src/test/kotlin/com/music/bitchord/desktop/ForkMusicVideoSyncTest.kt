package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.ForkVideoPacer.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkMusicVideoSyncTest {
    private val ms = 1_000_000L
    private val video = Song(videoId = "dQw4w9WgXcQ", title = "Song", artist = "Artist", thumbnailUrl = null, isVideo = true)

    private fun state(
        positionMs: Long,
        sampledAtMs: Long,
        playing: Boolean = true,
        seeks: Int = 0,
        awaiting: Boolean = false,
        song: Song? = video,
        durationMs: Long = 240_000,
    ) = DesktopPlaybackState(
        song = song,
        isPlaying = playing,
        positionMs = positionMs,
        positionSampledAtNanos = sampledAtMs * ms,
        seeks = seeks,
        awaitingAudio = awaiting,
        durationMs = durationMs,
    )

    // ---- the clock -------------------------------------------------------

    @Test
    fun `the clock runs on from the last reading at the listener's speed`() {
        val s = state(positionMs = 10_000, sampledAtMs = 1_000)
        assertEquals(10_100, ForkVideoClock.at(s, 1f, 1_100 * ms))
        assertEquals(10_200, ForkVideoClock.at(s, 2f, 1_100 * ms))
    }

    @Test
    fun `paused, awaiting a seek's audio, or never sampled, the clock stands`() {
        assertEquals(10_000, ForkVideoClock.at(state(10_000, 1_000, playing = false), 1f, 5_000 * ms))
        assertEquals(10_000, ForkVideoClock.at(state(10_000, 1_000, awaiting = true), 1f, 5_000 * ms))
        assertEquals(10_000, ForkVideoClock.at(state(10_000, 0), 1f, 5_000 * ms))
        assertFalse(ForkVideoClock.running(state(10_000, 1_000, awaiting = true)))
    }

    @Test
    fun `a stalled engine is not a clock running on, and the clock stops at the song's end`() {
        assertEquals(11_000, ForkVideoClock.at(state(10_000, 1_000), 1f, 60_000 * ms))
        assertEquals(240_000, ForkVideoClock.at(state(239_900, 1_000), 1f, 1_500 * ms))
    }

    // ---- frames against the clock ---------------------------------------

    @Test
    fun `a song from its start needs no seek, and frames wait for the audio`() {
        val p = ForkVideoPacer()
        assertNull(p.owedSeek(state(0, 0), 0, 1f, 0))
        assertEquals(Step.Show(0), p.decide(0, 0, running = true, speed = 1f, nowNs = 0))
        p.shown(0)
        assertEquals(Step.Show(50), p.decide(100, 50, running = true, speed = 1f, nowNs = 50 * ms))
        // At double speed the audio gets there in half the time.
        assertEquals(Step.Show(25), p.decide(100, 50, running = true, speed = 2f, nowNs = 50 * ms))
    }

    @Test
    fun `a little behind is shown at once, further behind is dropped unconverted`() {
        val p = ForkVideoPacer()
        p.owedSeek(state(0, 0), 0, 1f, 0)
        p.shown(0)
        assertEquals(Step.Show(0), p.decide(960, 1_000, running = true, speed = 1f, nowNs = 1_000 * ms))
        assertEquals(Step.Drop, p.decide(900, 1_000, running = true, speed = 1f, nowNs = 1_000 * ms))
    }

    @Test
    fun `a seek on the audio is followed, aimed a little ahead while playing and exactly while paused`() {
        val p = ForkVideoPacer()
        p.owedSeek(state(0, 0), 0, 1f, 0)
        assertNull(p.owedSeek(state(250, 250), 250, 1f, 250 * ms))
        assertEquals(60_300, p.owedSeek(state(60_000, 300, seeks = 1), 60_000, 1f, 300 * ms))
        val paused = state(90_000, 400, playing = false, seeks = 2)
        assertEquals(90_000, p.owedSeek(paused, 90_000, 1f, 400 * ms))
    }

    @Test
    fun `a song opened mid-way seeks once to where the audio is`() {
        val p = ForkVideoPacer()
        assertEquals(30_300, p.owedSeek(state(30_000, 0), 30_000, 1f, 0))
        assertNull(p.owedSeek(state(30_250, 250), 30_250, 1f, 250 * ms))
    }

    @Test
    fun `after a seek lands on an early keyframe the frames are decoded through, never sought again`() {
        val p = ForkVideoPacer()
        p.owedSeek(state(0, 0), 0, 1f, 0)
        p.shown(0)
        assertEquals(60_300, p.owedSeek(state(60_000, 1_000, seeks = 1), 60_000, 1f, 1_000 * ms))
        // The keyframe was four seconds before the target: 56 s, 56.033 s, ... all far behind.
        var pts = 56_000L
        var now = 1_000L
        var shown: Step? = null
        while (shown == null) {
            val clock = 60_000 + (now - 1_000)
            when (val step = p.decide(pts, clock, running = true, speed = 1f, nowNs = now * ms)) {
                is Step.Seek -> error("sought again at $pts")
                Step.Drop -> Unit
                is Step.Show -> shown = step
            }
            pts += 33
            now += 4 // decoding through runs far faster than real time
        }
        assertTrue(p.catching) // until a frame goes up
        p.shown(pts)
        assertFalse(p.catching)
    }

    @Test
    fun `a catch-up that takes too long may seek again`() {
        val p = ForkVideoPacer(catchUpLimitMs = 5_000)
        p.owedSeek(state(60_000, 0, seeks = 1), 60_000, 1f, 0)
        assertEquals(Step.Drop, p.decide(56_000, 60_000, running = true, speed = 1f, nowNs = 1_000 * ms))
        assertIs<Step.Seek>(p.decide(56_100, 66_000, running = true, speed = 1f, nowNs = 6_000 * ms))
    }

    @Test
    fun `steady playing far behind seeks ahead of the audio`() {
        val p = ForkVideoPacer()
        p.owedSeek(state(0, 0), 0, 1f, 0)
        p.shown(0)
        assertEquals(Step.Seek(12_300), p.decide(10_000, 12_000, running = true, speed = 1f, nowNs = 12_000 * ms))
    }

    @Test
    fun `the picture well ahead of the audio seeks back rather than freezing`() {
        val p = ForkVideoPacer()
        p.owedSeek(state(0, 0), 0, 1f, 0)
        p.shown(0)
        assertEquals(Step.Seek(20_300), p.decide(25_000, 20_000, running = true, speed = 1f, nowNs = 20_000 * ms))
        // Within a second ahead it only waits, at most that second.
        val q = ForkVideoPacer()
        q.owedSeek(state(0, 0), 0, 1f, 0)
        q.shown(0)
        assertEquals(Step.Show(900), q.decide(20_900, 20_000, running = true, speed = 1f, nowNs = 20_000 * ms))
    }

    @Test
    fun `a jump of the audio nothing counted is caught from the clock itself`() {
        val p = ForkVideoPacer()
        p.owedSeek(state(10_000, 0), 10_000, 1f, 0)
        // 250 ms later the clock is 5 s on, with the same seek count.
        assertEquals(15_300, p.owedSeek(state(15_000, 250), 15_000, 1f, 250 * ms))
        // Ordinary progress is not a jump, at any speed.
        assertNull(p.owedSeek(state(15_500, 500), 15_500, 2f, 500 * ms))
    }

    @Test
    fun `a new song is a fresh start even with the same seek count`() {
        val p = ForkVideoPacer()
        p.owedSeek(state(100_000, 0), 100_000, 1f, 0)
        val next = video.copy(videoId = "aaaaaaaaaaa")
        assertNull(p.owedSeek(state(200, 100, song = next), 200, 1f, 100 * ms))
    }

    @Test
    fun `paused, the frame at the clock is found exactly and shown`() {
        val p = ForkVideoPacer(frameMs = 33)
        p.owedSeek(state(45_000, 0, playing = false, seeks = 1), 45_000, 1f, 0)
        assertEquals(Step.Drop, p.decide(44_000, 45_000, running = false, speed = 1f, nowNs = 10 * ms))
        assertEquals(Step.Drop, p.decide(44_950, 45_000, running = false, speed = 1f, nowNs = 20 * ms))
        assertEquals(Step.Show(0), p.decide(44_990, 45_000, running = false, speed = 1f, nowNs = 30 * ms))
        p.shown(44_990)
        assertTrue(p.shownSinceSeek)
        // A frame way past a paused clock: seek back to it.
        assertEquals(Step.Seek(45_000), p.decide(47_000, 45_000, running = false, speed = 1f, nowNs = 40 * ms))
    }

    @Test
    fun `a machine dropping most frames is slow, and catch-up drops don't count`() {
        val p = ForkVideoPacer(slowWindowMs = 5_000)
        p.owedSeek(state(0, 0), 0, 1f, 0)
        p.shown(0)
        var now = 0L
        repeat(160) { i ->
            // Three late frames of every five.
            val pts = now - if (i % 5 < 3) 200 else 0
            p.decide(pts, now, running = true, speed = 1f, nowNs = now * ms)
            now += 33
        }
        assertTrue(p.slow)

        val q = ForkVideoPacer(slowWindowMs = 5_000)
        q.owedSeek(state(30_000, 0, seeks = 1), 30_000, 1f, 0)
        now = 0
        repeat(160) {
            q.decide(27_000 + now, 30_000 + now, running = true, speed = 1f, nowNs = now * ms)
            now += 20
        }
        assertFalse(q.slow)
    }

    @Test
    fun `stalls hand the sleeve back, but not while paused, at the end, or in a short catch-up`() {
        val p = ForkVideoPacer()
        p.owedSeek(state(0, 0), 0, 1f, 0)
        p.shown(10_000)
        assertFalse(p.stalled(12_000, running = true, ended = false, nowNs = 0))
        assertTrue(p.stalled(13_000, running = true, ended = false, nowNs = 0))
        assertFalse(p.stalled(13_000, running = false, ended = false, nowNs = 0))
        assertFalse(p.stalled(13_000, running = true, ended = true, nowNs = 0))
        p.owedSeek(state(90_000, 1_000, seeks = 1), 90_000, 1f, 1_000 * ms)
        assertFalse(p.stalled(90_300, running = true, ended = false, nowNs = 1_300 * ms))
        assertTrue(p.stalled(90_800, running = true, ended = false, nowNs = 1_800 * ms))
    }

    // ---- which songs, at what height ------------------------------------

    @Test
    fun `only a YouTube music video has a picture`() {
        assertTrue(ForkVideoRules.eligible(video))
        assertFalse(ForkVideoRules.eligible(null))
        assertFalse(ForkVideoRules.eligible(video.copy(isVideo = false)))
        assertFalse(ForkVideoRules.eligible(video.copy(localUri = "file:///music/a.flac")))
        assertFalse(ForkVideoRules.eligible(video.copy(videoId = "navidrome-track-0001")))
        assertFalse(ForkVideoRules.eligible(video.copy(videoId = "radio:abcdefg")))
    }

    @Test
    fun `the height asked for`() {
        assertEquals(720, ForkVideoRules.capFor(held = null, steppedDown = false, slow = false))
        assertEquals(480, ForkVideoRules.capFor(held = null, steppedDown = false, slow = true))
        assertEquals(2160, ForkVideoRules.capFor(held = 2160, steppedDown = false, slow = true))
        assertEquals(1080, ForkVideoRules.capFor(held = 2160, steppedDown = true, slow = false))
        assertEquals(480, ForkVideoRules.capFor(held = 480, steppedDown = true, slow = false))
        assertNull(ForkVideoRules.storedHeight(0))
        assertNull(ForkVideoRules.storedHeight(99_999))
        assertEquals(1080, ForkVideoRules.storedHeight(1080))
        assertEquals(1280, ForkVideoRules.decodeEdge(1280, 720, 1080))
        assertEquals(1280, ForkVideoRules.decodeEdge(0, 0, 720))
    }
}
