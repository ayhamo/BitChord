package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.ForkLoopLyrics
import com.music.bitchord.playback.smart.ForkLoopSpans
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.levelFromRms
import java.io.File
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * The owner's baseline (research/automix-baseline-2026-11-08.md): the loop mix both apps play is planned by
 * [ForkV2Loop.plan] (upstream's [LoopMix.plan], BitChord web's engine), and given Vaina Loca and SI NO ES
 * CONTIGO it lands on the Nov 8 recording's mix by itself, as the extension plans it at 020f8a1: a 4-bar
 * loop at 153.31 s (94 BPM) into a 4-bar loop at 9.60 s (100 BPM), ramp 3, 12 bars, YouTube's player taking
 * over 34.4 s after the loop starts, at 24.0 s of B. No song id or per-song constant anywhere: the songs are
 * the extension's pair fixture 1 (grids, vocal mask, loudness, synced lines read as the loop reads them,
 * [ForkLoopSpans]), the same inputs the apps' analyses and lyrics give the planner.
 *
 * Reads the spec where the Docker build mounts it (/spec, or RULEBOOK_SPEC), as the fixtures test.
 */
@Suppress("UNCHECKED_CAST")
class ForkLoopBaselineTest {

    @Test
    fun vainaLocaIntoSiNoEsContigoLandsOnTheBaselinesMix() {
        val pair = pairFile("1-")
        val (a, b) = (pair["songs"] as List<Map<String, Any?>>).let { it[0] to it[1] }
        val out = analysisOf(a)
        val inn = analysisOf(b)
        // the apps plan once the outgoing song's last minute is in reach: playhead + 15 s of setup
        val earliest = max(30.0, out.duration - 95) + 15
        val why = StringBuilder()
        val plan = assertNotNull(
            ForkV2Loop.plan(out, out.duration, inn, inn.duration, earliest, sungOf(a), sungOf(b), why = why),
            "no loop mix: $why",
        )
        assertEquals(4, plan.out.bars, "a 4-bar loop on the outgoing song")
        assertEquals(4, plan.inn.bars, "a 4-bar loop on the incoming song")
        assertEquals(153.31, plan.out.loopStart, 0.02, "A's loop at 2:33")
        assertEquals(9.60, plan.inn.loopStart, 0.02, "B's loop at 9.60 s")
        assertEquals(94.0, plan.out.bpm, 0.5)
        assertEquals(100.0, plan.inn.bpm / plan.fold, 0.5)
        assertEquals(4, plan.enterBars)
        assertEquals(3, plan.rampBars)
        assertEquals(12, plan.totalBars)
        val clock = LoopMix.Clock(plan)
        assertEquals(10.21, clock.enterEnd, 0.05, "4 bars at 94")
        assertEquals(34.44, clock.elementAt, 0.1, "YouTube's player takes over about 34.4 s after the loop starts")
        assertEquals(24.0, clock.elementTrackTime, 0.05, "at 24.0 s of B")
        assertEquals(LoopMix.Check.LYRICS, plan.out.checkedBy, "the synced lines decide, as on Nov 8")
    }

    /** Rule 6: NetEase's credit at 0.00 s with no end blocked SI NO ES CONTIGO's first 10 s; marked not sung, the plan is the same. */
    @Test
    fun aCreditLineAtTheStartChangesNothing() {
        val pair = pairFile("1-")
        val (a, b) = (pair["songs"] as List<Map<String, Any?>>).let { it[0] to it[1] }
        val out = analysisOf(a)
        val inn = analysisOf(b)
        val earliest = max(30.0, out.duration - 95) + 15
        val credit = ForkLoopSpans.Shape(0.0, null, emptyList(),
            notSung = ForkLoopLyrics.notSung("作曲: Cristopher Andrés Álvarez Garcia", 0.0, "SI NO ES CONTIGO", "Cris Mj"))
        val withCredit = ForkLoopSpans.spans(listOf(credit) + shapesOf(b))
        assertEquals(ForkLoopSpans.spans(shapesOf(b)), withCredit)
        val p1 = assertNotNull(ForkV2Loop.plan(out, out.duration, inn, inn.duration, earliest, sungOf(a), sungOf(b)))
        val p2 = assertNotNull(ForkV2Loop.plan(out, out.duration, inn, inn.duration, earliest, sungOf(a), withCredit))
        assertEquals(p1.out.loopStart, p2.out.loopStart)
        assertEquals(p1.inn.loopStart, p2.inn.loopStart)
        assertEquals(12, p2.totalBars)
    }

    private fun analysisOf(song: Map<String, Any?>): TrackAnalysis {
        val a = song["analysis"] as Map<String, Any?>
        val loud = ForkV2Json.nums(song["loudness20Hz"])!!
        val vocals = (a["vocals"] as List<Map<String, Any?>>).map { w ->
            val curve = ForkV2Json.nums(w["curve"])!!
            CurveWindow(ForkV2Json.num(w, "startSec"), ForkV2Json.num(w, "fps"), FloatArray(curve.size) { curve[it].toFloat() })
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = song["id"] as String,
            duration = ForkV2Json.num(a, "durationSec"),
            bpm = ForkV2Json.num(a, "bpm"),
            beatInterval = ForkV2Json.num(a, "beatIntervalSec"),
            beatConfidence = ForkV2Json.num(a, "gridConfidence"),
            firstBeat = ForkV2Json.num(a, "firstDownbeatSec"),
            headBeats = side(a["headBeats"]),
            tailBeats = side(a["tailBeats"]),
            vocalWindows = vocals,
            levelWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(loud.size) { levelFromRms(loud[it]).toFloat() })),
        )
    }

    private fun side(o: Any?): BeatSide? {
        val m = o as? Map<String, Any?> ?: return null
        return BeatSide(
            beats = ForkV2Json.nums(m["beats"])!!.toList(),
            downbeats = ForkV2Json.nums(m["downbeats"])!!.toList(),
            bpm = ForkV2Json.num(m, "bpm"),
            confidence = ForkV2Json.num(m, "confidence"),
        )
    }

    /** The song's synced lines as the loop reads them on both apps ([ForkLoopSpans], the extension's loopSpansFromLines). */
    private fun sungOf(song: Map<String, Any?>): List<LoopMix.Span> = ForkLoopSpans.spans(shapesOf(song))

    private fun shapesOf(song: Map<String, Any?>): List<ForkLoopSpans.Shape> =
        (song["lines"] as List<Map<String, Any?>>).map { l ->
            ForkLoopSpans.Shape(
                startSec = ForkV2Json.num(l, "start"),
                endSec = (l["end"] as? Number)?.toDouble(),
                wordEndsSec = (l["wordTimes"] as? List<Any?>)?.map { ForkV2Json.nums(it)!![1] } ?: emptyList(),
            )
        }

    private fun pairFile(prefix: String): Map<String, Any?> {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec", "automix/pairs")
        val f = dir.listFiles { _, n -> n.startsWith(prefix) && n.endsWith(".json") }?.firstOrNull()
            ?: run { org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true"); fail("no $prefix pair in $dir (mount the extension's spec at /spec)") }
        return ForkV2Json.parse(f.readText()) as Map<String, Any?>
    }
}
