package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The PC's build number, the release's checksum line and the safe unzip ([ForkUpdater]), and the "Later" rule ([ForkUpdateFlow]). */
class ForkUpdaterTest {

    private val temp: Path = Files.createTempDirectory("fork-updater-test")

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    // ---- build number -----------------------------------------------------------------------------

    @Test
    fun theBuildIsReadFromItsOwnLineInTheNotes() {
        val notes = "BitChord 1.9.1 for Windows.\r\n\r\nBuild: 42\r\n\r\nCommit: abc\r\n"
        assertEquals(42, ForkUpdater.buildOf(notes))
        assertEquals(7, ForkUpdater.buildOf("**Build:** 7"))
        assertEquals(9, ForkUpdater.buildOf("- build 9"))
    }

    @Test
    fun notesWithoutTheLineAreBuildZero() {
        assertEquals(0, ForkUpdater.buildOf(null))
        assertEquals(0, ForkUpdater.buildOf(""))
        assertEquals(0, ForkUpdater.buildOf("### New in this build\n- Navidrome: rebuild the cache"))
        assertEquals(0, ForkUpdater.buildOf("Fixed the build 12 crash")) // not at the start of a line
    }

    @Test
    fun aHigherVersionIsOfferedWhateverTheBuild() {
        assertTrue(ForkUpdater.offers("1.9.2", 0, "1.9.1", 50))
        assertFalse(ForkUpdater.offers("1.9.0", 99, "1.9.1", 1))
    }

    @Test
    fun theSameVersionIsOfferedOnlyWithAHigherBuild() {
        assertTrue(ForkUpdater.offers("1.9.1", 43, "1.9.1", 42))
        assertFalse(ForkUpdater.offers("1.9.1", 42, "1.9.1", 42))
        assertFalse(ForkUpdater.offers("1.9.1", 41, "1.9.1", 42))
        // Releases from before the build number (build 0) are never offered over the same version.
        assertFalse(ForkUpdater.offers("1.9.1", 0, "1.9.1", 0))
        // A local build (0) is offered the release's rebuild.
        assertTrue(ForkUpdater.offers("1.9.1", 5, "1.9.1", 0))
    }

    @Test
    fun aBetaIsNotTheSameVersionAsItsRelease() {
        // 1.9.1 is newer than 1.9.1-beta1 anyway; the other way round is never offered.
        assertTrue(ForkUpdater.offers("1.9.1", 0, "1.9.1-beta1", 10))
        assertFalse(ForkUpdater.offers("1.9.1-beta1", 99, "1.9.1", 1))
    }

    private fun release(tag: String, body: String?, vararg assets: String): JsonObject = Json.parseToJsonElement(
        buildString {
            append("""{"tag_name":"$tag","html_url":"https://example.invalid/releases/$tag","draft":false""")
            if (body != null) append(""","body":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(body))}""")
            append(""","assets":[""")
            append(assets.joinToString { name -> """{"name":"$name","state":"uploaded","browser_download_url":"https://example.invalid/$name"}""" })
            append("]}")
        },
    ).jsonObject

    @Test
    fun theCheckerOffersARebuildOfTheSameVersion() {
        val rebuilt = release("fork-pc-v1.9.1", "Build: 12\n\nCommit: x", "SHA256SUMS.txt", "BitChord.Portable.zip")
        val update = DesktopUpdateChecker.updateFrom(rebuilt, "1.9.1", DesktopUpdateChecker.WINDOWS_SUFFIXES, currentBuild = 11)
        assertNotNull(update)
        assertEquals(12, update.build)
        assertEquals("https://example.invalid/SHA256SUMS.txt", update.sumsUrl)
        assertNull(DesktopUpdateChecker.updateFrom(rebuilt, "1.9.1", DesktopUpdateChecker.WINDOWS_SUFFIXES, currentBuild = 12))
        val listed = DesktopUpdateChecker.forkPcUpdate(listOf(rebuilt), "1.9.1", DesktopUpdateChecker.WINDOWS_SUFFIXES, currentBuild = 3)
        assertEquals(12, listed?.build)
    }

    @Test
    fun aReleaseWithoutSumsHasNoSumsUrl() {
        assertNull(ForkUpdater.sumsUrlOf(release("fork-pc-v1.9.1", null, "BitChord.Portable.zip")))
    }

    // ---- SHA256SUMS ---------------------------------------------------------------------------------

    private val hash = "a".repeat(32) + "B".repeat(32)

