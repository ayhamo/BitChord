package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fork: where the song cache lives, first in Storage, where the phone has its own cache row
 * ("Show cached songs folder", `show_cache_folder`).
 *
 * The phone's row is a switch that adds a "Cached songs" folder to Library, listing the tracks
 * its `AudioCache` holds. The PC's files carry no song of their own, so that list needed a record
 * of its own ([ForkCachedSongs], `songs.json` beside the windows); the switch follows this row
 * ([ForkShowCachedSongsRow]). This row names the folder and opens it in the system's file manager
 * (Explorer on Windows).
 *
 * The folder is [DesktopMediaCache.directory] whatever the build: the portable app moves
 * `user.home` into its own `data\` folder before anything reads it ([ForkPortable]), so the path
 * shown there is inside the app's folder with no special case here.
 */
@Composable
internal fun ForkCacheFolderRow() {
    val folder = remember { DesktopMediaCache.directory }
    var failed by remember { mutableStateOf<String?>(null) }
    SettingsRow(
        Icons.Rounded.FolderOpen,
        DesktopStrings["d_song_cache_folder", "Song cache folder"],
        failed ?: folder.toString(),
    ) {
        failed = ForkCacheFolder.open(folder)
    }
    ForkShowCachedSongsRow() // the phone's "Show cached songs folder" (ForkCachedSongs.kt)
}

internal object ForkCacheFolder {

    /**
     * Opens [folder] in the file manager; null when it opened, or what went wrong.
     *
     * `Desktop.open` is the portable way, but some Linux desktops report it unsupported; and on
     * Windows a folder that was cleared away (Clear song cache deletes files, not the folder,
     * but a user may delete the folder itself) makes it throw, so the folder is made again first.
     * Explorer is asked directly when the AWT route is missing, since `explorer.exe <path>` is
     * always there on Windows.
     */
    fun open(folder: Path): String? = runCatching {
        Files.createDirectories(folder)
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(folder.toFile())
        } else if (DesktopPlatform.isWindows) {
            ProcessBuilder("explorer.exe", folder.toString()).start()
        } else {
            ProcessBuilder(if (DesktopPlatform.isMac) "open" else "xdg-open", folder.toString()).start()
        }
        null
    }.getOrElse { failure ->
        DesktopTrackLog.log("could not open the song cache folder $folder: ${failure.message}")
        "Could not open $folder"
    }
}
