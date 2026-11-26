package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ForkWindowFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: the rulebook's window check before a better copy replaces YouTube's on the PC (YT Music
 * Extension `spec/addons/offset-map.md`, "Window check [all]"; protocol.md §8.2.3). The fit is the
 * shared [ForkWindowFit]; this decodes its two sides with the decoder that plays them
 * ([DesktopAudioDecoder], the spec's "Input": the same path in the fit as in playback):
 * 12 s of the copy from YouTube's position, and YouTube's copy around it.
 *
 * No match (or a copy whose window doesn't decode): the copy is not taken, its row is passed over for
 * the song and the next row or source is asked ([ForkNextRowPc], protocol.md 8.1.4), the song staying
 * on what plays meanwhile. A match: its offset is kept for the copy's URL, and every deck that plays that URL reads
 * it through the offset map ([onYouTubeClock]), so the swap, a seek, an Automix cue and the lyrics
 * all stay on YouTube's clock as the spec's Map asks (`addonTime = youtubeTime + offsetSec`).
 *
 * YouTube's side is the playing deck's stream when it is YouTube's, else the YouTube stream this
 * song played from before (a lossless pass after a lossy copy). A song that never played from
 * YouTube here has no YouTube audio to check against, and the copy is not taken (spec, Input).
 */
internal object ForkWindowCheckPc {

    private const val CAP = 200

    /** The copy's URL → its offset in µs (addon time − YouTube time). */
    private val offsets = lru<String, Long>()

    /** A song's id → the YouTube stream it last played from. */
    private val youtube = lru<String, DesktopStream>()

    private fun <K, V> lru() = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > CAP
    }

    /**
     * True when [better] may replace what plays for [song] at [positionMs] (YouTube's time):
     * its window fits YouTube's copy. Logs the verdict either way.
     */
    suspend fun passes(song: Song, playing: DesktopStream?, better: DesktopStream, positionMs: Long): Boolean {
        if (better.sourceId == YOUTUBE) return true // YouTube's own copy is YouTube's clock
        if (!YOUTUBE_ID.matches(song.videoId)) return true // a server's or a picked row's song has no YouTube copy to hold it to
        if (playing?.sourceId == YOUTUBE) synchronized(youtube) { youtube[song.videoId] = playing }
        val yt = synchronized(youtube) { youtube[song.videoId] }
        if (yt == null) {
            DesktopTrackLog.log("window check for '${song.title}': no YouTube audio of it to check against; staying on what plays")
            return false
        }
        // the progress row (editor.md §11 rule 6): the copy's window is its first bytes fetched, then the fit lines it up;
        // a refusal's line goes with the pass or the next row's ask
        ForkAddonCopyPc.fetching(song.videoId, better)
        val p = positionMs.coerceAtLeast(0L) / 1000.0
        val result = withContext(Dispatchers.IO) {
            val (from, to) = ForkWindowFit.youtubeSpan(p)
            val ytSide = decode(yt, from, to - from) ?: return@withContext null to "YouTube's copy did not decode"
            // read once more before it counts: a refusal passes the row over for the song (ForkAddonRefusals.copyWindow)
            val window = com.music.bitchord.data.sources.ForkAddonRefusals.copyWindow { decode(better, p, ForkWindowFit.WINDOW_SEC) } ?: return@withContext null to COPY_UNDECODABLE
            ForkAddonCopyPc.liningUp(song.videoId, better)
            ForkWindowFit.fitWindow(ytSide, window, p - from) to null
        }
        val fit = result.first
        if (fit == null) {
            DesktopTrackLog.log("window check for '${song.title}': ${result.second}; staying on what plays")
            if (result.second == COPY_UNDECODABLE) ForkNextRowPc.refused(song, better, COPY_UNDECODABLE)
            return false
        }
        DesktopTrackLog.log("window check for '${song.title}' at ${"%.1f".format(java.util.Locale.ROOT, p)} s: ${fit.describe()}")
        if (!fit.match) {
            ForkNextRowPc.refused(song, better, "no match")
            return false
        }
        noteOffset(better.url, Math.round(fit.offsetSec!! * 1_000_000))
        return true
    }

    /** Records [url]'s measured offset (µs, copy − YouTube). */
    internal fun noteOffset(url: String, offsetUs: Long) {
        synchronized(offsets) { offsets[url] = offsetUs }
    }

    /**
     * [decoder] read on YouTube's clock when [url] has a measured offset, else [decoder] itself.
     * Times before the file's start clamp to its start (the spec's silence would only pad the
     * opening of a song a copy is never swapped into).
     */
    fun onYouTubeClock(decoder: DesktopSampleSource, url: String): DesktopSampleSource {
        val offsetUs = synchronized(offsets) { offsets[url] } ?: return decoder
        if (offsetUs == 0L) return decoder
        return object : DesktopSampleSource by decoder {
            override fun seek(micros: Long): Boolean = decoder.seek((micros + offsetUs).coerceAtLeast(0L))
            override val forkLandedUs: Long? get() = decoder.forkLandedUs?.minus(offsetUs)
        }
    }

    /**
     * The audition's other check (protocol.md §8.3.1, the phone's `auditionVerdict`): a copy sold as
     * lossless whose decoder turns out lossy is not swapped in. Logged when it refuses.
     */
    fun fakeLossless(song: Song, stream: DesktopStream, measured: DesktopStreamFormat?): Boolean {
        if (!stream.format.isLossless || measured?.codec == null || measured.isLossless) return false
        DesktopTrackLog.log("not taking the copy of '${song.title}': promised ${stream.format.summary}, the decoder reads ${measured.codec}")
        return true
    }

    /** Mono at [ForkWindowFit.FIT_RATE] from [fromSec] for [seconds], decoded as the deck would. */
    private fun decode(stream: DesktopStream, fromSec: Double, seconds: Double): FloatArray? {
        val decoder = DesktopAudioDecoder()
        return try {
            decoder.open(
                url = stream.url,
                headers = stream.headers,
                requested = DesktopPcmFormat(48_000, 2, bytesPerSample = 4, isFloat = true),
                windowed = stream.windowedReads,
                transport = stream.transport,
            ).getOrElse { return null }
            val rate = decoder.outputFormat.sampleRate.takeIf { it > 0 } ?: return null
            val channels = decoder.outputFormat.channels.coerceAtLeast(1)
            if (fromSec > 0 && !decoder.seek(Math.round(fromSec * 1_000_000))) return null
            val want = (seconds * rate).toInt() + rate / 10
            val mono = FloatArray(want)
            var have = 0
            var firstSec: Double? = null
            while (have < want) {
                val block = decoder.readSamples() ?: break
                if (firstSec == null) firstSec = (decoder.forkLandedUs ?: Math.round(fromSec * 1_000_000)) / 1_000_000.0
                val m = ForkWindowFit.mono(block, decoder.sampleCount, channels)
                val n = minOf(m.size, want - have)
                m.copyInto(mono, have, 0, n)
                have += n
            }
            if (have == 0 || firstSec == null) return null
            ForkWindowFit.toFitRate(mono.copyOf(have), rate.toDouble(), firstSec, fromSec, seconds)
        } catch (e: Exception) {
            DesktopTrackLog.log("window check: decode failed: ${e.message}")
            null
        } finally {
            runCatching { decoder.close() }
        }
    }

    private const val COPY_UNDECODABLE = "the copy's window did not decode"
    private const val YOUTUBE = "youtube"
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
}
