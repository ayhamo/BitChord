package com.music.bitchord.fork

import android.net.Uri
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.ForkAddonFloor
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: the rulebook's lossy floor and the pass after a copy plays (protocol.md 8.2.1, a6322d5) on
 * the phone, over BitChord's own race ([SourceResolver.bestAcross]) and second look
 * ([QualityUpgrade] → [SourceResolver.upgradeFor]). The rules are [ForkAddonFloor]'s, shared with
 * the PC and checked against the rulebook's cases.
 *
 *  - **The floor is part of the race** ([raceAccepts], `substituteForYouTube` and `directStream`): an
 *    addon's lossy answer under YouTube's bitrate + 96 kbps doesn't win; the song starts on YouTube
 *    (or another addon) as if that addon had nothing. YouTube's bitrate is the last one its walk
 *    picked ([seenYouTube], from the playing song each tick), 160 kbps when none was seen yet. Only
 *    addons are held to it, as the rulebook says; JioSaavn keeps BitChord's own rules.
 *  - **The pass** ([passFor], handed to `upgradeFor` by `QualityUpgrade.lookAgain`): once a copy that
 *    isn't YouTube's plays, one pass. A lossy copy: every other addon, for lossless or better (BitChord
 *    leaves out the whole addon that served it, not only its row: the race already opened that addon's
 *    best three rows). A copy adopted from the cache below the tier asked: only the addons ranked above
 *    the one it came from ([tagSource] records that in the cache entry; an entry written before this
 *    has none, and then every addon is asked), for better than it. A fresh lossless copy: no pass,
 *    whatever its depth. While YouTube's own copy plays, the second look keeps BitChord's
 *    `worthSwapping`, with 160 kbps for YouTube when its bitrate isn't known ([overYouTube]).
 *
 * The rulebook's tier for BitChord's request: `StreamRequest.Lossless` is its `LOSSLESS` (the top
 * tier, which sends the addon's quality pick), anything else asks for nothing more.
 */
object ForkAddonFloorPhone {

    private const val TAG = "BitChord"

    /** The cache entry's note of which addon wrote it (content metadata, like `AudioCache`'s own). */
    private const val META_SOURCE = "fork_addon_source"

    @Volatile
    private var lastYouTubeKbps: Int? = null

    /** Called each tick with the playing song: what YouTube's walk picked for it, when it did. */
    fun seenYouTube(videoId: String?) {
        NerdStats.pickedBitrateKbps(videoId)?.takeIf { it > 0 }?.let { lastYouTubeKbps = it }
    }

    /** YouTube's bitrate as the floor reads it: the last one seen, or null for the usual 160 kbps. */
    fun youTubeKbps(): Int? = lastYouTubeKbps

    /** `bestAcross`'s `accept` for the playback race: an addon's copy has to pass the floor. */
    fun raceAccepts(source: MusicSource, stream: SourceStream): Boolean {
        if (source.kind != SourceKind.ADDON) return true
        val why = ForkAddonFloor.refusal(stream.format, youTubeKbps()) ?: return true
        TrackLog.d(TAG, "${source.displayName}'s ${stream.format.summary} doesn't replace YouTube's copy: $why")
        return false
    }

    // ── The pass ──────────────────────────────────────────────────────

    /** What `upgradeFor` does for one song's second look. */
    sealed interface Look {
        /** YouTube's own copy plays: BitChord's `worthSwapping`, with 160 kbps for an unknown YouTube bitrate. */
        data object OverYouTube : Look

        /** No pass: the copy that plays is already what was asked for. */
        data object None : Look

        /** The rulebook's pass; [above] is the addon the copy came from, when it is known. */
        data class Pass(val pass: ForkAddonFloor.Pass, val above: String?) : Look
    }

    /** Songs whose playing copy was adopted from an addon's cache entry, with the addon that wrote it. */
    private val cachedFrom = ConcurrentHashMap<String, String>()
    private const val UNKNOWN = ""

    private fun tier(): String =
        if (SourceResolver.requestForNow() is StreamRequest.Lossless) ForkAddonFloor.TIER_BEST else ForkAddonFloor.TIER_LOWER

    /** The pass for [mediaId]'s second look, from what plays and who served it. */
    fun passFor(mediaId: String, playing: StreamFormat?, servedBy: String?): Look {
        val cached = cachedFrom.remove(mediaId)
        if (cached == null && servedBy == null) return Look.OverYouTube
        val facts = playing?.let(ForkAddonFloor::factsOf) ?: ForkAddonFloor.Facts()
        val pass = ForkAddonFloor.upgradeWanted(facts, tier(), cached = cached != null)
        if (pass == null) {
            TrackLog.d(TAG, "${playing?.summary ?: "the copy playing"} is what was asked for; no upgrade pass", about = mediaId)
            return Look.None
        }
        return Look.Pass(pass, above = if (pass.aboveOnly) cached?.ifEmpty { null } ?: servedBy else servedBy)
    }

    /**
     * The sources `upgradeFor` asks: [ranked] (in rank order) cut to the pass's. [Look.None] asks
     * nobody: [worth] refuses every answer then, and until 2026-11-26 every addon was still searched
     * and up to three `/stream` requests each were sent for nothing, out of TorBox-backed addons'
     * 5-a-minute and 60-an-hour budgets (protocol.md 8.2.1: a fresh lossless copy gets no pass).
     */
    fun <T : MusicSource> sources(look: Look?, ranked: List<T>, servedBy: String?): List<T> = when (look) {
        Look.None -> emptyList()
        is Look.Pass -> if (look.pass.aboveOnly) {
            val at = ranked.indexOfFirst { it.configId == look.above }
            if (at < 0) ranked.filterNot { it.configId == servedBy } else ranked.take(at)
        } else {
            ranked.filterNot { it.configId == (look.above ?: servedBy) }
        }
        else -> ranked.filterNot { it.configId == servedBy }
    }

    /** Whether `upgradeFor` may swap [candidate] in over [playing]. */
    fun worth(look: Look?, candidate: StreamFormat, playing: StreamFormat?, upstream: () -> Boolean): Boolean = when (look) {
        is Look.Pass -> ForkAddonFloor.passAccepts(look.pass, candidate, playing)
        Look.OverYouTube -> overYouTube(candidate, playing, upstream)
        Look.None -> false
        null -> upstream()
    }

    /** BitChord's `worthSwapping`, or the floor at 160 kbps when YouTube's bitrate isn't known. */
    private fun overYouTube(candidate: StreamFormat, playing: StreamFormat?, upstream: () -> Boolean): Boolean {
        if (playing?.kbps != null || playing?.isDolbyAtmos == true) return upstream()
        return ForkAddonFloor.passesFloor(candidate, null)
    }

    // ── A copy played from the cache ──────────────────────────────────

    /**
     * From `QualityUpgrade.adoptUnresolved`, for a song playing off the disk cache: whether a lossless
     * (or Atmos) copy still gets a look. Only an addon's copy below the tier asked does (a 16-bit file
     * when the best is asked), and only from the addons ranked above it. Also notes, for [passFor], that
     * the copy came from the cache.
     */
    fun cachedLookWanted(mediaId: String, uri: Uri, playingMime: String?): Boolean {
        // An Atmos mix (plain E-AC-3 too, as `NerdStats` reads it) is final: never a stereo FLAC over it.
        if (NerdStats.isDolbyAtmosMime(playingMime)) return false
        val from = cachedAddon(mediaId, uri) ?: return false
        val playing = cachedFormat(mediaId, playingMime, null)
        val wanted = !playing.isDolbyAtmos && ForkAddonFloor.upgradeWanted(ForkAddonFloor.factsOf(playing), tier(), cached = true) != null
        if (!wanted) cachedFrom.remove(mediaId) else cachedFrom[mediaId] = from
        return wanted
    }

    /**
     * What a song adopted from the cache is playing, with the codec, depth and rate the decoder states
     * ([NerdStats.current]) added to the bitrate `PlaybackService.cachedFloor` measured; and, for an
     * addon's copy, a note that it came from the cache for [passFor].
     */
    fun cachedPlaying(mediaId: String, uri: Uri, playingMime: String?, measured: StreamFormat?): StreamFormat? {
        cachedAddon(mediaId, uri)?.let { cachedFrom[mediaId] = it }
        return cachedFormat(mediaId, playingMime, measured)
    }

    /** The addon whose file [uri]'s cache entry holds ("" when it didn't say), or null for anything else. */
    private fun cachedAddon(mediaId: String, uri: Uri): String? {
        if (AudioCache.originOf(uri) != AudioCache.Origin.OTHER) return null
        return AudioCache.forkMetaOf(uri, META_SOURCE) ?: UNKNOWN.also {
            TrackLog.d(TAG, "the cached addon copy names no addon; asking every one for a better copy", about = mediaId)
        }
    }

    private fun cachedFormat(mediaId: String, mime: String?, measured: StreamFormat?): StreamFormat {
        val now = NerdStats.current.value?.takeIf { it.mediaId == mediaId }
        return StreamFormat(
            codec = codecOf(mime ?: now?.mimeType),
            kbps = measured?.kbps,
            sampleRateHz = now?.sampleRateHz,
            bitDepth = now?.bitDepth,
        )
    }

    internal fun codecOf(mime: String?): String? {
        val sub = mime?.substringAfter('/')?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        return when {
            "flac" in sub -> "flac"
            "alac" in sub -> "alac"
            sub == "raw" || "wav" in sub -> "wav"
            "eac3-joc" in sub -> "eac3-joc"
            "mp4a" in sub || "aac" in sub -> "aac"
            "mpeg" in sub -> "mp3"
            else -> sub
        }
    }

    /**
     * From `AudioCache.recordServed`, as an entry is first tagged: which addon's file it is, so a later
     * play from the cache knows which addons rank above it. Read the way `AudioCache.originOf` reads it:
     * an upgrade's stream, a picked row's own source, then the choice serving the song.
     */
    fun tagSource(mutations: ContentMetadataMutations, uri: Uri, mediaId: String) {
        val id = QualityUpgrade.forcedStream(uri)?.sourceConfigId
            ?: uri.getQueryParameter("s")
            ?: StreamChoice.of(mediaId)?.sourceConfigId
            ?: return
        mutations.set(META_SOURCE, id)
    }
}
