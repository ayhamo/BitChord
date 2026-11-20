package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.canvas.ForkBestPart
import com.music.bitchord.data.canvas.ForkBestParts
import com.music.bitchord.data.canvas.ForkMostReplayed
import com.music.bitchord.data.canvas.ForkVideoCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: "Your best part" (canvas.md §14 rule 5, Prism's `PlayerSheets` best-part sheet), the same
 * dialog on the phone and the PC, in place of the song menu's two "Set / Clear your best part" rows.
 * Opened from the player's song menu or by holding (the PC: right-clicking) the Best part button
 * ([ForkBestParts.openEditor]); drawn by [ForkBestPartFollow] while the player is open.
 *
 * It opens at the listener's own part, else the automatic one (the most replayed part, else the
 * chorus, snapped to a line), else where the song is now: a bar over the song to pick a moment,
 * −5 s and +5 s, Now, Listen from here (seeks the playing song there), Save; with one saved, Forget
 * ("Back to the part people replay" when an automatic one exists). The saved part wins wherever a
 * best part is used ([ForkBestParts.mine]). Seeking and the position come from the app's player
 * ([ForkBestParts.player]).
 */
@Composable
internal fun ForkBestPartEditor(songId: String, durationMs: Long, onDismiss: () -> Unit) {
    val mine = remember(songId) { ForkBestParts.mine(songId) }
    val player = ForkBestParts.player
    val known = ForkBestParts.current.value?.takeIf { it.songId == songId }
    var auto by remember(songId) { mutableStateOf(known?.autoMs) }
    var at by remember(songId) { mutableLongStateOf(ForkBestParts.editorStart(mine, auto, player?.positionOf(songId))) }
    var touched by remember(songId) { mutableStateOf(false) }
    LaunchedEffect(songId) {
        if (auto != null) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val peak = if (ForkMostReplayed.eligible(songId)) ForkBestPart.partStart(ForkVideoCanvas.heatmap(songId), 0) else -1L
                ForkBestParts.start(null, peak, ForkBestParts.lines(songId), durationMs)
            }.getOrNull()
        }
        auto = found
        if (found != null && mine == null && !touched) at = found
    }
    val length = durationMs.takeIf { it > 0 } ?: maxOf(at + 60_000, 1L)
    fun move(ms: Long) {
        touched = true
        at = ms.coerceIn(0, length)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ForkBestParts.EDITOR_TITLE) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    ForkBestParts.clock(at),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                ThinSlider(
                    value = (at.toFloat() / length).coerceIn(0f, 1f),
                    onValueChange = { move((it * length).toLong()) },
                    modifier = Modifier.padding(vertical = 8.dp),
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { move(at - 5_000) }) { Text(ForkBestParts.BACK_5) }
                    TextButton(
                        onClick = { player?.positionOf(songId)?.let(::move) },
                        enabled = player?.positionOf(songId) != null,
                    ) { Text(ForkBestParts.NOW) }
                    TextButton(onClick = { move(at + 5_000) }) { Text(ForkBestParts.FORWARD_5) }
                }
                TextButton(
                    onClick = { player?.seek(songId, at) },
                    enabled = player?.positionOf(songId) != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(ForkBestParts.LISTEN) }
                if (mine != null) {
                    TextButton(
                        onClick = {
                            ForkBestParts.setMine(songId, null)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (auto != null) ForkBestParts.FORGET_TO_AUTO else ForkBestParts.FORGET) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ForkBestParts.setMine(songId, at)
                onDismiss()
            }) { Text(ForkBestParts.SAVE) }
        },
    )
}
