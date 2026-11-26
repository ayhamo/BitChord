package com.music.bitchord.data.canvas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI

private typealias Source = ForkCanvasRules.Source

/**
 * Fork: the canvas rulebook's §13 Settings (Metrolist's Artwork page), for the phone and the PC.
 *
 * What a setting does is here; where it is shown is each app's (the phone's Settings sheet, the
 * PC's settings popup). The two master switches are the ones the fork already had:
 * "Backdrop videos" is the rulebook's **Canvas** (the music video and Spotify, and the server's
 * backdrops) and "Animated covers" its **Animated covers** (Apple, then Tidal and the community
 * index, the fork's extras after Apple). Both live in `ForkCanvasSwitch` (sharedUi), which this
 * module can't see, so the apps hand their values in where the order is worked out.
 *
 * - [order] and [enabled]: the source list (rules 2-3), stored as the rulebook says
 *   ([storeOrder], [storeEnabled]) and read leniently ([parseOrder], [parseEnabled]).
 *   The music video's on/off is also the fork's older "Music video canvas" switch
 *   (`ForkCanvasSwitch.videos`); the apps keep the two the same.
 * - [coverQuality] (rule 5): which rendition of Apple's HLS master list is played ([pickVariant]).
 * - [videoQuality] and [videoMode] (rules 6-7): the height the music video's loop is cut at, and
 *   clips (the part [ForkClipJudge] picks) or the whole video, muted.
 * - [artistImages], [artistVideos], [artistVideoQuality] (rule 8): the artist page header's
 *   Apple art.
 * - [generation] (rule 10): moves on every change to any of the above, so the "failed" marks are
 *   cleared and the player asks again at once (the apps put it in their lookup keys).
 *
 * Rule 4 (off on mobile data, phone only) is upstream's own "Animated cover art on cellular"
 * (`AppSettings.canvasOverCellular`), already a gate on every lookup; rule 9 (no Spotify login for
 * canvas) is how `ForkAnonymousSpotifyCanvas` works without a cookie.
 */
object ForkCanvasSettings {

    // ---- rule 3: ids, order, sources on ------------------------------------------------------

    /** Rule 3: the rulebook's ids (Metrolist: YOUTUBE_MUSIC_VIDEO, SPOTIFY_CANVAS, APPLE_MUSIC). */
    fun id(source: Source): String = when (source) {
        Source.YOUTUBE_MUSIC_VIDEO -> "video"
        Source.SPOTIFY -> "spotify"
        Source.APPLE_MUSIC -> "apple"
    }

    /** The source an id names, or null for an unknown one. */
    fun sourceOf(id: String): Source? = Source.entries.firstOrNull { id(it) == id.trim() }

    /** What "all off" is stored as, so it never reads back as empty (all on). */
    const val NONE = "NONE"

    /**
     * Rule 3: the stored order (ids joined by commas): unknown names are dropped, a repeat counts
     * once, and the sources it leaves out follow in the default order.
     */
    fun parseOrder(stored: String?): List<Source> {
        val named = stored.orEmpty().split(',').mapNotNull(::sourceOf).distinct()
        return named + ForkCanvasRules.DEFAULT_ORDER.filter { it !in named }
    }

    /** Rule 3: the sources on: empty is all of them, [NONE] none; listed in the default order. */
    fun parseEnabled(stored: String?): List<Source> {
        val s = stored.orEmpty().trim()
        if (s.isEmpty()) return ForkCanvasRules.DEFAULT_ORDER
        if (s == NONE) return emptyList()
        val named = s.split(',').mapNotNull(::sourceOf).toSet()
        return ForkCanvasRules.DEFAULT_ORDER.filter { it in named }
    }

    /** The order as it is stored. */
    fun storeOrder(order: List<Source>): String = parseOrder(order.joinToString(",") { id(it) }).joinToString(",") { id(it) }

    /** The sources on as they are stored: all is empty, none is [NONE]. */
    fun storeEnabled(on: Collection<Source>): String = when {
        on.toSet().containsAll(Source.entries) -> ""
        on.isEmpty() -> NONE
        else -> ForkCanvasRules.DEFAULT_ORDER.filter { it in on }.joinToString(",") { id(it) }
    }

