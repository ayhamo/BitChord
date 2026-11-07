package com.music.bitchord.desktop

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

/**
 * Fork: the update dialog's state ([ForkUpdateFlow]): a late progress step doesn't undo Cancel or
 * Later, and "Install now" never leaves the dialog on "Closing BitChord…" with no way out.
 */
class ForkUpdateFlowTest {

    private fun info(build: Int = 2) = DesktopUpdateChecker.UpdateInfo(
        version = "1.9.1",
        releaseUrl = "https://example.invalid/release",
        downloadUrl = "https://example.invalid/BitChord.Portable.zip",
        notes = "Build: $build",
        build = build,
        sumsUrl = "https://example.invalid/SHA256SUMS.txt",
    )

    @AfterTest
    fun forget() {
        ForkUpdateFlow.shown.value = null
    }

    // ---- progress -------------------------------------------------------------------------------

    @Test
    fun progressMovesTheBarWhileThatReleaseDownloads() {
        val release = info()
        val downloading = ForkUpdateFlow.Shown(release, ForkUpdateFlow.Phase.Downloading(null))
        assertEquals(ForkUpdateFlow.Phase.Downloading(0.4f), ForkUpdateFlow.withProgress(downloading, release, 0.4f)?.phase)
    }

    @Test
    fun aStepAfterCancelDoesNotPutTheBarBack() {
        val release = info()
        val offered = ForkUpdateFlow.Shown(release, ForkUpdateFlow.Phase.Offered)
        assertSame(offered, ForkUpdateFlow.withProgress(offered, release, 0.5f))
    }

    @Test
    fun aStepAfterLaterDoesNotBringTheDialogBack() {
        assertNull(ForkUpdateFlow.withProgress(null, info(), 0.5f))
    }

    @Test
    fun aStepOfAnotherReleaseIsDropped() {
        val newer = ForkUpdateFlow.Shown(info(build = 3), ForkUpdateFlow.Phase.Downloading(0.1f))
        assertSame(newer, ForkUpdateFlow.withProgress(newer, info(build = 2), 0.9f))
    }

    // ---- install --------------------------------------------------------------------------------

    private fun ready(root: Path = Path.of("staged")) = ForkUpdateFlow.Shown(info(), ForkUpdateFlow.Phase.Ready(root)).also {
        ForkUpdateFlow.shown.value = it
    }

    @Test
    fun aQuitThatNeverHappensEndsInAMessageWithAWayOut() = runBlocking {
        ready()
        var started: Path? = null
        var quits = 0
        val job = ForkUpdateFlow.install(this, start = { started = it }, quit = { quits++ }, graceMs = 50)
        assertEquals(ForkUpdateFlow.Phase.Installing, ForkUpdateFlow.shown.value?.phase)
        job?.join()
        assertEquals(Path.of("staged"), started)
        assertEquals(1, quits)
        // Its own phase, not Failed: Failed's "Try again" would download into staged\ while the
        // updater already waits to move it in.
        assertEquals(ForkUpdateFlow.Phase.QuitStalled, ForkUpdateFlow.shown.value?.phase)
    }

    @Test
    fun aDialogClosedWhileQuittingStaysClosed() = runBlocking {
        ready()
        val job = ForkUpdateFlow.install(this, start = {}, quit = { ForkUpdateFlow.shown.value = null }, graceMs = 50)
        job?.join()
        assertNull(ForkUpdateFlow.shown.value)
    }

    @Test
    fun aCheckReplacesOnlyAnIdleDialog() {
        assertEquals(true, ForkUpdateFlow.replaceable(null))
        assertEquals(true, ForkUpdateFlow.replaceable(ForkUpdateFlow.Phase.Offered))
        assertEquals(true, ForkUpdateFlow.replaceable(ForkUpdateFlow.Phase.Failed("x")))
        assertEquals(false, ForkUpdateFlow.replaceable(ForkUpdateFlow.Phase.Downloading(0.5f)))
        assertEquals(false, ForkUpdateFlow.replaceable(ForkUpdateFlow.Phase.Ready(Path.of("staged"))))
        assertEquals(false, ForkUpdateFlow.replaceable(ForkUpdateFlow.Phase.Installing))
        assertEquals(false, ForkUpdateFlow.replaceable(ForkUpdateFlow.Phase.QuitStalled))
    }

    @Test
    fun anUpdaterThatWontStartSaysWhyAndNothingQuits() = runBlocking {
        ready()
        var quits = 0
        val job = ForkUpdateFlow.install(this, start = { throw ForkUpdater.UpdateFailed("no updater") }, quit = { quits++ }, graceMs = 50)
        job?.join()
        assertEquals(0, quits)
        assertEquals(ForkUpdateFlow.Phase.Failed("no updater"), ForkUpdateFlow.shown.value?.phase)
    }

    @Test
    fun installNeedsAStagedUpdate() = runBlocking {
        ForkUpdateFlow.shown.value = ForkUpdateFlow.Shown(info(), ForkUpdateFlow.Phase.Offered)
        assertNull(ForkUpdateFlow.install(this, start = { error("not started") }, quit = { error("not quit") }, graceMs = 50))
        assertEquals(ForkUpdateFlow.Phase.Offered, ForkUpdateFlow.shown.value?.phase)
    }
}
