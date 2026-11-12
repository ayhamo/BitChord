package com.music.bitchord.playback.smart

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keys saved before the analyser's ♯/♭ reached Kotlin whole ("F♯ major" arrived as "F major")
 * read back as unknown, not as the wrong key; keys saved since come back as they were.
 */
class ForkAnalysisKeyTest {

    private fun store(dir: File) = AnalysisStore { dir }

    @Test
    fun aKeySavedNowComesBackWhole() {
        val dir = Files.createTempDirectory("fork-keys").toFile()
        try {
            store(dir).save("t1", TrackAnalysis(status = TrackAnalysis.STATUS_READY, bpm = 120.0, duration = 200.0, key = "F♯ major", keyConfidence = 0.8))
            val back = store(dir).load("t1")!!
            assertEquals("F♯ major", back.key)
            assertEquals(0.8, back.keyConfidence, 1e-9)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aKeySavedBeforeTheFixIsUnknown() {
        val dir = Files.createTempDirectory("fork-keys").toFile()
        try {
            store(dir).save("t2", TrackAnalysis(status = TrackAnalysis.STATUS_READY, bpm = 120.0, duration = 200.0, key = "F major", keyConfidence = 0.8))
            val file = File(dir, "smart_analysis").listFiles()!!.single { it.name.endsWith(".json") }
            file.writeText(file.readText().replace(Regex(""","?"forkKeyText":true"""), ""))
            val back = store(dir).load("t2")!!
            assertEquals("", back.key)
            assertEquals(0.0, back.keyConfidence, 1e-9)
            assertEquals(120.0, back.bpm, 1e-9)
        } finally {
            dir.deleteRecursively()
        }
    }
}
