package com.music.bitchord.playback

import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.ForkWindowFit
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Fork: the rulebook's window check before a quality upgrade replaces what plays (YT Music Extension
 * `spec/addons/offset-map.md`, "Window check [all]"; protocol.md §8.2.3 and §8.3.1). Upstream's
 * audition (`PlaybackService.auditionUpgrade`) buffers the copy 12 s past the playhead and swaps it
 * in at the same position. Same position is wrong twice over (the phone measured, 9 songs): Poker
 * Face's lossless copy is 117.2 ms off YouTube's, a jump at the swap, and 2 of the 9 copies were
 * other recordings or edits the length rule let through.
 *
 * So, after the audition and before the cut: 12 s of the copy from YouTube's position, decoded from
 * what the audition cached, is fitted ([ForkWindowFit]) against YouTube's Opus of the song, which
 * the player has been caching as it plays. No match: no swap, the song stays on what plays. A
 * match: the swap lands at the position plus the offset ([swapAtMs]), and the cover lines the copies
 * up by it ([landShiftMs]).
 *
 * Both sides go through [AudioDecoder] (the platform MediaCodec, which the player's renderers use
 * too), so a decoder delay is treated alike on both. The copy's bytes are read through the cache
 * with the network behind it: the extractor's seek can probe a byte the audition skipped.
 *
 * What it does not do (FORK.md): an HLS/DASH copy has no single file to decode a window from and is
 * swapped as before; after the swap the player's clock is the copy's (the offset is applied at the
 * swap, not mapped for every later read as on the PC).
 */
@OptIn(UnstableApi::class)
internal object ForkWindowCheckPhone {

    private const val CAP = 200

    /** An item URI → the offset (ms, copy − YouTube) of the copy it plays; YouTube's own are 0. */
    private val offsets = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > CAP
    }

    private fun offsetOf(uri: String): Long? = synchronized(offsets) { offsets[uri] }

    /**
     * True when the copy [upgradedUri] (stream [stream]) may replace what [playingUri] plays: its
     * window at [positionMs] (the audition's start, on the playing copy's clock) fits YouTube's copy.
     */
    suspend fun passes(mediaId: String, playingUri: String, positionMs: Long, upgradedUri: String, stream: SourceStream): Boolean {
        val playing = Uri.parse(playingUri)
        val videoId = playing.getQueryParameter("v")
        val current = offsetOf(playingUri) ?: 0L
        if (videoId == null) return true // a picked row or a server's song: there is no YouTube copy to hold it to
        if (StreamContainer.manifestMimeOf(stream.url) != null) {
            TrackLog.d("BitChord", "window check skipped: an HLS/DASH copy has no file to read a window from", about = mediaId)
            synchronized(offsets) { offsets[upgradedUri] = current }
            return true
        }
        val p = (positionMs - current).coerceAtLeast(0L) / 1000.0
        val (from, to) = ForkWindowFit.youtubeSpan(p)
        val yt = withContext(Dispatchers.IO) { youtubeSide(playing, videoId, from, to - from) }
        if (yt == null) {
            TrackLog.d("BitChord", "window check: no YouTube audio of the song on disk to check against; staying", about = mediaId)
            return false
        }
        val window = withContext(Dispatchers.IO) { copySide(Uri.parse(upgradedUri), p) }
        if (window == null) {
            TrackLog.d("BitChord", "window check: the copy's window did not decode; staying", about = mediaId)
            return false
        }
        val fit = withContext(Dispatchers.Default) { ForkWindowFit.fitWindow(yt, window, p - from) }
        TrackLog.d("BitChord", "window check at ${"%.1f".format(java.util.Locale.ROOT, p)}s: ${fit.describe()}", about = mediaId)
        if (!fit.match) return false
        synchronized(offsets) { offsets[upgradedUri] = fit.offsetMs!! }
        return true
    }

    /** Where the copy [upgradedUri] goes in for [positionMs] on [playingUri]'s clock. */
    fun swapAtMs(playingUri: String, upgradedUri: String, positionMs: Long): Long =
        (positionMs + landShiftMs(playingUri, upgradedUri)).coerceAtLeast(0L)

    /** The new copy's time less the playing copy's at the same moment of the song. */
    fun landShiftMs(playingUri: String, upgradedUri: String): Long {
        val current = offsetOf(playingUri) ?: 0L
        return (offsetOf(upgradedUri) ?: current) - current
    }

    /** YouTube's Opus from [fromSec] for [seconds], from whichever canonical rendition is on disk. */
    private fun youtubeSide(uri: Uri, videoId: String, fromSec: Double, seconds: Double): FloatArray? {
        val renditions = AudioCache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(videoId, it.key) && it.cachedPrefix > 0 }
            .sortedByDescending { it.cachedPrefix }
        for (rendition in renditions) {
            val decoded = AudioCache.renditionDataSource(uri, rendition).use { AudioDecoder.decodeRegion(it, fromSec, fromSec + seconds) }
                ?: continue
            val (pcm, firstSec) = decoded
            if (pcm.samples.isEmpty()) continue
            return ForkWindowFit.toFitRate(pcm.samples, pcm.sampleRate, firstSec, fromSec, seconds)
        }
        return null
    }

    /** The copy's 12 s from [fromSec]; cached bytes from disk, any the extractor probes besides from the network. */
    private fun copySide(uri: Uri, fromSec: Double): FloatArray? {
        val source = AudioCache.forkReadAhead() ?: return null
        val decoded = DataSourceReader(source, uri).use {
            AudioDecoder.decodeRegion(it, fromSec, fromSec + ForkWindowFit.WINDOW_SEC)
        } ?: return null
        val (pcm, firstSec) = decoded
        if (pcm.samples.isEmpty()) return null
        return ForkWindowFit.toFitRate(pcm.samples, pcm.sampleRate, firstSec, fromSec, ForkWindowFit.WINDOW_SEC)
    }

    /** A Media3 [DataSource] as the [MediaDataSource] MediaExtractor reads; kept open across sequential reads. */
    private class DataSourceReader(private val source: DataSource, private val uri: Uri) : MediaDataSource() {
        private var openAt = -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            try {
                if (openAt != position) {
                    shut()
                    if (source.open(DataSpec.Builder().setUri(uri).setPosition(position).build()) == 0L) return -1
                    openAt = position
                }
                val n = source.read(buffer, offset, size)
                if (n == C.RESULT_END_OF_INPUT) { shut(); return -1 }
                openAt += n
                return n
            } catch (e: IOException) {
                shut()
                return -1
            }
        }

        override fun getSize(): Long {
            shut()
            return try { source.open(DataSpec(uri)).let { if (it == C.LENGTH_UNSET.toLong()) -1L else it } } catch (e: IOException) { -1L } finally { shut() }
        }

        override fun close() = shut()

        private fun shut() {
            runCatching { source.close() }
            openAt = -1L
        }
    }
}
