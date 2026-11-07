package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork (owner, 2026-11-07): the update dialog and Settings > About, for [ForkUpdater].
 *
 * Upstream checked once at start and its dialog's Download opened the zip in the browser, to be
 * unzipped over the folder by hand. The PC sits in the tray for days, so the check now also runs
 * every 12 hours while the app runs, and on demand from "Check for updates". A build the user put
 * off with "Later" is not offered again by the timed check in the same run (a manual check still
 * shows it: that was asked for). In the portable app the dialog downloads, checks and unpacks the
 * update itself (progress, Cancel), then "Install now" hands over to `BitChordUpdate.exe` and
 * quits; elsewhere its button opens the download in the browser, as before. Wording follows the
 * phone's `UpdateAvailableDialog`.
 */
internal object ForkUpdateFlow {

    sealed interface Phase {
        data object Offered : Phase
        data class Downloading(val fraction: Float?) : Phase
        data class Ready(val root: Path) : Phase
        data class Failed(val message: String) : Phase
        data object Installing : Phase
        /** The updater runs and waits, but the app didn't close: "Close now" or Later, never a new download. */
        data object QuitStalled : Phase
    }

    data class Shown(val info: DesktopUpdateChecker.UpdateInfo, val phase: Phase)

    val shown = MutableStateFlow<Shown?>(null)

    sealed interface CheckState {
        data object Idle : CheckState
        data object Checking : CheckState
        data object UpToDate : CheckState
        data class Found(val label: String) : CheckState
        data class Failed(val message: String) : CheckState
    }

    val checkState = MutableStateFlow<CheckState>(CheckState.Idle)

    /** The build put off with "Later" in this run, as [key]. */
    @Volatile private var dismissed: String? = null

    private fun key(info: DesktopUpdateChecker.UpdateInfo) = "${info.version}+${info.build}"

    /** Whether the timed check should stay quiet about [info]. */
    internal fun timedCheckSkips(info: DesktopUpdateChecker.UpdateInfo, dismissedKey: String?): Boolean =
        dismissedKey != null && dismissedKey == key(info)

    const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

    private var job: Job? = null

    /** A check; [manual] from the About row (shows what it found even if put off before, and says "up to date"). */
    suspend fun check(manual: Boolean) {
        if (manual) checkState.value = CheckState.Checking
        val result = DesktopUpdateChecker.forkCheck()
        val info = result.getOrNull()
        when {
            result.isFailure -> {
                if (manual) checkState.value = CheckState.Failed(result.exceptionOrNull()?.message ?: "no connection")
                DesktopTrackLog.log("update: check failed: ${result.exceptionOrNull()?.message}")
            }
            info == null -> if (manual) checkState.value = CheckState.UpToDate
            else -> {
                checkState.value = CheckState.Found(ForkUpdater.label(info.version, info.build))
                // Never over a dialog already at work (a download, a staged update).
                if (replaceable(shown.value?.phase) &&(manual || !timedCheckSkips(info, dismissed))) shown.value = Shown(info, Phase.Offered)
            }
        }
    }

    /** Whether a check's dialog may take the place of [phase]'s: never over a download, a staged update or the hand-over. */
    internal fun replaceable(phase: Phase?): Boolean = phase == null || phase == Phase.Offered || phase is Phase.Failed

    fun later() {
        shown.value?.let { dismissed = key(it.info) }
        job?.cancel()
        shown.value = null
    }

    fun download(scope: CoroutineScope) {
        val current = shown.value ?: return
        if (!ForkUpdater.canUpdateInApp) {
            DesktopExternalLinks.open(current.info.downloadUrl ?: current.info.releaseUrl)
            shown.value = null
            return
        }
        job?.cancel()
        shown.value = current.copy(phase = Phase.Downloading(null))
        job = scope.launch {
            try {
                val root = ForkUpdater.prepare(current.info) { fraction ->
                    // Compare-and-set: a step that lands just after Cancel or Later must not put
                    // the progress bar (or the whole dialog) back.
                    shown.update { withProgress(it, current.info, fraction) }
                }
                shown.value = current.copy(phase = Phase.Ready(root))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                DesktopTrackLog.log("update: download failed: $failure")
                shown.value = current.copy(phase = Phase.Failed(failure.message ?: failure.toString()))
            }
        }
    }

