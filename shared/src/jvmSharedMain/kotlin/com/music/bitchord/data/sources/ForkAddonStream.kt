package com.music.bitchord.data.sources

import com.music.bitchord.data.lyrics.LyricsRepository
import java.net.URI
import java.util.Locale

/**
 * The shared addon rulebook's `paramsFor` and `readStream` rules that upstream's
 * addon clients answer differently (YT Music Extension `spec/addons/protocol.md`
 * §1 and §4, `src/addons/client.js`; the cases are `pick-cases.json`). Pure,
 * and the same for the phone (`AddonClient`, `AddonSource`) and the PC
 * (`DesktopAddon.kt`), each of which calls in with one hook line.
 *
 * Also the song side of the rulebook's "ISRC first" rule ([songIsrc]), which
 * `TrackMatcher.targetOf` asks so both apps hand [ForkAddonPick.rowsToOpen] the
 * playing song's ISRC when the app has one.
 */
object ForkAddonStream {

    // ── paramsFor: the quality value for a tier below the top ─────────────

    /**
     * The `quality` value to send for [tier] (`HIGH` or `LOW`) from an addon's
     * listed [options] (value to label), or null when it listed none.
     *
     * Upstream (`AddonClient.matchTier`, the PC's copy) matched the tier's words
     * on the values only and, finding none, sent the first option. Addons list
     * their best first, so a metered "Low" request on an addon offering
     * `max / hires / high` was sent `max`: the most expensive file it has, the
     * opposite of what the setting is for. The rulebook's rule (marked "ours"
     * there): the option named as the tier, then one whose value or label has
     * the tier's words, and for LOW with no low option the high one, else the
     * last listed, which addons list as the smallest. HIGH with no match keeps
     * upstream's first option.
     */
    fun lowerQuality(tier: String, options: List<Pair<String, String>>): String? {
        if (options.isEmpty()) return null
        val low = tier.equals(TIER_LOW, ignoreCase = true)
        options.firstOrNull { it.first.uppercase(Locale.ROOT) == tier.uppercase(Locale.ROOT) }?.let { return it.first }
        fun by(re: Regex) = options.firstOrNull { (value, label) -> re.containsMatchIn(value) || re.containsMatchIn(label) }?.first
        return by(if (low) LOW_WORDS else HIGH_WORDS)
            ?: if (low) by(HIGH_WORDS) ?: options.last().first else options.first().first
    }

    // ── readStream ────────────────────────────────────────────────────────

    /**
     * The rulebook's `tierOf` (`pick.js`): `LOSSLESS`, `HIGH`, `LOW` or null for
     * a format text. Read here off a stream answer's own words (its format,
     * quality labels and codec), so `{"codec": "flac"}` alone is lossless and
     * `{"format": "aac"}` alone is HIGH. Upstream read the answer's quality
     * labels and format but never its codec field.
     */
    fun tierOf(text: String?): String? {
        val t = text.orEmpty().lowercase(Locale.ROOT)
        if (t.isBlank()) return null
        return when {
            LOSSLESS_TEXT.containsMatchIn(t) -> TIER_LOSSLESS
            LOW_TEXT.containsMatchIn(t) -> TIER_LOW
            HIGH_TEXT.containsMatchIn(t) -> TIER_HIGH
            else -> null
        }
    }

    /**
     * The kbps to assume for a stream answer that stated none.
     *
     * The rulebook reads it off the answer: a HIGH answer (`format: aac`, `MP3`)
     * is 320, a LOW one 128, a lossless one has no single figure. Upstream read
     * it off the tier it *asked* for, so an AAC answer to a lossless ask (the
     * addon walking down its fallback chain, the case the figure is there to
     * show) stayed at "unknown" and ranked as if it might be lossless. When the
     * answer names no tier at all, upstream's asked-tier figure stands.
     * Null is unknown; a figure from here is an inference, and callers flag it so.
     */
    fun inferredKbps(answerTier: String?, askedTier: String): Int? = when (answerTier) {
        TIER_LOSSLESS -> null
        TIER_HIGH -> 320
        TIER_LOW -> 128
        else -> when (askedTier.uppercase(Locale.ROOT)) {
            TIER_HIGH -> 320
            TIER_LOW -> 128
            else -> null
        }
    }

