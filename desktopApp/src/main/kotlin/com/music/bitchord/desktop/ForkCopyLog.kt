package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.runtime.Composable
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fork: the phone's "Copy log" (`SongActionsMenu`, last row of the player's song menu): the
 * playing track's log, as [TrackLog.forTrack] writes it, on the clipboard, and a line saying how
 * many lines went.
 *
 * The text is the phone's own, from the shared [TrackLog], but on the PC that log held almost
 * nothing: the shared code (InnerTube, BotGuard) writes there, while every line the PC's own
 * engine and source ladder write goes to [DesktopTrackLog] only, which is a file. So
 * [DesktopTrackLog.log] hands each of its lines here ([record]) to be kept in [TrackLog] too.
 *
 * The two logs already feed each other one way: [TrackLog.echo] and [DebugLog.sink] print
 * through [DesktopTrackLog] (Main.kt). Recording that echo again would keep every shared line
 * twice, and recording a PC line through [TrackLog.d] would echo it back into the file twice. One
 * per-thread flag ([busy]) settles both: [install] wraps the two echoes so a line passing through
 * them is not recorded again, and [record] skips the echo for a line it is recording itself.
 * [DebugLog] stays out of the copy, as on the phone, where it only ever reaches logcat: it is the
 * lyrics and the catalogue, which TrackLog's note says were never the answer.
 *
 * The PC's lines carry no track id (its engine doesn't run under [TrackLog.about]), so they count
 * as "about nothing in particular" and are kept from where the track's own story starts; the start
 * is [TrackLog.onTrackStarted], called as the playing song changes ([noteTrack]), which the phone's
 * player calls at the same moment.
 */
internal object ForkCopyLog {

    private val busy = ThreadLocal<Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastTrack: String? = null

    /** After Main.kt has set [TrackLog.echo] and [DebugLog.sink]. */
    fun install() {
        val echo = TrackLog.echo
        TrackLog.echo = { level, tag, message, error -> quietly { echo?.invoke(level, tag, message, error) } }
        val sink = DebugLog.sink
        if (sink != null) DebugLog.sink = DebugLog.Sink { level, tag, message, error -> quietly { sink.log(level, tag, message, error) } }
        // The phone's header ends with the build and the device; these are the PC's.
        TrackLog.headerLines = {
            listOf(
                "build: ${DesktopUpdateChecker.currentVersion} (desktop)",
                "device: ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                    "${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}",
            )
        }
    }

    private inline fun quietly(block: () -> Unit) {
        // Busy means [record] is filing one of DesktopTrackLog's own lines: that line has already
        // been printed and written, so its echo is skipped (running it would log it a second time,
        // as "BitChord/D: …").
        if (busy.get() == true) return
        busy.set(true)
        try {
            block()
        } finally {
            busy.set(false)
        }
    }

    /** From [DesktopTrackLog.log]: one of the PC's own lines, kept for the copy unless it is an echo. */
    fun record(line: String) {
        if (busy.get() == true) return
        busy.set(true)
        try {
            TrackLog.d(TAG, line)
        } finally {
            busy.set(false)
        }
    }

    /** From the window's playback state: marks where a newly playing song's story starts. */
    fun noteTrack(videoId: String?) {
        if (videoId == null || videoId == lastTrack) return
        lastTrack = videoId
        TrackLog.onTrackStarted(videoId)
    }

    /** Copies [song]'s log to the clipboard and says how many lines it was, as the phone's toast does. */
    fun copy(song: Song) {
        scope.launch {
            val text = TrackLog.forTrack(song, NerdStats.current.value)
            val lines = text.lineSequence().count()
            DesktopExternalLinks.copy(text).fold(
                onSuccess = { ForkNotice.show(if (lines == 1) "Log copied · 1 line" else "Log copied · $lines lines") },
                onFailure = { ForkNotice.show("Could not copy the log: ${it.message}") },
            )
        }
    }

    private const val TAG = "BitChord"
}

/**
 * The player's song menu, last of its rows, as on the phone ("the one row here nobody reaches for
 * by accident"); only in the player's menu, where the phone shows it too (`fromPlayer`).
 */
@Composable
internal fun ForkCopyLogRow(song: Song, onDismiss: () -> Unit) {
    DesktopMenuRow(Icons.Rounded.BugReport, DesktopStrings["copy_log", "Copy log"]) {
        ForkCopyLog.copy(song)
        onDismiss()
    }
}
