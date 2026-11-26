package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.math.max
import kotlin.math.min

/**
 * Best effort: the fork's [TrackAnalysis] (what TrackAnalyzer stores) as the ported planner's
 * [ForkV2Analysis], built the way the phone's Analyzer.analyzeDecoded fills one. Not wired yet.
 *
 * Field by field:
 * - fileKey ← trackId; durationSec ← duration (NaN when 0).
 * - contentEndSec ← contentEndTime (NaN when 0). TrackAnalyzer writes the duration when it found
 *   no end; the planner ignores an end within a second of the duration, so that is harmless.
 * - headBeats / tailBeats ← headBeats / tailBeats (Beat This!, the same model and the same 59 s
 *   windows as the phone). With no tail side the phone reuses the head (short songs); so does this.
 * - bpm, beatIntervalSec, firstDownbeatSec, gridConfidence ← [ForkV2BeatGrid.localGrid] of the
 *   head side, as the phone does; with no head side, the native analyser's grid: beatInterval
 *   folded into 70-180 BPM, firstBeat, and beatConfidence mapped 0.35-0.95 onto 0-1. (TrackAnalysis
 *   keeps the model's confidence in beatConfidence when the model ran; that case never reaches the
 *   fallback, since then headBeats is set.)
 * - tailGrid ← localGrid of the tail side, else the same native grid; tailGridKnown = true.
 * - introEndSec / outroStartSec ← introEndTime / outroStartTime. The phone derives these from its
 *   own 16-beat energy windows (ForkV2BeatGrid.introOutro); TrackAnalyzer's come from its DSP pass,
 *   close but not identical.
 * - energyCurve ← energyCurve's values divided by their peak, assumed evenly spaced over the song
 *   (the planner reads it as duration / size wide windows); energy ← their mean. The phone's
 *   curve is RMS over 16-beat windows; TrackAnalyzer's is its own scale and spacing, so the Auto
 *   length's "calm" test (energy share under 0.7) is approximate.
 * - camelotNum / camelotLetter ← [ForkV2BeatGrid.camelot] of key; keyName ← key; keyConfidence ←
 *   keyConfidence. CAUTION: the phone only trusts the evidence's key correlation (0.5-0.9) here and sets
 *   0 for the native analyser's key (whose "confidence" is a 0-0.1 gap). If TrackAnalyzer's
 *   keyConfidence is the native gap, Automix v2's key rules (TRUSTED_KEY 0.65) simply never fire;
 *   run [ForkKeyExtractor.detect] on the decoded mono to get the PC's value.
 * - vocals ← vocalWindows (the open-unmix output per window: startTime, frameRate, values), the
 *   same thing the phone keeps; levelWindows are not used.
 * - musicVideo ← the [musicVideo] argument (TrackAnalysis has no such field).
 *
 * Missing (left NaN / null, so the Automix v2 rules that need them stand aside, as the planner
 * does for an analysis made before them): subBassRatio, bassCurve, loudnessCurve, chroma and
 * chromaCurve. These are [ForkV2Evidence.measure] over the whole decoded mono song; without
 * them there is no sub-bass veto, no beatless bridge (it needs bass curves), no loudness trim and
 * no chroma part of the key score.
 *
 * Not carried over (the ported planner has no use for them): phraseBoundaries, mixIn/OutTime and
 * candidates, lowEnergyCurve, vocalActivityMask, vocalProbability, audibleStartTime, pickupTime.
 */
object ForkV2FromTrackAnalysis {

    fun map(t: TrackAnalysis, musicVideo: Boolean = false): ForkV2Analysis {
        val a = ForkV2Analysis()
        a.fileKey = t.trackId
        a.durationSec = if (t.duration > 0) t.duration else Double.NaN
        a.contentEndSec = if (t.contentEndTime > 0) t.contentEndTime else Double.NaN

        a.headBeats = t.headBeats?.let(::side)
        a.tailBeats = t.tailBeats?.let(::side) ?: a.headBeats

        val native = nativeGrid(t)
        val head = a.headBeats?.let { gridOf(it) } ?: native
        if (head != null) {
            a.bpm = head.bpm
            a.beatIntervalSec = head.beatIntervalSec
            a.firstDownbeatSec = head.firstDownbeatSec
            a.gridConfidence = head.gridConfidence
        }
        a.tailGrid = a.tailBeats?.let { gridOf(it) } ?: native
        a.tailGridKnown = true

        a.introEndSec = t.introEndTime
        a.outroStartSec = if (t.outroStartTime > 0) t.outroStartTime else Double.NaN
        val values = t.energyCurve.map { it.energy }
        val peak = max(1e-9, values.maxOrNull() ?: 0.0)
        a.energyCurve = DoubleArray(values.size) { values[it] / peak }
        a.energy = a.energyCurve.sum() / max(1, a.energyCurve.size)

        if (t.key.isNotBlank() && ForkV2BeatGrid.camelot(t.key, a)) {
            a.keyName = t.key
            a.keyConfidence = max(0.0, t.keyConfidence)
        }
        for (w in t.vocalWindows) {
            val v = ForkV2Analysis.VocalWindow()
            v.startSec = w.startTime
            v.fps = w.frameRate
            v.curve = w.values
            a.vocals.add(v)
        }
        a.musicVideo = musicVideo
        return ForkV2BarPhase.apply(a) // the bars on the model's downbeats (analysis-cases.md "The bar")
    }

    private fun side(s: BeatSide): ForkV2Analysis.BeatSide {
        val b = ForkV2Analysis.BeatSide()
        b.beats = s.beats.toDoubleArray()
        b.downbeats = s.downbeats.toDoubleArray()
        b.bpm = s.bpm
        b.confidence = s.confidence
        return b
    }

    /** localGrid needs beats (it reads beats[0] when there are no downbeats). */
    private fun gridOf(s: ForkV2Analysis.BeatSide): ForkV2Analysis.LocalGrid? =
        if (s.beats.isEmpty() || !(s.bpm > 0)) null else ForkV2BeatGrid.localGrid(s)

    /** The phone's nativeGrid: the DSP analyser's tempo and first beat when Beat This! found none. */
    private fun nativeGrid(t: TrackAnalysis): ForkV2Analysis.LocalGrid? {
        val interval = if (t.beatInterval > 0) t.beatInterval else if (t.bpm > 0) 60 / t.bpm else 0.0
        if (!(t.bpm > 0) || !(interval > 0)) return null
        val g = ForkV2Analysis.LocalGrid()
        g.beatIntervalSec = ForkV2BeatGrid.normalizeInterval(interval)
        g.bpm = 60 / g.beatIntervalSec
        g.firstDownbeatSec = t.firstBeat
        g.gridConfidence = max(0.0, min(1.0, (t.beatConfidence - 0.35) / 0.6))
        return g
    }
}
