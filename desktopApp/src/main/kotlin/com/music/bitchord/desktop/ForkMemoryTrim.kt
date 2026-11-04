package com.music.bitchord.desktop

import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Fork: decoded pictures let go of while the window is away (hidden to the tray, or minimized),
 * from Desktop Revived a6b5311c ("optimize startup and reduce idle memory", their
 * `DesktopMemoryOptimizer.onWindowHidden` called from `DesktopWindowVisibility.onCloseRequest` and
 * a `LaunchedEffect(visible, state.isMinimized)` in `Main.kt`).
 *
 * A PC left playing in the tray for hours still held every cover it had decoded: the artwork
 * budget (`DesktopArtworkCache.budgetBytes`, a tenth of the 512 MB heap) and Coil's memory cache
 * for the shared player's covers ([ForkOffline.installImageLoader]). Nobody sees either while the
 * window is away, and the heap they pin is what Task Manager reports as the app's size.
 *
 * Where ours differs from Revived's, and why:
 * - One hook, the `LaunchedEffect` in `Main.kt`, not two: our tray close only flips
 *   [DesktopWindowVisibility.visible], which that effect already keys on, so the line Revived also
 *   put in `onCloseRequest` would trim twice.
 * - It waits [SETTLE_MS] first and the effect's restart cancels the wait: a quick minimize and
 *   restore (Win+D and back, a game alt-tab) would otherwise throw away every cover on screen and
 *   fetch them again over the network, `DesktopArtworkCache.load` having no disk copy.
 * - No `System.gc()`: on the PC's G1 (`-XX:+UseG1GC`) that is a full stop-the-world collection
 *   of the heap while the audio thread is feeding the sink, which risks the very click the engine
 *   works to avoid. The launcher's `-XX:G1PeriodicGCInterval=20000` already runs a concurrent
 *   collection on an idle heap every 20 s and gives the freed regions back to Windows, so the
 *   bitmaps dropped here leave the process within about 20 s anyway.
 * - No `DesktopMediaCache.trim()`: that is the disk cache (audio, clips), not memory, and it is
 *   already trimmed after every write.
 */
internal object ForkMemoryTrim {

    /** How long the window must stay away before its pictures are dropped. */
    private const val SETTLE_MS = 30_000L

    /**
     * Called from the window's `LaunchedEffect(visible, state.isMinimized)`; a change of either
     * cancels this, so only a window that has stayed away [SETTLE_MS] is trimmed.
     */
    suspend fun whileAway(away: Boolean) {
        if (!away) return
        delay(SETTLE_MS)
        withContext(Dispatchers.IO) { trim() }
    }

    private fun trim() {
        DesktopArtworkCache.clear()
        runCatching { SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear() }
        DesktopTrackLog.log("memory: window away ${SETTLE_MS / 1000} s, decoded covers dropped (Revived a6b5311c)")
    }
}
