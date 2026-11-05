package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Downloads resolved audio into the user's own BitChord folder. */
object DesktopDownloadManager {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Fork: the targets downloads in flight have claimed, by lower-cased path (Windows names ignore
     * case) to the track claiming it. Two tracks with the same "Artist - Title" running at once
     * both saw a free name, and the second move replaced the first file.
     */
    private val claimed = ConcurrentHashMap<String, String>()

    /**
     * Keeps an interrupted transfer in place and resumes it when the user tries again; one the
     * listener called off is deleted.
     */
    suspend fun download(
        song: Song,
        quality: String = DesktopPersistence().string("download_quality", "LOSSLESS"),
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result<Song> {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[song.videoId] = job
        var temporary: Path? = null // fork: outside the try, so a cancel can delete it
        var sidecar: Path? = null
        var claim: String? = null
        return try {
            require(song.localPath == null) { "This track is already local" }
            val stream = DesktopMusicSources.resolve(song, quality, forDownload = true).getOrThrow()
            val directory = Path.of(ForkPortable.userHome, "Music", "BitChord") // fork: was user.home; downloads stay the listener's files
            Files.createDirectories(directory)
            val safeName = buildString {
                append(song.artist)
                append(" - ")
                append(song.title)
            }.replace(ILLEGAL_FILENAME, "_").trim().take(180).ifBlank { song.videoId }
            // A manifest is an index of the audio, not the audio: fetched as a file it saved a few
            // KB of XML as `.flac`. Its segments are fMP4, so it can only become FLAC or M4A.
            val manifest = DesktopManifestDownload.isManifest(stream)
            val extension = stream.format.fileExtension().let { if (manifest && it != "flac") "m4a" else it }
            // Fork: a name another track's record (or download in flight) holds gets " (2)" and so on,
            // and the .part carries the track id. Keyed by name alone, two different tracks with the
            // same "Artist - Title" shared one file: the second was "already downloaded" as the
            // first, or both wrote into one .part and the result was neither.
            val ownerOf = { candidate: Path ->
                claimed[candidate.toString().lowercase()]?.takeIf { it != song.videoId } ?: forkRecordedOwner(candidate)
            }
            var target = forkDownloadTarget(directory, safeName, extension, song.videoId, ownerOf)
            while (true) {
                // Claimed under the map's own atomicity: another track taking the name in between
                // sends this one to the next free name.
                val key = target.toString().lowercase()
                val holder = claimed.putIfAbsent(key, song.videoId)
                if (holder == null || holder == song.videoId) {
                    claim = key
                    break
                }
                target = forkDownloadTarget(directory, safeName, extension, song.videoId, ownerOf)
            }
            val idName = song.videoId.replace(ILLEGAL_FILENAME, "_")
            val part = directory.resolve(".$safeName.$idName.$extension.part")
            val src = directory.resolve(".$safeName.$idName.$extension.part.src")
            temporary = part
            sidecar = src
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                return Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
            }
            val forkTags = ForkDownloadTags.start(song) // fork: cover and lyrics looked up while the audio downloads

            if (manifest) {
                // Segments are fetched and joined whole, so there is no partial file to resume.
                Files.deleteIfExists(part)
                Files.newOutputStream(part).buffered().use { output ->
                    DesktopManifestDownload.save(
                        stream,
                        extension,
                        output,
                        tempDir = java.io.File(System.getProperty("java.io.tmpdir")),
                        userAgent = DOWNLOAD_USER_AGENT,
                    ) { done, total -> onProgress(done, total) }
                }
                check(Files.size(part) > 0L) { "Download failed: nothing was sent" }
                ForkDownloadTags.write(forkTags, part, song) // fork: the phone's tags
                currentCoroutineContext().ensureActive() // fork: a cancel during tagging doesn't still land the file
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                return Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
            }

            // Fork: a .part is resumed only from the stream (source and format) it was begun from:
            // appended to from another source, the file was two encodings glued together.
            val identity = forkStreamIdentity(stream)
            val stored = forkReadSidecar(src)
            var existing = if (Files.isRegularFile(part)) Files.size(part) else 0L
            if (existing > 0L && stored?.first != identity) {
                Files.deleteIfExists(part)
                existing = 0L
            }
            val call = { from: Long ->
                DesktopDownloadHttp.client.newCall(
                    DesktopDownloadHttp.request(stream.url, DOWNLOAD_USER_AGENT, stream.headers, rangeFrom = from.takeIf { it > 0L }),
                ).execute()
            }
            var opened = call(existing)
            var alreadyWhole = false
            // Fork: 416 to a resume is a .part that is already whole (its recorded length) or one the
            // server can't continue; either way it used to fail with "HTTP 416" on every retry.
            if (opened.code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0L) {
                opened.close()
                if (stored?.second == existing) {
                    alreadyWhole = true
                } else {
                    Files.deleteIfExists(part)
                    existing = 0L
                    opened = call(0L)
                }
            }
            if (!alreadyWhole) opened.use { response ->
                check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
                val body = response.body
                val append = existing > 0L && response.code == HTTP_PARTIAL
                val offset = if (append) existing else 0L
                val total = response.header("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: body.contentLength().takeIf { it > 0L }?.let { it + offset }
                if (!append && existing > 0L) Files.deleteIfExists(part)
                // Fork: a resume whose whole length differs from the recorded one is another file.
                val recordedTotal = stored?.second
                if (append && recordedTotal != null && total != null && total != recordedTotal) {
                    Files.deleteIfExists(part)
                    throw java.io.IOException("The source changed; try again")
                }
                forkWriteSidecar(src, identity, total)
                Files.newOutputStream(
                    part,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
                ).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = offset
                        onProgress(downloaded, total)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            check(Files.size(part) > 0L) { "Download failed: nothing was sent" }
            ForkDownloadTags.write(forkTags, part, song) // fork: the phone's tags
            currentCoroutineContext().ensureActive() // fork: a cancel during tagging doesn't still land the file
            runCatching {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.deleteIfExists(src)
            Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
        } catch (cancelled: CancellationException) {
            // Fork: called off rather than interrupted, so nothing is left to resume (the .part used
            // to stay behind for good).
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
            sidecar?.let { runCatching { Files.deleteIfExists(it) } }
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        } finally {
            if (job != null) activeJobs.remove(song.videoId, job)
            claim?.let { claimed.remove(it, song.videoId) }
        }
    }

    /** Fork: the track whose download record names [path], from the window's downloads list. */
    private fun forkRecordedOwner(path: Path): String? {
        val wanted = path.toString()
        return ForkCollectionActions.window?.downloads()?.firstOrNull { it.localPath.equals(wanted, ignoreCase = true) }?.videoId
    }

    /** Fork: what a .part was begun from; it is resumed only from the same. */
    private fun forkStreamIdentity(stream: DesktopStream): String =
        listOf(stream.sourceId, stream.format.codec, stream.format.kbps, stream.format.sampleRateHz, stream.format.bitDepth, stream.isDolbyAtmos)
            .joinToString("|")

    /** Fork: the .part's sidecar: its stream identity, and its whole length when known. */
    private fun forkReadSidecar(file: Path): Pair<String, Long?>? = runCatching {
        if (!Files.isRegularFile(file)) return null
        val lines = Files.readAllLines(file)
        lines.firstOrNull()?.let { it to lines.getOrNull(1)?.toLongOrNull() }
    }.getOrNull()

    private fun forkWriteSidecar(file: Path, identity: String, total: Long?) {
        runCatching { Files.write(file, listOf(identity, total?.toString().orEmpty())) }
    }

    fun cancel(videoId: String) {
        activeJobs[videoId]?.cancel(CancellationException("Download cancelled"))
    }

    /**
     * The file saved for [song], or null when the record has outlived it.
     *
     * A download record is a claim about a folder this app does not own: the listener is expected
     * to manage it with a file manager, so an entry can name a file that has since been moved or
     * deleted. Android checks before it answers for the same reason — without it the player is
     * handed a path that is simply not there and the track refuses to play, saying only "could not
     * open stream".
     */
    fun savedFile(song: Song): Path? {
        val path = song.localPath?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: song.localUri?.takeIf { it.startsWith("file:") }
                ?.let { runCatching { Path.of(URI(it)) }.getOrNull() }
            ?: return null
        return path.takeIf { Files.isRegularFile(it) && runCatching { Files.size(it) > 0 }.getOrDefault(false) }
    }

    /** Whether [song] is really on this computer, rather than merely recorded as being. */
    fun isSaved(song: Song): Boolean = savedFile(song) != null

    /**
     * The records that still name a file, with the rest dropped.
     *
     * A claim that has just been shown to be false is not worth keeping to be shown false again on
     * the next play.
     */
    fun verified(downloads: List<Song>): List<Song> = downloads.filter(::isSaved)

    /** Deletes the file saved for [song]. Returns whether there was one to delete. */
    fun delete(song: Song): Boolean {
        val file = savedFile(song) ?: return false
        return runCatching { Files.deleteIfExists(file) }
            .onFailure { DesktopTrackLog.log("could not delete the download of '${song.title}': ${it.message}") }
            .getOrDefault(false)
    }

    private fun DesktopStreamFormat.fileExtension(): String {
        val codec = codec.orEmpty().substringAfterLast('/').substringBefore(';').lowercase()
        return when (codec) {
            "flac" -> "flac"
            "wav", "wave" -> "wav"
            "alac" -> "m4a"
            "aac" -> "m4a"
            "mp3" -> "mp3"
            "ogg", "opus" -> "ogg"
            "webm" -> "webm"
            else -> "m4a"
        }
    }

    private const val HTTP_PARTIAL = 206
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    private const val DOWNLOAD_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val ILLEGAL_FILENAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
}

/**
 * Fork: where a download of [videoId] goes in [directory]: "[base].[extension]", or the first of
 * "[base] (2).[extension]", "[base] (3)…" that no other track owns. [ownerOf] answers which track a
 * candidate belongs to (a download record, or a download in flight), null when none does: a file
 * nobody owns is taken as this track's, as it always was (a record can be lost while its file stays).
 */
internal fun forkDownloadTarget(directory: Path, base: String, extension: String, videoId: String, ownerOf: (Path) -> String?): Path {
    var n = 1
    while (true) {
        val candidate = directory.resolve(if (n == 1) "$base.$extension" else "$base ($n).$extension")
        val owner = ownerOf(candidate)
        if (owner == null || owner == videoId) return candidate
        n++
    }
}

/**
 * Fork: whether the startup check may forget the [recorded] downloads it found gone ([onDisk] is
 * what it found). Not when the download folder itself is missing ([folderThere]: an unplugged or
 * not yet mounted Music drive) or when every record failed at once: either reads as a drive that
 * is away, not as files the listener deleted, and pruning then forgot every download for good.
 */
internal fun forkMayPruneDownloads(recorded: List<Song>, onDisk: List<Song>, folderThere: Boolean): Boolean =
    folderThere && (onDisk.isNotEmpty() || recorded.isEmpty())