    /** Rule 2: [source] swapped with its neighbour above ([up]) or below; the ends stay. */
    fun moved(order: List<Source>, source: Source, up: Boolean): List<Source> {
        val list = order.toMutableList()
        val i = list.indexOf(source)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return order
        list[i] = list[j]
        list[j] = source
        return list
    }

    // ---- rules 5-8: the choices --------------------------------------------------------------

    /** Rules 5 and 8: an Apple rendition's cap on its shorter side (Original: none). */
    enum class CoverQuality(val cap: Int?, val label: String) {
        P360(360, "360p"), P720(720, "720p"), P1080(1080, "1080p"), ORIGINAL(null, "Original");

        companion object {
            val DEFAULT = P720
            fun of(stored: String?) = entries.firstOrNull { it.name == stored } ?: DEFAULT
        }
    }

    /** Rule 6: the height the music video is asked for (Auto: the player's own choice). */
    enum class VideoQuality(val height: Int?, val label: String) {
        AUTO(null, "Auto"), P2160(2160, "2160p"), P1440(1440, "1440p"), P1080(1080, "1080p"), P720(720, "720p"), P480(480, "480p");

        companion object {
            val DEFAULT = P480
            fun of(stored: String?) = entries.firstOrNull { it.name == stored } ?: DEFAULT
        }
    }

    /** Rule 7: the part the loop is cut from, or the whole video, muted. */
    enum class VideoMode(val label: String) {
        CLIPS("Clips"), FULL("Full video");

        companion object {
            val DEFAULT = CLIPS
            fun of(stored: String?) = entries.firstOrNull { it.name == stored } ?: DEFAULT
        }
    }

    // ---- rule 5: Apple's rendition -----------------------------------------------------------

    /** One rendition of an HLS master list: its size, its `BANDWIDTH` and its playlist's URL. */
    data class Variant(val width: Int, val height: Int, val bandwidth: Long, val uri: String = "")

    /**
     * Rule 5: the index in [variants] of the one with the most pixels (ties: the higher bandwidth)
     * whose shorter side is at most [cap] (null: no cap); when none fits, the one with the fewest
     * pixels. -1 when there are none.
     */
    fun pickVariant(variants: List<Variant>, cap: Int?): Int {
        if (variants.isEmpty()) return -1
        fun px(v: Variant) = v.width.toLong() * v.height
        val fits = variants.indices.filter { cap == null || minOf(variants[it].width, variants[it].height) <= cap }
        if (fits.isNotEmpty()) {
            return fits.maxWithOrNull(compareBy<Int>({ px(variants[it]) }, { variants[it].bandwidth }))!!
        }
        return variants.indices.minByOrNull { px(variants[it]) }!!
    }

    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val BANDWIDTH = Regex("""(?<![-A-Z])BANDWIDTH=(\d+)""")

