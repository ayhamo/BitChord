package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: the transition editor's guards (audit 2026-11-24): narrow pictures, the progress sweep, the drawing state. */
class ForkTransitionEditorTest {

    @Test
    fun aNarrowOrUnlaidPictureNeitherThrowsNorDraws() {
        // wider than the chip: centred on the mix, kept 2 px inside
        assertEquals(40f, chipLeft(mid = 80f, chipWidth = 80f, width = 400f), 0f)
        assertEquals(2f, chipLeft(mid = 0f, chipWidth = 80f, width = 400f), 0f)
        assertEquals(318f, chipLeft(mid = 400f, chipWidth = 80f, width = 400f), 0f)
        // narrower than the chip (the clamp's range was empty and coerceIn threw): centred
        assertEquals(-20f, chipLeft(mid = 10f, chipWidth = 80f, width = 40f), 0f)
        assertFalse(editorSized(0f, 120f))
        assertFalse(editorSized(300f, 0f))
        assertTrue(editorSized(300f, 120f))
    }

    @Test
    fun theSweepRunsOnlyWhileTheRowIsSeen() {
        assertTrue(progressSweeps(visible = true, fill = Float.NaN))
        assertFalse(progressSweeps(visible = false, fill = Float.NaN)) // held for its room at alpha 0
        assertFalse(progressSweeps(visible = true, fill = 0.4f))
        assertFalse(progressSweeps(visible = true, fill = null))
    }

    @Test
    fun anotherPairOrResetDropsTheChosenCurve() {
        ForkEditorUi.closeDrawing()
        ForkEditorUi.showing("a>b")
        ForkEditorUi.drawKey = "gain"
        ForkEditorUi.showing(null) // the plan worked out again after a save: still drawing
        ForkEditorUi.showing("a>b")
        assertEquals("gain", ForkEditorUi.drawKey)
        ForkEditorUi.showing("b>c") // the song moved on with the popup open
        assertNull(ForkEditorUi.drawKey)
        ForkEditorUi.drawKey = "highDb"
        ForkEditorUi.stopDrawing() // Reset to Auto
        assertNull(ForkEditorUi.drawKey)
        assertNull(ForkEditorUi.pointDrag)
        ForkEditorUi.closeDrawing()
    }

    /** Audit 2026-11-26 3a #8: the replace question, a drag and a Draw waiting on a loop mix don't outlive their pair. */
    @Test
    fun closingOrResettingLeavesNoQuestionDragOrWaitingDraw() {
        ForkEditorUi.askReplace = ForkEditorUi.ReplaceAsk("Replace the drawing?") {}
        ForkEditorUi.drag = EditorDrag("a>b", out = true, base = 10.0, pos = com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride.Position(1, 1, 0, 10.0), limit = null)
        ForkEditorUi.pendingDraw = "a>b" to "volume"
        ForkEditorUi.closeDrawing()
        assertNull(ForkEditorUi.askReplace)
        assertNull(ForkEditorUi.drag)
        assertNull(ForkEditorUi.pendingDraw)

        // Reset to Auto
        ForkEditorUi.askReplace = ForkEditorUi.ReplaceAsk("Replace the drawing?") {}
        ForkEditorUi.pendingDraw = "a>b" to "volume"
        ForkEditorUi.stopDrawing()
        assertNull(ForkEditorUi.pendingDraw)
        assertNull(ForkEditorUi.askReplace)
        ForkEditorUi.closeDrawing()
    }

    /** Audit 2026-11-26 3a #10: "Set" is off for an empty or unreadable box, and for the value already saved. */
    @Test
    fun setTakesOnlyANewNumber() {
        assertNull(lateToSet("", 0.0))
        assertNull(lateToSet(".", 2.0))
        assertNull(lateToSet("abc", 2.0))
        assertNull(lateToSet("2", 2.0))
        assertNull(lateToSet("0", 0.0))
        assertEquals(2.5, lateToSet("2,5", 0.0)!!, 0.0)
        assertEquals(0.0, lateToSet("0", 3.0)!!, 0.0)
    }
}
