package com.music.bitchord.desktop

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/**
 * Java Preferences kept in files inside the portable folder (`data\prefs`), instead of the
 * registry (`HKCU\Software\JavaSoft\Prefs`) that Java uses on Windows.
 *
 * Every setting, saved login and YouTube/Spotify session of the desktop app goes through
 * `Preferences.userRoot()` (DesktopPersistence, DesktopAccounts, ForkNavidrome, ...), so this is
 * what makes copying the folder carry them. Only chosen when the portable launcher asks for it
 * ([ForkPortable.install] sets `java.util.prefs.PreferencesFactory` before anything reads a
 * preference); a dev or test run keeps the registry.
 *
 * Layout: one folder per node (its name percent-encoded so any node name is a safe, case-distinct
 * Windows folder name), its values in `=node.properties` inside it. `=` is always encoded in a
 * node's folder name, so the values file can never collide with a child.
 *
 * Writes are batched: the registry wrote each `put` at once and the app never calls `flush()` for
 * most settings, so a change is written a second after the last one, on `flush()`, and at exit.
 * Without the delay, a chunked value (DesktopPreferenceChunks, up to hundreds of KB) rewrote its
 * node's file once per chunk.
 *
 * A write that fails (OneDrive or an antivirus holding the file, a folder in the way) is not
 * dropped: the node stays dirty and is tried again on a backoff, at exit, and on the next change,
 * and an explicit `flush()` throws [BackingStoreException]. Before, a failed write was logged and
 * forgotten, so the change, or a whole backup import, was lost at exit (audit 2026-11-05).
 */
class ForkFilePreferencesFactory : PreferencesFactory {
    override fun userRoot(): Preferences = ForkFilePreferences.userRoot

    override fun systemRoot(): Preferences = ForkFilePreferences.systemRoot
}

