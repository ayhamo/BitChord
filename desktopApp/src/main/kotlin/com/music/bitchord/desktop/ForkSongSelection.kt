package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork (owner's list, 2026-11-04; built 2026-11-07): multi-select on the PC's Downloads, Cached
 * songs and Local Music pages, the phone's Downloads selection (`LocalFolderState`,
 * `LocalFolderSelectionBar` in app/…/ui/screens/LocalFolder.kt) adapted to a mouse.
 *
 * The phone enters selection by holding a row; a mouse has right-click, but that already opens the
 * song menu on every PC list, so selection starts by holding the left button (half a second, as a
 * long press), by Ctrl+click (as in a file manager), or by the page's Select button. While
 * selecting, a click toggles a row, Shift+click adds the range from the last row clicked, Ctrl+A
 * selects every song shown and Esc leaves (the window's key handler asks [onKey]). The bar under the
 * search row carries the phone's wording and actions: "Select all", "N Selected", then
 * Downloads: Delete; Cached songs: Download and Remove (out of the song cache); Cancel. Deleting
 * downloads asks once more on the button itself, as every other delete on the PC does (the phone
 * deletes at once). Local Music has no selection on the phone; on the PC it gets Play next and Add
 * to queue only, nothing that touches the files. Unselecting the last row leaves selection, as on
 * the phone; one entered with Select stays open, empty, until Cancel or Esc.
 *
 * Songs only, on the Songs tab and in an artist or album opened from the page; the phone also
 * selects whole albums on its Albums tab, which the PC's group rows (with their own menu) don't.
 */
@Stable
internal class ForkSongSelection(val kind: Kind) {

    enum class Kind { DOWNLOADS, CACHED, LOCAL }

    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Selection mode: a row's click toggles instead of playing, and the bar shows. */
    var active by mutableStateOf(false)
        private set

    /** The last row clicked, where a Shift+click's range starts. */
    private var anchor: String? = null

    /** The rows on screen, in order, for Select all and Shift ranges; set by the list as it draws. */
    var shown: List<String> = emptyList()

    /** Whether the page's search field is empty: Ctrl+A then selects songs, not the field's text. */
    var queryBlank: Boolean = true

    fun begin() {
        active = true
    }

    fun clear() {
        selected = emptySet()
        active = false
        anchor = null
    }

    /** A held row: selection starts with it, as the phone's hold does. */
    fun hold(id: String) {
        active = true
        selected = selected + id
        anchor = id
    }

    fun toggle(id: String) {
        active = true
        selected = if (id in selected) selected - id else selected + id
        anchor = id
        if (selected.isEmpty()) clear() // the phone's rule: the last one off ends it
    }

    /** Shift+click: every row from [anchor] to [id] in [order] joins the selection. */
    fun range(order: List<String>, id: String) {
        val from = anchor?.let(order::indexOf) ?: -1
        val to = order.indexOf(id)
        if (from < 0 || to < 0) return toggle(id)
        active = true
        selected = selected + order.subList(minOf(from, to), maxOf(from, to) + 1)
        anchor = id
    }

    fun selectAll(order: List<String>) {
        if (order.isEmpty()) return
        active = true
        selected = selected + order
    }

    /**
     * A click on a row: true when selection took it (toggled, ranged or started by Ctrl), false
     * when the row should play as usual.
     */
    fun click(id: String, ctrl: Boolean, shift: Boolean): Boolean {
        if (!active && !ctrl && !shift) return false
        if (shift && anchor != null) range(shown, id) else toggle(id)
        return true
    }

    /** The selected songs among [songs], in their order; the selection then ends (the phone's `chosen()`). */
    fun take(songs: List<Song>): List<Song> = songs.filter { it.videoId in selected }.also { clear() }

    /** Every row on screen is selected: "Select all" greys. */
    val allShownSelected: Boolean get() = shown.isNotEmpty() && shown.all { it in selected }

    /** What a row's click does, given what its press held ([rowClick]). */
    enum class RowClick { HOLD_RELEASED, SELECTION, PLAY }

    /**
     * A row's click: the release of a hold that already selected it is no second click; otherwise
     * [click] decides between selection and playing.
     */
    fun rowClick(id: String, ctrl: Boolean, shift: Boolean, held: Boolean): RowClick = when {
        held -> RowClick.HOLD_RELEASED
        click(id, ctrl, shift) -> RowClick.SELECTION
        else -> RowClick.PLAY
    }

    /** One of the bar's buttons. */
    enum class Action { PLAY_NEXT, ADD_TO_QUEUE, DOWNLOAD, REMOVE_FROM_CACHE, DELETE }

    /**
     * The bar's buttons for this page, in order, each with whether it can be pressed: nothing with
     * no song selected, and what goes through the window ([hasWindow]: Download, Delete) not before
     * the window is up. Local Music only queues: nothing on that page touches the files.
     */
    fun actions(hasWindow: Boolean): List<Pair<Action, Boolean>> {
        val any = selected.isNotEmpty()
        return when (kind) {
            Kind.LOCAL -> listOf(Action.PLAY_NEXT to any, Action.ADD_TO_QUEUE to any)
            Kind.CACHED -> listOf(Action.DOWNLOAD to (any && hasWindow), Action.REMOVE_FROM_CACHE to any)
            Kind.DOWNLOADS -> listOf(Action.DELETE to (any && hasWindow))
        }
    }

    /**
     * Deleting downloads takes two presses on the button itself, as every other delete on the PC
     * does: the first arms it (and names how many), the second deletes. Remembered per selection
     * size, so changing the selection disarms it.
     */
    @Stable
    class TwoClickDelete {
        var armed by mutableStateOf(false)
            private set

        /** True when this press deletes. */
        fun press(): Boolean {
            if (armed) return true
            armed = true
            return false
        }

        fun label(count: Int, plain: String): String = if (armed) "Delete $count? Click again" else plain
    }

    companion object {
        /** The selection of the page on screen, for the window's keys. */
        @Volatile var current: ForkSongSelection? = null

        /** Which pages select, by `DesktopLocalMusicPage`'s `persistenceKey`; Navidrome's doesn't. */
        fun kindOf(persistenceKey: String): Kind? = when (persistenceKey) {
            "downloaded" -> Kind.DOWNLOADS
            "cached" -> Kind.CACHED
            "local" -> Kind.LOCAL
            else -> null
        }

        /** From the window's key handler (key up): Esc leaves selection, Ctrl+A selects all. */
        fun onKey(key: Key, ctrl: Boolean): Boolean {
            val selection = current ?: return false
            return when {
                key == Key.Escape && selection.active -> {
                    selection.clear()
                    true
                }
                key == Key.A && ctrl && (selection.active || selection.queryBlank) -> {
                    selection.selectAll(selection.shown)
                    true
                }
                else -> false
            }
        }
    }
}

/** The page's selection, or null on a page that has none ([ForkSongSelection.kindOf]); it answers the window's keys while shown. */
@Composable
internal fun rememberForkSongSelection(persistenceKey: String, query: String): ForkSongSelection? {
    val kind = ForkSongSelection.kindOf(persistenceKey) ?: return null
    val selection = remember(persistenceKey) { ForkSongSelection(kind) }
    selection.queryBlank = query.isBlank()
    DisposableEffect(selection) {
        ForkSongSelection.current = selection
        onDispose { if (ForkSongSelection.current === selection) ForkSongSelection.current = null }
    }
    return selection
}

/**
 * One row (or grid card) of a list that can select: holding it selects it, a click while selecting
 * toggles it; [content] gets the click to hand its own clickable, which plays otherwise. A selected
 * row is outlined in the accent with a filled tick; while selecting, the others show an empty one.
 */
@Composable
internal fun ForkSelectableRow(
    selection: ForkSongSelection?,
    song: Song,
    onClick: (Song) -> Unit,
    content: @Composable (onClick: (Song) -> Unit) -> Unit,
) {
    if (selection == null) return content(onClick)
    // What the press that ends in this click held: read on the way in (the Initial pass), before
    // the row's own clickable takes the event.
    val press = remember { PressState() }
    val selected = song.videoId in selection.selected
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(DesktopAccent.copy(alpha = 0.14f), shape).border(1.5.dp, DesktopAccent, shape) else Modifier)
            .pointerInput(selection, song.videoId) {
                coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                                press.ctrl = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                                press.shift = event.keyboardModifiers.isShiftPressed
                                press.held = false
                                press.timer?.cancel()
                                press.timer = launch {
                                    delay(HOLD_MS)
                                    press.held = true
                                    selection.hold(song.videoId)
                                }
                            } else if (event.type == PointerEventType.Release || event.type == PointerEventType.Exit) {
                                press.timer?.cancel()
                                press.timer = null
                            }
                        }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            content { clicked ->
                when (selection.rowClick(clicked.videoId, press.ctrl, press.shift, press.held)) {
                    // The hold already selected it; its release is not a click as well.
                    ForkSongSelection.RowClick.HOLD_RELEASED -> press.held = false
                    ForkSongSelection.RowClick.SELECTION -> Unit
                    ForkSongSelection.RowClick.PLAY -> onClick(clicked)
                }
                press.ctrl = false
                press.shift = false
            }
        }
        if (selection.active) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) DesktopStrings["selected", "Selected"] else null,
                tint = if (selected) DesktopAccent else DesktopSecondary,
                modifier = Modifier.padding(horizontal = 10.dp).size(20.dp),
            )
        }
    }
}

