package com.music.bitchord.ui.replay.year

import com.music.bitchord.data.canvas.ForkBestParts
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/**
 * Fork: the Replay Year's story reads [BestParts.known] before it asks [BestParts.startFor], and a
 * start remembered there before "Your best part" was saved used to win on the next opening.
 */
class ForkReplayBestPartTest {

    @Test
    fun yourBestPartIsWhatTheStoryKnows() {
        val dir = Files.createTempDirectory("replaybest").toFile()
        try {
            ForkBestParts.init(dir) // once per test JVM; a second call keeps the first store
            val id = "kJQP7kiw5Fk"
            ForkBestParts.setMine(id, 61_000)
            assertEquals(BestPartStart(61_000, BestPartStart.Source.HEATMAP), BestParts.known(id))
            ForkBestParts.setMine(id, null)
        } finally {
            dir.deleteRecursively()
        }
    }
}
