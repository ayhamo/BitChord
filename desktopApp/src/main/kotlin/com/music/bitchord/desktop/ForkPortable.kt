package com.music.bitchord.desktop

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.prefs.Preferences
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * The portable release: `BitChord Portable\BitChord.exe` (desktopApp/native/launcher) starts the
 * app with `-Dbitchord.portableData=<folder>\data`, `-Duser.home=<folder>\data` and
 * `-Djava.io.tmpdir=<folder>\data\tmp`, so everything the app keeps stays in that folder:
 *
 * - settings, saved logins, YouTube and Spotify sessions: Java Preferences, here files under
 *   `data\prefs` ([ForkFilePreferences]) instead of the registry;
 * - the app's folders, which upstream puts under `user.home` (`.local/share/bitchord` for Automix
 *   analyses, offline songs and saved queues, `.cache/bitchord` for clips and translations,
 *   `.local/state/bitchord` for the log) and the libraries' unpacked natives (`.javacpp`,
 *   `.skiko`, `.openjfx`): they follow `user.home` without a change;
 * - temporary files (cookie store copies, the sign-in and Spotify pages' storage): `java.io.tmpdir`;
 * - the BotGuard page's web storage, which JavaFX would put in `%APPDATA%\java\webview`:
 *   [webViewDirectory].
 *
 * The environment is not redirected: DesktopBrowserCookies reads the real `APPDATA` and
 * `LOCALAPPDATA` on purpose (other browsers' cookies, for signing in). The listener's own folders
 * (Music, Downloads, the folder picker) use [userHome], the real profile, not `data\`.
 *
 * Without the property (dev runs, docker/pc-run.sh's plain mode) nothing here changes anything.
 */
internal object ForkPortable {

    const val DATA_PROPERTY = "bitchord.portableData"

    /** `<folder>\data`, or null when not started by the portable launcher. */
    val dataDir: Path? = System.getProperty(DATA_PROPERTY)?.takeIf(String::isNotBlank)?.let { Path.of(it).toAbsolutePath() }

    /**
     * The Windows user's own home, for the listener's own folders. In the portable app `user.home`
     * is `data\`; Windows' `USERPROFILE` is what `user.home` was before.
     */
    val userHome: String
        get() = if (dataDir != null) {
            System.getenv("USERPROFILE")?.takeIf(String::isNotBlank) ?: System.getProperty("user.home")
        } else {
            System.getProperty("user.home")
        }

    /**
     * Fork: first thing in `main`. BitChord.exe passes `.` for its folder when the folder's name is
     * outside the ANSI code page and has no 8.3 short name (see java_folder in BitChord.c), so
     * `user.home` and `java.io.tmpdir` arrive relative to the working folder. Made absolute here,
     * before any library reads them, so a native library handed one (System.load wants an absolute
     * path) or anything that changes the working folder later still finds the right place.
     */
    fun absolutePaths() {
        if (dataDir == null) return
        for (key in listOf("user.home", "java.io.tmpdir", DATA_PROPERTY)) {
            absolutized(System.getProperty(key))?.let { System.setProperty(key, it) }
        }
    }

    /** [value] made absolute against the working folder, or null when it already is (or is blank). */
    internal fun absolutized(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val path = runCatching { Path.of(value) }.getOrNull() ?: return null
        return if (path.isAbsolute) null else path.toAbsolutePath().normalize().toString()
    }

    /** Where a WebView without storage of its own keeps it, in the portable app; null otherwise. */
    fun webViewDirectory(): File? = dataDir?.resolve("webview")?.toFile()?.also { it.mkdirs() }

    /**
     * First thing in `main`, before anything reads a preference: Java picks its preferences store
     * once, at the first use of [Preferences], from this property.
     */
    fun install() {
        val data = dataDir ?: return
        System.setProperty("java.util.prefs.PreferencesFactory", ForkFilePreferencesFactory::class.java.name)
        runCatching {
            Files.createDirectories(data)
            Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")))
        }
        val unfinished = importMarker.exists()
        if ((!ForkFilePreferences.userFolder.exists() || unfinished) && System.getProperty(IMPORT_PROPERTY) != "false") {
            // The marker is there for the whole import and gone when it ends, so a start that was
            // killed during it (a closed console, Task Manager on a long copy) tries again; the
            // settings folder alone did not say that, since the timer of [ForkFilePreferences]
            // can write a first node a second into the settings copy. An import that fails with an
            // error is not marked for another try: the app then runs and saves settings of its
            // own, which a later import would overwrite with the old ones.
            runCatching {
                importMarker.parentFile?.mkdirs()
                importMarker.createNewFile()
            }
            if (unfinished) DesktopTrackLog.log("Portable: the last import did not finish, trying again")
            runCatching { importOldApp(data) }
                .onFailure { DesktopTrackLog.log("Portable: importing the old app's data stopped: ${it.javaClass.simpleName}: ${it.message} at ${it.stackTrace.take(4).joinToString(" < ") { f -> "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}" }}") }
            importMarker.delete()
        }
    }

    /** `-Dbitchord.portableImport=false` skips the import (docker/pc-run.sh, so a test run starts clean). */
    private const val IMPORT_PROPERTY = "bitchord.portableImport"

    /** `data\prefs\import-unfinished`: beside the settings tree, not in it, so never read as a node. */
    private val importMarker: File get() = File(ForkFilePreferences.userFolder.parentFile, "import-unfinished")

    /**
     * The first start of a portable folder (no `data\prefs` yet) takes over what the earlier zip
     * (`BitChord.bat`) kept in the Windows profile, so it opens signed in and set up as before.
     * The old places are only read. Files are copied as bytes, settings value by value through the
     * Preferences API; neither is looked into or logged, only the names of what was copied.
     * Saved Navidrome passwords are sealed with DPAPI for this Windows user, so they open again
     * for the same user.
     *
     * Files first and settings last; a start cut short during either tries again ([importMarker]),
     * keeping the files already copied.
     *
     * It runs before any window, so a large old cache or many offline songs keep the app from
     * showing until they are copied.
     */
    private fun importOldApp(data: Path) {
        val oldHome = System.getenv("USERPROFILE")?.takeIf(String::isNotBlank)?.let { Path.of(it) }
        if (oldHome != null) {
            // The same rule as the code that uses them: an XDG variable wins, otherwise the home.
            fun base(variable: String, underHome: String) {
                val xdg = System.getenv(variable)?.takeIf(String::isNotBlank)?.let { Path.of(it) }
                val from = (xdg ?: oldHome.resolve(underHome)).resolve("bitchord")
                val to = (xdg ?: data.resolve(underHome)).resolve("bitchord")
                if (Files.isDirectory(from) && from.toAbsolutePath() != to.toAbsolutePath()) importFolder(from, to, "$underHome/bitchord")
            }
            base("XDG_DATA_HOME", ".local/share")
            base("XDG_CACHE_HOME", ".cache")
        }
        importSettings()
    }

    /** Copies each entry of [from] into [to], one log line per entry. */
    private fun importFolder(from: Path, to: Path, label: String) {
        Files.list(from).use { it.toList() }.sortedBy { it.fileName.toString() }.forEach { entry ->
            val name = entry.fileName.toString()
            // The analyser's library and model are unpacked from the jar by DesktopAnalysisRuntime,
            // which keeps a copy that is the same size and no older: a copied old one would pass
            // that check and a native fix in this build would never load. Only the analyses move.
            val skipTopFiles = name == "analysis"
            val count = copyTree(entry, to.resolve(name), skipTopFiles)
            if (count > 0) DesktopTrackLog.log("Portable: imported $label/$name ($count files)")
        }
    }

    private fun copyTree(from: Path, to: Path, skipTopFiles: Boolean): Int {
        var count = 0
        // A download or copy still in progress in the old app, and this import's own leftover.
        fun unfinished(name: String) = name.endsWith(".part") || name.endsWith(".partial") || name.endsWith(COPY_SUFFIX)
        if (Files.isRegularFile(from)) return if (!unfinished(from.fileName.toString()) && copyFile(from, to)) 1 else 0
        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = try {
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                    FileVisitResult.CONTINUE
                } catch (_: IOException) {
                    // One folder that can't be made skips that folder, not the rest of the import
                    FileVisitResult.SKIP_SUBTREE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = from.relativize(file)
                    val name = file.fileName.toString()
                    if (attrs.isRegularFile && !unfinished(name) && !(skipTopFiles && relative.nameCount == 1)) {
                        if (copyFile(file, to.resolve(relative.toString()))) count++
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return count
    }

    /** What a file is copied to before it gets its name. */
    private const val COPY_SUFFIX = ".bitchord-import"

    /**
     * Byte for byte, with its times: the caches order and expire by them. Never over a file
     * already there. Copied beside its place first and then renamed, so a start killed during a
     * copy leaves no cut-short file under the real name, which the retry would take as copied.
     */
    private fun copyFile(from: Path, to: Path): Boolean {
        if (Files.exists(to)) return false
        val temp = to.resolveSibling(to.fileName.toString() + COPY_SUFFIX)
        return try {
            Files.createDirectories(to.parent)
            Files.copy(from, temp, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, to) // no REPLACE_EXISTING: a file that appeared meanwhile is kept
            true
        } catch (_: IOException) {
            // FileAlreadyExistsException included
            runCatching { Files.deleteIfExists(temp) }
            false
        }
    }

    /**
     * The old app's Java Preferences, read straight from the registry, where Java's Windows store
     * keeps them under `HKCU\Software\JavaSoft\Prefs`. Only the app's nodes: that key also holds
     * other Java programs' values.
     *
     * Not through Java's own store: built by reflection (its class isn't public) it opened its root
     * under HKEY_LOCAL_MACHINE, "Could not open windows registry node Software\JavaSoft\Prefs at
     * root 0x80000002", and nothing was imported (2026-11-04). Read here through JNA, which only
     * opens keys that exist and never creates one, and decoded the way that store encodes
     * ([javaName], [javaValue]).
     */
    private fun importSettings() {
        val target = Preferences.userRoot()
        val hkcu = WinReg.HKEY_CURRENT_USER
        if (!Advapi32Util.registryKeyExists(hkcu, PREFS_KEY)) {
            DesktopTrackLog.log("Portable: no earlier settings in the registry")
            ForkFilePreferences.userFolder.mkdirs()
            return
        }
        var keys = 0
        val roots = Advapi32Util.registryGetKeys(hkcu, PREFS_KEY)
            .filter { javaName(it).startsWith("com.music.bitchord") }
            .map { it to javaName(it) }
            .toMutableList()
        // The node `com/music/bitchord` is the nested keys com\music\bitchord.
        if (Advapi32Util.registryKeyExists(hkcu, "$PREFS_KEY\\com\\music\\bitchord")) roots += "com\\music\\bitchord" to "com/music/bitchord"
        roots.forEach { (windowsPath, javaPath) ->
            val copied = copyKey("$PREFS_KEY\\$windowsPath", target.node(javaPath))
            if (copied > 0) DesktopTrackLog.log("Portable: imported settings $javaPath ($copied values)")
            keys += copied
        }
        target.flush()
        ForkFilePreferences.userFolder.mkdirs()
        if (keys == 0) DesktopTrackLog.log("Portable: no earlier settings to import")
    }

    /** Every value under [key] and its subkeys into [to]; the count copied. Values are never logged. */
    private fun copyKey(key: String, to: Preferences): Int {
        val hkcu = WinReg.HKEY_CURRENT_USER
        var count = 0
        Advapi32Util.registryGetValues(hkcu, key).forEach { (name, value) ->
            // The unnamed default value is no preference; an alt-encoded name is skipped, none of
            // the app's keys need it.
            if (name.isEmpty() || name.startsWith("/!") || value !is String) return@forEach
            to.put(javaName(name), javaValue(value))
            count++
        }
        Advapi32Util.registryGetKeys(hkcu, key).forEach { child ->
            if (!child.startsWith("/!")) count += copyKey("$key\\$child", to.node(javaName(child)))
        }
        return count
    }

    /**
     * A key or value name as Java's Windows store wrote it, back to the Java name: `/X` is an upper
     * case letter, `//` a backslash, a backslash a slash (`WindowsPreferences.toJavaName`).
     */
    internal fun javaName(windows: String): String = buildString {
        var i = 0
        while (i < windows.length) {
            var ch = windows[i]
            if (ch == '/' && i + 1 < windows.length && windows[i + 1] in 'A'..'Z') { ch = windows[i + 1]; i++ }
            else if (ch == '/' && i + 1 < windows.length && windows[i + 1] == '/') { ch = '\\'; i++ }
            else if (ch == '\\') ch = '/'
            append(ch)
            i++
        }
    }

    /**
     * A value as Java's Windows store wrote it, back to the Java string: as [javaName], plus `/uXXXX`
     * for a character outside printable ASCII (`WindowsPreferences.toJavaValueString`).
     */
    internal fun javaValue(windows: String): String = buildString {
        var i = 0
        while (i < windows.length) {
            var ch = windows[i]
            if (ch == '/' && i + 1 < windows.length) {
                val next = windows[i + 1]
                when {
                    next == 'u' && i + 6 <= windows.length && windows.substring(i + 2, i + 6).toIntOrNull(16) != null -> {
                        append(windows.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                        continue
                    }
                    next in 'A'..'Z' -> { ch = next; i++ }
                    next == '/' -> { ch = '\\'; i++ }
                }
            } else if (ch == '\\') ch = '/'
            append(ch)
            i++
        }
    }

    private const val PREFS_KEY = "Software\\JavaSoft\\Prefs"
}