private class PressState {
    var ctrl = false
    var shift = false
    var held = false
    var timer: kotlinx.coroutines.Job? = null
}

private const val HOLD_MS = 500L

/**
 * Before selection: the page's "Select" button, beside the view toggle. Greyed while the page has
 * no songs ([hasSongs]): an empty Downloads page offered a selection mode with nothing to pick.
 * Greyed rather than hidden so the row of buttons doesn't shift when the first song arrives.
 */
@Composable
internal fun ForkSelectButton(selection: ForkSongSelection?, hasSongs: Boolean = true) {
    if (selection == null || selection.active) return
    TextButton(onClick = selection::begin, enabled = hasSongs) { Text(DesktopStrings["d_select", "Select"]) }
}

/**
 * The selection's bar, under the search row while selecting: the phone's `LocalFolderSelectionBar`
 * with its wording. [songs] is the page's whole list (what the selection resolves against), [title]
 * names the queue for Play next / Add to queue.
 */
@Composable
internal fun ForkSelectionBar(selection: ForkSongSelection?, songs: List<Song>, title: String) {
    if (selection == null || !selection.active) return
    val window = ForkCollectionActions.window
    val count = selection.selected.size
    val confirmDelete = remember(count) { ForkSongSelection.TwoClickDelete() }
    val enabled = selection.actions(hasWindow = window != null).toMap()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(48.dp)
            .background(Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { selection.selectAll(selection.shown) }, enabled = !selection.allShownSelected) {
            Icon(Icons.Rounded.Check, DesktopStrings["select_all", "Select all"], modifier = Modifier.size(20.dp))
        }
        Text(
            "$count ${DesktopStrings["selected", "Selected"]}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        val source = DesktopQueueSource(title, PlaybackSourceType.BROWSE)
        when (selection.kind) {
            ForkSongSelection.Kind.LOCAL -> {
                SelectionAction(Icons.Rounded.PlaylistPlay, DesktopStrings["play_next", "Play next"], enabled[ForkSongSelection.Action.PLAY_NEXT] == true) {
                    ForkCollectionActions.queue(selection.take(songs), next = true, source = source)
                }
                SelectionAction(Icons.Rounded.PlaylistAdd, DesktopStrings["add_to_queue", "Add to queue"], enabled[ForkSongSelection.Action.ADD_TO_QUEUE] == true) {
                    ForkCollectionActions.queue(selection.take(songs), next = false, source = source)
                }
            }
            ForkSongSelection.Kind.CACHED -> {
                SelectionAction(Icons.Rounded.Download, DesktopStrings["download", "Download"], enabled[ForkSongSelection.Action.DOWNLOAD] == true) {
                    window?.download?.invoke(selection.take(songs))
                }
                SelectionAction(Icons.Rounded.Delete, DesktopStrings["cached_song_remove", "Remove"], enabled[ForkSongSelection.Action.REMOVE_FROM_CACHE] == true, destructive = true) {
                    val chosen = selection.take(songs)
                    (window?.scope ?: return@SelectionAction).launch {
                        withContext(Dispatchers.IO) { chosen.forEach { runCatching { ForkSongCache.remove(it) } } }
                        ForkNotice.show(DesktopStrings["removed_from_cache", "Removed from the song cache"])
                    }
                }
            }
            ForkSongSelection.Kind.DOWNLOADS -> {
                SelectionAction(
                    Icons.Rounded.Delete,
                    confirmDelete.label(count, DesktopStrings["delete", "Delete"]),
                    enabled[ForkSongSelection.Action.DELETE] == true,
                    destructive = true,
                ) {
                    if (!confirmDelete.press()) return@SelectionAction
                    val chosen = selection.take(songs)
                    val scope = window?.scope ?: return@SelectionAction
                    // Through the window's own removal, as an album's "Delete download" from this page:
                    // the file, the record and every row's tick go together.
                    scope.launch { ForkCollectionActions.deleteDownloads("", chosen.mapTo(HashSet()) { it.videoId }, "${chosen.size} selected songs") }
                }
            }
        }
        TextButton(onClick = selection::clear) { Text(DesktopStrings["cancel", "Cancel"]) }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else DesktopAccent
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, null, tint = if (enabled) color else DesktopSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) color else DesktopSecondary)
    }
}
