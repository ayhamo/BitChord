package com.music.bitchord.data.canvas

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias Source = ForkCanvasRules.Source

/** One song's canvas choices (canvas.md §15): stored, read back, part of the loop's key and of the order. */
class ForkCanvasChoicesTest {

    private val dir = Files.createTempDirectory("choices").toFile()

    @AfterTest
    fun cleanUp() {
        ForkCanvasChoices.resetForTest(null)
        dir.deleteRecursively()
    }

    @Test
    fun aLineReadsBackAndJunkIsTheDefault() {
        val c = ForkCanvasChoices.Choice(
            loopOff = true, coverOff = true, picked = "abcdefghijk", partMs = 64_000, partVideo = "abcdefghijk",
            rejected = setOf("x1", "x2"), use = Source.SPOTIFY,
        )
        assertEquals(c, ForkCanvasChoices.Choice.parse(c.line()))
        assertEquals(ForkCanvasChoices.Choice(), ForkCanvasChoices.Choice.parse(null))
        assertEquals(ForkCanvasChoices.Choice(), ForkCanvasChoices.Choice.parse("0||-3|,|"))
        assertEquals(ForkCanvasChoices.Choice(), ForkCanvasChoices.Choice.parse("2||||||nonsense"))
    }

    @Test
    fun aLineFromBeforeTheSwitchesKeepsItsOffAndVideos() {
        val c = ForkCanvasChoices.Choice.parse("1|picked00001|2|r1,r2|seen0000001")
        assertTrue(c.loopOff && c.spotifyOff && c.coverOff)
        assertEquals("picked00001", c.picked)
        assertEquals(setOf("r1", "r2"), c.rejected)
        assertNull(c.partMs)
    }

    @Test
    fun choicesAreKeptAndAnnounced() {
        ForkCanvasChoices.resetForTest(dir)
        val id = "dQw4w9WgXcQ"
        assertEquals("", ForkCanvasChoices.keySuffix(id))
        val gen = ForkCanvasSettings.generation.value
        ForkCanvasChoices.setOff(id, Source.SPOTIFY, true)
        assertTrue(ForkCanvasChoices.of(id).spotifyOff)
        assertFalse(ForkCanvasChoices.of(id).loopOff)
        assertTrue(ForkCanvasSettings.generation.value > gen)
        ForkCanvasChoices.setOff(id, Source.SPOTIFY, false)
        assertEquals(ForkCanvasChoices.Choice(), ForkCanvasChoices.of(id)) // nothing chosen: nothing kept

        // what the lookup found is a note, not a choice
        val noted = ForkCanvasSettings.generation.value
        ForkCanvasChoices.noteLoop(id, "vid00000001", 60_000, listOf(20_000, 90_000, 120_000, 150_000, 180_000))
        assertEquals(noted, ForkCanvasSettings.generation.value)
        assertEquals("", ForkCanvasChoices.keySuffix(id))
        assertEquals(4, ForkCanvasChoices.noteOf(id).others.size)

        // a picked part is the part itself, in its video, and the old part takes its place
        ForkCanvasChoices.pickPart(id, 90_000)
        assertEquals(90_000L, ForkCanvasChoices.of(id).partMs)
        assertEquals("vid00000001", ForkCanvasChoices.of(id).partVideo)
        assertEquals(listOf(20_000L, 60_000L, 120_000L, 150_000L), ForkCanvasChoices.noteOf(id).others)
        val part = ForkCanvasChoices.keySuffix(id)
        assertTrue("90000" in part)

        // not this video: turned away for good, the part in it goes
        assertEquals("vid00000001", ForkCanvasChoices.reject(id))
        assertEquals(setOf("vid00000001"), ForkCanvasChoices.of(id).rejected)
        assertNull(ForkCanvasChoices.of(id).partMs)
        assertNull(ForkCanvasChoices.noteOf(id).video)
        assertNotEquals(part, ForkCanvasChoices.keySuffix(id))

        // a picked video: no longer turned away, and used first
        ForkCanvasChoices.pick(id, "vid00000001")
        val c = ForkCanvasChoices.of(id)
        assertEquals("vid00000001", c.picked)
        assertFalse("vid00000001" in c.rejected)
        assertEquals(Source.YOUTUBE_MUSIC_VIDEO, c.use)

        // kept across a restart; the notes are not
        ForkCanvasChoices.resetForTest(dir)
        assertEquals("vid00000001", ForkCanvasChoices.of(id).picked)
        assertNull(ForkCanvasChoices.of("other").picked)
        assertNull(ForkCanvasChoices.noteOf(id).video)
    }

