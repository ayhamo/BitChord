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
    fun mainEntered() {
        Thread(ForkAppCdsStamp::check, "BitChord-CdsStamp").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

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

    /** Whether the first frame has really been drawn ([awaitFirstFrame] also returns after its cap); ForkUpdater's clean-up waits on it. */
    val firstFrameShown: Boolean get() = firstFrame.isCompleted

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

/**
 * Fork: keeps the AppCDS archive (`-XX:SharedArchiveFile=…\data\bitchord.jsa -XX:+AutoCreateSharedArchive`,
 * written by `windowsPortable` into `BitChord.args`) in step with the app's jars.
 *
 * Measured 2026-11-06 (portable zip, same PC): first frame 1.65–1.8 s with the archive, 2.8–3.0 s
 * when the JVM finds it out of date, 17.5–20.6 s on the one start that records a new one. An
 * update replaces the jars, the JVM then rejects the archive ("timestamp has changed"), but it does
 * not write a new one at exit while the old file is there (tried with the file writable too), so
 * every start after an update would stay on the 3 s path. A stamp of the class path is kept
 * beside the archive; when it differs, the archive is deleted, so the next start records a fresh
 * one. A rejected archive is not mapped, so Windows lets it go; a valid one that is mapped (the
 * first start with this code, before any stamp) refuses, which is right, and the stamp is written.
 */
internal object ForkAppCdsStamp {

    fun check() {
        runCatching {
            val archive = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
                .firstOrNull { it.startsWith(ARCHIVE_FLAG) }
                ?.removePrefix(ARCHIVE_FLAG)
                ?.let { java.io.File(it) }
                ?: return
            val stampFile = java.io.File(archive.parentFile, archive.name + ".stamp")
            val current = classPathStamp()
            val kept = runCatching { stampFile.readText() }.getOrNull()
            if (kept == current) return
            val existed = archive.exists()
            val deleted = existed && run { archive.setWritable(true); archive.delete() }
            val next = stampAfter(kept, current, existed, deleted)
            if (existed) {
                DesktopTrackLog.log(
                    when {
                        deleted -> "startup: AppCDS archive out of date, deleted; the next start records a new one"
                        next == current + RETRY -> "startup: AppCDS archive could not be deleted, trying again next start"
                        else -> "startup: AppCDS archive kept (in use)"
                    },
                )
            }
            stampFile.writeText(next)
        }.onFailure { DesktopTrackLog.log("startup: AppCDS stamp check failed: ${it.message}") }
    }

    /**
     * The stamp to write once [kept] (null: none yet) has been found to differ from [current] and the
     * archive, if it [existed], was asked to go.
     *
     * A valid archive is mapped by this JVM and can't go, which is right; but something else
     * (antivirus, the indexer) can hold a stale one too, and a stamp written now would keep it for
     * good. So the first failure only marks the stamp ([RETRY]), the next start tries again, and a
     * second failure in a row takes the archive as in use.
     */
    internal fun stampAfter(kept: String?, current: String, existed: Boolean, deleted: Boolean): String =
        if (existed && !deleted && kept != current + RETRY) current + RETRY else current

    /**
     * The jars' names, sizes and times, what the JVM's own check compares, and the runtime: an archive
     * from another JVM build is rejected too, and an update that only bumps the bundled runtime leaves
     * the jars as they were.
     */
    private fun classPathStamp(): String {
        val jars = System.getProperty("java.class.path").orEmpty()
            .split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString("\n") { path ->
                val f = java.io.File(path)
                "${f.name}:${f.length()}:${f.lastModified()}"
            }
        return "jvm:${System.getProperty("java.vm.version").orEmpty()}\n$jars"
    }

    private const val ARCHIVE_FLAG = "-XX:SharedArchiveFile="
    internal const val RETRY = "\nretry"
}
