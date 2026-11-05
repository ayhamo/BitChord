package com.music.bitchord.desktop

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Fork: one BitChord per data folder. Closing hides the window to the tray, so starting
 * `BitChord.exe` again used to open a second JVM on the same settings, queue and offline manifest,
 * each writing over the other. Neither PC fork has this.
 *
 * The first start holds an OS lock on `instance.lock` in the data folder in use (the portable
 * `data\`, else `.local/share/bitchord` under `user.home`, which is where docker/pc-run.sh's plain
 * mode and dev runs keep theirs, so each folder has its own instance) and listens on a random
 * loopback port (127.0.0.1 only, nothing exposed), written with a random key into `instance.port`.
 * A later start that finds the lock taken sends that key to the port, the first one brings its
 * window back (from the tray, from minimized) and answers, and the later one exits. The lock goes
 * with the process, so a crash leaves nothing to clean up; a stale port file is just rewritten.
 *
 * `-Dbitchord.singleInstance=false` turns it off.
 */
internal object ForkSingleInstance {

    sealed interface Claim {
        /** This process owns the folder; [close] gives it up (tests, and nothing else). */
        class Primary internal constructor(
            private val channel: FileChannel,
            private val lock: FileLock,
            private val server: ServerSocket,
        ) : Claim, AutoCloseable {
            val port: Int get() = server.localPort

            /**
             * Stops answering later starts, keeping the lock. Quitting calls this first: the
             * listener is a daemon thread that went on answering SHOW while the process wound down,
             * so a start made in that moment handed over to a window that was going away and
             * exited, and nothing started at all.
             */
            fun stopAnswering() {
                runCatching { server.close() }
            }

            override fun close() {
                runCatching { server.close() }
                runCatching { lock.release() }
                runCatching { channel.close() }
            }
        }

        /** Another process owns it; [handedOver] says whether it answered. */
        data class Secondary(val handedOver: Boolean) : Claim
    }

    /** Kept for the life of the process, so the lock and the socket stay held. */
    @Volatile
    private var held: Claim.Primary? = null

    /**
     * For quitting, before anything else winds down: later starts are no longer answered (see
     * [Claim.Primary.stopAnswering]) and become the owner as soon as this process has ended.
     *
     * The lock itself stays until the process ends, on purpose: the shutdown hooks still write
     * the settings and the queue (ForkFilePreferences, ForkPlaybackWrites), and a new start that
     * read them before that would carry on from the old values and write over the last change.
     * A start that finds no answer keeps trying the lock for [HANDOVER_WAIT_MS] instead.
     */
    fun release() {
        held?.stopAnswering()
    }

    /**
     * First thing in `main`, before anything reads a setting: ends the process when another
     * BitChord already runs on this data folder, after asking it to show its window.
     */
    fun claimOrExit() {
        if (System.getProperty("bitchord.singleInstance") == "false") return
        val claim = runCatching { claim(folder(), onShow = DesktopWindowVisibility::show) }
            .onFailure { DesktopTrackLog.log("Single instance: not checked (${it.javaClass.simpleName}: ${it.message})") }
            .getOrNull() ?: return
        when (claim) {
            is Claim.Primary -> held = claim
            is Claim.Secondary -> {
                DesktopTrackLog.log(
                    if (claim.handedOver) "Single instance: BitChord already runs here, its window brought back"
                    else "Single instance: BitChord already runs here but did not answer; not starting a second one",
                )
                kotlin.system.exitProcess(0)
            }
        }
    }

    /** The data folder in use: the portable `data\`, or the per-user data home of a plain run. */
    internal fun folder(): Path = ForkPortable.dataDir ?: run {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank) ?: "${System.getProperty("user.home")}/.local/share"
        Path.of(base, "bitchord")
    }

    /**
     * Takes [dir]'s lock and starts listening, or, when another process (or an earlier claim in
     * this one) holds it, asks that one to show itself. [onShow] runs on the listener's thread.
     */
    internal fun claim(dir: Path, onShow: () -> Unit, waitMs: Long = HANDOVER_WAIT_MS): Claim {
        Files.createDirectories(dir)
        val channel = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = tryLock(channel) ?: when (val waited = handOverOrLock(dir, channel, waitMs)) {
            is Waited.Freed -> waited.lock
            is Waited.Answered, is Waited.Neither -> {
                runCatching { channel.close() }
                return Claim.Secondary(handedOver = waited is Waited.Answered)
            }
        }
        val server = ServerSocket()
        try {
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4)
            val key = newKey()
            // Written in full under a temporary name, then moved: a later start never reads half a file.
            val portFile = dir.resolve(PORT_FILE)
            val partial = dir.resolve("$PORT_FILE.tmp")
            Files.writeString(partial, "${server.localPort} $key")
            runCatching { Files.move(partial, portFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                .onFailure {
                    // Windows refuses the replace while a later start has the old file open for a read.
                    Files.deleteIfExists(partial)
                    Files.writeString(portFile, "${server.localPort} $key")
                }
            listen(server, key, onShow)
        } catch (e: Exception) {
            runCatching { server.close() }
            runCatching { lock.release() }
            runCatching { channel.close() }
            throw e
        }
        return Claim.Primary(channel, lock, server)
    }

    private fun listen(server: ServerSocket, key: String, onShow: () -> Unit) {
        thread(name = "BitChord single instance", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: continue
                runCatching {
                    socket.use {
                        it.soTimeout = 2_000
                        // Loopback only by the bind; checked again in case of an odd stack.
                        if (!it.inetAddress.isLoopbackAddress) return@use
                        // Read to the first newline, capped: whatever else listens on loopback gets no
                        // more than a line's worth of memory, and the key is compared in constant time.
                        val line = readLine(it.getInputStream(), MAX_LINE) ?: return@use
                        if (!java.security.MessageDigest.isEqual(line.toByteArray(StandardCharsets.UTF_8), "$SHOW $key".toByteArray(StandardCharsets.UTF_8))) return@use
                        runCatching(onShow)
                        OutputStreamWriter(it.getOutputStream(), StandardCharsets.UTF_8).apply {
                            write("$OK\n")
                            flush()
                        }
                    }
                }
            }
        }
    }

    /** One line of at most [max] bytes (null past that or at the end), without its newline. */
    internal fun readLine(input: java.io.InputStream, max: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == NEWLINE) break
            if (bytes.size() >= max) return null
            bytes.write(b)
        }
        return bytes.toString(StandardCharsets.UTF_8).trimEnd(CARRIAGE_RETURN)
    }

    private fun tryLock(channel: FileChannel): FileLock? = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null // held by this same JVM: as good as another process for this purpose
    }

    /**
     * Asks the owner to show itself, for up to [waitMs]: an owner that has just taken the lock may
     * not have written its port yet, or the file may still name the port of a crashed one.
     *
     * Between tries the lock is tried again. An owner that is quitting no longer answers (see
     * [release]) but holds the lock until its process ends; this start used to give up on it and
     * exit, so a Quit followed straight away by a new start left no BitChord running. Now it
     * becomes the owner once that process is gone.
     */
    private fun handOverOrLock(dir: Path, channel: FileChannel, waitMs: Long): Waited {
        allowForeground()
        val deadline = System.nanoTime() + waitMs * 1_000_000
        var pause = FIRST_PAUSE_MS
        do {
            val parts = runCatching { Files.readString(dir.resolve(PORT_FILE)).trim().split(' ') }.getOrNull()
            val port = parts?.getOrNull(0)?.toIntOrNull()
            val key = parts?.getOrNull(1)
            if (port != null && key != null && ping(port, key)) return Waited.Answered
            tryLock(channel)?.let { return Waited.Freed(it) }
            Thread.sleep(pause)
            pause = (pause * 2).coerceAtMost(MAX_PAUSE_MS)
        } while (System.nanoTime() < deadline)
        return tryLock(channel)?.let(Waited::Freed) ?: Waited.Neither
    }

    /** How a wait on a taken folder ended: the owner answered, its lock came free, or neither. */
    private sealed interface Waited {
        data object Answered : Waited
        class Freed(val lock: FileLock) : Waited
        data object Neither : Waited
    }

    private fun ping(port: Int, key: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
            socket.soTimeout = 3_000
            OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8).apply {
                write("$SHOW $key\n")
                flush()
            }
            BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine() == OK
        }
    }.getOrDefault(false)

    /**
     * Windows lets only the process the listener just started take the foreground; this start
     * passes that right on (ASFW_ANY), so the running window comes to the front instead of only
     * flashing on the taskbar.
     */
    private fun allowForeground() {
        if (!DesktopPlatform.isWindows) return
        runCatching { ForegroundRights.INSTANCE.AllowSetForegroundWindow(-1) }
    }

    @Suppress("FunctionName")
    private interface ForegroundRights : com.sun.jna.Library {
        fun AllowSetForegroundWindow(processId: Int): Boolean

        companion object {
            val INSTANCE: ForegroundRights by lazy { com.sun.jna.Native.load("user32", ForegroundRights::class.java) }
        }
    }

    private fun newKey(): String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private const val LOCK_FILE = "instance.lock"
    private const val PORT_FILE = "instance.port"
    private const val SHOW = "SHOW"
    private const val OK = "OK"
    private const val HANDOVER_WAIT_MS = 8_000L
    private const val FIRST_PAUSE_MS = 100L
    private const val MAX_PAUSE_MS = 500L
    /** "SHOW " and a 32-digit key, with room to spare. */
    private const val MAX_LINE = 128
    private const val NEWLINE = 10
    private val CARRIAGE_RETURN = 13.toChar()
}
