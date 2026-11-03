package com.music.bitchord

import com.music.bitchord.fork.ForkAnalysisRedo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ForkAnalysisRedoTest {

    @get:Rule val tmp = TemporaryFolder()

    /**
     * One test, because the object reads its file once per process: a weak restore skipped and never
     * measured is skipped again; only a saved new pass ends that, and it is written down for later.
     */
    @Test
    fun aSkippedRestoreCountsAsRedoneOnlyAfterTheNewPass() {
        val dir = tmp.root
        assertTrue(ForkAnalysisRedo.skipRestore(dir, "skipped-past"))
        assertTrue(ForkAnalysisRedo.skipRestore(dir, "skipped-past"))

        assertTrue(ForkAnalysisRedo.skipRestore(dir, "measured"))
        ForkAnalysisRedo.measured(dir, "measured")
        assertFalse(ForkAnalysisRedo.skipRestore(dir, "measured"))

        // A fresh track's first pass was not waiting on anything: nothing is recorded for it.
        ForkAnalysisRedo.measured(dir, "fresh")
        assertEquals(listOf("measured"), File(dir, "automix_redone.txt").readLines())
    }
}
