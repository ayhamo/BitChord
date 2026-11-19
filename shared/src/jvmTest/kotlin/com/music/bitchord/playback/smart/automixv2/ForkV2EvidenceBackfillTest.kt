package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a song analysed before Automix v2 gets its evidence pass ([ForkV2EvidenceBackfill]). */
class ForkV2EvidenceBackfillTest {

    private val measured = HashSet<String>()

    @BeforeTest
    fun setUp() {
        ForkV2EvidenceBackfill.forget()
        ForkV2EvidenceBackfill.has = { it in measured }
        ForkV2Automix.forget()
        ForkV2Automix.evidence = { id -> if (id in measured) ForkV2Analysis() else null }
        ForkV2EvidenceBackfill.install()
    }

    @AfterTest
    fun tearDown() {
        ForkV2EvidenceBackfill.forget()
        ForkV2Automix.forget()
        ForkV2Automix.evidence = null
        ForkV2Automix.evidenceMissing = null
    }

    private fun analysed(id: String, bpm: Double = 120.0) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = id,
        duration = 200.0,
        bpm = bpm,
        beatInterval = if (bpm > 0) 60.0 / bpm else 0.0,
        beatConfidence = 0.8,
    )

    @Test
    fun `a planned song with an analysis and no evidence is wanted, and claimed once`() {
        ForkV2Automix.analysisOf(analysed("old"), "old", 200.0)
        assertTrue(ForkV2EvidenceBackfill.isWanted("old"))
        assertTrue(ForkV2EvidenceBackfill.claim("old"))
        assertFalse(ForkV2EvidenceBackfill.claim("old"), "claimed twice")
        // Planned again (every second) while its pass runs or after it failed: not wanted again this run.
        ForkV2Automix.analysisOf(analysed("old"), "old", 200.0)
        assertFalse(ForkV2EvidenceBackfill.isWanted("old"))
        assertFalse(ForkV2EvidenceBackfill.claim("old"))
    }

    @Test
    fun `a song with evidence, or without a usable analysis, is not wanted`() {
        measured += "new"
        ForkV2Automix.analysisOf(analysed("new"), "new", 200.0)
        assertFalse(ForkV2EvidenceBackfill.isWanted("new"))
        ForkV2Automix.analysisOf(analysed("failed", bpm = 0.0), "failed", 200.0)
        assertFalse(ForkV2EvidenceBackfill.isWanted("failed"))
        ForkV2Automix.analysisOf(analysed("x"), "", 200.0) // no id: the analysis's own
        assertTrue(ForkV2EvidenceBackfill.isWanted("x"))
    }

    @Test
    fun `nothing is claimed that the planner did not ask for`() {
        assertFalse(ForkV2EvidenceBackfill.claim("never-planned"))
        assertFalse(ForkV2EvidenceBackfill.claim(""))
    }

    @Test
    fun `a song analysed afresh meanwhile is not claimed`() {
        ForkV2EvidenceBackfill.want("song")
        measured += "song" // its whole-track pass measured the evidence itself
        assertFalse(ForkV2EvidenceBackfill.claim("song"))
    }

    @Test
    fun `the wanted list is bounded, the oldest forgotten`() {
        for (i in 0 until ForkV2EvidenceBackfill.MAX_WANTED + 6) ForkV2EvidenceBackfill.want("s$i")
        assertFalse(ForkV2EvidenceBackfill.isWanted("s0"))
        assertTrue(ForkV2EvidenceBackfill.isWanted("s${ForkV2EvidenceBackfill.MAX_WANTED + 5}"))
    }

    @Test
    fun `the store wires the planner to the backfill`() {
        ForkV2Automix.evidenceMissing = null
        val dir = kotlin.io.path.createTempDirectory("v2ev").toFile()
        try {
            ForkV2EvidenceStore.install(dir)
            ForkV2EvidenceBackfill.has = { false }
            ForkV2Automix.analysisOf(analysed("wired"), "wired", 200.0)
            assertTrue(ForkV2EvidenceBackfill.isWanted("wired"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
