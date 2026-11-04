package com.music.bitchord.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * Fork: the menu on an artist or album in the Library pages' Artists and Albums tabs (Downloads,
 * Local Music, the Navidrome page), the phone's hold on a group in `LocalFolder` (`onGroupLongPress`,
 * which opens `BrowseActionsMenu` with the rows the group covers in hand). On the PC it opens on a
 * right-click, or from the ⋯ on a list row.
 *
 * The phone's rows in its order: Play, Shuffle, Play next, Add to queue, Open album / Open artist,
 * and in Downloads "Delete download" (asked twice, as everywhere). Nothing here needs fetching:
 * the group's rows are the ones on screen, filtered and sorted as the tab shows them. Queueing goes
 * through [ForkCollectionActions] like a release card's menu. A group in Local Music or on the
 * server is not a download, so it has no delete; in Downloads every row is one, so the whole
 * group's files go (the phone offers it there only for a release downloaded whole, a record the PC
 * does not keep for albums).
 *
 * @property type what the groups are: [BrowseType.ARTIST] or [BrowseType.ALBUM], for "Open …".
 * @property deletable the Downloads page's groups.
 * @property onOpen what a click on the group does (opens it in the tab).
 */
internal class ForkGroupMenu(
    val type: BrowseType,
    val deletable: Boolean,
    val onOpen: (Pair<String, List<Song>>) -> Unit,
)

/**
 * Plants the menu under the pointer for whichever group [content] hands to its `open`; with no
 * [menu] (a caller that offers none) `open` is null and nothing is planted.
 */
@Composable
internal fun ForkGroupMenuHost(
    menu: ForkGroupMenu?,
    content: @Composable (open: ((Pair<String, List<Song>>) -> Unit)?) -> Unit,
) {
    if (menu == null) {
        content(null)
        return
    }
    DesktopPointerMenuHost<Pair<String, List<Song>>>(
        menu = { group, onDismiss -> ForkGroupMenuRows(menu, group, onDismiss) },
    ) { open -> content(open) }
}

/** The ⋯ at the end of a group's list row, for those who don't reach for the right button. */
@Composable
internal fun ForkGroupMoreButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(Icons.Rounded.MoreHoriz, DesktopStrings["more", "More"], tint = DesktopSecondary)
    }
}

@Composable
private fun ForkGroupMenuRows(menu: ForkGroupMenu, group: Pair<String, List<Song>>, onDismiss: () -> Unit) {
    val forkGroupScope = androidx.compose.runtime.rememberCoroutineScope()
    val (title, songs) = group
    val source = DesktopQueueSource(title, PlaybackSourceType.BROWSE)
    val window = ForkCollectionActions.window
    fun act(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(DesktopStrings["play", "Play"]) },
            leadingIcon = { Icon(BitChordIcons.Play, null) },
            onClick = act { window?.play?.invoke(songs, source) },
        )
        DropdownMenuItem(
            text = { Text(DesktopStrings["shuffle", "Shuffle"]) },
            leadingIcon = { Icon(BitChordIcons.Shuffle, null) },
            // As a release card's Shuffle on the PC: the rows shuffled, then played from the top.
            onClick = act { window?.play?.invoke(songs.shuffled(), source) },
        )
        DropdownMenuItem(
            text = { Text(DesktopStrings["play_next", "Play next"]) },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
            onClick = act { ForkCollectionActions.queue(songs, next = true, source = source) },
        )
        DropdownMenuItem(
            text = { Text(DesktopStrings["add_to_queue", "Add to queue"]) },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
            onClick = act { ForkCollectionActions.queue(songs, next = false, source = source) },
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (menu.type == BrowseType.ARTIST) DesktopStrings["open_artist", "Open artist"]
                    else DesktopStrings["open_album", "Open album"],
                )
            },
            leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null) },
            onClick = act { menu.onOpen(group) },
        )
        if (menu.deletable && window != null) {
            ForkDeleteDownloadRow(title) {
                onDismiss()
                // As a page's delete: files a followed playlist also names stay (the phone's tracksOnlyIn).
                forkGroupScope.launch { ForkCollectionActions.deleteDownloads("", songs.map { it.videoId }.toSet(), "${if (menu.type == BrowseType.ARTIST) "the artist" else "the album"} '$title' (${songs.size} rows)") }
            }
        }
    }
}
