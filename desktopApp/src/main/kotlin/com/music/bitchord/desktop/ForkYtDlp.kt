package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import java.awt.FileDialog
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Fork (idea from PC-Continued c310d0e3 `DesktopYtDlpResolver`): the owner's own yt-dlp as a
 * resolver for YouTube streams. Nothing is bundled: it runs the yt-dlp named in Settings > Sources >
 * yt-dlp, else the one on PATH, else the copy fetched by that row's "Download yt-dlp" button
 * ([download]; for listeners who can't install a tool or edit PATH). Off by default, also after a
 * download.
 *
 * Where it sits: first in the YouTube leg ([DesktopStreamClient.resolve]), with the in-process
 * resolver (InnerTubeX, PoToken WebView, cipher solver) as the fallback for anything it can't do:
 * no yt-dlp, a failure, a timeout, an age-gated track (no cookies are passed), a URL Google refuses.
 * First rather than last because the point, as in PC-Continued, is not to load WebKit and QuickJS
 * at all: they have no dispose, so the first in-process resolve keeps a couple of hundred megabytes
 * resident for the session. For the same reason InnerTubeX's prewarm is skipped while this is on and
 * a yt-dlp is found ([InnerTubeXResolver.autoPrewarm]); the cost is that a track that does fall back
 * pays the cold start the prewarm would have paid (seconds, once), which is the rarer case.
 *
 * Only the addon/module ordering above YouTube is untouched: this is a way of resolving YouTube's
 * copy, not a new source. A URL lives until its `expire` parameter, so each id is asked once per
 * few hours ([cache]); a failed id is not asked again for [FAILED_MS] (no retries); never more than
 * [MAX_PROCESSES] yt-dlp processes run at once, each killed after [RESOLVE_TIMEOUT_MS].
 */
internal object ForkYtDlp {

    const val TITLE_KEY = "fork_ytdlp"
    const val TITLE = "yt-dlp"

    private const val KEY_ENABLED = "fork_ytdlp_enabled"
    private const val KEY_PATH = "fork_ytdlp_path"
    private const val MAX_PROCESSES = 2
    private const val RESOLVE_TIMEOUT_MS = 30_000L
    private const val VERSION_TIMEOUT_MS = 10_000L
    private const val UPDATE_TIMEOUT_MS = 180_000L
    private const val FAILED_MS = 15 * 60_000L
    /** A URL is dropped this long before Google's own expiry, so a long track doesn't outlive it. */
    private const val EXPIRY_MARGIN_MS = 10 * 60_000L
    /** When a URL carries no `expire`: well under the six hours Google usually grants. */
    private const val DEFAULT_LIFE_MS = 60 * 60_000L

    /** What the decoder sends Google; the URL is not tied to yt-dlp's own client headers. */
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val persistence by lazy { DesktopPersistence() }

    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _path = MutableStateFlow(persistence.string(KEY_PATH, ""))
    /** The path set in Settings; blank means "the yt-dlp on PATH". */
    val path: StateFlow<String> = _path.asStateFlow()

    private val processes = Semaphore(MAX_PROCESSES)
    private class Resolved(val stream: DesktopStream, val until: Long)
    private val cache = ConcurrentHashMap<String, Resolved>()
    private val failed = ConcurrentHashMap<String, Long>()

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        persistence.saveBoolean(KEY_ENABLED, on)
        applyPrewarm()
    }

    fun setPath(value: String) {
        _path.value = value.trim()
        persistence.saveString(KEY_PATH, value.trim())
        failed.clear() // a new yt-dlp may do what the old one couldn't
        applyPrewarm()
    }

    /** From Main, before InnerTubeX's init: the in-process prewarm is skipped while yt-dlp answers first. */
    fun applyPrewarm() {
        InnerTubeXResolver.autoPrewarm = !(_enabled.value && executable() != null)
    }

    /**
     * The yt-dlp to run: the path set, else the first on PATH, else the downloaded copy
     * ([downloadedFile]); null when there is none. A path that is set but has no file stays null
     * (the row says so) rather than quietly running another yt-dlp.
     */
    fun executable(): String? {
        val set = _path.value
        if (set.isNotBlank()) return set.takeIf { File(it).isFile }
        return findOnPath() ?: downloadedFile().takeIf { it.isFile }?.absolutePath
    }

    /** Where [download] puts yt-dlp: `tools` in the app's data folder (the portable `data` or the per-user one). */
    fun downloadedFile(): File = ForkSingleInstance.folder().resolve("tools").resolve(localName()).toFile()

    private val windows: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val mac: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    private fun localName(): String = if (windows) "yt-dlp.exe" else "yt-dlp"

    /** The official standalone build's asset name in yt-dlp's GitHub releases. */
    private fun assetName(): String = when {
        windows -> "yt-dlp.exe"
        mac -> "yt-dlp_macos"
        else -> "yt-dlp_linux"
    }

    private const val RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
    /** yt-dlp.exe is ~18 MB; anything far past that is not the build asked for. */
    private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloading = AtomicBoolean(false)
    private val _download = MutableStateFlow<String?>(null)
    /** The download's status line: progress, then the result or the error; null before one runs. */
    val downloadStatus: StateFlow<String?> = _download.asStateFlow()
    private val _downloadRunning = MutableStateFlow(false)
    val downloadRunning: StateFlow<Boolean> = _downloadRunning.asStateFlow()

    /**
     * From the Settings button: fetches the latest official build into [downloadedFile], checked
     * against the release's `SHA2-256SUMS`, through a `.part` file moved into place only once it
     * matches. Off the UI thread, in a scope of its own so leaving Settings doesn't cut it; one at
     * a time; no retries (a failure says why, and the button is pressed again). A verified download
     * turns the switch on (the owner's call: friends press Download and it works).
     */
    fun download() {
        if (!downloading.compareAndSet(false, true)) return
        _downloadRunning.value = true
        downloadScope.launch {
            try {
                _download.value = runCatching { fetchVerified().also { setEnabled(true) } }.getOrElse { // owner, 2026-11-05: a verified download turns yt-dlp on
                    DesktopStrings["fork_ytdlp_download_failed", "Download failed"] + ": " + (it.message ?: it.javaClass.simpleName)
                }
                failed.clear() // a new yt-dlp may do what the old one couldn't
                applyPrewarm()
            } finally {
                downloading.set(false)
                _downloadRunning.value = false
            }
        }
    }

    private fun fetchVerified(): String {
        val asset = assetName()
        val target = downloadedFile()
        target.parentFile.mkdirs()
        _download.value = DesktopStrings["fork_ytdlp_download_sums", "Fetching checksums…"]
        val sumsResponse = http.send(
            HttpRequest.newBuilder(URI.create(RELEASE_BASE + "SHA2-256SUMS")).timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(sumsResponse.statusCode() == 200) { "SHA2-256SUMS answered HTTP ${sumsResponse.statusCode()}" }
        val expected = checkNotNull(sha256For(sumsResponse.body(), asset)) { "SHA2-256SUMS has no line for $asset" }

        val part = File(target.parentFile, target.name + ".part")
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(RELEASE_BASE + asset)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            response.body().use { input ->
                check(response.statusCode() == 200) { "$asset answered HTTP ${response.statusCode()}" }
                val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
                check(total <= MAX_DOWNLOAD_BYTES) { "$asset is ${megabytes(total)}, not the expected build" }
                var read = 0L
                var shown = 0L
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        check(read <= MAX_DOWNLOAD_BYTES) { "$asset is over ${megabytes(MAX_DOWNLOAD_BYTES)}, not the expected build" }
                        if (read - shown >= 512 * 1024) {
                            shown = read
                            _download.value = DesktopStrings["fork_ytdlp_downloading", "Downloading yt-dlp…"] + " " +
                                megabytes(read) + (if (total > 0) " / " + megabytes(total) else "")
                        }
                    }
                }
            }
            check(matchesSha256(part, expected)) { "the SHA-256 of $asset does not match SHA2-256SUMS; nothing was replaced" }
            if (!windows) part.setExecutable(true)
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (atomic: Exception) {
                // ATOMIC_MOVE over an existing file isn't supported everywhere. A yt-dlp.exe that is
                // running (a resolve in progress) refuses both, and that error is what the row shows.
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return DesktopStrings["fork_ytdlp_downloaded", "Downloaded and verified"] + " · " + megabytes(target.length()) + " · " + target.absolutePath
        } finally {
            part.delete() // a no-op after the move
        }
    }

    private fun megabytes(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

    /**
     * The SHA-256 for [asset] in a `sha256sum`-style listing (`<64 hex>  <name>`, the name maybe
     * prefixed `*` for binary mode), lowercased; null when no well-formed line names it exactly
     * (`yt-dlp.exe` is not `yt-dlp_x86.exe`).
     */
    internal fun sha256For(sums: String, asset: String): String? = sums.lineSequence()
        .map(String::trim)
        .mapNotNull { line ->
            val hash = line.takeWhile { !it.isWhitespace() }
            val name = line.substring(hash.length).trim().removePrefix("*")
            if (name == asset && SHA256_HEX.matches(hash)) hash.lowercase() else null
        }
        .firstOrNull()

    /** Whether [file]'s SHA-256 is [expected] (hex, any case). */
    internal fun matchesSha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest()).equals(expected.trim(), ignoreCase = true)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val SHA256_HEX = Regex("[0-9A-Fa-f]{64}")

    /** The first yt-dlp on PATH, or null. */
    fun findOnPath(): String? {
        val names = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            listOf("yt-dlp.exe", "yt-dlp")
        } else {
            listOf("yt-dlp")
        }
        return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .asSequence()
            .filter(String::isNotBlank)
            .flatMap { dir -> names.asSequence().map { File(dir.trim().trim('"'), it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    /**
     * From [DesktopStreamClient.resolve]: [song]'s stream through yt-dlp, or null for the in-process
     * resolver (off, no yt-dlp, a failure). Never throws but a cancellation.
     */
    suspend fun resolve(song: Song, maxKbps: Int): DesktopStream? {
        if (!_enabled.value || !VIDEO_ID.matches(song.videoId)) return null // only a YouTube id goes into the command line
        val selector = if (maxKbps < LOW_CEILING_KBPS) "bestaudio[abr<=$maxKbps]/bestaudio" else "bestaudio"
        val key = "${song.videoId}|$selector"
        fresh(key)?.let { return it }
        if ((failed[key] ?: 0L) > System.currentTimeMillis()) return null
        val exe = executable() ?: return null
        return processes.withPermit {
            fresh(key)?.let { return@withPermit it } // the other process was asking for the same song
            val started = System.currentTimeMillis()
            val ran = runCatching {
                exec(
                    listOf(exe, "-f", selector, "-g", "--no-playlist", "--no-warnings", "https://music.youtube.com/watch?v=${song.videoId}"),
                    RESOLVE_TIMEOUT_MS,
                )
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
            val url = ran?.takeIf { it.exit == 0 }?.out?.lineSequence()?.map(String::trim)?.firstOrNull { it.startsWith("https://") }
            if (url == null) {
                failed[key] = System.currentTimeMillis() + FAILED_MS
                val why = ran?.err?.lineSequence()?.map(String::trim)?.lastOrNull { it.isNotEmpty() }?.take(160) ?: "did not run"
                DesktopTrackLog.log("  yt-dlp: no stream for '${song.title}' ($why); the built-in resolver takes it")
                return@withPermit null
            }
            val headers = mapOf("User-Agent" to USER_AGENT)
            if (!playable(url, headers)) {
                failed[key] = System.currentTimeMillis() + FAILED_MS
                DesktopTrackLog.log("  yt-dlp: Google refused the stream for '${song.title}'; the built-in resolver takes it")
                return@withPermit null
            }
            val stream = DesktopStream(
                url = url,
                format = DesktopStreamFormat(codec = query(url, "mime")?.substringAfterLast('/')?.lowercase(), kbps = itagKbps(query(url, "itag"))),
                headers = headers,
                sourceId = "youtube",
                // Google's media servers answer 403 to a request for a whole file, as for the in-process URL.
                windowedReads = true,
            )
            val until = query(url, "expire")?.toLongOrNull()?.let { it * 1_000 - EXPIRY_MARGIN_MS } ?: (System.currentTimeMillis() + DEFAULT_LIFE_MS)
            cache[key] = Resolved(stream, until)
            DesktopTrackLog.log("  youtube: resolved '${song.title}' through yt-dlp in ${System.currentTimeMillis() - started}ms")
            stream
        }
    }

    /**
     * Drops every cached answer that handed out [url]. Called when Google refuses that URL mid-track
     * ([DesktopRangeStream], via `StreamResolver.onPlaybackRefused`) and when a YouTube track is
     * reopened after a failure (decoder recovery, retry after a failed open): until its `expire` the
     * cache kept handing the refused URL back, so the recovery reopened the same dead stream, the
     * song ended early, and a replay struck YouTube off for the track for the session (audit
     * 2026-11-05). The next [resolve] asks yt-dlp again. A URL that never came from here is a no-op.
     */
    fun forget(url: String) {
        if (url.isEmpty()) return
        cache.entries.removeIf { it.value.stream.url == url }
    }

    /** For tests: what [resolve] would answer from the cache for [key], and a way to seed it. */
    internal fun cachedFor(key: String): DesktopStream? = fresh(key)

    internal fun cacheForTest(key: String, stream: DesktopStream, until: Long) {
        cache[key] = Resolved(stream, until)
    }

    private fun fresh(key: String): DesktopStream? {
        val hit = cache[key] ?: return null
        if (hit.until > System.currentTimeMillis()) return hit.stream
        cache.remove(key)
        return null
    }

    /**
     * One byte of the stream, asked the way the decoder will ask (a window, with its User-Agent): a
     * URL from a client Google binds to something yt-dlp had and the decoder doesn't is refused here
     * rather than as a decode failure, which would strike YouTube off for the track.
     */
    private suspend fun playable(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Range", "bytes=0-0")
                .also { builder -> headers.forEach { (name, value) -> builder.header(name, value) } }
                .GET().build()
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        }.getOrDefault(false)
    }

    /** `yt-dlp --version`, for the Settings row's status line; null when it can't be run. */
    suspend fun version(): String? {
        val exe = executable() ?: return null
        return processes.withPermit {
            runCatching { exec(listOf(exe, "--version"), VERSION_TIMEOUT_MS) }.getOrNull()
                ?.takeIf { it.exit == 0 }?.out?.trim()?.lineSequence()?.firstOrNull()?.takeIf(String::isNotBlank)
        }
    }

    /** `yt-dlp -U`, only from the Settings button; what it printed. */
    suspend fun update(): String {
        val exe = executable() ?: return DesktopStrings["fork_ytdlp_missing", "yt-dlp was not found"]
        return processes.withPermit {
            runCatching { exec(listOf(exe, "-U"), UPDATE_TIMEOUT_MS) }.fold(
                onSuccess = { (it.out + "\n" + it.err).trim().ifBlank { "yt-dlp exited with ${it.exit}" } },
                onFailure = { if (it is kotlinx.coroutines.CancellationException) throw it; it.message ?: "yt-dlp could not run" },
            )
        }
    }

    private class Ran(val exit: Int, val out: String, val err: String)

    /**
     * One process, both pipes drained on their own threads (a child writing more than the pipe
     * holds would otherwise block and read as a timeout), killed at [timeoutMs] or on a cancel.
     */
    private suspend fun exec(command: List<String>, timeoutMs: Long): Ran = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        try {
            runCatching { process.outputStream.close() }
            val out = StringBuffer()
            val err = StringBuffer()
            val readers = listOf(drain(process.inputStream, out), drain(process.errorStream, err))
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                ensureActive()
                check(System.nanoTime() < deadline) { "yt-dlp took longer than ${timeoutMs / 1_000} s" }
            }
            readers.forEach { it.join(2_000) }
            Ran(process.exitValue(), out.toString(), err.toString())
        } finally {
            // yt-dlp.exe is a PyInstaller one-file build: the process started is a bootloader whose
            // child runs the program, and killing only the bootloader leaves that child running.
            if (process.isAlive) {
                runCatching { process.descendants().forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }
    }

    private fun drain(input: InputStream, into: StringBuffer): Thread =
        Thread({ runCatching { input.bufferedReader().forEachLine { into.append(it).append('\n') } } }, "BitChord yt-dlp pipe")
            .apply { isDaemon = true; start() }

    private fun query(url: String, name: String): String? =
        runCatching { URI(url).rawQuery }.getOrNull()?.split('&')
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    /** YouTube's audio itags and their nominal bitrates, for the format line (yt-dlp's `-g` prints only the URL). */
    private fun itagKbps(itag: String?): Int? = when (itag) {
        "249" -> 50
        "250" -> 70
        "251" -> 160
        "774" -> 256
        "139" -> 48
        "140" -> 128
        "141" -> 256
        else -> null
    }

    /** A YouTube video id; anything else (a Navidrome or local id) is not handed to yt-dlp. */
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,64}")

    /** Below this ceiling (the "low" qualities) yt-dlp is asked for a stream under it; above, the best. */
    private const val LOW_CEILING_KBPS = 160
}

/** Settings > Sources: "yt-dlp", its path and Find, the version line and Update, and Download (fork, ForkYtDlp). */
@Composable
internal fun ForkYtDlpRows() {
    val on by ForkYtDlp.enabled.collectAsState()
    val stored by ForkYtDlp.path.collectAsState()
    SettingsToggle(
        DesktopStrings[ForkYtDlp.TITLE_KEY, ForkYtDlp.TITLE],
        DesktopStrings["fork_ytdlp_subtitle", "Resolve YouTube streams with your own yt-dlp first; the built-in resolver stays the fallback"],
        on,
        ForkYtDlp::setEnabled,
    )
    val pathTitle = DesktopStrings["fork_ytdlp_path", "yt-dlp path"]
    if (!settingsRowVisible(pathTitle, stored)) return
    var input by remember(stored) { mutableStateOf(stored) }
    var status by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf(0) }
    var updating by remember { mutableStateOf(false) }
    var updateOutput by remember { mutableStateOf<String?>(null) }
    val downloadStatus by ForkYtDlp.downloadStatus.collectAsState()
    val downloadRunning by ForkYtDlp.downloadRunning.collectAsState()
    // Whether to offer the download: no yt-dlp found, or the one in use is the downloaded copy (to fetch it again).
    var offerDownload by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(on, stored, checks, downloadRunning) {
        if (downloadRunning) return@LaunchedEffect
        // Checked with the switch off too: the download is how yt-dlp gets here before it is turned on.
        val exe = withContext(Dispatchers.IO) { ForkYtDlp.executable() }
        offerDownload = exe == null || withContext(Dispatchers.IO) { ForkYtDlp.downloadedFile().absolutePath == exe }
        if (!on) {
            status = null
            return@LaunchedEffect
        }
        status = DesktopStrings["fork_ytdlp_checking", "Checking…"]
        status = if (exe == null) {
            if (stored.isBlank()) DesktopStrings["fork_ytdlp_not_found", "Not found on PATH: set its path, use Find, or Download yt-dlp"]
            else DesktopStrings["fork_ytdlp_no_file", "No file at that path"]
        } else {
            ForkYtDlp.version()?.let { "yt-dlp $it · $exe" } ?: DesktopStrings["fork_ytdlp_no_version", "Found, but yt-dlp --version did not answer"]
        }
    }
    ForkDisabledWhen(!on) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(pathTitle, style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                input,
                { input = it },
                Modifier.fillMaxWidth().padding(top = 6.dp),
                placeholder = { Text(DesktopStrings["fork_ytdlp_path_hint", "Blank: the yt-dlp on PATH"]) },
                singleLine = true,
            )
            status?.let {
                Text(it, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (input.trim() != stored) {
                    TextButton(onClick = { ForkYtDlp.setPath(input) }) { Text(DesktopStrings["save", "Save"]) }
                }
                TextButton(onClick = {
                    // PATH first; a file picker when it isn't there.
                    val found = ForkYtDlp.findOnPath() ?: pickExecutable()
                    if (found != null) {
                        input = found
                        ForkYtDlp.setPath(found)
                    }
                    checks++
                }) { Text(DesktopStrings["fork_ytdlp_find", "Find"]) }
                TextButton(
                    enabled = !updating,
                    onClick = {
                        updating = true
                        updateOutput = DesktopStrings["fork_ytdlp_updating", "Running yt-dlp -U…"]
                        scope.launch {
                            updateOutput = ForkYtDlp.update()
                            updating = false
                            checks++
                        }
                    },
                ) { Text(DesktopStrings["fork_ytdlp_update", "Update yt-dlp"]) }
            }
            updateOutput?.let {
                Text(
                    it,
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp),
                )
            }
        }
    }
    // Outside ForkDisabledWhen on purpose: usable with the switch off, which is when it's needed.
    if (offerDownload || downloadRunning || downloadStatus != null) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !downloadRunning, onClick = ForkYtDlp::download) {
                    Text(DesktopStrings["fork_ytdlp_download", "Download yt-dlp"])
                }
            }
            Text(
                downloadStatus ?: DesktopStrings["fork_ytdlp_download_hint", "The official build from yt-dlp's GitHub releases, checked against its SHA-256, into BitChord's data folder"],
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The system's file dialog, for a yt-dlp that isn't on PATH. */
private fun pickExecutable(): String? = runCatching {
    val dialog = FileDialog(null as java.awt.Frame?, DesktopStrings["fork_ytdlp_pick", "Find yt-dlp"], FileDialog.LOAD)
    dialog.directory = ForkPortable.userHome
    dialog.isVisible = true
    val name = dialog.file ?: return null
    File(dialog.directory ?: ForkPortable.userHome, name).absolutePath
}.getOrNull()
