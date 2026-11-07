package com.music.bitchord.desktop

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fork (owner, 2026-11-07): the PC's build number and its in-app updater, the work done in Java.
 *
 * **Build number.** The PC follows three forks, so its version stays e.g. 1.9.1 for weeks while
 * `.github/workflows/fork-pc-release.yml` replaces the release `fork-pc-v1.9.1` at every push. A
 * check by version alone never offered such a rebuild. The workflow now passes `github.run_number`
 * into the build (`-Pbitchord.build`, written into `BitChord.args` as `-Dbitchord.build=N`) and
 * writes a `Build: N` line into the release notes; a local build is 0. An update is offered when the
 * release's version is higher, or the same with a higher build ([offers]). The notes line was chosen
 * over a `build.txt` asset because the release list already carries the notes: no second request,
 * and nothing to go stale between two uploads. A release without the line (all the ones before
 * this) counts as build 0, so it is never offered over the same version.
 *
 * **Update.** All the work that can be done while the app still runs is done here: the zip is
 * downloaded into `data\update\`, its SHA-256 checked against the release's `SHA256SUMS.txt`, it is
 * unzipped (in Java, refusing any entry that would land outside the folder) into
 * `data\update\staged\`, and the files the app cannot start without are checked. Only then the
 * app copies `BitChordUpdate.exe` (desktopApp/native/launcher/BitChordUpdate.c) into
 * `data\update\`, starts it with the app folder, the staged folder and its own PID, and quits
 * through the tray's Quit, so the queue, the hand-off note and the AppCDS archive are written as on
 * any quit. The updater swaps the program files (never `data\`), keeps the old ones in
 * `data\update\old\`, and starts the new app, which deletes `old\` and `staged\` once its first
 * frame is drawn ([cleanUpAfterStart]); a start that never gets that far leaves `old\` for a manual
 * roll-back. Ideas (not code) from Desktop Revived 1.9.1 #106: wait for the PID, back up and roll
 * back, delete the backup only after a confirmed start. Its PowerShell host and its swap of the
 * whole folder are not taken: here `data\` lives inside the folder.
 *
 * Only the portable app ([appFolder]) updates itself; a dev run or the old `.bat` zip still gets
 * the release page in the browser, as before.
 */
internal object ForkUpdater {

    /** This build's number: `github.run_number` of the workflow run that built it, 0 for a local build. */
    val currentBuild: Int = System.getProperty("bitchord.build")?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /**
     * For testing only: a release list to read instead of GitHub's (`-Dbitchord.updateUrl=`), so a
     * second local zip can be offered from a local server. "Latest" is then not asked at all.
     */
    val testReleasesUrl: String? = System.getProperty("bitchord.updateUrl")?.takeIf(String::isNotBlank)

    /** "1.9.1 (build 42)", or the bare version for a build without a number. */
    fun label(version: String, build: Int): String = if (build > 0) "$version (build $build)" else version

    /** The running app's [label]. */
    val currentLabel: String get() = label(DesktopUpdateChecker.currentVersion, currentBuild)

    /** What the settings footer adds after the version. */
    val footerBuild: String get() = if (currentBuild > 0) " (build $currentBuild)" else ""

    private val BUILD_LINE = Regex("""^\W*build\W*(\d{1,9})\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * The release's build number from its notes' `Build: N` line; 0 when there is none. Lenient on
     * purpose (Markdown bold, a dash, CRLF, upper case), as the notes are hand-editable on GitHub,
     * but only at the start of a line, so a changelog entry that mentions a build is not read.
     */
    fun buildOf(release: JsonObject): Int = buildOf(release["body"]?.jsonPrimitive?.contentOrNull)

    fun buildOf(notes: String?): Int =
        notes?.let { BUILD_LINE.find(it.replace("\r", "")) }?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Whether a release of [latestVersion] / [latestBuild] is an update over [currentVersion] / [currentBuild]. */
    fun offers(latestVersion: String, latestBuild: Int, currentVersion: String, currentBuild: Int): Boolean {
        if (DesktopUpdateChecker.isNewer(latestVersion, currentVersion)) return true
        val sameVersion = !DesktopUpdateChecker.isNewer(currentVersion, latestVersion)
        return sameVersion && latestBuild > currentBuild
    }

    /** The release's `SHA256SUMS.txt`, or null when it has none (then nothing is installed from it). */
    fun sumsUrlOf(release: JsonObject): String? =
        runCatching { release["assets"]?.jsonArray }.getOrNull()?.firstNotNullOfOrNull { element ->
            val asset = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val name = asset["name"]?.jsonPrimitive?.contentOrNull
            val state = asset["state"]?.jsonPrimitive?.contentOrNull
            if (name.equals(SUMS_NAME, ignoreCase = true) && (state == null || state == "uploaded")) {
                asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }

    const val SUMS_NAME = "SHA256SUMS.txt"

    /**
     * The SHA-256 that [sums] (`sha256sum` output) gives for the zip downloaded from [zipUrl].
     * Matched by the URL's file name first, then by the names the zip has gone by (GitHub lists
     * "BitChord Portable.zip" as `BitChord.Portable.zip`, and the workflow writes that name), in
     * either case; `*name` (binary mode) is accepted. Null when no line names it.
     */
    fun expectedSha256(sums: String, zipUrl: String): String? {
        val fromUrl = runCatching {
            URLDecoder.decode(URI(zipUrl).path.substringAfterLast('/'), StandardCharsets.UTF_8)
        }.getOrNull()
        val wanted = listOfNotNull(fromUrl, "BitChord.Portable.zip", "BitChord Portable.zip")
        val lines = sums.lineSequence().mapNotNull { line ->
            val match = SUMS_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            match.groupValues[2].trim() to match.groupValues[1].lowercase()
        }.toList()
        for (name in wanted) {
            lines.firstOrNull { it.first.equals(name, ignoreCase = true) }?.let { return it.second }
        }
        return null
    }

    private val SUMS_LINE = Regex("""([0-9a-fA-F]{64})\s+\*?(.+)""")

    // ---- folders --------------------------------------------------------------------------------

    /**
     * The portable app's folder (the one holding `BitChord.exe` and `data\`), or null when this run
     * can't update itself: a dev run, or a data folder that isn't inside the app's folder.
     */
    val appFolder: Path? by lazy {
        val data = ForkPortable.dataDir ?: return@lazy null
        val folder = data.parent ?: return@lazy null
        folder.takeIf { DesktopPlatform.isWindows && Files.isRegularFile(it.resolve("BitChord.exe")) }
    }

    val canUpdateInApp: Boolean get() = appFolder != null

    private val updateDir: Path? get() = ForkPortable.dataDir?.resolve("update")

    /** What the new app needs to start; the updater checks the same before it moves anything. */
    val REQUIRED = listOf("BitChord.exe", "lib/bitchord-app.jar", "runtime/bin/javaw.exe")

    const val UPDATER_EXE = "BitChordUpdate.exe"

    /** [REQUIRED] files missing under [root]. */
    fun missingIn(root: Path): List<String> = REQUIRED.filterNot { Files.isRegularFile(root.resolve(it)) }

    /**
     * Where the unzipped app is inside [staged]: the folder itself, or its one subfolder (the zip
     * holds a single `BitChord Portable` folder) when that is where `BitChord.exe` is.
     */
    fun stagedRoot(staged: Path): Path? {
        if (Files.isRegularFile(staged.resolve("BitChord.exe"))) return staged
        val folders = Files.list(staged).use { list -> list.filter { Files.isDirectory(it) }.toList() }
        return folders.singleOrNull()?.takeIf { Files.isRegularFile(it.resolve("BitChord.exe")) }
    }

    // ---- download, check, unzip -----------------------------------------------------------------

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub's download links redirect to its file host
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    class UpdateFailed(message: String) : IOException(message)

    /**
     * Downloads, checks and unzips [info]'s zip into `data\update\staged\`; returns the staged app
     * folder. [onProgress] gets a fraction (null while the size is unknown). Cancelling the calling
     * coroutine stops the download and removes what it wrote. [into] is `data\update\` (a test's
     * own folder in `ForkUpdaterDownloadTest`).
     */
    suspend fun prepare(
        info: DesktopUpdateChecker.UpdateInfo,
        into: Path? = updateDir,
        onProgress: (Float?) -> Unit,
    ): Path = withContext(Dispatchers.IO) {
        val dir = into ?: throw UpdateFailed("This copy of BitChord can't update itself.")
        val zipUrl = info.downloadUrl ?: throw UpdateFailed("The release has no Windows download.")
        val sumsUrl = info.sumsUrl ?: throw UpdateFailed("The release has no $SUMS_NAME to check the download against.")
        clear(dir.resolve("staged"))
        Files.createDirectories(dir)
        val zip = dir.resolve("BitChord Portable.zip")
        val part = dir.resolve("BitChord Portable.zip.part")
        try {
            val sums = fetchText(sumsUrl)
            val expected = expectedSha256(sums, zipUrl) ?: throw UpdateFailed("$SUMS_NAME doesn't list the download.")
            val actual = download(zipUrl, part, onProgress)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw UpdateFailed("The download is damaged (its SHA-256 doesn't match the release's). Try again.")
            }
            Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING)
            onProgress(null)
            val staged = dir.resolve("staged")
            unzip(zip, staged)
            Files.deleteIfExists(zip)
            val root = stagedRoot(staged) ?: throw UpdateFailed("The download holds no BitChord.exe.")
            val missing = missingIn(root)
            if (missing.isNotEmpty()) throw UpdateFailed("The download is missing ${missing.joinToString()}.")
            DesktopTrackLog.log("update: ${label(info.version, info.build)} staged in $root")
            root
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(part) }
            runCatching { Files.deleteIfExists(zip) }
            runCatching { clear(dir.resolve("staged")) }
            throw failure
        }
    }

    private fun fetchText(url: String): String {
        val response = http.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) throw UpdateFailed("$SUMS_NAME couldn't be read (HTTP ${response.statusCode()}).")
        return response.body()
    }

    /** Streams [url] into [to], hashing as it goes; returns the SHA-256 in hex. */
    private suspend fun download(url: String, to: Path, onProgress: (Float?) -> Unit): String {
        val response = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body()
        body.use { input ->
            if (response.statusCode() !in 200..299) throw UpdateFailed("The download failed (HTTP ${response.statusCode()}).")
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(to).use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastShown = -1
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    done += read
                    if (total > 0) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastShown) {
                            lastShown = percent
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total > 0 && done != total) throw UpdateFailed("The download ended early. Try again.")
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Unzips [zip] into [into], refusing the whole zip when any entry would land outside it: an
     * absolute name, a drive letter, or `..` that climbs out ("zip slip"). Checked on the resolved,
     * normalised path, so `a/../../x` is caught as well as `../x`.
     */
    fun unzip(zip: Path, into: Path) {
        Files.createDirectories(into)
        val base = into.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val target = safeTarget(base, entry.name) ?: throw UpdateFailed("The download holds a file outside its folder (${entry.name}).")
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** Where [name] unzips to under [base], or null when it would land outside it. */
    fun safeTarget(base: Path, name: String): Path? {
        val clean = name.replace('\\', '/')
        if (clean.isBlank() || clean.startsWith("/") || clean.contains(':') || clean.contains('\u0000')) return null
        val target = runCatching { base.resolve(clean).normalize() }.getOrNull() ?: return null
        return target.takeIf { it != base && it.startsWith(base) }
    }

    // ---- hand-over ------------------------------------------------------------------------------

    /**
     * Starts `BitChordUpdate.exe` for [root] (the staged app) and returns once it runs; the caller
     * then quits. The updater runs from a copy in `data\update\`, as the one in the app folder is
     * among the files it replaces. The running app's own copy is used (it speaks this build's
     * arguments), the staged one only when this folder has none (the first build with the updater
     * unzipped without it, say).
     */
    fun startUpdater(root: Path) {
        val app = appFolder ?: throw UpdateFailed("This copy of BitChord can't update itself.")
        val dir = updateDir ?: throw UpdateFailed("This copy of BitChord can't update itself.")
        val source = listOf(app.resolve(UPDATER_EXE), root.resolve(UPDATER_EXE)).firstOrNull(Files::isRegularFile)
            ?: throw UpdateFailed("$UPDATER_EXE is missing. Download the zip from the releases page instead.")
        val updater = dir.resolve(UPDATER_EXE)
        Files.copy(source, updater, StandardCopyOption.REPLACE_EXISTING)
        val command = mutableListOf(
            updater.toString(),
            app.toAbsolutePath().toString(),
            root.toAbsolutePath().toString(),
            ProcessHandle.current().pid().toString(),
        )
        // Started with `BitChord.exe --log` (java.exe rather than javaw.exe): the new app logs too.
        val java = ProcessHandle.current().info().command().orElse("")
        if (File(java).name.equals("java.exe", ignoreCase = true)) command += "--log"
        ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        DesktopTrackLog.log("update: $UPDATER_EXE started for $root; quitting")
    }

    /**
     * After an update, once the new app has drawn its first frame: the old program files and the
     * unzipped copy go. Also drops what a cancelled or failed download left. Only when the frame
     * really came: [ForkStartup.awaitFirstFrame] gives up after 10 s for a window that starts
     * hidden, and a start that isn't confirmed keeps `old\` for a manual roll-back.
     */
    fun cleanUpAfterStart(firstFrameShown: Boolean, dir: Path? = updateDir) {
        dir ?: return
        if (!Files.isDirectory(dir)) return
        DesktopTrackLog.log("update: running ${label(DesktopUpdateChecker.currentVersion, currentBuild)}")
        if (!firstFrameShown) return
        val old = dir.resolve("old")
        if (Files.exists(old)) {
            DesktopTrackLog.log("update: started after an update; removing the old program files")
            clear(old)
        }
        clear(dir.resolve("staged"))
        runCatching { Files.deleteIfExists(dir.resolve("BitChord Portable.zip")) }
        runCatching { Files.deleteIfExists(dir.resolve("BitChord Portable.zip.part")) }
    }

    /** Deletes [path] and everything under it; quietly leaves what Windows refuses (a file still open). */
    private fun clear(path: Path) {
        if (!Files.exists(path)) return
        runCatching {
            Files.walk(path).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