    /**
     * The renditions of an HLS master list [text] read from [base]: each `#EXT-X-STREAM-INF` with a
     * size, its URI resolved against [base]. Only trusted `.m3u8` renditions count ([trusted]).
     */
    fun variants(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val res = RESOLUTION.find(line)
                val bw = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                val uri = lines.getOrNull(j)?.let { resolve(base, it) }
                if (res != null && uri != null && trusted(uri)) {
                    out += Variant(res.groupValues[1].toInt(), res.groupValues[2].toInt(), bw, uri)
                }
                i = j
            }
            i++
        }
        return out
    }

    private fun resolve(base: String, ref: String): String? = runCatching { URI(base).resolve(ref.trim()).toString() }.getOrNull()

    /**
     * An https `.m3u8` on Apple's own hosts (apple.com, mzstatic.com): [ForkAppleCanvasRules.trustedMotion]
     * (which also refuses user info and a port other than 443, audit 2026-11-26) plus the extension.
     */
    fun trusted(url: String): Boolean = ForkAppleCanvasRules.trustedMotion(url) &&
        runCatching { URI(url).path.orEmpty().lowercase().endsWith(".m3u8") }.getOrDefault(false)

    /**
     * Rule 5: [masterUrl]'s rendition for [cap], read with [fetch] (the master list's text, or null);
     * the master itself when it is no trusted master list or can't be read (the player then picks).
     */
    fun variantUrl(masterUrl: String, cap: Int?, fetch: (String) -> String?): String {
        if (!trusted(masterUrl)) return masterUrl
        val text = runCatching { fetch(masterUrl) }.getOrNull() ?: return masterUrl
        if (!text.contains("#EXT-X-STREAM-INF")) return masterUrl
        val all = variants(text, masterUrl)
        val pick = pickVariant(all, cap)
        return if (pick < 0) masterUrl else all[pick].uri
    }

    // ---- the state ---------------------------------------------------------------------------

    /** Where the values are kept: the phone's fork prefs, the PC's fork preferences node. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    @Volatile private var store: Store? = null

    private val _order = MutableStateFlow(ForkCanvasRules.DEFAULT_ORDER)
    private val _enabled = MutableStateFlow(Source.entries.toSet())
    private val _coverQuality = MutableStateFlow(CoverQuality.DEFAULT)
    private val _videoQuality = MutableStateFlow(VideoQuality.DEFAULT)
    private val _videoMode = MutableStateFlow(VideoMode.DEFAULT)
    private val _artistImages = MutableStateFlow(true)
    private val _artistVideos = MutableStateFlow<Boolean?>(null)
    private val _artistVideoQuality = MutableStateFlow(CoverQuality.DEFAULT)
    private val _generation = MutableStateFlow(0)

    /** Rule 2: the sources in the order they are asked. */
    val order: StateFlow<List<Source>> = _order.asStateFlow()

    /** Rule 2: the sources switched on (each keeps its place in [order] while off). */
    val enabled: StateFlow<Set<Source>> = _enabled.asStateFlow()

    val coverQuality: StateFlow<CoverQuality> = _coverQuality.asStateFlow()
    val videoQuality: StateFlow<VideoQuality> = _videoQuality.asStateFlow()
    val videoMode: StateFlow<VideoMode> = _videoMode.asStateFlow()

    /**
     * Rule 8: Apple's artist photograph (and title logo) over the artist page. The rulebook ships it
     * off; the fork keeps it on, as BitChord's phone always showed it and the PC's "Artist Art"
     * switch does by default (FORK.md).
     */
    val artistImages: StateFlow<Boolean> = _artistImages.asStateFlow()

    /** Rule 8: the artist's looping video, as set; null follows [artistImages] (see [artistVideosOn]). */
    val artistVideos: StateFlow<Boolean?> = _artistVideos.asStateFlow()

    val artistVideoQuality: StateFlow<CoverQuality> = _artistVideoQuality.asStateFlow()

    /** Rule 10: moves on every change; part of the apps' lookup keys. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Rule 8: artist videos follow artist images until set. */
    fun artistVideosOn(): Boolean = _artistVideos.value ?: _artistImages.value

    const val KEY_ORDER = "canvas_order"
    const val KEY_ENABLED = "canvas_sources_on"
    const val KEY_COVER_QUALITY = "canvas_cover_quality"
    const val KEY_VIDEO_QUALITY = "canvas_video_quality"
    const val KEY_VIDEO_MODE = "canvas_video_mode"
    const val KEY_ARTIST_IMAGES = "canvas_artist_images"
    const val KEY_ARTIST_VIDEOS = "canvas_artist_videos"
    const val KEY_ARTIST_VIDEO_QUALITY = "canvas_artist_video_quality"

    /** Every key [load] reads, for the phone's backup. */
    val KEYS = listOf(
        KEY_ORDER, KEY_ENABLED, KEY_COVER_QUALITY, KEY_VIDEO_QUALITY, KEY_VIDEO_MODE,
        KEY_ARTIST_IMAGES, KEY_ARTIST_VIDEOS, KEY_ARTIST_VIDEO_QUALITY,
    )

    /**
     * Reads the values from [from]. The music video's on/off is the app's "Music video canvas"
     * switch ([videoOn]) when the stored list doesn't say (an install from before the list).
     */
    fun load(from: Store, videoOn: Boolean) {
        store = from
        _order.value = parseOrder(from.get(KEY_ORDER))
        val stored = from.get(KEY_ENABLED)
        _enabled.value = if (stored == null) {
            Source.entries.filter { it != Source.YOUTUBE_MUSIC_VIDEO || videoOn }.toSet()
        } else parseEnabled(stored).toSet()
        _coverQuality.value = CoverQuality.of(from.get(KEY_COVER_QUALITY))
        _videoQuality.value = VideoQuality.of(from.get(KEY_VIDEO_QUALITY))
        _videoMode.value = VideoMode.of(from.get(KEY_VIDEO_MODE))
        _artistImages.value = from.get(KEY_ARTIST_IMAGES) != "off"
        _artistVideos.value = when (from.get(KEY_ARTIST_VIDEOS)) { "on" -> true; "off" -> false; else -> null }
        _artistVideoQuality.value = CoverQuality.of(from.get(KEY_ARTIST_VIDEO_QUALITY))
        _generation.update { it + 1 } // (atomic: a choice from the UI and a settings load can bump it at once; value++ lost one)
    }

    /** Whether the music video is on in the list (the apps' "Music video canvas" switch follows it). */
    fun videoOn(): Boolean = Source.YOUTUBE_MUSIC_VIDEO in _enabled.value

    fun setOrder(value: List<Source>) {
        _order.value = parseOrder(storeOrder(value))
        save(KEY_ORDER, storeOrder(_order.value))
    }

    fun move(source: Source, up: Boolean) = setOrder(moved(_order.value, source, up))

    /** Turns [source] on or off; its place in [order] stays. */
    fun setOn(source: Source, on: Boolean) {
        val now = if (on) _enabled.value + source else _enabled.value - source
        if (now == _enabled.value) return
        _enabled.value = now
        save(KEY_ENABLED, storeEnabled(now))
        onSourceSet?.invoke(source, on)
    }

    /**
     * Told each switch change: the phone keeps v1.10's "Disable Spotify Canvas" the Spotify switch's
     * other side (`ForkSettings.joinSpotifySwitch`), so upstream's own checks follow the list.
     */
    @Volatile var onSourceSet: ((Source, Boolean) -> Unit)? = null

    fun setCoverQuality(value: CoverQuality) { _coverQuality.value = value; save(KEY_COVER_QUALITY, value.name) }
    fun setVideoQuality(value: VideoQuality) { _videoQuality.value = value; save(KEY_VIDEO_QUALITY, value.name) }
    fun setVideoMode(value: VideoMode) { _videoMode.value = value; save(KEY_VIDEO_MODE, value.name) }
    fun setArtistImages(value: Boolean) { _artistImages.value = value; save(KEY_ARTIST_IMAGES, if (value) "on" else "off") }
    fun setArtistVideos(value: Boolean) { _artistVideos.value = value; save(KEY_ARTIST_VIDEOS, if (value) "on" else "off") }
    fun setArtistVideoQuality(value: CoverQuality) { _artistVideoQuality.value = value; save(KEY_ARTIST_VIDEO_QUALITY, value.name) }

    private fun save(key: String, value: String) {
        runCatching { store?.put(key, value) }
        changed()
    }

    /**
     * Rule 10: a change clears the "failed" marks (the lookups that failed within a minute,
     * [ForkCanvasLookup]) and moves [generation], so the new choice is tried at once. Also what a
     * per-song choice ([ForkCanvasChoices]) calls.
     */
    fun changed() {
        ForkCanvasLookup.clearNotes()
        _generation.update { it + 1 } // (atomic: a choice from the UI and a settings load can bump it at once; value++ lost one)
    }

    /** For the apps' in-memory lookup keys: a changed setting is a new question. */
    fun keySuffix(): String = "|cs=${_generation.value}"

    /**
     * The order the player asks in: the stored [order], with Spotify first when the app's
     * "Prioritise Spotify Canvas" is on ([spotifyFirst]), only the sources [enabled] and allowed
     * by their master switch ([canvasOn]: Canvas; [coversOn]: Animated covers). Rules 1-2.
     */
    fun effectiveOrder(canvasOn: Boolean, coversOn: Boolean, spotifyFirst: Boolean = false): List<Source> {
        val custom = if (spotifyFirst) listOf(Source.SPOTIFY) + _order.value else _order.value
        return ForkCanvasRules.order(custom = custom, enabled = _enabled.value, canvasOn = canvasOn, coversOn = coversOn)
    }

    /**
     * The order a song's canvas menu lists its sources in (phone and PC): [effectiveOrder] with the
     * player's own [spotifyFirst] ("Prioritise Spotify Canvas"), the music video left out while
     * "Music video canvas" ([videosOn]) is off. Audit 2026-11-26: the menus asked [effectiveOrder]
     * without [spotifyFirst], so with the setting on they listed and picked in another order than
     * the player played.
     */
    fun menuOrder(canvasOn: Boolean, coversOn: Boolean, videosOn: Boolean, spotifyFirst: Boolean): List<Source> =
        effectiveOrder(canvasOn, coversOn, spotifyFirst).filter { it != Source.YOUTUBE_MUSIC_VIDEO || videosOn }

    /**
     * The backdrop sources (the video, Spotify) worth asking beside a cover clip already found: only
     * those ordered before Apple, since a cover that comes first in the order is the answer.
     */
    fun backdropsBesideCover(order: List<Source>): List<Source> {
        val apple = order.indexOf(Source.APPLE_MUSIC)
        return order.filterIndexed { i, s -> s.canvas && (apple < 0 || i < apple) }
    }

    /** Rule 5: the suffix Apple's saved answer is keyed with (the quality is part of its key). */
    fun appleKeySuffix(): String = "|q=" + (_coverQuality.value.cap?.toString() ?: "orig")

    /**
     * Rules 6-7: the suffix the music video's loop is keyed with: none at the default (Clips,
     * 480p), so the loops cut before the setting existed are still found.
     */
    fun videoKeySuffix(): String {
        val q = _videoQuality.value
        val m = _videoMode.value
        if (q == VideoQuality.DEFAULT && m == VideoMode.DEFAULT) return ""
        return "|q=${q.height ?: "auto"}" + if (m == VideoMode.FULL) "|full" else ""
    }

    /** Lets a test start from the defaults. */
    internal fun resetForTest() {
        store = null
        _order.value = ForkCanvasRules.DEFAULT_ORDER
        _enabled.value = Source.entries.toSet()
        _coverQuality.value = CoverQuality.DEFAULT
        _videoQuality.value = VideoQuality.DEFAULT
        _videoMode.value = VideoMode.DEFAULT
        _artistImages.value = true
        _artistVideos.value = null
        _artistVideoQuality.value = CoverQuality.DEFAULT
    }

    // ---- display text, the same on both apps -------------------------------------------------

    const val SOURCES_TITLE = "Canvas sources"
    const val SOURCES_SUBTITLE = "The order they are tried in; the first with a clip plays. Each can be switched off and keeps its place"
    const val COVER_QUALITY_TITLE = "Animated cover quality"
    const val COVER_QUALITY_SUBTITLE = "The size of Apple Music's motion covers"
    const val VIDEO_QUALITY_TITLE = "YouTube canvas quality"
    const val VIDEO_QUALITY_SUBTITLE = "The height the music video is cut at; a change cuts the loop again"
    const val VIDEO_MODE_TITLE = "YouTube canvas mode"
    const val VIDEO_MODE_SUBTITLE = "Clips: the music video's best part, looped. Full video: the whole video, muted"
    const val ARTIST_IMAGES_TITLE = "Artist images"
    const val ARTIST_IMAGES_SUBTITLE = "Apple Music's artist photograph and logo over the artist page"
    const val ARTIST_VIDEOS_TITLE = "Artist videos"
    const val ARTIST_VIDEOS_SUBTITLE = "Apple Music's looping artist video over the artist page. Needs Animated covers"
    const val ARTIST_VIDEO_QUALITY_TITLE = "Artist video quality"
    const val ARTIST_VIDEO_QUALITY_SUBTITLE = "The size of Apple Music's artist videos"

    /** A source's name in the list. */
    fun name(source: Source): String = when (source) {
        Source.YOUTUBE_MUSIC_VIDEO -> "Music video"
        Source.SPOTIFY -> "Spotify Canvas"
        Source.APPLE_MUSIC -> "Animated cover (Apple Music, Tidal, community)"
    }

    /** What a source needs, said under its name when that master switch is off. */
    fun needs(source: Source): String = if (source.canvas) "Needs Backdrop videos" else "Needs Animated covers"
}
