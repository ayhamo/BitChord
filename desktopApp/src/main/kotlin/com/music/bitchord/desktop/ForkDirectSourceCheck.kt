package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fork: upstream v1.9.1's length and artist checks for Direct Source Stream on the PC (94250cce,
 * "addon song match fixes": the phone's `SourceResolver.directStream` / `cutShort`,
 * `StreamLengthProbe`, `DirectSourceGate.youTubeLength` / `knownLength`,
 * `PlaybackService.checkSubstituteLength`). They stop a different song, or an addon's 30-second
 * preview, being played in place of the track.
 *
 * Why: with Direct Source Stream on ([ForkDirectSource]) an addon's copy plays from the first note
 * with no second look behind it, so the checks the upgrade path makes before a swap (the "Track
 * length tolerance", [ForkUpgradeLength]) have to be made up front:
 *  - [strict]: the addon's rows are held to the tolerance against the track's runtime (the queue
 *    row's, else YouTube's, looked up beside the search: [startLength]) and must credit an artist
 *    when the track names one. The PC's matcher already refuses a row whose artists share none with
 *    the track's and a row more than 30 s out, and `preferred` falls back to every row when none is
 *    within 2 s, which let a same-titled other song or another cut through.
 *  - [cutShort]: the file behind each opened row is measured with one ranged GET; a file too small
 *    to hold the runtime even at the lowest plausible bitrate is a preview or a cut-off upload.
 *  - [wrongLength]: once a copy is open, its decoder's runtime against the row's (or YouTube's),
 *    for every addon copy that started a song with no audition (the race's winner too, as on the
 *    phone); a wrong one sends the song back through the sources without that source, which ends on
 *    YouTube as the phone's `replaceWithYouTube` does.
 *
 * Not ported: the phone's "cached copy of unknown origin" half (bytes on disk from an earlier
 * session, refused under half the runtime); the PC's range cache keeps no copy that plays without
 * being resolved again, so every copy here has a known source.
 */
internal object ForkDirectSourceCheck {

    /** The phone's `StreamLengthProbe` floors. */
    private const val LOSSLESS_FLOOR_KBPS = 200
    private const val LOSSY_FLOOR_KBPS = 64
    private const val PROBE_TIMEOUT_MS = 4_000

    /** The phone's `DirectSourceGate.LENGTH_TIMEOUT_MS` and `LENGTHS_KEPT`. */
    private const val LENGTH_TIMEOUT_MS = 5_000L
    private const val LENGTHS_KEPT = 256

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** YouTube's runtimes for tracks queued without one, by video id. Bounded, access-ordered. */
    private val lengths = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > LENGTHS_KEPT
    }
    private val pending = HashMap<String, Deferred<Int?>>()

    private fun slackSec(): Int = ForkUpgradeLength.seconds.value

    /** The runtime the queue row carried, in seconds, or null. */
    private fun rowSeconds(song: Song): Int? = song.durationText.durationSeconds().roundToInt().takeIf { it > 0 }

    /** YouTube's runtime for [videoId], when it has been looked up. */
    fun knownLength(videoId: String): Int? = synchronized(lengths) { lengths[videoId] }

    /**
     * Starts the YouTube runtime lookup for a row with none, beside the addon search (the phone
     * starts it in `directStream` and awaits it only once a search has answered). One `next` call,
     * metadata only; a YouTube id only.
     */
    fun startLength(song: Song) {
        if (rowSeconds(song) != null || knownLength(song.videoId) != null) return
        if (!DesktopSearchClient.isVideoId(song.videoId)) return
        synchronized(lengths) {
            if (pending[song.videoId]?.isActive == true) return
            pending[song.videoId] = scope.async(start = CoroutineStart.DEFAULT) {
                val seconds = withTimeoutOrNull(LENGTH_TIMEOUT_MS) {
                    DesktopSearchClient.trackLinks(song.videoId).getOrNull()?.durationText.durationSeconds().roundToInt().takeIf { it > 0 }
                }
                synchronized(lengths) {
                    pending.remove(song.videoId)
                    if (seconds != null) lengths[song.videoId] = seconds
                }
                DesktopTrackLog.log(
                    if (seconds == null) "no YouTube runtime for '${song.title}'; matching addons on title and artist"
                    else "YouTube runtime for '${song.title}' is ${seconds}s; matching addons against it",
                )
                seconds
            }
        }
    }

    /** The track's runtime: the row's, else YouTube's (waiting for a lookup [startLength] began). */
    private suspend fun expectedSec(song: Song): Int? {
        rowSeconds(song)?.let { return it }
        knownLength(song.videoId)?.let { return it }
        val running = synchronized(lengths) { pending[song.videoId] } ?: return null
        return runCatching { running.await() }.getOrNull()
    }

    /**
     * [matches] held to Direct Source Stream's bar: within the tolerance of the track's runtime when
     * one is known (strict only then, as the phone's `strict`), and crediting an artist when the
     * track names one. The refused rows are named in the log, as the phone names them.
     */
    suspend fun strict(sourceName: String, matches: List<Song>, song: Song): List<Song> {
        if (matches.isEmpty()) return matches
        var kept = matches
        val artistNamed = song.artist.isNotBlank() && song.artist.lowercase() !in UNKNOWN_ARTISTS
        if (artistNamed) {
            // The matcher has already refused rows whose credits share no artist with the track's;
            // a row crediting nobody is what is left of the phone's `sharesArtist`.
            kept = kept.filter { it.artist.isNotBlank() && it.artist.lowercase() !in UNKNOWN_ARTISTS }
        }
        val expected = expectedSec(song)
        if (expected != null) {
            val slack = slackSec()
            val (same, other) = kept.partition { row ->
                rowSeconds(row)?.let { abs(it - expected) <= slack } ?: false
            }
            if (other.isNotEmpty()) {
                DesktopTrackLog.log(
                    "$sourceName: ${other.size} match(es) for '${song.title}' outside ±${slack}s of ${expected}s: " +
                        other.joinToString { "'${it.title}' ${it.durationText ?: "?"}" },
                )
            }
            kept = same
        }
        return kept
    }

    /**
     * Whether the file behind [stream] is too small to hold the track: an addon's preview or a
     * cut-off upload under a row claiming the full runtime. No when it can't be measured (a
     * manifest, a server that won't say), which the decoder check after opening still covers.
     */
    suspend fun cutShort(sourceName: String, match: Song, stream: DesktopStream, song: Song): Boolean {
        val expected = expectedSec(song) ?: rowSeconds(match) ?: return false
        if (isManifest(stream)) return false
        val bytes = bytesOf(stream) ?: return false
        if (!tooShort(bytes, stream.format, expected, slackSec())) return false
        DesktopTrackLog.log(
            "$sourceName served ${bytes / 1024}kB of ${stream.format.summary.ifBlank { "an unstated format" }} for " +
                "'${match.title}', too little to hold ${expected}s — a preview or a cut-off file; looking further",
        )
        return true
    }

    /** The phone's `StreamLengthProbe.tooShort`: the most audio [bytes] could hold, at the lowest plausible bitrate. */
    internal fun tooShort(bytes: Long, format: DesktopStreamFormat, expectedSec: Int, slackSec: Int): Boolean {
        if (bytes <= 0 || expectedSec <= 0) return false
        val floorKbps = if (format.isLossless) {
            LOSSLESS_FLOOR_KBPS
        } else {
            minOf(format.kbps?.takeIf { it > 0 } ?: LOSSY_FLOOR_KBPS, LOSSY_FLOOR_KBPS)
        }
        val mostSec = bytes * 8.0 / (floorKbps * 1000.0)
        return mostSec < expectedSec - slackSec
    }

    private fun isManifest(stream: DesktopStream): Boolean {
        if (stream.transport != null) return true
        val path = stream.url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    /** One ranged GET for one byte; the total from `Content-Range`, or `Content-Length` from a server ignoring the range. */
    private suspend fun bytesOf(stream: DesktopStream): Long? = withContext(Dispatchers.IO) {
        if (!stream.url.startsWith("http://") && !stream.url.startsWith("https://")) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            connection = (URI(stream.url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = true
                stream.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                setRequestProperty("Range", "bytes=0-0")
            }
            when (connection.responseCode) {
                206 -> connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                200 -> connection.contentLengthLong.takeIf { it > 0 }
                else -> null
            }?.takeIf { it > 0 }
        } catch (e: Exception) {
            null // unmeasured is not short
        } finally {
            connection?.disconnect() // the body is never read
        }
    }

    /**
     * Whether the opened copy of [song] from an addon (or another source standing in for YouTube)
     * decodes to the wrong length: outside the tolerance and a second for rounding, as the phone's
     * `checkSubstituteLength`. Only a stand-in: a row a source owns is that source's or nothing, and
     * YouTube's own, a file or a server song has nothing to be measured against.
     */
    fun wrongLength(song: Song, stream: DesktopStream, decoderDurationUs: Long?): Boolean {
        val sourceId = stream.sourceId ?: return false
        if (sourceId == "youtube" || song.isVideo) return false
        if (ForkSourcePins.of(song.videoId) == sourceId) return false // the listener picked this source for the song
        if (DesktopAddonSource.parseTrack(song.videoId) != null || DesktopModuleSource.parseTrack(song.videoId) != null ||
            song.videoId.startsWith("jiosaavn:")
        ) return false
        val decodedMs = decoderDurationUs?.takeIf { it > 0 }?.div(1_000) ?: return false
        val expectedSec = rowSeconds(song) ?: knownLength(song.videoId) ?: return false
        val slackMs = slackSec() * 1_000L + 1_000L
        if (abs(decodedMs - expectedSec * 1_000L) <= slackMs) return false
        DesktopTrackLog.log(
            "'${song.title}' decodes to ${decodedMs}ms against ${expectedSec}s listed (tolerance ±${slackSec()}s) on " +
                "${DesktopMusicSources.sourceNameFor(stream)}'s copy; looking again without it",
        )
        return true
    }

    private val UNKNOWN_ARTISTS = setOf("unknown", "unknown artist", "unknown artist(s)")
}
