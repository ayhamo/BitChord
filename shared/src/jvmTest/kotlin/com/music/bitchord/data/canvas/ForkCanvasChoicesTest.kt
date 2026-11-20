package com.music.bitchord.data.canvas

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-song canvas menu's choices: stored, read back, and part of the loop's key. */
class ForkCanvasChoicesTest {

    private val dir = Files.createTempDirectory("choices").toFile()

    @AfterTest
    fun cleanUp() {
        ForkCanvasChoices.resetForTest(null)
        dir.deleteRecursively()
    }

    @Test
    fun aLineReadsBackAndJunkIsTheDefault() {
        val c = ForkCanvasChoices.Choice(off = true, picked = "abcdefghijk", part = 2, rejected = setOf("x1", "x2"), video = "v")
        assertEquals(c, ForkCanvasChoices.Choice.parse(c.line()))
        assertEquals(ForkCanvasChoices.Choice(), ForkCanvasChoices.Choice.parse(null))
        assertEquals(ForkCanvasChoices.Choice(), ForkCanvasChoices.Choice.parse("0||-3|,|"))
    }

    @Test
    fun choicesAreKeptAndAnnounced() {
        ForkCanvasChoices.resetForTest(dir)
        val id = "dQw4w9WgXcQ"
        assertEquals("", ForkCanvasChoices.keySuffix(id))
        val gen = ForkCanvasSettings.generation.value
        ForkCanvasChoices.setOff(id, true)
        assertTrue(ForkCanvasChoices.of(id).off)
        assertTrue(ForkCanvasSettings.generation.value > gen)
        ForkCanvasChoices.setOff(id, false)

        // noting the loop's video is not a change of choice
        val noted = ForkCanvasSettings.generation.value
        ForkCanvasChoices.noteVideo(id, "vid00000001")
        assertEquals(noted, ForkCanvasSettings.generation.value)
        assertEquals("", ForkCanvasChoices.keySuffix(id))

        ForkCanvasChoices.nextPart(id)
        val part = ForkCanvasChoices.keySuffix(id)
        assertNotEquals("", part)
        ForkCanvasChoices.reject(id)
        assertEquals(setOf("vid00000001"), ForkCanvasChoices.of(id).rejected)
        assertNotEquals(part, ForkCanvasChoices.keySuffix(id))
        ForkCanvasChoices.pick(id, "vid00000001")
        val c = ForkCanvasChoices.of(id)
        assertEquals("vid00000001", c.picked)
        assertEquals(0, c.part)
        assertFalse("vid00000001" in c.rejected)

        // kept across a restart
        ForkCanvasChoices.resetForTest(dir)
        assertEquals("vid00000001", ForkCanvasChoices.of(id).picked)
        assertNull(ForkCanvasChoices.of("other").picked)
    }
}
