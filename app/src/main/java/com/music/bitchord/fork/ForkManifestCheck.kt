package com.music.bitchord.fork

import com.music.bitchord.data.Http
import com.music.bitchord.data.sources.SourceStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fork: an addon's HLS or DASH answer read once before its row wins (protocol.md 8.1.5 rule 5, the
 * extension's refusal rules, 3dce391; the owner's go-ahead 2026-11-26).
 *
 * The phone plays an announced HLS/DASH answer through Media3's own HLS/DASH sources from its URL,
 * never through the audio cache ([ForkManifestSniff], case 1). Media3 would also play much that the
 * rulebook refuses, or fail on it only after the race was won and YouTube's audio had been dropped
 * for it. So `ForkNextRowPhone.refusesManifest` reads the playlist or manifest once ([of]) and refuses
 * the row like a bad fit (8.1.4: the next row opens, YouTube's audio meanwhile) when it is:
 *
 *  - **encrypted**: an `#EXT-X-KEY` or `#EXT-X-SESSION-KEY` whose METHOD isn't `NONE` (SAMPLE-AES
 *    included), or a DASH `ContentProtection`. Nothing here holds the key or the licence;
 *  - **live**: no `#EXT-X-ENDLIST` in the chosen media playlist, or an MPD of type `dynamic`. A song is
 *    a file with an end; a live list never ends, so the length rules and the mix have nothing to read;
 *  - **more than one init segment** (`#EXT-X-MAP`) **or `Period`**: the extension joins one init and
 *    its segments into one file, and splits nothing else; the phone follows its rule so both apps
 *    refuse the same rows;
 *  - **Dolby only** (`ac-3`, `ec-3`, `ac-4` and nothing else to choose), unless the phone's Atmos path
 *    wants and can decode them (`DeviceCodecs.atmosWanted`, §3a, the rule the rows already follow);
 *  - **unreadable**: not fetched, not a 2xx, over [MAX_BYTES], not a playlist or manifest, or a
 *    master that leads to no media playlist, a media playlist with no segment, an MPD with no audio;
 *  - **off https or to this machine**: the playlist itself, every link it holds (variants,
 *    renditions, init and segments, DASH `BaseURL`s and templates) and every redirect on the way must
 *    be `https` to a host that isn't this phone (§1 and the file download's rule): an addon may not
 *    point the player at plain http, where its token reads in the clear, or at a service on the
 *    phone itself. Redirects are followed by hand so a hop is judged before it is asked, and the
 *    lookup refuses a name that resolves to this machine ([noThisMachine]).
 *
 * The requests ([fetch]) carry the stream answer's own headers and nothing else: no cookies, no
 * WebDAV or Navidrome credential (the app's shared client adds those for their hosts, so this one is
 * a plain client on the shared pool), 30 s each. A playlist's size says nothing of the song's length,
 * so these rows get no Range probe: `SourceResolver.cutShort` already passes manifests over (§7).
 * Only the media playlist the extension would pick is read (the best audio rendition, else the best
 * variant, audio-only first, then by `BANDWIDTH`), and that is what the player is served ([ForkManifestPlay]):
 * Media3 never picks a variant of its own from the master.
 */
object ForkManifestCheck {

    /** Rulebook 8.1.5 rule 5: "30 s each". */
    const val TIMEOUT_SEC = 30L

    /** A playlist or manifest larger than this is not read (unreadable): a song's lists are kilobytes. */
    const val MAX_BYTES = 2L * 1024 * 1024

    private const val MAX_REDIRECTS = 5

    /** What one request got. */
    sealed interface Got {
        /** A 2xx body from [url], the address it was finally read from. */
        class Page(val url: String, val text: String) : Got
        /** Not fetched, not 2xx, too large. */
        data object Unreadable : Got
        /** A link or a redirect off https or to this machine. */
        data object OffLimits : Got
    }

    const val ENCRYPTED = "encrypted"
    const val LIVE = "live"
    const val INITS = "more than one init segment"
    const val PERIODS = "more than one Period"
    const val DOLBY_ONLY = "Dolby only"
    const val UNREADABLE = "unreadable"
    const val OFF_LIMITS = "a link off https or to this machine"