    /**
     * [shown] with a download's progress step: only while that same release is still downloading.
     * After Cancel (back to Offered), Later (gone) or a new check's dialog, the step is dropped.
     */
    internal fun withProgress(shown: Shown?, info: DesktopUpdateChecker.UpdateInfo, fraction: Float?): Shown? =
        if (shown != null && shown.info === info && shown.phase is Phase.Downloading) shown.copy(phase = Phase.Downloading(fraction)) else shown

    /** How long "Install now" waits for the app to close before it says so and offers the way out. */
    const val QUIT_GRACE_MS = 20_000L

    const val QUIT_STALLED =
        "BitChord didn't close by itself. Close it now to finish the update (the queue may not be saved), or quit it later: the update goes on once it is closed."

    /**
     * "Close now" after a quit that hung: the normal quit already failed to end the process, so
     * this one doesn't run it (or the shutdown hooks it could hang in) again.
     */
    fun forceClose() {
        DesktopTrackLog.log("update: closing at once for the updater")
        Runtime.getRuntime().halt(0)
    }

    fun cancel() {
        job?.cancel()
        job = null
        shown.value = shown.value?.copy(phase = Phase.Offered)
    }

    /**
     * Starts the updater ([start]) and quits ([quit]: the tray's Quit, so the queue, the hand-off
     * note and the AppCDS archive are written as on any quit). A quit normally ends this process
     * well inside [graceMs]; if the app is still here after it (a shutdown that hangs), the dialog
     * says so instead of sitting on "Closing BitChord…" with no button, while the updater, which
     * waits for this process, goes on once the app is quit by hand.
     */
    fun install(
        scope: CoroutineScope,
        start: (Path) -> Unit = ForkUpdater::startUpdater,
        quit: () -> Unit = { javax.swing.SwingUtilities.invokeLater { DesktopTrayMenu.forkQuit() } },
        graceMs: Long = QUIT_GRACE_MS,
    ): Job? {
        val current = shown.value ?: return null
        val root = (current.phase as? Phase.Ready)?.root ?: return null
        shown.value = current.copy(phase = Phase.Installing)
        return scope.launch {
            val started = withContext(Dispatchers.IO) { runCatching { start(root) } }
            started.exceptionOrNull()?.let { failure ->
                shown.value = current.copy(phase = Phase.Failed(failure.message ?: failure.toString()))
                return@launch
            }
            quit()
            delay(graceMs)
            DesktopTrackLog.log("update: still running ${graceMs / 1000} s after Quit")
            shown.update { if (it?.phase == Phase.Installing) it.copy(phase = Phase.QuitStalled) else it }
        }
    }
}

/**
 * The app-level part: the check at start and every 12 hours, the clean-up after an update once the
 * first frame is drawn, and the dialog. Replaces upstream's check-at-start and its dialog in
 * [BitChordDesktopApp].
 */
