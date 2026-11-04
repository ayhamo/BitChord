package com.music.bitchord.desktop

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkLoudnessTest {

    private fun near(expected: Double, actual: Double?, tolerance: Double = 1e-6) {
        assertTrue(actual != null && abs(expected - actual) < tolerance, "expected $expected, got $actual")
    }

    @Test
    fun youtubeFigureIsTheNegatedLoudnessAsOnThePhone() {
        near(-5.0, ForkLoudnessMath.gainDb(null, 5.0, null))
        near(2.5, ForkLoudnessMath.gainDb(null, -2.5, null))
    }

    @Test
    fun boundedToThePhonesRange() {
        near(3.0, ForkLoudnessMath.gainDb(null, -10.0, null))
        near(-15.0, ForkLoudnessMath.gainDb(null, 30.0, null))
    }

    @Test
    fun serverFirstThenYoutubeThenTags() {
        near(-4.0, ForkLoudnessMath.gainDb(-8.0, 5.0, 1.0)) // ReplayGain -8 dB to -18 LUFS is -4 dB to -14
        near(-5.0, ForkLoudnessMath.gainDb(null, 5.0, 1.0))
        near(1.0, ForkLoudnessMath.gainDb(null, null, 1.0))
        assertNull(ForkLoudnessMath.gainDb(null, null, null))
        near(-5.0, ForkLoudnessMath.gainDb(Double.NaN, 5.0, null))
    }

    @Test
    fun replayGainAndR128TagsRead() {
        near(-3.89, ForkLoudnessMath.replayGainTag("-7.89 dB"))
        near(5.5, ForkLoudnessMath.replayGainTag("+1,5 dB"))
        assertNull(ForkLoudnessMath.replayGainTag("dB"))
        assertNull(ForkLoudnessMath.replayGainTag(null))
        near(-1.0, ForkLoudnessMath.r128Tag("-2560")) // -10 dB to -23 LUFS
        assertNull(ForkLoudnessMath.r128Tag("x"))
    }

    @Test
    fun trackTagsOverAlbumAndReplayGainOverR128() {
        val tags = mapOf(
            "REPLAYGAIN_ALBUM_GAIN" to "-9 dB",
            "R128_TRACK_GAIN" to "-2560",
            "REPLAYGAIN_TRACK_GAIN" to "-6 dB",
        )
        near(-2.0, ForkLoudnessMath.fromTags { tags[it] })
        near(-1.0, ForkLoudnessMath.fromTags { (tags - "REPLAYGAIN_TRACK_GAIN")[it] })
        near(-5.0, ForkLoudnessMath.fromTags { if (it == "REPLAYGAIN_ALBUM_GAIN") "-9 dB" else null })
        assertNull(ForkLoudnessMath.fromTags { null })
    }

    @Test
    fun ratioAndLinear() {
        assertEquals(0.5f, ForkLoudnessMath.linear(-6.0206), 1e-4f)
        assertEquals(2f, ForkLoudnessMath.ratio(1f, 0.5f), 1e-6f)
        assertEquals(1f, ForkLoudnessMath.ratio(1f, 0f))
    }

    @Test
    fun unityTouchesNoSample() {
        val stage = ForkLoudnessStage()
        val samples = FloatArray(512) { ((it % 37) - 18) / 18f }
        val before = samples.copyOf()
        stage.process(samples, samples.size, 2, 48_000, "a", 1f)
        assertContentEquals(before, samples)
    }

    @Test
    fun aNewSongStartsAtItsGainAndAChangeOnTheSameSongGlides() {
        val stage = ForkLoudnessStage()
        val block = FloatArray(200) { 0.1f }
        stage.process(block, block.size, 2, 48_000, "a", 0.5f)
        assertTrue(block.all { abs(it - 0.05f) < 1e-6f }, "snapped to the new song's gain")

        val glide = FloatArray(200) { 0.1f }
        stage.process(glide, glide.size, 2, 48_000, "a", 1f)
        assertTrue(glide.first() < 0.06f && glide.last() > glide.first() && glide.last() < 0.1f, "glides, no step")

        val next = FloatArray(200) { 0.1f }
        stage.process(next, next.size, 2, 48_000, "b", 0.25f)
        assertTrue(next.all { abs(it - 0.025f) < 1e-6f }, "another song jumps to its own gain")
    }

    @Test
    fun aBoostNeverPassesTheCeiling() {
        val stage = ForkLoudnessStage()
        val block = FloatArray(4_800) { if (it % 2 == 0) 0.95f else -0.95f }
        stage.process(block, block.size, 2, 48_000, "a", ForkLoudnessMath.linear(3.0))
        assertTrue(block.all { abs(it) <= ForkLoudnessStage.LIMIT + 1e-6f })
    }

    @Test
    fun quietMaterialTakesTheFullBoost() {
        val stage = ForkLoudnessStage()
        val gain = ForkLoudnessMath.linear(3.0)
        val block = FloatArray(400) { 0.2f }
        stage.process(block, block.size, 2, 48_000, "a", gain)
        assertTrue(block.all { abs(it - 0.2f * gain) < 1e-5f })
    }

    @Test
    fun theLoopMixsIncomingSideLandsAtItsOwnLevel() {
        // The mix goes out through the outgoing song's stage, so the incoming deck carries the ratio.
        val outGain = ForkLoudnessMath.linear(-6.0)
        val inGain = ForkLoudnessMath.linear(2.0)
        assertEquals(inGain, ForkLoudnessMath.ratio(inGain, outGain) * outGain, 1e-6f)
    }

    @Test
    fun resetLetsTheLimiterGo() {
        val stage = ForkLoudnessStage()
        val loud = FloatArray(400) { 0.99f }
        stage.process(loud, loud.size, 2, 48_000, "a", ForkLoudnessMath.linear(3.0))
        stage.reset()
        val quiet = FloatArray(400) { 0.1f }
        val before = quiet.copyOf()
        stage.process(quiet, quiet.size, 2, 48_000, "b", 1f)
        assertContentEquals(before, quiet, "parked at unity straight after a reset")
    }

    @Test
    fun tagFiguresAreKeptForTheLastStreams() {
        ForkLoudnessTags.remember("u1", -2.0)
        near(-2.0, ForkLoudnessTags.gainFor("u1"))
        repeat(520) { ForkLoudnessTags.remember("x$it", 1.0) }
        assertNull(ForkLoudnessTags.gainFor("u1"))
        ForkLoudnessTags.remember("x519", null)
        assertNull(ForkLoudnessTags.gainFor("x519"))
    }

    @Test
    fun aVersionSwitchGlidesToTheNewGainWhereANewSongJumps() {
        val rate = 48_000
        fun firstSampleAfterKeyChange(follow: Boolean): Float {
            val stage = ForkLoudnessStage()
            stage.process(FloatArray(960) { 0.5f }, 960, 2, rate, "video", 0.5f)
            if (follow) stage.follow("audio")
            val block = FloatArray(960) { 0.5f }
            stage.process(block, 960, 2, rate, "audio", 1f)
            return block[0]
        }
        // A new song on the deck starts at its own gain at once.
        assertEquals(0.5f, firstSampleAfterKeyChange(follow = false), 1e-6f)
        // The other version, crossed in at the old gain, starts there and glides.
        assertTrue(firstSampleAfterKeyChange(follow = true) < 0.26f)
    }

    @Test
    fun followOnAStageThatPlaysNothingStillSnaps() {
        val stage = ForkLoudnessStage()
        stage.follow("audio")
        val block = FloatArray(4) { 0.5f }
        stage.process(block, 4, 2, 48_000, "audio", 0.5f)
        assertEquals(0.25f, block[0], 1e-6f)
    }
}
