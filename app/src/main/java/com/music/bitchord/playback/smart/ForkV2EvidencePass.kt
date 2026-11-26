package com.music.bitchord.playback.smart

import android.media.MediaDataSource
import android.os.Process
import android.util.Log
import com.music.bitchord.playback.smart.automixv2.ForkV2EvidenceBackfill
import com.music.bitchord.playback.smart.automixv2.ForkV2EvidenceStore
import java.io.File

/**
 * Fork: the phone's evidence-only pass for a song analysed before Automix v2
 * ([ForkV2EvidenceBackfill] explains when and why).
 *
 * The same decode as `TrackAnalyzer.structure`'s Pass 1 and nothing after it: the whole song from
 * the copy the analyser would read, resampled chunk by chunk to the DSP rate
 * ([ForkChunkedResampler], the buffer the whole-track pass measures the evidence from), then
 * [ForkV2EvidenceStore.measure]. No beat or vocal model is loaded and the stored analysis is not
 * touched, so a song costs one decode (about a second for a 4-minute Opus on a mid-range phone; the
 * same order as Pass 1) and its grid stays the one the listener has been hearing.
 *
 * It runs on the analyser's own single thread (`TrackAnalyzer.request` queues it there, behind any
 * real analysis), at background priority whatever the performance mode: nothing waits on it, and
 * the next real pass sets its own priority again. A decode stopped short (a party started, the copy
 * has holes) measures nothing: a curve that ends early would read as a silent tail. Nothing here
 * throws: the worst case is a song that stays without evidence until the next launch.
 */
internal object ForkV2EvidencePass {

    private const val TAG = "ForkV2Evidence"

    /** As `TrackAnalyzer.MIN_DECODED_FRACTION`: less than this much of the song decoded is a broken copy. */
    private const val MIN_DECODED_FRACTION = 0.95

    /**
     * Measures [trackId]'s evidence into [base]'s store. [copy] picks the copy to read (the analyser's
     * `copyToRead`) and answers its opener, or null when none is on the device now.
     */
    fun run(
        base: File,
        trackId: String,
        durationSeconds: Double,
        stopped: () -> Boolean,
        copy: () -> (() -> MediaDataSource?)?,
    ) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            if (stopped()) return
            val open = copy() ?: return
            var duration = durationSeconds
            if (!duration.isFinite() || duration <= 0) duration = open()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
            if (duration <= 0) return
            // the queue's length can be unknown (a local file): the over-8-minutes rule is checked on the file's own
            if (com.music.bitchord.playback.smart.ForkAnalysisProgress.tooLong(trackId, duration)) return
            val rate = TrackFeatures.sampleRate
            val stream = ForkChunkedResampler(rate, duration, TrackFeatures::resample)
            open()?.use { source ->
                AudioDecoder.streamRegion(source, 0.0, duration, stopped) { chunk, _, r -> stream.push(chunk, r) }
            } ?: return
            val pcm = stream.finish().takeIf { it.decoded } ?: return
            if (stopped() || pcm.inputSeconds < duration * MIN_DECODED_FRACTION) return
            val samples = pcm.samples ?: return
            ForkV2EvidenceStore.measure(base, trackId, samples, rate)
            Log.d(TAG, "Automix v2 evidence measured for $trackId (analysed before it)")
        } catch (error: Throwable) {
            // OutOfMemoryError and codec errors included: this pass is optional by nature.
            Log.w(TAG, "Automix v2 evidence for $trackId failed", error)
        }
    }
}
