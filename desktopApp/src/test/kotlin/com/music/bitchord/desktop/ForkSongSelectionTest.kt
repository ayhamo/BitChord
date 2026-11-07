package com.music.bitchord.desktop

import androidx.compose.ui.input.key.Key
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The PC's song multi-select ([ForkSongSelection]): how clicks, holds, ranges and keys move it. */
class ForkSongSelectionTest {

    private val order = listOf("a", "b", "c", "d", "e")

    private fun selection() = ForkSongSelection(ForkSongSelection.Kind.DOWNLOADS).apply { shown = order }

    @AfterTest
    fun forget() {
        ForkSongSelection.current = null
    }

    @Test
    fun aPlainClickPlaysUntilSelectionStarts() {
        val s = selection()
        assertFalse(s.click("a", ctrl = false, shift = false))
        assertFalse(s.active)
    }

    @Test
    fun ctrlClickOrAHoldStartsIt() {
        val s = selection()
        assertTrue(s.click("b", ctrl = true, shift = false))
        assertTrue(s.active)
        assertEquals(setOf("b"), s.selected)
        val held = selection()
        held.hold("c")
        assertTrue(held.active)
        assertEquals(setOf("c"), held.selected)
    }

    @Test
    fun whileSelectingAClickToggles() {
        val s = selection()
        s.hold("a")
        assertTrue(s.click("b", ctrl = false, shift = false))
        assertEquals(setOf("a", "b"), s.selected)
        s.click("a", ctrl = false, shift = false)
        assertEquals(setOf("b"), s.selected)
    }

    @Test
    fun unselectingTheLastOneEndsIt() {
        val s = selection()
        s.hold("a")
        s.click("a", ctrl = false, shift = false)
        assertFalse(s.active)
        assertTrue(s.selected.isEmpty())
    }

    @Test
    fun theSelectButtonOpensAnEmptySelection() {
        val s = selection()
        s.begin()
        assertTrue(s.active)
        assertTrue(s.click("c", ctrl = false, shift = false))
        assertEquals(setOf("c"), s.selected)
    }

    @Test
    fun shiftClickAddsTheRangeFromTheLastRowClicked() {
        val s = selection()
        s.hold("b")
        s.click("d", ctrl = false, shift = true)
        assertEquals(setOf("b", "c", "d"), s.selected)
        // Upwards from the new anchor, added to what is there.
        s.click("a", ctrl = false, shift = true)
        assertEquals(setOf("a", "b", "c", "d"), s.selected)
    }

    @Test
    fun shiftWithoutAnAnchorOrOffScreenToggles() {
        val s = selection()
        assertTrue(s.click("c", ctrl = false, shift = true))
        assertEquals(setOf("c"), s.selected)
        s.shown = listOf("x", "y")
        s.click("y", ctrl = false, shift = true) // the anchor "c" is no longer shown
        assertEquals(setOf("c", "y"), s.selected)
    }

    @Test
    fun selectAllAndTakeEndTheSelection() {
        val s = selection()
        s.selectAll(order)
        assertEquals(order.toSet(), s.selected)
        val songs = order.map { Song(videoId = it, title = it, artist = "", thumbnailUrl = null) }
        s.click("c", ctrl = false, shift = false)
        val taken = s.take(songs)
        assertEquals(listOf("a", "b", "d", "e"), taken.map { it.videoId })
        assertFalse(s.active)
        assertTrue(s.selected.isEmpty())
    }

    @Test
    fun escapeLeavesAndCtrlASelectsAll() {
        val s = selection()
        ForkSongSelection.current = s
        assertTrue(ForkSongSelection.onKey(Key.A, ctrl = true))
        assertEquals(order.toSet(), s.selected)
        assertTrue(ForkSongSelection.onKey(Key.Escape, ctrl = false))
        assertFalse(s.active)
        // Not selecting: Esc is left to the rest of the window.
        assertFalse(ForkSongSelection.onKey(Key.Escape, ctrl = false))
    }

    @Test
    fun ctrlAIsTheSearchFieldsWhileItHoldsText() {
        val s = selection()
        s.queryBlank = false
        ForkSongSelection.current = s
        assertFalse(ForkSongSelection.onKey(Key.A, ctrl = true))
        assertFalse(s.active)
        assertFalse(ForkSongSelection.onKey(Key.A, ctrl = false))
    }

    @Test
    fun onlyDownloadsCachedAndLocalSelect() {
        assertEquals(ForkSongSelection.Kind.DOWNLOADS, ForkSongSelection.kindOf("downloaded"))
        assertEquals(ForkSongSelection.Kind.CACHED, ForkSongSelection.kindOf("cached"))
        assertEquals(ForkSongSelection.Kind.LOCAL, ForkSongSelection.kindOf("local"))
        assertNull(ForkSongSelection.kindOf("navidrome"))
        assertFalse(ForkSongSelection.onKey(Key.Escape, ctrl = false)) // no page shown
    }
}
