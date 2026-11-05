package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRadio
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avutil.av_dict_get

/** FFmpeg's `AVFMT_EVENT_FLAG_METADATA_UPDATED` / `AVSTREAM_EVENT_FLAG_METADATA_UPDATED` (both 0x0001, avformat.h); the bytedeco presets don't map these macros. */
private const val AVFMT_EVENT_FLAG_METADATA_UPDATED = 0x0001
private const val AVSTREAM_EVENT_FLAG_METADATA_UPDATED = 0x0001

/**
 * Fork (idea from PC-Continued 5b1b855c / e16ed1f8 `DesktopRadioNowPlaying`): what an internet radio
 * station is playing right now, in the player, the top and bottom bars, the tray and the OS media
 * controls, instead of only the station's name.
 *
 * PC-Continued opens a second connection to every station on its radio page and reads the ICY
 * blocks, HLS playlists and segment tags itself. Here the record is taken off the stream the
 * decoder already plays, so a station costs no extra request at all: FFmpeg's HTTP client asks for
 * ICY metadata by default (its `icy` option is on), parses each `StreamTitle='…'` block spliced into
 * the audio, and its demuxer copies it into the format context's metadata with
 * `AVFMT_EVENT_FLAG_METADATA_UPDATED` set after the read that carried it. An Ogg (Vorbis/Opus)
 * station names each new song in a chained stream's comments, which arrive as the audio stream's
 * metadata with `AVSTREAM_EVENT_FLAG_METADATA_UPDATED`. [DesktopAudioDecoder] checks those two
 * flags after each packet (a field read) and hands the dictionary here.
 *
 * Not covered: an HLS station's `#EXTINF` titles and per-segment ID3 changes. FFmpeg's HLS demuxer
 * keeps neither (a changing ID3 tag is "missing feature" there), and reading them would take the
 * second connection this avoids; such a station shows its name, as before.
 *
 * Only Navidrome's stations ([NavidromeRadio]) are watched: the decoder is told which address is a
 * station by [ForkNavidrome.stream], the one place a station's stream is handed to the engine.
 */
internal object ForkRadioNowPlaying {

    /** The station's current record; [artist] null when the line had no "Artist - Title" shape. */
    data class Caption(val title: String, val artist: String?)

    private class Held(val caption: Caption, val owner: Any)

    /** Stream address (as the engine opens it) to the station's song id. */
    private val stations = ConcurrentHashMap<String, String>()

    private val held = MutableStateFlow<Map<String, Held>>(emptyMap())

    private val _captions = MutableStateFlow<Map<String, Caption>>(emptyMap())

    /** Station id to what it is playing; a station with nothing to say has no entry. */
    val captions: StateFlow<Map<String, Caption>> = _captions.asStateFlow()

    /** From [ForkNavidrome.stream]: [url] is [stationId]'s stream, so its decoder watches it. */
    fun expect(stationId: String, url: String) {
        stations[url] = stationId
    }

    /** The station [url] belongs to, or null for anything else (every song but a station). */
    fun stationFor(url: String): String? = stations[url]

    /**
     * After a read on [stationId]'s decoder ([owner]); [atOpen] reads the dictionaries whether or
     * not a flag is set (the first block often lands during `avformat_find_stream_info`).
     */
    fun poll(stationId: String, owner: Any, container: AVFormatContext, streamIndex: Int, atOpen: Boolean = false) {
        val flags = container.event_flags()
        val containerUpdated = (flags and AVFMT_EVENT_FLAG_METADATA_UPDATED) != 0
        if (containerUpdated) container.event_flags(flags and AVFMT_EVENT_FLAG_METADATA_UPDATED.inv())
        if (atOpen || containerUpdated) {
            val metadata = container.metadata()
            // Present but empty is an answer too: the station has nothing to say now, and its name shows.
            if (metadata != null && av_dict_get(metadata, "StreamTitle", null, 0) != null) {
                publish(stationId, owner, text(metadata, "StreamTitle")?.let(::parse))
                return
            }
        }
        if (streamIndex < 0 || streamIndex >= container.nb_streams()) return
        val stream = container.streams(streamIndex)
        val streamFlags = stream.event_flags()
        val streamUpdated = (streamFlags and AVSTREAM_EVENT_FLAG_METADATA_UPDATED) != 0
        if (streamUpdated) stream.event_flags(streamFlags and AVSTREAM_EVENT_FLAG_METADATA_UPDATED.inv())
        if (!atOpen && !streamUpdated) return
        val title = text(stream.metadata(), "title") ?: return
        publish(stationId, owner, Caption(title, text(stream.metadata(), "artist")))
    }

    /** The decoder [owner] closed: its caption goes, unless a newer decoder of the station took it over. */
    fun closed(stationId: String, owner: Any) {
        held.update { map -> if (map[stationId]?.owner === owner) map - stationId else map }
        _captions.value = held.value.mapValues { it.value.caption }
    }

    private fun publish(stationId: String, owner: Any, caption: Caption?) {
        held.update { map -> if (caption == null) map - stationId else map + (stationId to Held(caption, owner)) }
        val shown = held.value.mapValues { it.value.caption }
        if (shown != _captions.value) {
            _captions.value = shown
            caption?.let { DesktopTrackLog.log("radio: now playing '${it.artist?.let { a -> "$a - " }.orEmpty()}${it.title}'") }
        }
    }

    /** `Artist - Title` (the ICY convention), or the whole line as the title when it has no such split. */
    internal fun parse(line: String): Caption? {
        val clean = line.replace("&#39;", "'").replace("&amp;", "&").trim()
        if (clean.isEmpty() || clean == "-") return null
        val at = clean.indexOf(" - ")
        if (at <= 0 || at + 3 >= clean.length) return Caption(clean, null)
        return Caption(clean.substring(at + 3).trim(), clean.substring(0, at).trim())
    }

    /**
     * [song] as it should be drawn: a station with a caption gets the record as its title and
     * "Artist · Station" as its artist. Everything keyed on the song (lyrics, canvas, scrobbles,
     * the queue) keeps reading the station itself: only the drawn copy changes.
     */
    fun shown(song: Song?, captions: Map<String, Caption>): Song? {
        if (song == null || !NavidromeRadio.isRadio(song.videoId)) return song
        val caption = captions[song.videoId] ?: return song
        return song.copy(
            title = caption.title,
            artist = caption.artist?.let { "$it · ${song.title}" } ?: song.title,
        )
    }

    /** The same, for the OS media controls (SMTC on Windows, MPRIS on Linux). */
    fun shown(state: DesktopPlaybackState, captions: Map<String, Caption>): DesktopPlaybackState {
        val song = state.song ?: return state
        val drawn = shown(song, captions)
        return if (drawn === song) state else state.copy(song = drawn)
    }

    /** One dictionary value as text: UTF-8 when it decodes as such, Latin-1 (old Shoutcast servers) otherwise. */
    private fun text(dictionary: AVDictionary?, key: String): String? {
        if (dictionary == null) return null
        val bytes = av_dict_get(dictionary, key, null, 0)?.value()?.stringBytes ?: return null
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, StandardCharsets.ISO_8859_1) }
        return decoded.trim().trimEnd('\u0000').takeIf { it.isNotEmpty() }
    }
}