    /**
     * Why [stream] is refused, or null when it plays. Cancellation passes through; anything else
     * that goes wrong reading it is [UNREADABLE].
     */
    suspend fun of(stream: SourceStream): String? =
        check(stream.url, com.music.bitchord.data.sources.DeviceCodecs.atmosWanted, ForkManifestPlay::accepted) { fetch(it, stream.headers) }

    /** [of] with the fetch handed in (tests). */
    internal suspend fun check(
        url: String,
        dolbyOk: Boolean,
        accepted: (Accepted) -> Unit = {},
        fetch: suspend (String) -> Got,
    ): String? {
        val seen = Seen()
        if (!seen.allow(url)) return OFF_LIMITS
        val first = when (val got = fetch(url)) {
            is Got.Page -> got
            Got.OffLimits -> return OFF_LIMITS
            Got.Unreadable -> return UNREADABLE
        }
        val body = first.text.trimStart('﻿', ' ', '\t', '\r', '\n')
        seen.allow(first.url)
        val why = when {
            body.startsWith("#EXTM3U") -> seen.hls(first.url, body, dolbyOk, fetch)
            MPD.containsMatchIn(body.take(4096)) -> seen.dash(first.url, body, dolbyOk)
            else -> UNREADABLE
        }
        if (why == null) accepted(Accepted(url, seen.pick, seen.mpd, seen.hosts.toSet()))
        return why
    }

    /**
     * A manifest that plays ([check] found nothing to refuse), for the player's reads of it
     * ([ForkManifestPlay]): [url] as the race answered it; [pick], an HLS master's media playlist this
     * check chose (the extension's pick), which the player is then served in the master's place so
     * Media3 never chooses a variant of its own; [mpd], a DASH manifest; [hosts], every host its
     * playlists, links and segments are on.
     */
    class Accepted(val url: String, val pick: String?, val mpd: Boolean, val hosts: Set<String>)

    /** What one [check] passed: the hosts it allowed and the media playlist it chose. */
    private class Seen {
        val hosts = HashSet<String>()
        var pick: String? = null
        var mpd = false

        fun allow(url: String): Boolean = linkAllowed(url).also { ok -> if (ok) url.toHttpUrlOrNull()?.host?.let(hosts::add) }
    }

    // ── HLS ─────────────────────────────────────────────────────────────

    private class Variant(val uri: String, val bandwidth: Long, val codecs: List<String>, val audioGroup: String?)
    private class Rendition(val uri: String, val group: String, val isDefault: Boolean)

