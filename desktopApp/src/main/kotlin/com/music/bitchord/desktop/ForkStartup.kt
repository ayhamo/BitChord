package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fork: the PC app's start-up order, after Desktop Revived 3d73286b and its family (6a0720c8,
 * a6b5311c; their docs/performance/1.8.3-startup-memory.md): "stop doing everything at launch".
 *
 * Before this, the app's one start-up effect in [BitChordDesktopApp] walked the Music and Downloads
 * folders four levels deep for Local Music, then stat'ed every recorded download, and only then
 * asked for Home, so the Home spinner waited on the disk. Local Music now waits for its page (or a
 * few seconds after the first frame), the download check runs beside Home, and the Library's start
 * fetch (which [ForkLibraryRefresh] keeps: the owner asked for refresh on start) waits for the first
 * frame instead of racing it.
 *
 * The time logged is measured from the JVM process start where the OS reports it (which includes the
 * JVM's own start and class loading, the part AppCDS shortens), else from [main].
 */
internal object ForkStartup {

    /** When [main] began: this object is touched there first. */
    private val mainAtMs = System.currentTimeMillis()

    /** The process's start, from the OS; null where it is not reported. */
    private val processAtMs: Long? = runCatching {
        ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(null)
    }.getOrNull()

    private val firstFrame = CompletableDeferred<Unit>()

    /** Called first in [main], so [mainAtMs] is the real entry time. */
    fun mainEntered() = Unit

    fun markFirstFrame() {
        if (!firstFrame.complete(Unit)) return
        val now = System.currentTimeMillis()
        val fromMain = now - mainAtMs
        val line = processAtMs?.let { "startup: first frame at ${now - it} ms (main() +$fromMain ms)" }
            ?: "startup: first frame at $fromMain ms after main()"
        DesktopTrackLog.log(line)
    }

    /**
     * Returns once the window has drawn its first frame. Bounded: a window that starts hidden draws
     * nothing, and the work waiting here must still run.
     */
    suspend fun awaitFirstFrame() {
        withTimeoutOrNull(FIRST_FRAME_FALLBACK_MS) { firstFrame.await() }
    }

    /** How long Local Music waits after the first frame before scanning anyway, if its page is not opened. */
    const val LOCAL_MUSIC_DELAY_MS = 4_000L

    private const val FIRST_FRAME_FALLBACK_MS = 10_000L
}

/**
 * Marks the first frame. A LaunchedEffect starts after the first composition is applied, past that
 * frame's frame-callback phase, so the callback it waits for runs in the next frame: by then the
 * first one has been drawn.
 */
@Composable
internal fun ForkFirstFrameProbe() {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        ForkStartup.markFirstFrame()
    }
}
