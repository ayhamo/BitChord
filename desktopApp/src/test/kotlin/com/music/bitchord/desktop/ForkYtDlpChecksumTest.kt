package com.music.bitchord.desktop

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "Download yt-dlp" button keeps a file only when it matches the release's `SHA2-256SUMS` line for it. */
class ForkYtDlpChecksumTest {

    private val exeHash = "a".repeat(64)
    private val x86Hash = "b".repeat(64)

    /** The shape of yt-dlp's real SHA2-256SUMS: `sha256sum` output, two spaces, one asset per line. */
    private val sums = """
        ${"1".repeat(64)}  yt-dlp
        ${"2".repeat(64)}  yt-dlp.tar.gz
        ${exeHash.uppercase()}  yt-dlp.exe
        ${"3".repeat(64)}  yt-dlp_linux
        $x86Hash  yt-dlp_x86.exe
        ${"4".repeat(64)}  yt-dlp_win.zip
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun picksTheExactAssetLowercased() {
        assertEquals(exeHash, ForkYtDlp.sha256For(sums, "yt-dlp.exe"))
        assertEquals(x86Hash, ForkYtDlp.sha256For(sums, "yt-dlp_x86.exe"))
        assertEquals("1".repeat(64), ForkYtDlp.sha256For(sums, "yt-dlp"))
    }

    @Test
    fun acceptsTheBinaryModeMarker() {
        assertEquals(exeHash, ForkYtDlp.sha256For("$exeHash *yt-dlp.exe\n", "yt-dlp.exe"))
    }

    @Test
    fun noLineOrABadHashIsNull() {
        assertNull(ForkYtDlp.sha256For(sums, "yt-dlp_macos"))
        assertNull(ForkYtDlp.sha256For("", "yt-dlp.exe"))
        assertNull(ForkYtDlp.sha256For("${"a".repeat(63)}  yt-dlp.exe", "yt-dlp.exe"))
        assertNull(ForkYtDlp.sha256For("${"g".repeat(64)}  yt-dlp.exe", "yt-dlp.exe"))
        assertNull(ForkYtDlp.sha256For("<html>Not Found</html>", "yt-dlp.exe"))
    }

    @Test
    fun verifiesAFileAgainstItsHash() {
        val file = File.createTempFile("ytdlp-checksum", ".bin")
        try {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertTrue(ForkYtDlp.matchesSha256(file, hash))
            assertTrue(ForkYtDlp.matchesSha256(file, hash.uppercase()))
            assertFalse(ForkYtDlp.matchesSha256(file, exeHash))
        } finally {
            file.delete()
        }
    }
}