    private suspend fun Seen.hls(url: String, text: String, dolbyOk: Boolean, fetch: suspend (String) -> Got): String? {
        val lines = text.lines().map { it.trim() }
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) return media(url, lines)
        if (encrypted(lines, "#EXT-X-SESSION-KEY") || encrypted(lines, "#EXT-X-KEY")) return ENCRYPTED
        val variants = mutableListOf<Variant>()
        val renditions = mutableListOf<Rendition>()
        lines.forEachIndexed { i, line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val a = attrs(line)
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                    val ref = resolve(url, uri) ?: return OFF_LIMITS
                    variants += Variant(
                        ref,
                        a["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        a["CODECS"].orEmpty().split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() },
                        a["AUDIO"],
                    )
                }
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attrs(line)
                    if (a["TYPE"] != "AUDIO") return@forEachIndexed
                    val uri = a["URI"] ?: return@forEachIndexed // muxed into the variants
                    renditions += Rendition(resolve(url, uri) ?: return OFF_LIMITS, a["GROUP-ID"].orEmpty(), a["DEFAULT"] == "YES")
                }
            }
        }
        if ((variants.map { it.uri } + renditions.map { it.uri }).any { !allow(it) }) return OFF_LIMITS
        val usable = variants.filter { v -> audioCodecs(v.codecs).let { it.isEmpty() || dolbyOk || it.any { c -> !isDolby(c) } } }
        if (variants.isNotEmpty() && usable.isEmpty()) return DOLBY_ONLY
        val best = usable.sortedWith(compareByDescending<Variant> { v -> v.codecs.isNotEmpty() && v.codecs.none(::isVideo) }.thenByDescending { it.bandwidth })
            .firstOrNull() ?: return UNREADABLE
        val rendition = best.audioGroup?.let { group ->
            renditions.filter { it.group == group }.let { inGroup -> inGroup.firstOrNull { it.isDefault } ?: inGroup.firstOrNull() }
        }
        val mediaUrl = rendition?.uri ?: best.uri
        pick = mediaUrl
        val page = when (val got = fetch(mediaUrl)) {
            is Got.Page -> got
            Got.OffLimits -> return OFF_LIMITS
            Got.Unreadable -> return UNREADABLE
        }
        val mediaLines = page.text.trimStart('﻿').lines().map { it.trim() }
        if (mediaLines.firstOrNull { it.isNotEmpty() } != "#EXTM3U") return UNREADABLE
        if (mediaLines.any { it.startsWith("#EXT-X-STREAM-INF") }) return UNREADABLE // a master under a master
        allow(page.url)
        return media(page.url, mediaLines)
    }

    private fun Seen.media(url: String, lines: List<String>): String? {
        if (encrypted(lines, "#EXT-X-KEY")) return ENCRYPTED
        if (lines.none { it.startsWith("#EXT-X-ENDLIST") }) return LIVE
        val maps = lines.filter { it.startsWith("#EXT-X-MAP") }.map(::attrs)
        if (maps.distinct().size > 1) return INITS
        val segments = lines.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segments.isEmpty()) return UNREADABLE
        val links = maps.mapNotNull { it["URI"] } + segments
        return if (links.any { resolve(url, it)?.let(::allow) != true }) OFF_LIMITS else null
    }

    private fun encrypted(lines: List<String>, tag: String) = lines.any {
        it.startsWith("$tag:") && attrs(it)["METHOD"]?.uppercase(Locale.ROOT).let { m -> m != null && m != "NONE" }
    }

    /** An HLS tag's attribute list (`KEY=VALUE` or `KEY="VALUE"`), quotes taken off. */
    internal fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':', "")).associate { m -> m.groupValues[1] to m.groupValues[2].removeSurrounding("\"") }

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    // ── DASH ────────────────────────────────────────────────────────────

    private fun Seen.dash(url: String, text: String, dolbyOk: Boolean): String? {
        val root = parseXml(text)?.takeIf { localName(it) == "MPD" } ?: return UNREADABLE
        if (root.getAttribute("type").equals("dynamic", ignoreCase = true)) return LIVE
        if (descendants(root).any { localName(it) == "ContentProtection" }) return ENCRYPTED
        val periods = children(root).filter { localName(it) == "Period" }
        if (periods.size > 1) return PERIODS
        if (periods.isEmpty()) return UNREADABLE
        val audio = children(periods[0]).filter { localName(it) == "AdaptationSet" }.flatMap { set ->
            val reps = children(set).filter { localName(it) == "Representation" }
            val isAudio = set.getAttribute("contentType") == "audio" || set.getAttribute("mimeType").startsWith("audio") ||
                reps.any { it.getAttribute("mimeType").startsWith("audio") } ||
                children(set).any { localName(it) == "ContentComponent" && it.getAttribute("contentType") == "audio" }
            if (!isAudio) emptyList() else reps.map { it.getAttribute("codecs").ifBlank { set.getAttribute("codecs") }.lowercase(Locale.ROOT) }
        }
        if (audio.isEmpty()) return UNREADABLE
        if (!dolbyOk && audio.all { codecs -> codecs.isNotBlank() && codecs.split(',').map { it.trim() }.all(::isDolby) }) return DOLBY_ONLY
        mpd = true
        return if (dashLinksAllowed(root, url)) null else OFF_LIMITS
    }

    /** Every address the MPD holds, each against the `BaseURL` in force where it stands. */
    private fun Seen.dashLinksAllowed(element: Element, base: String): Boolean {
        val here = children(element).firstOrNull { localName(it) == "BaseURL" }?.textContent?.trim()
            ?.let { resolve(base, it) ?: return false } ?: base
        if (!allow(here)) return false
        val refs = when (localName(element)) {
            "SegmentTemplate" -> listOf("media", "initialization", "index")
            "Initialization", "RepresentationIndex" -> listOf("sourceURL")
            "SegmentURL" -> listOf("media", "index")
            else -> emptyList()
        }.map { element.getAttribute(it) }.filter { it.isNotBlank() }
        if (refs.any { ref -> resolve(here, ref)?.let(::allow) != true }) return false
        return children(element).all { localName(it) == "BaseURL" || dashLinksAllowed(it, here) }
    }

    private fun parseXml(text: String): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))).documentElement
    }.getOrNull()

    private fun localName(e: Element) = e.localName ?: e.tagName.substringAfter(':')

    private fun children(e: Element): List<Element> {
        val nodes = e.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun descendants(e: Element): Sequence<Element> = sequence {
        for (child in children(e)) {
            yield(child)
            yieldAll(descendants(child))
        }
    }

    private val MPD = Regex("""<(\w+:)?MPD[\s>]""", RegexOption.IGNORE_CASE)

    // ── Codecs ──────────────────────────────────────────────────────────

    internal fun isDolby(codec: String): Boolean = codec.lowercase(Locale.ROOT).let {
        it.startsWith("ac-3") || it.startsWith("ec-3") || it.startsWith("ec+3") || it.startsWith("ac-4")
    }

    private fun isVideo(codec: String) = VIDEO.any { codec.startsWith(it) }

    private fun audioCodecs(codecs: List<String>) = codecs.filterNot(::isVideo)

    private val VIDEO = listOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "vp08", "vp8", "vp09", "vp9", "av01", "mp4v")

    // ── Links ───────────────────────────────────────────────────────────

    /** [ref] against [base], or null when it isn't an http(s) address at all. */
    internal fun resolve(base: String, ref: String): String? = base.toHttpUrlOrNull()?.resolve(ref)?.toString()

    /** `https` to a host that isn't this phone (protocol.md §1, 8.1.5 rule 5). */
    fun linkAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && !thisMachine(parsed.host)
    }

    /**
     * A name or literal address for this machine ([com.music.bitchord.data.sources.ForkThisMachine]: all of 127/8 and
     * 0/8 in any IPv4 spelling, `127.1` and `2130706433` included, loopback, unspecified and IPv4-mapped IPv6,
     * `*.localhost`). The text check here missed the short and decimal forms, which a literal's connect never asks
     * [noThisMachine] about (the combined audit 2026-11-26, step 2).
     */
    internal fun thisMachine(host: String): Boolean = com.music.bitchord.data.sources.ForkThisMachine.isThisMachine(host)

    /** The lookup refuses a name that resolves to this machine, so a DNS answer can't route around [linkAllowed]. */
    internal val noThisMachine = Dns { host ->
        val found = Dns.SYSTEM.lookup(host)
        if (found.any { it.isLoopbackAddress || it.isAnyLocalAddress }) throw UnknownHostException("refused: resolves to this machine")
        found
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(Http.client.connectionPool)
            .dns(noThisMachine)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    private val DROPPED = setOf("user-agent", "range", "accept-encoding")

    /** One GET of [url] with [headers] only, redirects followed by hand and each judged first. */
    private suspend fun fetch(url: String, headers: Map<String, String>): Got = withContext(Dispatchers.IO) {
        try {
            var at = url
            repeat(MAX_REDIRECTS + 1) {
                if (!linkAllowed(at)) return@withContext Got.OffLimits
                val request = Request.Builder().url(at).get().apply {
                    headers.forEach { (name, value) -> if (name.lowercase(Locale.ROOT) !in DROPPED) runCatching { header(name, value) } }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        at = response.header("Location")?.let { resolve(at, it) } ?: return@withContext Got.OffLimits
                        return@use
                    }
                    if (!response.isSuccessful) return@withContext Got.Unreadable
                    val source = response.body.source()
                    if (source.request(MAX_BYTES + 1)) return@withContext Got.Unreadable
                    return@withContext Got.Page(at, source.buffer.readUtf8())
                }
            }
            Got.Unreadable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Got.Unreadable
        }
    }
}