@Composable
internal fun ForkUpdaterHost() {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        ForkStartup.awaitFirstFrame()
        withContext(Dispatchers.IO) { runCatching { ForkUpdater.cleanUpAfterStart(ForkStartup.firstFrameShown) } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            ForkUpdateFlow.check(manual = false)
            delay(ForkUpdateFlow.CHECK_EVERY_MS)
        }
    }
    val shown by ForkUpdateFlow.shown.collectAsState()
    val current = shown ?: return
    val info = current.info
    val label = ForkUpdater.label(info.version, info.build)
    val phase = current.phase
    AlertDialog(
        // Not while a download or the hand-over runs: Cancel stops a download.
        onDismissRequest = {
            if (phase == ForkUpdateFlow.Phase.Offered || phase is ForkUpdateFlow.Phase.Failed || phase == ForkUpdateFlow.Phase.QuitStalled) ForkUpdateFlow.later()
        },
        title = { Text(DesktopStrings["d_update_available", "Update available"]) },
        text = {
            Column {
                when (phase) {
                    is ForkUpdateFlow.Phase.Downloading -> {
                        Text(DesktopStrings.format("update_downloading_body", label, fallback = "Downloading bitchord %1\$s…"))
                        Spacer(Modifier.height(10.dp))
                        val progressModifier = Modifier.fillMaxWidth().height(4.dp)
                        val track = Color.White.copy(alpha = 0.15f)
                        val fraction = phase.fraction
                        if (fraction != null && fraction > 0f) {
                            LinearProgressIndicator(progress = { fraction }, modifier = progressModifier, color = DesktopAccent, trackColor = track)
                            Text(
                                "${(fraction * 100).toInt()} %",
                                style = MaterialTheme.typography.bodySmall,
                                color = DesktopSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = progressModifier, color = DesktopAccent, trackColor = track)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    is ForkUpdateFlow.Phase.Ready -> {
                        Text(DesktopStrings.format("update_ready_body", label, fallback = "bitchord %1\$s is ready to install."))
                        Text(
                            "BitChord closes, swaps its program files (your settings and data stay) and opens again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                    }
                    ForkUpdateFlow.Phase.Installing -> {
                        Text("Closing BitChord to update…")
                        Spacer(Modifier.height(12.dp))
                    }
                    ForkUpdateFlow.Phase.QuitStalled -> {
                        Text(ForkUpdateFlow.QUIT_STALLED)
                        Spacer(Modifier.height(12.dp))
                    }
                    is ForkUpdateFlow.Phase.Failed -> {
                        Text(DesktopStrings.format("update_failed_body", label, fallback = "Couldn’t download bitchord %1\$s."))
                        Text(
                            phase.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                    }
                    ForkUpdateFlow.Phase.Offered -> Unit
                }
                ForkUpdateNotes(label, ForkUpdater.currentLabel, info.notes)
            }
        },
        confirmButton = {
            when (phase) {
                ForkUpdateFlow.Phase.Offered -> TextButton(onClick = { ForkUpdateFlow.download(scope) }) {
                    Text(
                        if (ForkUpdater.canUpdateInApp) DesktopStrings["download_now", "Download now"] else DesktopStrings["d_download", "Download"],
                    )
                }
                is ForkUpdateFlow.Phase.Downloading -> TextButton(onClick = ForkUpdateFlow::cancel) { Text(DesktopStrings["cancel", "Cancel"]) }
                is ForkUpdateFlow.Phase.Ready -> TextButton(onClick = { ForkUpdateFlow.install(scope) }) { Text(DesktopStrings["install_now", "Install now"]) }
                is ForkUpdateFlow.Phase.Failed -> TextButton(onClick = { ForkUpdateFlow.download(scope) }) { Text(DesktopStrings["try_again", "Try again"]) }
                ForkUpdateFlow.Phase.Installing -> Unit
                ForkUpdateFlow.Phase.QuitStalled -> TextButton(onClick = ForkUpdateFlow::forceClose) { Text("Close now") }
            }
        },
        dismissButton = {
            when (phase) {
                ForkUpdateFlow.Phase.Offered, is ForkUpdateFlow.Phase.Ready, ForkUpdateFlow.Phase.QuitStalled ->
                    TextButton(onClick = ForkUpdateFlow::later) { Text(DesktopStrings["d_later", "Later"]) }
                is ForkUpdateFlow.Phase.Failed -> Row {
                    TextButton(onClick = {
                        DesktopExternalLinks.open(info.releaseUrl)
                        ForkUpdateFlow.later()
                    }) { Text(DesktopStrings["open_releases_page", "Open releases page"]) }
                    TextButton(onClick = ForkUpdateFlow::later) { Text(DesktopStrings["d_later", "Later"]) }
                }
                else -> Unit
            }
        },
    )
}

/** Settings > About: the version with its build, and "Check for updates". */
@Composable
internal fun ForkAboutSettingsGroup() {
    val scope = rememberCoroutineScope()
    val state by ForkUpdateFlow.checkState.collectAsState()
    SettingsGroup(DesktopStrings["about", "About"]) {
        val version = DesktopUpdateChecker.currentVersion
        ForkAboutRow(
            "Version",
            if (ForkUpdater.currentBuild > 0) "$version, build ${ForkUpdater.currentBuild}" else "$version, local build",
            onClick = null,
        )
        ForkAboutRow(
            "Check for updates",
            when (val s = state) {
                ForkUpdateFlow.CheckState.Idle -> "Checked at start and every 12 hours"
                ForkUpdateFlow.CheckState.Checking -> "Checking…"
                ForkUpdateFlow.CheckState.UpToDate -> "BitChord is up to date"
                is ForkUpdateFlow.CheckState.Found -> "BitChord ${s.label} is available"
                is ForkUpdateFlow.CheckState.Failed -> "Couldn’t check for updates: ${s.message}"
            },
            onClick = {
                if (state != ForkUpdateFlow.CheckState.Checking) scope.launch { ForkUpdateFlow.check(manual = true) }
            },
        )
    }
}

@Composable
private fun ForkAboutRow(title: String, subtitle: String, onClick: (() -> Unit)?) {
    if (!settingsRowVisible(title, subtitle)) return
    Row(
        Modifier
            .fillMaxWidth()
            .desktopRowClickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
        Spacer(Modifier.width(18.dp))
    }
}