    @Test
    fun theMenuChecksOnlyTheLoopShownAndMakesTheOtherTheSongsChoice() {
        ForkCanvasChoices.resetForTest(dir)
        val id = "dQw4w9WgXcQ"
        val order = listOf(Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY, Source.APPLE_MUSIC)
        val note = ForkCanvasChoices.Note(video = "v", startMs = 1_000, had = setOf(Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY, Source.APPLE_MUSIC))
        val rows = ForkCanvasChoices.rows(ForkCanvasChoices.of(id), note, isSong = true, canvasOn = true, order = order)
        assertTrue(rows.loopSwitch && rows.loopChecked)
        assertTrue(rows.spotifySwitch && !rows.spotifyChecked) // there, but the loop shows
        assertTrue(rows.coverSwitch && rows.coverChecked)
        assertTrue(rows.pickPart && rows.pickVideo && rows.notThisVideo && rows.use)
        assertEquals(order, rows.useOptions)
        ForkCanvasChoices.toggle(id, Source.SPOTIFY, rows)
        assertEquals(Source.SPOTIFY, ForkCanvasChoices.of(id).use)
        val after = ForkCanvasChoices.rows(ForkCanvasChoices.of(id), note, isSong = true, canvasOn = true, order = order)
        assertTrue(after.spotifyChecked && !after.loopChecked)
        assertFalse(after.pickPart) // the loop no longer shows
        // a source with nothing found and not switched off has no switch
        val bare = ForkCanvasChoices.rows(ForkCanvasChoices.Choice(), ForkCanvasChoices.Note(), isSong = true, canvasOn = true, order = order)
        assertFalse(bare.loopSwitch || bare.spotifySwitch || bare.coverSwitch || bare.use)
        assertTrue(bare.pickVideo)
    }

    @Test
    fun aLoopsFramingCarriesItsVideoAndParts() {
        val f = ForkVideoCanvas.Framing(0.4f, floatArrayOf(0f, 0.1f, 0f, 0.1f), "vid00000001", 64_000, listOf(20_000, 120_000))
        val back = ForkVideoCanvas.Framing.parse(f.line())!!
        assertEquals("vid00000001", back.video)
        assertEquals(64_000L, back.startMs)
        assertEquals(listOf(20_000L, 120_000L), back.others)
        // a file from before: the crop and bars only
        val old = ForkVideoCanvas.Framing.parse("0.500 0.000 0.120 0.000 0.120")!!
        assertNull(old.video)
        assertEquals(0.12f, old.bars[1])
    }

    @Test
    fun theSongsOrderLeavesOutWhatIsOffAndPutsItsUseFirst() {
        val order = listOf(Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY, Source.APPLE_MUSIC)
        assertEquals(order, ForkCanvasChoices.orderFor(ForkCanvasChoices.Choice(), order))
        assertEquals(listOf(Source.SPOTIFY, Source.APPLE_MUSIC), ForkCanvasChoices.orderFor(ForkCanvasChoices.Choice(loopOff = true), order))
        assertEquals(listOf(Source.APPLE_MUSIC, Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY), ForkCanvasChoices.orderFor(ForkCanvasChoices.Choice(use = Source.APPLE_MUSIC), order))
        // a Use switched off for the song is no longer first
        assertEquals(listOf(Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY), ForkCanvasChoices.orderFor(ForkCanvasChoices.Choice(coverOff = true, use = Source.APPLE_MUSIC), order))
        // a picked video is used even where another source comes first (§15 rule 6)
        assertEquals(Source.YOUTUBE_MUSIC_VIDEO, ForkCanvasChoices.orderFor(ForkCanvasChoices.Choice(picked = "v"), listOf(Source.APPLE_MUSIC, Source.YOUTUBE_MUSIC_VIDEO)).first())
        assertEquals(Source.SPOTIFY, ForkCanvasChoices.showing(order, setOf(Source.SPOTIFY, Source.APPLE_MUSIC)))
        assertNull(ForkCanvasChoices.showing(order, emptySet()))
    }
}
