package com.music.bitchord.desktop

import com.music.bitchord.data.download.ForkAudioSniff
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fork: where a PC download's bytes come from, and what its file is called — the phone's download
 * lookup (upstream be8c5d66, `SourceResolver.forDownload` with `Downloads.keepable`) over the PC's
 * sources.
 *
 * The PC used to resolve a download like a play that nobody was waiting for: the sources walked one
 * at a time in rank order (`DesktopSourceRegistry.resolve`), each to the end of its search, with no
 * clock on the whole, and the first stream any of them gave won. A slow addon held up every source
 * behind it and could stop the queue for minutes, and a quick JioSaavn 320 was never weighed
 * against it. The file was then named from the source's label, which is the second half of what the
 * phone commit fixed: see [ForkAudioSniff].
 *
 * Now, as on the phone, for a YouTube row:
 *  1. Every source ranked above YouTube is asked **at once** ([Leg]s, from
 *     `DesktopSourceRegistry.forkDownloadLegs`), under one [SOURCE_LOOKUP_MS] budget, and each
 *     answer is probed ([keepable]) inside its own leg, in parallel, before it may win.
 *  2. On the Lossless rung the bit-exact sources (addons, modules) are read **in rank order**: the
 *     first one whose kept answer is lossless or Dolby Atmos wins outright.
 *  3. Otherwise the best of the rest wins on [isBetter] (rank breaks a tie), if it beats YouTube's
 *     own AAC ([beatsYouTubeAac]); a lossless answer on the High rung is kept in reserve for when
 *     nothing lossy does.
 *  4. Else YouTube, whose stream is probed too, for its name only.
 *
 * Rows that belong to a source (addon, module, JioSaavn), Navidrome songs, a track pinned to a
 * source (`ForkSourcePins`) or to YouTube's original keep the PC's own walk, which carries rules the
 * phone has no counterpart of; their stream is probed for its name in the same way.
 */
internal object ForkDownloadSources {

    /**
     * One source a download may come from: [open] is its whole lookup (match, then the best row it
     * can open). [bitExact] puts it in the Lossless rung's rank-order walk.
     */
    internal class Leg(
        val id: String,
        val name: String,
        val bitExact: Boolean,
        val open: suspend () -> DesktopStream?,
    )

    /**
     * A stream with what its first bytes said. [probe] is null only on the lenient path (YouTube, a
     * source's own row), where an unreadable opening falls back to the label rather than failing a
     * download the PC always made.
     */
    internal class Kept(val stream: DesktopStream, val probe: ForkAudioSniff.Probe?) {
        val manifest: Boolean
            get() = probe is ForkAudioSniff.Probe.Manifest || (probe == null && DesktopManifestDownload.isManifest(stream))

        /**
         * The file's extension: the container the bytes are in; a manifest is assembled into FLAC
         * when its codec is FLAC, else M4A (its segments are fMP4, see [DesktopManifestDownload]);
         * [label] (the source's own word) only when nothing could be read.
         */
        fun extension(label: (DesktopStream) -> String): String = when (val found = probe) {
            is ForkAudioSniff.Probe.File -> found.container.extension
            else -> label(stream).let { if (manifest && it != "flac") "m4a" else it }
        }
    }

    /**
     * The stream to download [song] from, at the download [quality] rung (`download_quality`:
     * STANDARD, HIGH or LOSSLESS). Throws as `DesktopSourceRegistry.resolve` does when nothing can
     * serve it.
     */
    suspend fun resolve(song: Song, quality: String): Kept {
        val wantsLossless = when (quality.uppercase()) {
            "LOSSLESS" -> true
            "HIGH" -> false
            // Standard (and anything older): YouTube's ladder is the only one that can be capped,
            // and a source's best answer is the bigger file the setting exists to avoid. The phone's
            // rule (`forDownload`'s `Capped`).
            else -> null
        }
        val ownWalk = !DesktopSourceRegistry.hasYouTubeOriginal(song) ||
            ForkNavidrome.owns(song) ||
            ForkSourcePins.of(song.videoId) != null ||
            DesktopOriginalVersion.isPinned(song.videoId)
        if (ownWalk) {
            val stream = DesktopMusicSources.resolve(song, quality, forDownload = true).getOrThrow()
            return lenient(stream)
        }
        if (wantsLossless != null && song.title.isNotBlank()) {
            val durationSec = song.durationText?.let(::forkDurationSeconds)
            pick(
                legs = DesktopSourceRegistry.forkDownloadLegs(song, quality),
                wantsLossless = wantsLossless,
                budgetMs = SOURCE_LOOKUP_MS,
                title = song.title,
                keep = { keepable(it, durationSec) },
            )?.let { return it }
        }
        return lenient(DesktopSourceRegistry.forkDownloadYouTube(song, quality).getOrThrow())
    }

