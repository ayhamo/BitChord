package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeReplayGain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Fork: loudness normalization on the PC, the phone's "Loudness normalization" (`AppSettings.loudnessNormalization`,
 * `PlaybackService.loudnessGainFor`, `LoudnessProcessor`). On by default, as on the phone. The phone's
 * "Disable on Phone Speaker" has no PC counterpart and is left out.
 *
 * Applied per deck, after the deck's equaliser and before the two decks are mixed ([ForkLoudnessStage]),
 * so each side of a crossfade, an Automix blend or a loop mix is levelled for its own song, and the
 * blend's faders and trim are untouched. The Atmos make-up ([ForkDownmixMakeup]) runs earlier, in the
 * decoder, so it is never undone here.
 *
 * Where a song's figure comes from, first found wins (the figures describe the song, not the copy
 * playing, so a better copy crossing in keeps the gain, as the phone keys it by media id):
 * 1. a Navidrome song: the server's ReplayGain ([NavidromeReplayGain], OpenSubsonic `replayGain`);
 * 2. YouTube's own figure from the player response ([StreamResolver.loudnessDbFor]), the phone's only
 *    source; known for any song whose YouTube stream was resolved, an addon's substitute included;
 * 3. the ReplayGain / R128 tags of the file being decoded ([ForkLoudnessTags]): local files, a server
 *    stream without the server's figure, an addon's file that carries them.
 * No figure: unity, as on the phone. Nothing is measured from the audio (the phone doesn't either).
 */
internal object ForkLoudness {

    const val TITLE_KEY = "loudness_normalization"
    const val TITLE = "Loudness normalization"
    const val SUBTITLE_KEY = "loudness_normalization_subtitle"
    const val SUBTITLE = "Levels every track to the same loudness"

    private const val KEY = "loudness_normalization"

    private val persistence by lazy { DesktopPersistence() }

    private val _enabled = MutableStateFlow(persistence.boolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        persistence.saveBoolean(KEY, on)
    }

    /** The linear gain for [song] decoded from [url]; 1 when off or nothing is known. Safe on the audio thread. */
    fun gainFor(song: Song, url: String?): Float {
        if (!_enabled.value) return 1f
        val db = ForkLoudnessMath.gainDb(
            serverReplayGainDb = NavidromeReplayGain.forVideoId(song.videoId),
            youtubeLoudnessDb = runCatching { StreamResolver.loudnessDbFor(song.videoId) }.getOrNull(),
            tagGainDb = url?.let(ForkLoudnessTags::gainFor),
        ) ?: return 1f
        return ForkLoudnessMath.linear(db)
    }

    /**
     * The incoming deck's level against the outgoing one's, for the loop mix: its decks go out through the
     * outgoing song's stage, so the incoming side carries the difference (the phone's `inLevel`,
     * `CrossfadeController.armLoopMix`).
     */
    fun ratio(inSong: Song, inUrl: String?, outSong: Song, outUrl: String?): Float =
        ForkLoudnessMath.ratio(gainFor(inSong, inUrl), gainFor(outSong, outUrl))
}

/** The arithmetic, apart from settings and lookups so it can be tested. */
internal object ForkLoudnessMath {

    /** The phone's bounds (`MIN_LOUDNESS_GAIN_MB` / `MAX_LOUDNESS_GAIN_MB`): -15 dB to +3 dB. */
    const val MIN_GAIN_DB = -15.0
    const val MAX_GAIN_DB = 3.0

    /**
     * ReplayGain 2.0 levels to -18 LUFS; YouTube's figure is against about -14 LUFS. Raised by the difference so
     * a server or local song lands as loud as a YouTube one in the same queue, instead of 4 dB under it.
     */
    const val REPLAYGAIN_TO_YOUTUBE_DB = 4.0

    /** R128 tags (Opus) level to -23 LUFS. */
    const val R128_TO_YOUTUBE_DB = 9.0

    /** The gain in dB, bounded; null when no source has a figure. */
    fun gainDb(serverReplayGainDb: Double?, youtubeLoudnessDb: Double?, tagGainDb: Double?): Double? {
        val raw = serverReplayGainDb?.takeIf { it.isFinite() }?.plus(REPLAYGAIN_TO_YOUTUBE_DB)
            ?: youtubeLoudnessDb?.takeIf { it.isFinite() }?.let { -it }
            ?: tagGainDb?.takeIf { it.isFinite() }
            ?: return null
        return raw.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    fun linear(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    fun ratio(inGain: Float, outGain: Float): Float =
        (inGain / outGain).takeIf { it.isFinite() && it > 0f } ?: 1f

    private val number = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    /** A ReplayGain tag ("-7.89 dB") as a gain to YouTube's reference; null when it doesn't read. */
    fun replayGainTag(value: String?): Double? {
        val match = value?.let { number.find(it) } ?: return null
        val db = match.value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return db + REPLAYGAIN_TO_YOUTUBE_DB
    }

    /** An R128 tag (a Q7.8 integer, dB * 256 to -23 LUFS) as a gain to YouTube's reference. */
    fun r128Tag(value: String?): Double? {
        val q = value?.trim()?.toIntOrNull() ?: return null
        return q / 256.0 + R128_TO_YOUTUBE_DB
    }

    /** The tags' figure: track over album, ReplayGain over R128. [tag] looks a key up, ignoring case. */
    fun fromTags(tag: (String) -> String?): Double? =
        replayGainTag(tag("REPLAYGAIN_TRACK_GAIN"))
            ?: r128Tag(tag("R128_TRACK_GAIN"))
            ?: replayGainTag(tag("REPLAYGAIN_ALBUM_GAIN"))
            ?: r128Tag(tag("R128_ALBUM_GAIN"))
}

/**
 * The ReplayGain / R128 figure of every stream the decoder opens, by URL (what the engine's track and the
 * loop mix's build both know). FLAC and MP3 carry the tags on the container, Ogg on the stream; FFmpeg's
 * lookups ignore case, so iTunes' lower-case `replaygain_track_gain` reads too.
 */
internal object ForkLoudnessTags {

    /** Generous: the analyser and the loop mix's builds open streams too, and the playing one must not drop out. */
    private const val KEEP = 512
    private val gains = ConcurrentHashMap<String, Double>()
    private val order = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun gainFor(url: String): Double? = gains[url]

    /** Called by [DesktopAudioDecoder.open] once the stream is chosen. Never throws. */
    fun read(url: String, container: AVFormatContext, stream: AVStream) {
        runCatching {
            fun lookup(dict: AVDictionary?, key: String): String? =
                dict?.let { av_dict_get(it, key, null, 0)?.value()?.string }
            val gain = ForkLoudnessMath.fromTags { key -> lookup(stream.metadata(), key) ?: lookup(container.metadata(), key) }
            remember(url, gain)
        }
    }

    fun remember(url: String, gainDb: Double?) {
        if (gainDb == null) {
            gains.remove(url)
            return
        }
        if (gains.put(url, gainDb) == null && url !in order) {
            order.add(url)
            while (order.size > KEEP) order.poll()?.let(gains::remove)
        }
    }
}

/**
 * One deck's loudness stage, a port of the phone's `LoudnessProcessor` on the PC's float blocks: the song's gain,
 * glided over 40 ms, and a peak catcher (instant attack, 80 ms release, 0.985 ceiling) for when a positive gain
 * pushes a peak past full scale. Parks, touching no sample, at unity with the limiter let go, so with the
 * setting off the chain stays bit-exact.
 *
 * Without the phone's blend trim: the PC's blend applies that itself, in its mix. And without `holdLimiter`:
 * the PC's equaliser runs its own limiter on what it shapes.
 *
 * A new song on the deck (another [key]) jumps straight to its gain, as the phone's stream boundary does. At a
 * blend's promotion the engine swaps the two stages with their equalisers, so the song keeps the stage that levelled
 * it through the blend; the idle one is [reset]. Used only on the audio thread.
 */
internal class ForkLoudnessStage {

    init {
        // The setting is read from the preferences once, when [ForkLoudness] first loads. The engine builds
        // its stages on the window's thread; without this the first load fell on the audio thread's first block.
        ForkLoudness.enabled
    }

    private var key: String? = null
    private var sampleRate = 0
    private var currentGain = 1f
    private var reduction = 1f
    private var glideCoef = 1f
    private var releaseCoef = 1f
    private var snap = true

    /** Levels [count] interleaved samples of [song], decoded from [url], where they lie. */
    fun process(samples: FloatArray, count: Int, channels: Int, rate: Int, song: Song, url: String?) {
        if (count <= 0) return
        process(samples, count, channels, rate, song.videoId, ForkLoudness.gainFor(song, url))
    }

    fun process(samples: FloatArray, count: Int, channels: Int, rate: Int, songKey: String?, targetGain: Float) {
        if (count <= 0 || channels < 1 || rate <= 0) return
        if (rate != sampleRate) {
            sampleRate = rate
            glideCoef = coefficient(GLIDE_SECONDS, rate)
            releaseCoef = coefficient(RELEASE_SECONDS, rate)
        }
        if (songKey != key) {
            key = songKey
            snap = true
        }
        if (snap) {
            currentGain = targetGain
            snap = false
        }
        // Parked: nothing to scale and nothing it could have pushed over. The limiter only has to have mostly
        // let go (the phone's PARK_REDUCTION), or a master peaking a hair over would hold it forever.
        if (targetGain == 1f && abs(currentGain - 1f) < SETTLED && reduction >= PARK_REDUCTION) {
            currentGain = 1f
            reduction = 1f
            return
        }
        var gain = currentGain
        var gr = reduction
        val frames = count / channels
        var index = 0
        for (frame in 0 until frames) {
            gain += (targetGain - gain) * glideCoef
            var peak = 0f
            for (channel in 0 until channels) {
                val magnitude = abs(samples[index + channel])
                if (magnitude > peak) peak = magnitude
            }
            peak *= gain
            val wanted = if (peak > LIMIT) LIMIT / peak else 1f
            gr = if (wanted < gr) wanted else gr + (wanted - gr) * releaseCoef
            val applied = gain * gr
            for (channel in 0 until channels) samples[index + channel] *= applied
            index += channels
        }
        currentGain = gain
        reduction = gr
    }

    /**
     * The deck now plays [songKey] without a break in the audio: a version switch's cross (ForkVersionSwitch),
     * where the crossed-in version was levelled at the old one's gain. The next block glides to the new song's
     * gain over the 40 ms instead of jumping to it, as a new song on the deck would.
     */
    fun follow(songKey: String?) {
        if (key != null) key = songKey
    }

    /** Forgets the song and the limiter, for a deck that starts its next song clean. */
    fun reset() {
        key = null
        currentGain = 1f
        reduction = 1f
        snap = true
    }

    private fun coefficient(seconds: Double, rate: Int): Float =
        if (rate <= 0) 1f else (1.0 - exp(-1.0 / (seconds * rate))).toFloat()

    companion object {
        /** The phone's: just under full scale. */
        const val LIMIT = 0.985f
        private const val GLIDE_SECONDS = 0.04
        private const val RELEASE_SECONDS = 0.08
        private const val SETTLED = 1e-4f
        private const val PARK_REDUCTION = 0.97f
    }
}

/** The Playback group's row, the phone's (its "Disable on Phone Speaker" sub-row is phone-only). */
@Composable
internal fun ForkLoudnessRow() {
    val on by ForkLoudness.enabled.collectAsState()
    SettingsToggle(
        DesktopStrings[ForkLoudness.TITLE_KEY, ForkLoudness.TITLE],
        DesktopStrings[ForkLoudness.SUBTITLE_KEY, ForkLoudness.SUBTITLE],
        on,
        ForkLoudness::set,
    )
}
