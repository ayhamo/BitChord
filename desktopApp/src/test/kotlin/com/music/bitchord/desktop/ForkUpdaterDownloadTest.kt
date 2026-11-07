package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Fork: the PC's in-app update from download to staged app ([ForkUpdater.prepare]) and the clean-up
 * after the new app starts ([ForkUpdater.cleanUpAfterStart]), against a local server standing in for
 * GitHub: a good release is staged and checked, and every way it can fail (a damaged or short
 * download, a missing sums line, an HTTP error, a zip without the app, Cancel) leaves nothing in
 * `data\update\` but what was there. The swap itself is `BitChordUpdate.exe` (native, Windows only),
 * tested end to end in the app (STATUS.md).
 */
class ForkUpdaterDownloadTest {

    private val temp: Path = Files.createTempDirectory("bitchord-update-download")
    private val dir: Path = temp.resolve("update")
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        temp.toFile().deleteRecursively()
    }

    // ---- the stand-in release -------------------------------------------------------------------

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val file = temp.resolve("built-${System.nanoTime()}.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return Files.readAllBytes(file).also { Files.delete(file) }
    }

    /** The release zip's shape: one `BitChord Portable` folder with what the app needs to start. */
    private fun appZip(padding: Int = 0, vararg leaveOut: String): ByteArray {
        val files = listOf(
            "BitChord Portable/BitChord.exe" to "exe".toByteArray(),
            "BitChord Portable/lib/bitchord-app.jar" to "jar".toByteArray(),
            "BitChord Portable/runtime/bin/javaw.exe" to "java".toByteArray(),
            // Random, so the zip can't shrink it: a download long enough to report progress and be cancelled.
            "BitChord Portable/lib/padding.bin" to Random(7).nextBytes(padding),
        ).filterNot { (name, _) -> leaveOut.any { name.endsWith(it) } }
        return zipOf(*files.toTypedArray())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Serves [body] at [path] with its length, in 64 KiB pieces [pauseMs] apart; [status] answers
     * with an error instead.
     */
    private fun serve(path: String, body: ByteArray, status: Int = 200, pauseMs: Long = 0) {
        server.createContext(path) { exchange ->
            runCatching {
                if (status != 200) {
                    exchange.sendResponseHeaders(status, -1)
                } else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { out ->
                        var sent = 0
                        while (sent < body.size) {
                            val piece = minOf(64 * 1024, body.size - sent)
                            out.write(body, sent, piece)
                            out.flush()
                            sent += piece
                            if (pauseMs > 0) Thread.sleep(pauseMs)
                        }
                    }
                }
            }
            exchange.close()
        }
    }

    private fun release(zip: ByteArray, sums: String = "${sha256(zip)}  BitChord.Portable.zip\n", zipPath: String = "/dl/BitChord.Portable.zip"): DesktopUpdateChecker.UpdateInfo {
        serve(zipPath, zip)
        serve("/dl/SHA256SUMS.txt", sums.toByteArray())
        return info(zipPath)
    }

    private fun info(zipPath: String?, sums: Boolean = true) = DesktopUpdateChecker.UpdateInfo(
        version = "1.9.1",
        releaseUrl = "$base/release",
        downloadUrl = zipPath?.let { base + it },
        notes = "Build: 2",
        build = 2,
        sumsUrl = if (sums) "$base/dl/SHA256SUMS.txt" else null,
    )

    /** Nothing a failed or cancelled update wrote is left. */
    private fun assertNothingLeft() {
        assertFalse(dir.resolve("BitChord Portable.zip.part").exists(), "the part file is left")
        assertFalse(dir.resolve("BitChord Portable.zip").exists(), "the zip is left")
        assertFalse(dir.resolve("staged").exists(), "the staged copy is left")
    }

    private fun failure(info: DesktopUpdateChecker.UpdateInfo): String = runBlocking {
        assertFailsWith<Exception> { ForkUpdater.prepare(info, dir) {} }.message.orEmpty()
    }

    // ---- download and check ---------------------------------------------------------------------

    @Test
    fun aGoodReleaseIsStagedAndItsDownloadRemoved() {
        val progress = mutableListOf<Float?>()
        val root = runBlocking { ForkUpdater.prepare(release(appZip(padding = 300_000)), dir) { progress += it } }
        assertEquals(dir.resolve("staged").resolve("BitChord Portable"), root)
        assertEquals(emptyList(), ForkUpdater.missingIn(root))
        assertEquals("jar", root.resolve("lib/bitchord-app.jar").readText())
        assertFalse(dir.resolve("BitChord Portable.zip").exists())
        assertFalse(dir.resolve("BitChord Portable.zip.part").exists())
        val fractions = progress.filterNotNull()
        assertTrue(fractions.size > 1, "progress reported in steps: $fractions")
        assertEquals(1f, fractions.last())
        assertEquals(fractions, fractions.sorted(), "progress never goes back")
        assertEquals(null, progress.last(), "unzipping shows as no fraction")
    }

    @Test
    fun aDamagedDownloadIsRefusedAndRemoved() {
        val message = failure(release(appZip(), sums = "${"0".repeat(64)}  BitChord.Portable.zip\n"))
        assertTrue(message.startsWith("The download is damaged"), message)
        assertNothingLeft()
    }

    @Test
    fun sumsThatDontNameTheDownloadRefuseIt() {
        val zip = appZip()
        val message = failure(release(zip, sums = "${sha256(zip)}  something-else.zip\n"))
        assertTrue(message.contains("doesn't list the download"), message)
        assertNothingLeft()
    }

    @Test
    fun aDownloadThatEndsEarlyIsRefused() {
        val zip = appZip(padding = 300_000)
        serve("/dl/SHA256SUMS.txt", "${sha256(zip)}  BitChord.Portable.zip\n".toByteArray())
        // A connection that drops half way: the length promised, half sent, the socket closed. (The
        // JDK's HttpServer keeps a short response's connection open, which is a stall, not a drop.)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            val dropper = thread {
                runCatching {
                    socket.accept().use { client ->
                        client.getInputStream().read(ByteArray(8192)) // the request
                        val out = client.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${zip.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(zip, 0, zip.size / 2)
                        out.flush()
                    }
                }
            }
            val dropped = info("/dl/BitChord.Portable.zip").copy(downloadUrl = "http://127.0.0.1:${socket.localPort}/BitChord.Portable.zip")
            failure(dropped) // the client's own "closed early" or ours: either refuses it
            dropper.join(5_000)
        }
        assertNothingLeft()
    }

    @Test
    fun anHttpErrorNamesItsCode() {
        serve("/dl/SHA256SUMS.txt", "${"a".repeat(64)}  BitChord.Portable.zip\n".toByteArray())
        serve("/dl/BitChord.Portable.zip", ByteArray(0), status = 404)
        assertEquals("The download failed (HTTP 404).", failure(info("/dl/BitChord.Portable.zip")))
        assertNothingLeft()
        serve("/gone/SHA256SUMS.txt", ByteArray(0), status = 500)
        val sumsGone = info("/dl/BitChord.Portable.zip").copy(sumsUrl = "$base/gone/SHA256SUMS.txt")
        assertEquals("SHA256SUMS.txt couldn't be read (HTTP 500).", failure(sumsGone))
    }

    @Test
    fun aReleaseWithoutTheAppOrItsSumsIsNotInstalled() {
        assertEquals("The release has no Windows download.", failure(info(null)))
        assertTrue(failure(info("/dl/BitChord.Portable.zip", sums = false)).contains("SHA256SUMS.txt"))
        assertEquals("This copy of BitChord can't update itself.", runBlocking {
            assertFailsWith<ForkUpdater.UpdateFailed> { ForkUpdater.prepare(info("/x"), into = null) {} }.message
        })
    }

    @Test
    fun aZipWithoutTheAppIsRefusedAndRemoved() {
        assertEquals("The download is missing runtime/bin/javaw.exe.", failure(release(appZip(0, "javaw.exe"))))
        assertNothingLeft()
        server.removeContext("/dl/BitChord.Portable.zip")
        server.removeContext("/dl/SHA256SUMS.txt")
        assertEquals("The download holds no BitChord.exe.", failure(release(zipOf("readme.txt" to "hi".toByteArray()))))
        assertNothingLeft()
    }

    @Test
    fun cancelStopsTheDownloadAndRemovesIt() {
        val zip = appZip(padding = 3_000_000)
        serve("/dl/SHA256SUMS.txt", "${sha256(zip)}  BitChord.Portable.zip\n".toByteArray())
        serve("/dl/BitChord.Portable.zip", zip, pauseMs = 20) // ~1 s for the whole file
        val started = CompletableDeferred<Unit>()
        runBlocking {
            val update = async(Dispatchers.IO) {
                ForkUpdater.prepare(info("/dl/BitChord.Portable.zip"), dir) { fraction ->
                    if (fraction != null && fraction > 0.05f) started.complete(Unit)
                }
            }
            started.await()
            assertTrue(dir.resolve("BitChord Portable.zip.part").exists(), "the download is under way")
            update.cancel()
            assertFailsWith<CancellationException> { update.await() }
        }
        assertNothingLeft()
    }

    @Test
    fun aNewDownloadStartsFromAnEmptyStagingFolder() {
        Files.createDirectories(dir.resolve("staged/BitChord Portable/lib"))
        Files.writeString(dir.resolve("staged/BitChord Portable/lib/stale.jar"), "old")
        val root = runBlocking { ForkUpdater.prepare(release(appZip()), dir) {} }
        assertFalse(root.resolve("lib/stale.jar").exists(), "a file from an earlier staging survived")
    }

    // ---- after the new app starts ---------------------------------------------------------------

    private fun leftovers() {
        Files.createDirectories(dir.resolve("old/lib"))
        Files.writeString(dir.resolve("old/lib/bitchord-app.jar"), "old")
        Files.createDirectories(dir.resolve("staged"))
        Files.writeString(dir.resolve("BitChord Portable.zip"), "zip")
        Files.writeString(dir.resolve("BitChord Portable.zip.part"), "part")
        Files.writeString(dir.resolve(ForkUpdater.UPDATER_EXE), "exe")
    }

    @Test
    fun aConfirmedStartRemovesTheOldAppAndEverythingStaged() {
        leftovers()
        ForkUpdater.cleanUpAfterStart(firstFrameShown = true, dir = dir)
        assertFalse(dir.resolve("old").exists())
        assertNothingLeft()
        assertTrue(dir.resolve(ForkUpdater.UPDATER_EXE).exists(), "the updater copy is replaced on the next update, not removed")
    }

    @Test
    fun anUnconfirmedStartKeepsTheOldAppForARollBack() {
        leftovers()
        ForkUpdater.cleanUpAfterStart(firstFrameShown = false, dir = dir)
        assertTrue(dir.resolve("old/lib/bitchord-app.jar").exists())
        assertTrue(dir.resolve("staged").exists())
    }

    @Test
    fun noUpdateFolderIsNothingToCleanUp() {
        ForkUpdater.cleanUpAfterStart(firstFrameShown = true, dir = temp.resolve("never-made"))
        ForkUpdater.cleanUpAfterStart(firstFrameShown = true, dir = null)
        assertFalse(temp.resolve("never-made").exists())
    }
}