    /**
     * The phone's `SourceResolver.forDownload` decision over [legs] (in rank order), or null for
     * YouTube. Every leg starts at once; whatever has answered when [budgetMs] runs out is decided
     * on, rather than lost with the slow ones (the phone's measured case: a JioSaavn 320 found in
     * the first second went to YouTube's Opus because an addon was still thinking).
     *
     * The legs run in this object's own scope and are cancelled, not joined, when the decision is
     * made: a source blocked in a call that ignores cancellation would otherwise hold the download
     * past its budget ([kotlinx.coroutines.coroutineScope] waits for every child).
     */
    internal suspend fun pick(
        legs: List<Leg>,
        wantsLossless: Boolean,
        budgetMs: Long,
        title: String,
        keep: suspend (DesktopStream) -> Kept?,
    ): Kept? {
        if (legs.isEmpty()) return null
        val until = System.nanoTime() + budgetMs * 1_000_000
        val bitExact = if (wantsLossless) legs.filter { it.bitExact } else emptyList()
        val runs: List<Pair<Leg, Deferred<Kept?>>> = legs.map { leg ->
            leg to scope.async {
                try {
                    leg.open()?.let { keep(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    DesktopTrackLog.log("download: ${leg.name} failed looking for '$title': ${failure.message}")
                    null
                }
            }
        }
        val rank = legs.withIndex().associate { (index, leg) -> leg.id to index }
        try {
            suspend fun answer(leg: Leg, run: Deferred<Kept?>): Kept? =
                if (run.isCompleted) {
                    run.await()
                } else {
                    withTimeoutOrNull(remainingMs(until)) { run.await() }.also {
                        if (it == null && !run.isCompleted) DesktopTrackLog.log("download: ${leg.name} ran out of lookup time")
                    }
                }

            // The best lossy copy seen so far and who offered it, so the log can say where the file came from.
            var best: Pair<Leg, Kept>? = null
            // Every bit-exact source was asked at once above, and is read here in rank order: a
            // quicker FLAC from a lower-ranked addon must not beat the one the listener put first.
            for ((leg, run) in runs.filter { (leg, _) -> leg in bitExact }) {
                val kept = answer(leg, run) ?: continue
                if (kept.stream.format.isLossless || ForkAtmos.isAtmos(kept.stream)) {
                    DesktopTrackLog.log("download: '$title' from ${leg.name} at ${kept.stream.format.summary}")
                    return kept
                }
                DesktopTrackLog.log("download: ${leg.name} offered ${kept.stream.format.summary.ifBlank { "an unstated format" }}; not lossless or Dolby")
                if (isBetter(kept.stream, best?.second?.stream)) best = leg to kept
            }

            // On the High rung a bit-exact answer is held back rather than taken: someone who picked
            // High picked the smaller file, so any lossy copy that beats YouTube's AAC still wins, but
            // a FLAC from their own addon is a better answer than YouTube's Opus when nothing lossy is.
            var losslessOverBudget: Pair<Leg, Kept>? = null
            for ((leg, run) in runs.filter { (leg, _) -> leg !in bitExact }) {
                val kept = answer(leg, run) ?: continue
                if (!wantsLossless && kept.stream.format.isLossless) {
                    DesktopTrackLog.log("download: ${leg.name} offered ${kept.stream.format.summary}; more than asked for, kept in reserve")
                    if (losslessOverBudget == null) losslessOverBudget = leg to kept
                    continue
                }
                val held = best
                // Two answers of equal quality go to the higher-ranked source, whenever each arrived.
                val wins = held == null || isBetter(kept.stream, held.second.stream) ||
                    (!isBetter(held.second.stream, kept.stream) && rank.getValue(leg.id) < rank.getValue(held.first.id))
                if (wins) best = leg to kept
            }

            val lossy = best?.takeIf { beatsYouTubeAac(it.second.stream.format) }
            if (lossy == null) {
                losslessOverBudget?.let { (leg, kept) ->
                    DesktopTrackLog.log("download: nothing lossy for '$title' beats YouTube's AAC; keeping ${leg.name}'s ${kept.stream.format.summary}")
                    return kept
                }
                best?.let { (leg, kept) ->
                    DesktopTrackLog.log(
                        "download: the best offered for '$title' is ${leg.name}'s " +
                            "${kept.stream.format.summary.ifBlank { "an unstated format" }}; taking YouTube's over that",
                    )
                }
                return null
            }
            DesktopTrackLog.log("download: '$title' from ${lossy.first.name} at ${lossy.second.stream.format.summary}, over YouTube's")
            return lossy.second
        } finally {
            runs.forEach { (_, run) -> run.cancel() }
        }
    }

    /**
     * [stream] as something a download can keep, described by its bytes, or null when it can't be
     * kept (the phone's `Downloads.keepable`). Asked of every candidate before it may win: a winner
     * thrown away afterwards would send the download to YouTube past a runner-up that could have
     * been kept.
     *
     * A file's codec comes from its container where that settles it (an addon "FLAC" whose bytes
     * are an MP3 competes as the MP3 it is), and a bitrate the source left unstated is measured from
     * the file's length over the track's, so it can be weighed against YouTube's.
     */
    suspend fun keepable(stream: DesktopStream, durationSec: Int?): Kept? {
        val probe = probe(stream)
        if (probe == null) {
            DesktopTrackLog.log("download: ${stream.format.summary.ifBlank { "a stream" }} is not a file a download can keep; passing")
            return null
        }
        return described(stream, probe, durationSec)
    }

    /** [stream] with what [probe] found written into it; see [keepable]. */
    internal fun described(stream: DesktopStream, probe: ForkAudioSniff.Probe, durationSec: Int?): Kept = when (probe) {
        // DesktopManifestDownload tells HLS from DASH by the declared transport or the URL's
        // extension, and an extensionless index has neither: say which one the bytes are.
        is ForkAudioSniff.Probe.Manifest ->
            Kept(stream.copy(transport = if (probe.dash) DesktopAddonStream.DASH else DesktopAddonStream.HLS), probe)
        is ForkAudioSniff.Probe.File -> {
            val container = probe.container
            val format = stream.format.copy(codec = container.codec ?: stream.format.codec ?: container.extension)
            val measured = ForkAudioSniff.estimatedKbps(probe.totalBytes, stream.durationSec ?: durationSec)
                ?.takeIf { format.kbps == null && !format.isLossless }
            Kept(stream.copy(format = if (measured != null) format.copy(kbps = measured) else format), probe)
        }
    }

    /** The stream the PC's own walk chose, probed for its name; an unreadable opening keeps the label. */
    private suspend fun lenient(stream: DesktopStream): Kept =
        probe(stream)?.let { described(stream, it, stream.durationSec) } ?: Kept(stream, null)

    /**
     * The opening [ForkAudioSniff.PROBE_BYTES] of [stream], judged; null when they couldn't be read
     * or are no audio. Awaited rather than executed: it runs inside a lookup with a deadline, and a
     * blocking call would hold that open on a server that stalled.
     */
    private suspend fun probe(stream: DesktopStream): ForkAudioSniff.Probe? = try {
        val request: Request = DesktopDownloadHttp.request(stream.url, PROBE_USER_AGENT, stream.headers).newBuilder()
            .header("Range", "bytes=0-${ForkAudioSniff.PROBE_BYTES - 1}")
            .build()
        DesktopDownloadHttp.client.newCall(request).awaitResponse().use { response ->
            if (response.code !in 200..299) {
                DesktopTrackLog.log("download: probe refused (HTTP ${response.code})")
                null
            } else {
                val total = ForkAudioSniff.totalBytes(response.code, response.header("Content-Range"), response.header("Content-Length"))
                val source = response.body.source()
                source.request(ForkAudioSniff.PROBE_BYTES.toLong())
                val head = source.buffer.readByteArray(minOf(source.buffer.size, ForkAudioSniff.PROBE_BYTES.toLong()))
                ForkAudioSniff.sniff(head, response.header("Content-Type").orEmpty(), stream.format.codec, total)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        DesktopTrackLog.log("download: could not probe the source file: ${failure.message}")
        null
    }

    /** [Call.execute], but given up on (and the call cancelled) when the coroutine is. */
    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    /**
     * Whether [candidate] is a better rendition than [current]: immersive first, then lossless, then
     * between two lossless copies depth and then rate, else bitrate. The phone's
     * `SourceResolver.isBetter`; a null [current] is beaten by anything.
     */
    internal fun isBetter(candidate: DesktopStream, current: DesktopStream?): Boolean {
        if (current == null) return true
        val atmos = ForkAtmos.isAtmos(candidate)
        if (atmos != ForkAtmos.isAtmos(current)) return atmos
        val a = candidate.format
        val b = current.format
        if (a.isLossless != b.isLossless) return a.isLossless
        if (a.isLossless) {
            val depth = (a.bitDepth ?: 0).compareTo(b.bitDepth ?: 0)
            if (depth != 0) return depth > 0
            val rate = (a.sampleRateHz ?: 0).compareTo(b.sampleRateHz ?: 0)
            if (rate != 0) return rate > 0
        }
        return (a.kbps ?: 0) > (b.kbps ?: 0)
    }

    /**
     * Whether a lossy copy is worth keeping over YouTube's own AAC: above the top of YouTube's
     * ladder, so a 320 wins and a module's 128 does not. An unstated bitrate is a no; a download has
     * to name the file first, and an undescribed rendition is nothing to weigh (the phone's floor).
     */
    internal fun beatsYouTubeAac(format: DesktopStreamFormat): Boolean = (format.kbps ?: 0) > YOUTUBE_BEST_AAC_KBPS

    private fun remainingMs(until: Long): Long = ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The phone's `Downloads.SOURCE_LOOKUP_MS`: a module search waits on every backend it has. */
    internal const val SOURCE_LOOKUP_MS = 85_000L

    /** The phone's `SourceResolver.YOUTUBE_BEST_AAC_KBPS`. */
    private const val YOUTUBE_BEST_AAC_KBPS = 256

    private const val PROBE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
}
