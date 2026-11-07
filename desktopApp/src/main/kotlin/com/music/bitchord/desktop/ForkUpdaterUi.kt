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
                val busy = shown.value?.phase.let { it != null && it != Phase.Offered && it !is Phase.Failed }
                if (!busy && (manual || !timedCheckSkips(info, dismissed))) shown.value = Shown(info, Phase.Offered)
            }
        }
    }

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
                    shown.value = current.copy(phase = Phase.Downloading(fraction))
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

    fun cancel() {
        job?.cancel()
        job = null
        shown.value = shown.value?.copy(phase = Phase.Offered)
    }

    fun install(scope: CoroutineScope) {
        val current = shown.value ?: return
        val root = (current.phase as? Phase.Ready)?.root ?: return
        shown.value = current.copy(phase = Phase.Installing)
        scope.launch {
            val started = withContext(Dispatchers.IO) { runCatching { ForkUpdater.startUpdater(root) } }
            started.exceptionOrNull()?.let { failure ->
                shown.value = current.copy(phase = Phase.Failed(failure.message ?: failure.toString()))
                return@launch
            }
            // The tray's Quit: queue, hand-off note and AppCDS archive are written as on any quit.
            javax.swing.SwingUtilities.invokeLater { DesktopTrayMenu.forkQuit() }
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
        onDismissRequest = { if (phase == ForkUpdateFlow.Phase.Offered || phase is ForkUpdateFlow.Phase.Failed) ForkUpdateFlow.later() },
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
            }
        },
        dismissButton = {
            when (phase) {
                ForkUpdateFlow.Phase.Offered, is ForkUpdateFlow.Phase.Ready ->
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
