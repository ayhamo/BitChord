package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.TrackFeatures
import com.music.bitchord.playback.smart.automixv2.ForkV2EvidenceBackfill
import com.music.bitchord.playback.smart.automixv2.ForkV2EvidenceStore
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.runBlocking

/**
 * Fork: the PC's evidence-only pass for a song analysed before Automix v2 ([ForkV2EvidenceBackfill]
 * explains when and why). Its one way in is [offer], from the line in `DesktopTrackAnalyzer.request`
 * that returns for a song already analysed.
 *
 * The audio is the analysis pass's own (`DesktopTrackAnalyzer.run`): the file on this machine, else a
 * Navidrome song's temporary copy or its stream, else the song's YouTube Opus; never the addon or
 * lossless copy playback may be on, so the evidence describes the recording the stored analysis was
 * measured on. It is decoded whole to mono and resampled to the DSP rate exactly as the analyser's
 * `decode` does (the buffer the evidence is measured from there), then
 * [ForkV2EvidenceStore.measure]. No beat or vocal model runs, and the stored analysis is left alone.
 *
 * Its own thread at the lowest priority, one song at a time, and not the analyser's worker: the
 * worker's queue holds only the playing and the next song and drops anything else (Desktop Revived
 * a5603aa5), and a pass queued on it would hold up the real analysis of the next song for a whole
 * decode. Here it only shares the CPU, at [Thread.MIN_PRIORITY], with a decode that is mostly I/O.
 * A party or a closed app ([stopped]) stops it between blocks; a decode cut short measures nothing.
 */
internal object ForkDesktopV2Evidence {

    /** The analyser's limit (`DesktopTrackAnalyzer.MAX_SECONDS`): a longer file is a mix, not a song. */
    private const val MAX_SECONDS = 20 * 60

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitChord-Automix-Evidence").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * From `DesktopTrackAnalyzer.request` for [song], already analysed: queues its evidence pass when
     * Automix v2 asked for it ([ForkV2EvidenceBackfill.claim], once per song). [stream] is the
     * playing copy, read only when it is a file on this machine.
     */
    fun offer(song: Song, stream: DesktopStream?, durationSeconds: Double, stopped: () -> Boolean) {
        val id = song.videoId
        if (!ForkV2EvidenceBackfill.isWanted(id) || !ForkV2EvidenceBackfill.claim(id)) return
        val local = stream?.takeIf { it.isLocalFile }
        try {
            worker.execute { run(song, local, durationSeconds, stopped) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun run(song: Song, local: DesktopStream?, durationSeconds: Double, stopped: () -> Boolean) {
        if (stopped()) return
        val id = song.videoId
        val serverCopy = if (local == null) runCatching { ForkPreload.analysisCopy(song) }.getOrNull() else null
        try {
            val source = local
                ?: serverCopy?.let { DesktopStream(it.absolutePath) }
                ?: ForkNavidrome.stream(song).takeIf { ForkNavidrome.owns(song) }
                ?: youTubeOpus(song)
                ?: return
            val rate = TrackFeatures.sampleRate
            val mono = decode(source, rate, stopped) ?: return
            // A decode stopped well short would read as a silent tail in the loudness and bass curves.
            if (durationSeconds > 0 && mono.size / rate < durationSeconds * 0.95) return
            ForkV2EvidenceStore.measure(DesktopAnalysisRuntime.analysisHome(), id, mono, rate)
            DesktopTrackLog.log("automix v2: measured the evidence of '${song.title}' (analysed before it)")
        } catch (error: Throwable) {
            DesktopTrackLog.log("automix v2: the evidence of '${song.title}' could not be measured: ${error.message}")
        } finally {
            serverCopy?.delete()
        }
    }

    private fun youTubeOpus(song: Song): DesktopStream? = runBlocking {
        val identity = DesktopSourceRegistry.youTubeIdentity(song) ?: return@runBlocking null
        DesktopStreamClient.resolve(identity).getOrNull()
    }

    /** The whole of [stream] as mono float at [rate] (the analyser's `decode`, without its waveform). */
    private fun decode(stream: DesktopStream, rate: Double, stopped: () -> Boolean): FloatArray? {
        val decoder = DesktopAudioDecoder()
        val opened = decoder.open(
            url = stream.url,
            headers = stream.headers,
            requested = DesktopPcmFormat(rate.toInt(), channels = 1, bytesPerSample = 4, isFloat = true),
            windowed = stream.windowedReads,
            transport = stream.transport,
        )
        if (opened.isFailure) {
            decoder.close()
            return null
        }
        return try {
            val sourceRate = decoder.outputFormat.sampleRate.toDouble().takeIf { it > 0 } ?: rate
            val limit = (MAX_SECONDS * sourceRate).toInt()
            val out = ArrayList<FloatArray>()
            var total = 0
            while (true) {
                if (stopped()) return null
                val block = decoder.readSamples() ?: break
                val count = decoder.sampleCount
                if (count <= 0) continue
                out += block.copyOf(count)
                total += count
                if (total > limit) break
            }
            if (total == 0) return null
            val samples = FloatArray(total)
            var at = 0
            for (block in out) {
                block.copyInto(samples, at)
                at += block.size
            }
            if (kotlin.math.abs(sourceRate - rate) < 1.0) samples else MelSpectrogram.resample(samples, sourceRate, rate)
        } finally {
            decoder.close()
        }
    }
}
