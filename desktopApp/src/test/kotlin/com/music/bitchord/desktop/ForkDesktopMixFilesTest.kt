package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.automixv2.ForkV2MixFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The PC's mix-file reads and writes, which run off the UI thread (audit 2026-11-26 3a #5): their answers. */
class ForkDesktopMixFilesTest {

    @Test
    fun aMixFileIsReadAndAnOversizedOneNeverIs() {
        val dir = Files.createTempDirectory("mixfiles")
        try {
            val ok = dir.resolve("a.vivimix.json")
            assertTrue(ForkDesktopMixFiles.write(ok, "{\"mixes\":[]}"))
            assertEquals("{\"mixes\":[]}", assertIs<ForkDesktopMixFiles.Read.Text>(ForkDesktopMixFiles.read(ok)).text)

            val big = dir.resolve("big.json")
            Files.write(big, ByteArray(ForkV2MixFile.MAX_BYTES + 1))
            assertEquals("the file is over 2 MB, not a mix file", assertIs<ForkDesktopMixFiles.Read.Failed>(ForkDesktopMixFiles.read(big)).why)

            assertEquals("the file could not be read", assertIs<ForkDesktopMixFiles.Read.Failed>(ForkDesktopMixFiles.read(dir.resolve("missing.json"))).why)
            assertFalse(ForkDesktopMixFiles.write(dir.resolve("no/such/dir/x.json"), "x"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
