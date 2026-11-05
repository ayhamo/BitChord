package com.music.bitchord.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Fork: the update dialog's body, with the release notes in full in a box that scrolls.
 *
 * Upstream cut the notes at 600 characters (`it.take(600)`), which on a release with a real
 * changelog stopped mid-sentence and hid most of what changed; the only way to read the rest was
 * the release page. From Desktop Revived fd4e5680, which caps the box's height and scrolls it
 * instead. A scrollbar is drawn because a desktop dialog gives no other hint that more is below,
 * and the notes are selectable so a line can be copied.
 */
@Composable
internal fun ForkUpdateNotes(version: String, current: String, notes: String?) {
    val scroll = rememberScrollState()
    Box(Modifier.heightIn(max = 360.dp)) {
        Column(Modifier.verticalScroll(scroll).padding(end = 12.dp)) {
            Text("BitChord $version is out — you have $current.")
            notes?.takeIf { it.isNotBlank() }?.let { text ->
                Spacer(Modifier.height(12.dp))
                SelectionContainer { Text(text.trim()) }
            }
        }
        // Sized to the notes, not the cap: a bare fillMaxHeight() here would stretch short notes'
        // dialog to the full 360 dp, so the bar sits in a box that only matches the column's size.
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}