    /**
     * Whether a stream [url] from the addon at [addonBase] is refused for being
     * plain http (the rulebook's `not https`).
     *
     * An addon's stream link is fetched with the addon's own headers and, on a
     * TorBox-backed addon, carries a debrid token; over plain http anyone on the
     * path reads both and can swap the audio. The rulebook takes https only and
     * refuses before the probe, so no request is spent on it. One exception, the
     * rulebook's too: a plain-http link to this machine from an addon that is on
     * this machine itself (a self-hosted addon on `localhost`, and the tests'
     * mock servers). A remote addon may not point the app at localhost.
     * Upstream opened any URL OkHttp could parse.
     */
    fun cleartextRefused(url: String, addonBase: String): Boolean {
        val scheme = runCatching { URI(url.trim()).scheme }.getOrNull()?.lowercase(Locale.ROOT)
        if (scheme != "http") return false
        return !(isLocal(url) && isLocal(addonBase))
    }

    /**
     * HLS or DASH from a stream answer's `mimeType`, or null. Upstream's
     * `transport` reads `manifest`, `mediaType` and `format` (the first one
     * filled in) and never `mimeType`, so an extensionless playlist link
     * stated only as `application/vnd.apple.mpegurl` reached a progressive
     * extractor. Asked only when upstream's reading found no transport.
     */
    fun transportOfMime(mimeType: String?): String? {
        val m = mimeType.orEmpty().lowercase(Locale.ROOT)
        return when {
            m.isBlank() -> null
            "mpegurl" in m || HLS_WORD.containsMatchIn(m) -> HLS
            "dash+xml" in m || DASH_WORD.containsMatchIn(m) -> DASH
            else -> null
        }
    }

    // ── ISRC: the song's side ─────────────────────────────────────────────

    /**
     * The ISRC of the song with [videoId], when the app has worked one out, else null.
     *
     * YouTube Music's InnerTube answers carry no ISRC anywhere this app reads
     * (player, next, browse), so the only ISRC either app holds for a YouTube
     * song is the one [LyricsRepository] remembers from its lyrics lookup
     * (BiniLyrics' identify, which matches the song to one recording, and
     * lrc.red's documents filed by ISRC), kept in memory for the last 100
     * songs. That covers the playing song once its lyrics were looked up, so
     * the upgrade pass and a replay find an addon row by ISRC; a song that has
     * never had lyrics fetched has none, and the matcher decides as before.
     * An ISRC match still answers to the length rule, so a lyrics match to the
     * wrong edit can't open a file of another length.
     */
    fun songIsrc(videoId: String?): String? =
        videoId?.takeIf { it.isNotBlank() }?.let { ForkAddonPick.isrcOf(LyricsRepository.forkKnownIsrc(it)) }

    private fun isLocal(url: String): Boolean = LOCAL.containsMatchIn(url.trim())

    const val TIER_LOSSLESS = "LOSSLESS"
    const val TIER_HIGH = "HIGH"
    const val TIER_LOW = "LOW"
    const val HLS = "hls"
    const val DASH = "dash"

    private val LOW_WORDS = Regex("low|96|128|min", RegexOption.IGNORE_CASE)
    private val HIGH_WORDS = Regex("high|320|normal|standard", RegexOption.IGNORE_CASE)
    private val LOSSLESS_TEXT = Regex("""flac|alac|wav|hi[\s_-]?res|(16|24)[\s-]?bit|lossless""")
    private val LOW_TEXT = Regex("""\blow\b|128|96|64""")
    private val HIGH_TEXT = Regex("""320|mp3|aac|opus|high""")
    private val HLS_WORD = Regex("""\b(hls|m3u8)\b""")
    private val DASH_WORD = Regex("""\b(dash|mpd)\b""")
    private val LOCAL = Regex("""^https?://(127\.0\.0\.1|localhost)(:\d+)?(/|$)""", RegexOption.IGNORE_CASE)
}