internal class ForkFilePreferences private constructor(
    parent: ForkFilePreferences?,
    name: String,
    private val folder: File,
) : AbstractPreferences(parent, name) {

    private val file = File(folder, VALUES_FILE)
    private val values = sortedMapOf<String, String>()

    /** Bumped under [lock] on every change; what was last written is [written]. */
    private var version = 0L
    private var written = 0L

    @Volatile
    private var removed = false

    /** Serialises writes of this node; taken without [lock] held by the writer thread, see [writeNow]. */
    private val writeLock = Any()

    init {
        newNode = !folder.isDirectory
        if (file.isFile) {
            runCatching {
                val read = Properties()
                file.inputStream().buffered().use { read.load(it) }
                read.stringPropertyNames().forEach { values[it] = read.getProperty(it) }
            }.onFailure { System.err.println("BitChord: settings file unreadable, starting it empty: ${file.name} (${it.message})") }
        }
        // A node made by node() exists from then on, as in the registry, even with nothing in it.
        if (newNode && parent != null) changed()
    }

    override fun putSpi(key: String, value: String) {
        values[key] = value
        changed()
    }

    override fun getSpi(key: String): String? = values[key]

    override fun removeSpi(key: String) {
        if (values.remove(key) != null) changed()
    }

    override fun removeNodeSpi() {
        removed = true
        values.clear()
        Store.forget(this)
        synchronized(writeLock) {
            file.delete()
            File(folder, TEMP_FILE).delete()
            folder.delete() // only empty: its children were removed first
        }
    }

    override fun keysSpi(): Array<String> = values.keys.toTypedArray()

    override fun childrenNamesSpi(): Array<String> =
        folder.listFiles { child -> child.isDirectory }.orEmpty()
            .mapNotNull { decode(it.name) }
            .toTypedArray()

    override fun childSpi(name: String): AbstractPreferences = ForkFilePreferences(this, name, File(folder, encode(name)))

    override fun syncSpi() = flushSpi()

    override fun flushSpi() {
        Store.forget(this)
        if (!writeNow()) {
            Store.retry(this)
            throw BackingStoreException("could not save settings to ${folder.name}")
        }
    }

    private fun changed() {
        version++
        if (Store.changed(this) && !writeNow()) Store.retry(this)
    }

    /**
     * Writes the node if it changed since the last write. The snapshot is taken under [lock] and
     * the file written under [writeLock] only, so a `flush()` (which holds [lock] and then wants
     * [writeLock]) and the timer thread can never wait on each other.
     *
     * False when the write failed: the caller keeps the node dirty ([Store.retry]).
     */
    fun writeNow(): Boolean {
        val (snapshot, at) = synchronized(lock) {
            if (removed) return true
            HashMap(values) to version
        }
        return synchronized(writeLock) {
            if (removed || at <= written) return true
            runCatching {
                folder.mkdirs()
                val temp = File(folder, TEMP_FILE)
                val out = Properties()
                snapshot.forEach { (key, value) -> out.setProperty(key, value) }
                // The stream form: it escapes everything outside Latin-1 as \uXXXX, so any value
                // the registry could hold survives.
                temp.outputStream().buffered().use { out.store(it, null) }
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                written = at
            }.onFailure { System.err.println("BitChord: could not save settings to ${file.parentFile?.name}: ${it.message}") }
                .isSuccess
        }
    }

    /** Whether the node holds changes no write has landed yet; for the tests. */
    internal val unsaved: Boolean get() = synchronized(lock) { !removed && version > synchronized(writeLock) { written } }

    /** The changed nodes and the one thread that writes them. */
    private object Store {
        private val dirty = LinkedHashSet<ForkFilePreferences>()

        /**
         * Every node changed this session. The exit hook writes each one that is still behind
         * (`written < version`; [writeNow] skips the rest), not only those on [dirty]: a node whose
         * timer write was failing as the hook ran had already been taken off it.
         */
        private val touched = LinkedHashSet<ForkFilePreferences>()
        private var pending: ScheduledFuture<*>? = null
        private val writer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "bitchord-settings").apply { isDaemon = true }
        }

        /**
         * Set by the exit hook. Shutdown hooks run side by side, and the app's own (DesktopPresence,
         * DesktopWindowsMedia, ...) may still put a value after this one has written everything; the
         * timer is a daemon thread that never runs again, so from then on a change is written by
         * the thread that makes it, as the registry did with every put.
         */
        private var exiting = false

        init {
            // Inside the try: an object first loaded during shutdown can no longer add a hook
            // (IllegalStateException), and is then already exiting.
            try {
                Runtime.getRuntime().addShutdownHook(Thread({ writeAll(exit = true) }, "bitchord-settings-exit"))
            } catch (_: IllegalStateException) {
                exiting = true
            }
        }

        /** True when the caller is to write [node] now itself (the app is exiting). */
        @Synchronized
        fun changed(node: ForkFilePreferences): Boolean {
            touched += node
            if (exiting) return true
            dirty += node
            if (pending == null) pending = writer.schedule({ writeAll(exit = false) }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
            return false
        }

        @Synchronized
        fun forget(node: ForkFilePreferences) {
            dirty -= node
        }

        /** Consecutive timer passes that left a node unwritten; the next retry waits longer. */
        private var failedPasses = 0

        /**
         * [node]'s write failed: kept dirty and tried again after a backoff (one, two, four... up
         * to [MAX_RETRY_DELAY_MS] seconds while it keeps failing), and in any case by the exit hook.
         * Not a loop: one scheduled pass at a time, as for any change.
         */
        @Synchronized
        fun retry(node: ForkFilePreferences) {
            touched += node
            dirty += node
            if (exiting || pending != null) return
            val delay = (WRITE_DELAY_MS shl failedPasses.coerceAtMost(6)).coerceAtMost(MAX_RETRY_DELAY_MS)
            pending = writer.schedule({ writeAll(exit = false) }, delay, TimeUnit.MILLISECONDS)
        }

        fun writeAll(exit: Boolean) {
            val nodes = synchronized(this) {
                if (exit) exiting = true
                pending = null
                (if (exit) (dirty + touched).toList() else dirty.toList()).also { dirty.clear() }
            }
            val failed = nodes.filterNot(ForkFilePreferences::writeNow)
            synchronized(this) { failedPasses = if (failed.isEmpty()) 0 else failedPasses + 1 }
            failed.forEach(::retry)
        }
    }

    companion object {
        private const val VALUES_FILE = "=node.properties"
        private const val TEMP_FILE = "=node.tmp"
        private const val WRITE_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        /** `data\prefs`, or a folder in the home when the factory is used without the portable launcher. */
        private val base: File by lazy {
            ForkPortable.dataDir?.toFile()?.let { File(it, "prefs") }
                ?: File(System.getProperty("user.home"), ".bitchord-prefs")
        }

        val userRoot: Preferences by lazy { ForkFilePreferences(null, "", File(base, "user")) }
        val systemRoot: Preferences by lazy { ForkFilePreferences(null, "", File(base, "system")) }

        /** The folder holding the user settings; its existence is what [ForkPortable] calls "has settings". */
        val userFolder: File get() = File(base, "user")

        /** A tree of its own in [folder], for the tests (the app has one, [userRoot]). */
        internal fun rootAt(folder: File): Preferences = ForkFilePreferences(null, "", folder)

        private val RESERVED = setOf("con", "prn", "aux", "nul") +
            (1..9).flatMap { listOf("com$it", "lpt$it") }

        /**
         * A node name as a folder name: lower-case letters, digits, `.`, `_` and `-` as they are,
         * everything else (upper case included, since Windows folders ignore case) as `%XX` of its
         * UTF-8 bytes. A trailing dot (which Windows drops) and a reserved device name are encoded too.
         */
        internal fun encode(name: String): String {
            val out = StringBuilder()
            val bytes = name.toByteArray(Charsets.UTF_8)
            bytes.forEachIndexed { index, byte ->
                val c = (byte.toInt() and 0xff).toChar()
                val plain = c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' ||
                    (c == '.' && index != bytes.lastIndex && name != "." && name != "..")
                if (plain) out.append(c) else out.append('%').append("%02X".format(byte.toInt() and 0xff))
            }
            val encoded = out.toString()
            val stem = encoded.substringBefore('.')
            return if (stem in RESERVED) "%%%02X".format(encoded[0].code) + encoded.substring(1) else encoded
        }

        /**
         * The node name a folder stands for, or null for a folder this did not make. Checked by
         * encoding the name back: a folder put there by hand ("Backup", "%4a") would otherwise be
         * listed as a child that [childSpi] then opens in a different, empty folder.
         */
        internal fun decode(folder: String): String? = decodeLoosely(folder)?.takeIf { encode(it) == folder }

        private fun decodeLoosely(folder: String): String? = runCatching {
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < folder.length) {
                val c = folder[i]
                if (c == '%') {
                    bytes.write(folder.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    require(c.code < 0x80)
                    bytes.write(c.code)
                    i++
                }
            }
            bytes.toString(Charsets.UTF_8)
        }.getOrNull()
    }
}
