package com.music.bitchord.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Fork (from Desktop Revived 88dcc5d0, `DesktopAudioCache`): the windows [DesktopRangeStream] fetches,
 * kept on disk up to [LIMIT_BYTES] (256 MiB), least recently used dropped first, so a replay, a seek
 * back or the analyser reading the same file doesn't fetch it again.
 *
 * Only what can't change under its key is kept: a YouTube file named by its `id`, `itag`, `lmt` and
 * `clen` (the same bytes whatever address was signed for them, so a song resolved again still hits),
 * or a response that says it may be cached. No address, header or login is written: the key is a hash
 * and a span holds audio bytes, a checksum and when it expires. One cache path with the rest of the
 * media cache (`<media cache>/audio_ranges`, inside the portable app's data), cleared with "Clear song
 * cache". Navidrome songs don't come through here: their pre-loaded and kept files ([ForkPreload],
 * offline) are read from disk first.
 */
internal class ForkAudioRangeCache(
    private val directory: () -> Path,
    private val limitBytes: Long = LIMIT_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Span(val bytes: ByteArray, val total: Long)

    @Synchronized
    fun read(key: String, start: Long): Span? = runCatching {
        val file = path(key, start)
        if (!Files.isRegularFile(file)) return null
        val span = DataInputStream(Files.newInputStream(file).buffered()).use { input ->
            check(input.readInt() == MAGIC)
            check(input.readLong() > now())
            val total = input.readLong()
            val size = input.readInt()
            check(size in 1..MAX_SPAN && total >= start + size)
            val hash = ByteArray(32).also(input::readFully)
            val bytes = ByteArray(size).also(input::readFully)
            check(input.read() == -1 && MessageDigest.isEqual(hash, sha(bytes)))
            Span(bytes, total)
        }
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(now()))
        span
    }.getOrElse {
        runCatching { Files.deleteIfExists(path(key, start)) }
        null
    }

    @Synchronized
    fun write(key: String, start: Long, bytes: ByteArray, total: Long, expiresAt: Long) {
        if (bytes.isEmpty() || bytes.size > MAX_SPAN || total < start + bytes.size || expiresAt <= now()) return
        // A full or read-only disk never stops playback.
        runCatching {
            val dir = directory()
            Files.createDirectories(dir)
            val temporary = Files.createTempFile(dir, ".span-", ".tmp")
            try {
                DataOutputStream(Files.newOutputStream(temporary).buffered()).use { out ->
                    out.writeInt(MAGIC)
                    out.writeLong(expiresAt)
                    out.writeLong(total)
                    out.writeInt(bytes.size)
                    out.write(sha(bytes))
                    out.write(bytes)
                }
                try {
                    Files.move(temporary, path(key, start), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, path(key, start), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
            trim()
        }
    }

    /**
     * Whether the window of [key] at [start] is on disk and not past its keep date, reading only its
     * header ([ForkCachedSongs] checks a whole song before playing it with no network, so a window
     * that [read] would throw away mid-song is found first). Its bytes are checked when read.
     */
    @Synchronized
    fun fresh(key: String, start: Long): Boolean = runCatching {
        DataInputStream(Files.newInputStream(path(key, start)).buffered()).use { input ->
            input.readInt() == MAGIC && input.readLong() > now()
        }
    }.getOrDefault(false)

    @Synchronized
    fun clear() {
        runCatching { Files.list(directory()).use { files -> files.forEach { Files.deleteIfExists(it) } } }
    }

    /** Oldest first until under the limit (and [MAX_FILES]), leftovers of a write cut short included. */
    private fun trim() {
        val files = Files.list(directory()).use { entries ->
            entries.filter {
                val name = it.fileName.toString()
                name.endsWith(".span") || (name.startsWith(".span-") && name.endsWith(".tmp"))
            }.toList()
        }
        var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        var count = files.size
        for (file in files.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }) {
            if (total <= limitBytes && count <= MAX_FILES) break
            val size = runCatching { Files.size(file) }.getOrDefault(0L)
            if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) {
                total -= size
                count--
            }
        }
    }

    private fun path(key: String, start: Long): Path {
        require(key.matches(KEY) && start >= 0)
        return directory().resolve("$key-$start.span")
    }

    companion object {
        const val LIMIT_BYTES = 256L * 1024 * 1024
        const val MAX_SPAN = 512 * 1024
        const val MAX_FILES = 1_024
        private const val MAGIC = 0x42434131
        private val KEY = Regex("[a-f0-9]{64}")

        fun identity(value: String): String = sha(value.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        val shared by lazy { ForkAudioRangeCache({ DesktopMediaCache.directory.resolve("audio_ranges") }) }

        private val QUERY = Regex("""(?:^|&)(id|itag|lmt|clen)=([^&]+)""")

        /**
         * The key a window of [url] is kept under, or null when it isn't kept: a login in the address
         * or the headers, or a host whose bytes aren't known to stay the same.
         */
        fun keyFor(url: String, headers: Map<String, String>, windowBytes: Int): String? {
            val uri = runCatching { java.net.URI.create(url) }.getOrNull() ?: return null
            if (uri.userInfo != null) return null
            if (headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) }) return null
            val query = queryOf(uri)
            val immutable = uri.host.orEmpty().endsWith(".googlevideo.com") &&
                listOf("id", "itag", "lmt", "clen").all { query[it] != null }
            if (!immutable) return null
            return identity("gv|${query["id"]}|${query["itag"]}|${query["lmt"]}|${query["clen"]}|$windowBytes")
        }

        /** How long a span is kept at most; its bytes don't change, the limit and LRU do the rest. */
        const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

        private fun queryOf(uri: java.net.URI): Map<String, String> =
            QUERY.findAll(uri.rawQuery.orEmpty()).associate { it.groupValues[1] to it.groupValues[2] }

    }
}
