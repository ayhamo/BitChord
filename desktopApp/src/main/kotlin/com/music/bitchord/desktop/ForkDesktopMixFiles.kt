package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.automixv2.ForkV2MixFile
import com.music.bitchord.ui.player.ForkMixFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fork: the PC's file side of the mix files' import and export (the transition editor, Automix settings) ([ForkMixFiles]): the system's file
 * dialog, as Export / Import data uses ([ForkBackup]), and the debug bridge's `/pick` for a scripted check.
 */
internal object ForkDesktopMixFiles {

    /**
     * The PC's queue for a mix file's "Play these mixes" (mix-file.md rule 7), from `DesktopApp`'s live queue: a fresh
     * queue of the file's songs in order, the queue before it replaced (the playing song too, unless it is the file's
     * first, which plays on), then the first plays. Refused while a party's host holds the controls.
     */
    fun attachQueue(
        playingId: () -> String?,
        fresh: (List<Song>, Boolean) -> Unit,
    ) {
        ForkMixFiles.queue = object : ForkMixFiles.Queue {
            override fun refusal(): String? =
                if (DesktopListenTogether.state.value.controlsLocked) "the party's host holds the queue" else null
            override fun playingId(): String? = playingId()
            override fun fresh(songs: List<Song>, keep: Boolean) = fresh(songs, keep)
        }
    }

    /**
     * Where the file is read and written: off the UI thread, the answer back on it (its callbacks set Compose state).
     * The dialog itself stays on the UI thread, as Export / Import data's: AWT's modal dialog pumps the window's events
     * while it is open. The reading and writing ran there too, and a 2 MB file on a slow or network drive held the
     * window (audit 2026-11-26 3a #5).
     */
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    fun install() {
        ForkMixFiles.open = { onText, onFail ->
            val path = pick(save = false, name = null)
            if (path != null) {
                scope.launch {
                    when (val read = withContext(Dispatchers.IO) { read(path) }) {
                        is Read.Text -> onText(read.text)
                        is Read.Failed -> onFail(read.why)
                    }
                }
            }
        }
        ForkMixFiles.saveAs = { name, text, done ->
            val path = pick(save = true, name = name)
            if (path == null) {
                done(null)
            } else {
                scope.launch { done(withContext(Dispatchers.IO) { write(path, text) }) }
            }
        }
    }

    internal sealed interface Read {
        class Text(val text: String) : Read
        class Failed(val why: String) : Read
    }

    /** The file at [path] as a mix file's text, or why not: over [ForkV2MixFile.MAX_BYTES] is never read in. Blocking. */
    internal fun read(path: Path): Read {
        val size = runCatching { Files.size(path) }.getOrNull()
        if (size != null && size > ForkV2MixFile.MAX_BYTES) return Read.Failed("the file is over 2 MB, not a mix file")
        val text = if (size != null) runCatching { Files.readString(path) }.getOrNull() else null
        return if (text == null) Read.Failed("the file could not be read") else Read.Text(text)
    }

    /** Writes [text] to [path]; whether it worked. Blocking. */
    internal fun write(path: Path, text: String): Boolean = runCatching { Files.writeString(path, text) }.isSuccess

    private fun pick(save: Boolean, name: String?): Path? = ForkDebugBridge.takePick() ?: runCatching {
        val dialog = FileDialog(null as java.awt.Frame?, if (save) "Export mixes" else "Import mixes", if (save) FileDialog.SAVE else FileDialog.LOAD)
        dialog.directory = ForkPortable.userHome
        if (save) dialog.file = name else dialog.setFilenameFilter { _, n -> n.endsWith(".json", ignoreCase = true) }
        dialog.isVisible = true
        val file = dialog.file ?: return null
        Path.of(dialog.directory ?: ForkPortable.userHome, file)
    }.getOrNull()
}