    @Test
    fun theSumIsFoundByTheDownloadsName() {
        val sums = "${"0".repeat(64)}  other.zip\n$hash  BitChord.Portable.zip\n"
        assertEquals(hash.lowercase(), ForkUpdater.expectedSha256(sums, "https://github.com/x/releases/download/t/BitChord.Portable.zip"))
        // binary-mode star, the name with its space, an encoded URL
        assertEquals(hash.lowercase(), ForkUpdater.expectedSha256("$hash *BitChord Portable.zip", "http://127.0.0.1/BitChord%20Portable.zip"))
    }

    @Test
    fun noLineForTheZipGivesNothing() {
        assertNull(ForkUpdater.expectedSha256("$hash  something-else.zip", "https://example.invalid/BitChord.Portable.zip"))
        assertNull(ForkUpdater.expectedSha256("not a sums file", "https://example.invalid/BitChord.Portable.zip"))
    }

    // ---- unzip ---------------------------------------------------------------------------------------

    private fun zip(vararg entries: Pair<String, String>): Path {
        val file = temp.resolve("test-${entries.size}-${System.nanoTime()}.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun theReleaseZipUnzipsAndItsRootIsFound() {
        val archive = zip(
            "BitChord Portable/" to "",
            "BitChord Portable/BitChord.exe" to "exe",
            "BitChord Portable/lib/bitchord-app.jar" to "jar",
            "BitChord Portable/runtime/bin/javaw.exe" to "java",
        )
        val staged = temp.resolve("staged")
        ForkUpdater.unzip(archive, staged)
        val root = ForkUpdater.stagedRoot(staged)
        assertEquals(staged.resolve("BitChord Portable"), root)
        assertEquals(emptyList(), ForkUpdater.missingIn(root!!))
        assertEquals("jar", root.resolve("lib/bitchord-app.jar").readText())
    }

    @Test
    fun missingProgramFilesAreNamed() {
        val root = temp.resolve("app").createDirectories()
        root.resolve("BitChord.exe").writeText("exe")
        assertEquals(listOf("lib/bitchord-app.jar", "runtime/bin/javaw.exe"), ForkUpdater.missingIn(root))
        assertNull(ForkUpdater.stagedRoot(temp.resolve("app").resolveSibling("empty").createDirectories()))
    }

    @Test
    fun anEntryThatClimbsOutRefusesTheWholeZip() {
        for (bad in listOf("../evil.txt", "BitChord Portable/../../evil.txt", "/abs.txt", "C:/Windows/evil.txt", "..\\evil.txt")) {
            val archive = zip("BitChord Portable/BitChord.exe" to "exe", bad to "x")
            val into = temp.resolve("into-${bad.hashCode()}")
            assertFailsWith<ForkUpdater.UpdateFailed>(bad) { ForkUpdater.unzip(archive, into) }
            assertFalse(temp.resolve("evil.txt").exists(), bad)
        }
    }

    @Test
    fun safeTargetKeepsInsideTheFolder() {
        val base = temp.resolve("base").toAbsolutePath().normalize()
        assertEquals(base.resolve("a/b.txt"), ForkUpdater.safeTarget(base, "a/b.txt"))
        assertEquals(base.resolve("b.txt"), ForkUpdater.safeTarget(base, "a/../b.txt"))
        assertNull(ForkUpdater.safeTarget(base, "a/../../b.txt"))
        assertNull(ForkUpdater.safeTarget(base, "."))
        assertNull(ForkUpdater.safeTarget(base, ""))
    }

    // ---- "Later" -------------------------------------------------------------------------------------

    private fun info(version: String, build: Int) =
        DesktopUpdateChecker.UpdateInfo(version, "https://example.invalid", "https://example.invalid/z.zip", null, build)

    @Test
    fun laterSilencesThatBuildOnlyForTheTimedCheck() {
        assertTrue(ForkUpdateFlow.timedCheckSkips(info("1.9.1", 12), "1.9.1+12"))
        assertFalse(ForkUpdateFlow.timedCheckSkips(info("1.9.1", 13), "1.9.1+12")) // a newer build is offered again
        assertFalse(ForkUpdateFlow.timedCheckSkips(info("1.9.2", 12), "1.9.1+12"))
        assertFalse(ForkUpdateFlow.timedCheckSkips(info("1.9.1", 12), null))
    }

    @Test
    fun theLabelNamesTheBuildWhenThereIsOne() {
        assertEquals("1.9.1 (build 4)", ForkUpdater.label("1.9.1", 4))
        assertEquals("1.9.1", ForkUpdater.label("1.9.1", 0))
    }

    @Suppress("unused")
    private fun unusedJson() = buildJsonObject { put("x", 1) }
}
