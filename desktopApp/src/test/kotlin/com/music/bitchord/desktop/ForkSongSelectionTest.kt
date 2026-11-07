package com.music.bitchord.desktop

import androidx.compose.ui.input.key.Key
import com.music.bitchord.desktop.ForkSongSelection.Action
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
        assertTrue(ForkSongSelection.onSelectAllKey(Key.A, ctrl = true))
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
        assertFalse(ForkSongSelection.onSelectAllKey(Key.A, ctrl = true))
        assertFalse(s.active)
        assertFalse(ForkSongSelection.onSelectAllKey(Key.A, ctrl = false))
    }

    @Test
    fun onlyDownloadsCachedAndLocalSelect() {
        assertEquals(ForkSongSelection.Kind.DOWNLOADS, ForkSongSelection.kindOf("downloaded"))
        assertEquals(ForkSongSelection.Kind.CACHED, ForkSongSelection.kindOf("cached"))
        assertEquals(ForkSongSelection.Kind.LOCAL, ForkSongSelection.kindOf("local"))
        assertNull(ForkSongSelection.kindOf("navidrome"))
        assertFalse(ForkSongSelection.onKey(Key.Escape, ctrl = false)) // no page shown
    }

    @Test
    fun selectAllGreysOnlyWhenEveryShownRowIsSelected() {
        val s = selection()
        assertFalse(s.allShownSelected)
        s.selectAll(order.dropLast(1))
        assertFalse(s.allShownSelected)
        s.toggle("e")
        assertTrue(s.allShownSelected)
        s.shown = emptyList()
        assertFalse(s.allShownSelected) // an empty page has nothing to select
    }

    @Test
    fun aRowClickAfterAHoldIsOnlyTheHoldsRelease() {
        val s = selection()
        s.hold("b")
        assertEquals(ForkSongSelection.RowClick.HOLD_RELEASED, s.rowClick("b", ctrl = false, shift = false, held = true))
        assertEquals(setOf("b"), s.selected) // not toggled off again
    }

    @Test
    fun aRowClickPlaysOutsideSelectionAndSelectsInside() {
        val s = selection()
        assertEquals(ForkSongSelection.RowClick.PLAY, s.rowClick("a", ctrl = false, shift = false, held = false))
        assertEquals(ForkSongSelection.RowClick.SELECTION, s.rowClick("a", ctrl = true, shift = false, held = false))
        assertEquals(ForkSongSelection.RowClick.SELECTION, s.rowClick("c", ctrl = false, shift = true, held = false))
        assertEquals(setOf("a", "b", "c"), s.selected)
    }

    private fun actions(kind: ForkSongSelection.Kind, pick: Boolean, window: Boolean) =
        ForkSongSelection(kind).apply { shown = order; if (pick) hold("a") }.actions(hasWindow = window)

    @Test
    fun localMusicOnlyQueues() {
        val k = ForkSongSelection.Kind.LOCAL
        assertEquals(listOf(Action.PLAY_NEXT to false, Action.ADD_TO_QUEUE to false), actions(k, pick = false, window = true))
        assertEquals(listOf(Action.PLAY_NEXT to true, Action.ADD_TO_QUEUE to true), actions(k, pick = true, window = false))
    }

    @Test
    fun cachedSongsDownloadAndRemoveOnlyWithTheWindowUp() {
        val k = ForkSongSelection.Kind.CACHED
        assertEquals(listOf(Action.DOWNLOAD to false, Action.REMOVE_FROM_CACHE to false), actions(k, pick = false, window = true))
        // Remove runs in the window's scope: enabled without it, it ended the selection and removed nothing.
        assertEquals(listOf(Action.DOWNLOAD to false, Action.REMOVE_FROM_CACHE to false), actions(k, pick = true, window = false))
        assertEquals(listOf(Action.DOWNLOAD to true, Action.REMOVE_FROM_CACHE to true), actions(k, pick = true, window = true))
    }

    @Test
    fun downloadsOnlyDeleteAndOnlyWithTheWindowUp() {
        val k = ForkSongSelection.Kind.DOWNLOADS
        assertEquals(listOf(Action.DELETE to false), actions(k, pick = false, window = true))
        assertEquals(listOf(Action.DELETE to false), actions(k, pick = true, window = false))
        assertEquals(listOf(Action.DELETE to true), actions(k, pick = true, window = true))
    }

    @Test
    fun deletingDownloadsTakesASecondPressThatNamesTheCount() {
        val d = ForkSongSelection.TwoClickDelete()
        assertEquals("Delete", d.label(3, "Delete"))
        assertFalse(d.press())
        assertTrue(d.armed)
        assertEquals("Delete 3? Click again", d.label(3, "Delete"))
        assertTrue(d.press())
        assertFalse(ForkSongSelection.TwoClickDelete().armed) // a new count starts unarmed
    }

    @Test
    fun ctrlAIsNotThePreviewHandlersAnyMore() {
        // The preview handler sees keys before a focused text field: Ctrl+A there selected every
        // download while the user selected a search box's text. Only Esc stays there.
        val s = selection()
        ForkSongSelection.current = s
        assertFalse(ForkSongSelection.onKey(Key.A, ctrl = true))
        assertFalse(s.active)
        assertTrue(s.selected.isEmpty())
    }

    @Test
    fun ctrlAWhileSelectingWorksWhateverTheSearchHolds() {
        val s = selection()
        s.queryBlank = false
        ForkSongSelection.current = s
        s.begin()
        assertTrue(ForkSongSelection.onSelectAllKey(Key.A, ctrl = true))
        assertEquals(order.toSet(), s.selected)
        assertFalse(ForkSongSelection.onSelectAllKey(Key.B, ctrl = true))
    }

    @Test
    fun noPageNoSelectAll() {
        assertFalse(ForkSongSelection.onSelectAllKey(Key.A, ctrl = true))
    }
}
