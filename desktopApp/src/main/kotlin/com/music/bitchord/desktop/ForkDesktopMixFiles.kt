package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.automixv2.ForkV2MixFile
import com.music.bitchord.ui.player.ForkMixFiles
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

    fun install() {
        ForkMixFiles.open = { onText, onFail ->
            val path = pick(save = false, name = null)
            if (path != null) {
                val size = runCatching { Files.size(path) }.getOrNull()
                val text = if (size != null && size <= ForkV2MixFile.MAX_BYTES) runCatching { Files.readString(path) }.getOrNull() else null
                when {
                    size != null && size > ForkV2MixFile.MAX_BYTES -> onFail("the file is over 2 MB, not a mix file")
                    text == null -> onFail("the file could not be read")
                    else -> onText(text)
                }
            }
        }
        ForkMixFiles.saveAs = { name, text, done ->
            val path = pick(save = true, name = name)
            done(if (path == null) null else runCatching { Files.writeString(path, text) }.isSuccess)
        }
    }

    private fun pick(save: Boolean, name: String?): Path? = ForkDebugBridge.takePick() ?: runCatching {
        val dialog = FileDialog(null as java.awt.Frame?, if (save) "Export mixes" else "Import mixes", if (save) FileDialog.SAVE else FileDialog.LOAD)
        dialog.directory = ForkPortable.userHome
        if (save) dialog.file = name else dialog.setFilenameFilter { _, n -> n.endsWith(".json", ignoreCase = true) }
        dialog.isVisible = true
        val file = dialog.file ?: return null
        Path.of(dialog.directory ?: ForkPortable.userHome, file)
    }.getOrNull()
}
