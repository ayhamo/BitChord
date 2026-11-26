package com.music.bitchord.desktop

import com.music.bitchord.data.sources.ForkThisMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

/**
 * Fork: an addon row announced as HLS or DASH, put together into one file for the PC's decks (YT
 * Music Extension `spec/addons/protocol.md` 8.1.5 rule 5 and rule 6 case 1, the extension's
 * `src/addons/stream-assemble.js`, 3dce391; owner, 2026-11-26: "let all do it then, as addons can be
 * unexpected").
 *
 * The extension's PC hands the page the stream's bytes like a progressive file's, so the fit and the
 * decks are the same; here the file goes to disk and every reader of the copy opens it in the stream's
 * place ([ForkAssembledStreams], one line in [DesktopAudioDecoder.open]): the engine's decks, the
 * analyser, the window check, the loop mix and the swap read the same file, as they read a progressive
 * one. Never cached (rule 6.1): each resolve fetches it again from its URL, and the file is dropped
 * once newer ones replace it.
 *
 * - HLS: from a master playlist the best audio rendition, else the best variant (audio-only first,
 *   then `BANDWIDTH`), then its media playlist; `#EXT-X-BYTERANGE` read. fMP4 segments (`#EXT-X-MAP`):
 *   the init and the segments joined. MPEG-TS segments: the audio stream taken out, ADTS AAC or MPEG
 *   audio, else refused. Packed ADTS or MP3 segments: joined without their ID3 tags.
 * - DASH: the best audio Representation by `bandwidth`; `SegmentTemplate` (`$Number$`, `$Time$`,
 *   `$RepresentationID$`, `$Bandwidth$`, with or without `SegmentTimeline`), `SegmentList`, or one
 *   file (`SegmentBase`, or a `BaseURL` alone); the init and the segments joined.
 * - Dolby (`ac-3`, `ec-3`, `ac-4`) renditions are passed over (the extension's browser can't decode
 *   them, and the rule is [all]).
 * - Refused like a bad fit (8.1.4), so the next row opens: encrypted, live, more than one init
 *   segment or `Period`, Dolby only, segments it can't read, a link or redirect off https or to this
 *   machine, and anything over [Limits].
 *
 * The downloader's own manifest path ([DesktopManifestDownload]) is left as it is; its MP4 box walk is
 * reused for the whole-file index.
 */
internal object ForkAddonStreamAssemble {

    /** Why a stream is not put together; refused like a bad fit (8.1.4). */
    class Refused(why: String) : Exception(why)

    /** Rule 5's limits: 30 s per request, 4 at a time, 300 MB over all segments, 120 s in all. */
    class Limits(
        val maxBytes: Long = 300L * 1024 * 1024,
        val totalMs: Long = 120_000,
        val requestMs: Long = 30_000,
        val parallel: Int = 4,
    )

    /** One request: [url], or [range] of it (inclusive), at most [limit] bytes kept. Throws [Refused] for what can't be read. */
    fun interface Fetch {
        suspend fun get(url: String, range: LongRange?, limit: Long): ByteArray
    }

    /** One piece of the stream: [url], or [range] of it. */
    data class Ref(val url: String, val range: LongRange? = null)

    /** How the pieces join: an MP4 init and fragments (or one whole file), TS to demux, or packed audio. */
    enum class Kind { MP4, TS, PACKED, WHOLE }

    /** What to fetch, in order. */
    class Plan(val init: Ref?, val media: List<Ref>, val kind: Kind?, val extension: String)

    // ---------------------------------------------------------------------------------------------
    // Putting it together

    /**
     * [url]'s stream put together into [out]; its extension. [transport] is `hls` or `dash`. Throws
     * [Refused]; [out] is deleted then. All within [Limits.totalMs].
     */
    suspend fun assemble(
        url: String,
        transport: String,
        out: File,
        fetch: Fetch,
        limits: Limits = Limits(),
    ): String {
        var ok = false
        try {
            val extension = withTimeoutOrNull(limits.totalMs) { together(url, transport, out, fetch, limits) }
                ?: throw Refused("not put together within ${limits.totalMs / 1000} s")
            ok = true
            return extension
        } finally {
            if (!ok) out.delete()
        }
    }

    private suspend fun together(url: String, transport: String, out: File, fetch: Fetch, limits: Limits): String {
        if (!linkAllowed(url)) throw Refused("a link off https or to this machine")
        val budget = Budget(limits.maxBytes)
        fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val plan = if (transport == DesktopAddonStream.DASH) {
            dashPlan(text(budget.take(fetch.get(url, null, budget.left))), url)
        } else {
            var playlistUrl = url
            var playlist = text(budget.take(fetch.get(url, null, budget.left)))
            if (isMaster(playlist)) {
                playlistUrl = resolve(url, pickHls(playlist))
                if (!linkAllowed(playlistUrl)) throw Refused("a link off https or to this machine")
                playlist = text(budget.take(fetch.get(playlistUrl, null, budget.left)))
                if (isMaster(playlist)) throw Refused("a master playlist pointing at another")
            }
            hlsPlan(playlist, playlistUrl)
        }
        (listOfNotNull(plan.init) + plan.media).forEach { if (!linkAllowed(it.url)) throw Refused("a link off https or to this machine") }
        out.parentFile?.mkdirs()
        var joined: String? = null
        out.outputStream().buffered().use { sink ->
            val joiner = Joiner(plan.kind, sink)
            plan.init?.let { joiner.init(budget.take(fetch.get(it.url, it.range, budget.left))) }
            // In order, [Limits.parallel] at a time; at most that many segments held at once.
            for (chunk in plan.media.chunked(limits.parallel.coerceAtLeast(1))) {
                val bytes = coroutineScope { chunk.map { ref -> async { fetch.get(ref.url, ref.range, budget.left) } }.awaitAll() }
                bytes.forEach { joiner.segment(budget.take(it)) }
            }
            if (!joiner.wroteAudio) throw Refused("the stream held no audio it can read")
            joined = joiner.extension
        }
        val extension = joined ?: plan.extension
        if (plan.kind == Kind.MP4 && out.length() <= SIDX_MAX_BYTES) {
            // One index for the whole file, as the downloader writes it, so a seek needs no scan of the fragments.
            val whole = out.readBytes()
            val indexed = DesktopManifestDownload.Mp4.withSidx(whole)
            if (indexed !== whole) out.writeBytes(indexed)
        }
        return extension
    }

    /** Bytes fetched so far against [Limits.maxBytes]. */
    private class Budget(private val max: Long) {
        private var used = 0L
        val left: Long get() = (max - used).coerceAtLeast(0)

        @Synchronized
        fun take(bytes: ByteArray): ByteArray {
            used += bytes.size
            if (used > max) throw Refused("over ${max / (1024 * 1024)} MB")
            return bytes
        }
    }

    /** Writes the pieces as [kind] asks; a kind unknown at the start is told from the first segment's bytes. */
    private class Joiner(private var kind: Kind?, private val sink: OutputStream) {
        var wroteAudio = false
            private set
        private var tsCodec: TsAudio.Codec? = null

        /** What TS or packed segments turned out to hold (`aac`, `mp3`); null for MP4 and whole files. */
        var extension: String? = null
            private set

        fun init(bytes: ByteArray) {
            sink.write(bytes)
        }

        fun segment(bytes: ByteArray) {
            val k = kind ?: sniff(bytes).also { kind = it }
            when (k) {
                Kind.MP4 -> {
                    // each fragment's own styp / sidx dropped, as the downloader does; one index is added at the end
                    val boxes = DesktopManifestDownload.Mp4.boxes(bytes)
                    if (boxes.none { it.type == "moof" || it.type == "mdat" }) throw Refused("a segment it can't read")
                    boxes.filter { it.type != "styp" && it.type != "sidx" }.forEach { sink.write(bytes, it.offset, it.size) }
                }
                Kind.WHOLE -> sink.write(bytes)
                Kind.TS -> {
                    val (codec, es) = TsAudio.demux(bytes)
                    if (tsCodec != null && codec != tsCodec) throw Refused("segments in more than one codec")
                    tsCodec = codec
                    extension = codec.extension
                    sink.write(es)
                }
                Kind.PACKED -> {
                    val audio = stripId3(bytes)
                    val codec = packedCodec(audio) ?: throw Refused("a segment it can't read")
                    if (extension != null && codec != extension) throw Refused("segments in more than one codec")
                    extension = codec
                    sink.write(audio)
                }
            }
            wroteAudio = true
        }

        /** An MPEG-TS segment (sync bytes 188 apart), packed ADTS or MP3 (after any ID3 tags). */
        private fun sniff(bytes: ByteArray): Kind {
            if (bytes.size >= 189 && bytes[0] == TS_SYNC && bytes[188] == TS_SYNC) return Kind.TS
            if (bytes.size >= 1 && bytes[0] == TS_SYNC && bytes.size % 188 == 0) return Kind.TS
            if (packedCodec(stripId3(bytes)) != null) return Kind.PACKED
            throw Refused("a segment it can't read")
        }
    }

    /** [bytes] less the ID3v2 tags at its start. */
    internal fun stripId3(bytes: ByteArray): ByteArray {
        var at = 0
        while (at + 10 <= bytes.size && bytes[at] == 'I'.code.toByte() && bytes[at + 1] == 'D'.code.toByte() && bytes[at + 2] == '3'.code.toByte()) {
            val size = ((bytes[at + 6].toInt() and 0x7F) shl 21) or ((bytes[at + 7].toInt() and 0x7F) shl 14) or
                ((bytes[at + 8].toInt() and 0x7F) shl 7) or (bytes[at + 9].toInt() and 0x7F)
            val footer = if (bytes[at + 5].toInt() and 0x10 != 0) 10 else 0
            at += 10 + size + footer
        }
        return if (at == 0) bytes else if (at >= bytes.size) ByteArray(0) else bytes.copyOfRange(at, bytes.size)
    }

    /** `aac` for an ADTS frame start, `mp3` for an MPEG audio one, else null. */
    internal fun packedCodec(bytes: ByteArray): String? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte()) return null
        val b1 = bytes[1].toInt() and 0xFF
        if (b1 and 0xF6 == 0xF0) return "aac" // sync 0xFFF, layer 00
        if (b1 and 0xE0 == 0xE0 && b1 and 0x06 != 0) return "mp3" // sync 0xFFE, layer not 00
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // HLS

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    internal fun attributes(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    internal fun isMaster(playlist: String): Boolean = playlist.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF") }

    private fun dolby(codecs: String?): Boolean {
        val c = codecs?.lowercase(Locale.ROOT) ?: return false
        return DOLBY.any { d -> c.split(',').any { it.trim().startsWith(d) } }
    }

    private fun dolbyOnly(codecs: String?): Boolean {
        val c = codecs?.lowercase(Locale.ROOT)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return false
        val audio = c.filterNot { codec -> VIDEO.any { codec.startsWith(it) } }
        return audio.isNotEmpty() && audio.all { codec -> DOLBY.any { codec.startsWith(it) } }
    }

    private fun audioOnly(attrs: Map<String, String>): Boolean {
        if (attrs["RESOLUTION"] != null) return false
        val codecs = attrs["CODECS"]?.lowercase(Locale.ROOT) ?: return true
        return codecs.split(',').none { codec -> VIDEO.any { codec.trim().startsWith(it) } }
    }

    /**
     * The media playlist a master playlist leads to (its URI as written): the best audio rendition, by
     * the highest `BANDWIDTH` of the variants naming its group and then `DEFAULT=YES`, else the best
     * variant, audio-only first and then by `BANDWIDTH`. Dolby renditions are passed over; refused when
     * only Dolby is left, or when a session key encrypts it.
     */
    internal fun pickHls(master: String): String {
        val lines = master.lines().map { it.trim() }
        lines.firstOrNull { it.startsWith("#EXT-X-SESSION-KEY") }?.let {
            if (attributes(it)["METHOD"]?.uppercase(Locale.ROOT) != "NONE") throw Refused("encrypted")
        }
        val variants = ArrayList<Pair<Map<String, String>, String>>()
        lines.forEachIndexed { i, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                variants += attributes(line) to uri
            }
        }
        val playable = variants.filterNot { dolbyOnly(it.first["CODECS"]) }
        val renditions = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes)
            .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrBlank() }
        val groupBandwidth = HashMap<String, Long>()
        for ((attrs, _) in playable) {
            val group = attrs["AUDIO"] ?: continue
            val bw = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
            groupBandwidth[group] = maxOf(groupBandwidth[group] ?: 0L, bw)
        }
        renditions.filter { (it["GROUP-ID"].orEmpty() in groupBandwidth || playable.isEmpty()) && !dolby(it["CODECS"]) }
            .maxWithOrNull(compareBy({ groupBandwidth[it["GROUP-ID"].orEmpty()] ?: 0L }, { it["DEFAULT"] == "YES" }))
            ?.let { return it["URI"]!! }
        if (variants.isNotEmpty() && playable.isEmpty()) throw Refused("Dolby only")
        return playable.maxWithOrNull(compareBy({ audioOnly(it.first) }, { it.first["BANDWIDTH"]?.toLongOrNull() ?: 0L }))?.second
            ?: throw Refused("a master playlist with no stream")
    }

    /** A media playlist's pieces, resolved against [playlistUrl]. */
    internal fun hlsPlan(playlist: String, playlistUrl: String): Plan {
        val lines = playlist.lines().map { it.trim() }
        if (lines.firstOrNull { it.isNotEmpty() } != "#EXTM3U") throw Refused("not an HLS playlist")
        if (lines.none { it == "#EXT-X-ENDLIST" } || lines.any { it.startsWith("#EXT-X-PLAYLIST-TYPE:EVENT") }) throw Refused("live")
        val maps = LinkedHashSet<Ref>()
        val media = ArrayList<Ref>()
        var pendingRange: Pair<Long, Long?>? = null
        val nextOffset = HashMap<String, Long>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY") -> {
                    val method = attributes(line)["METHOD"]?.uppercase(Locale.ROOT)
                    if (method != "NONE") throw Refused("encrypted")
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val a = attributes(line)
                    val uri = a["URI"] ?: throw Refused("an init segment with no link")
                    val url = resolve(playlistUrl, uri)
                    maps += Ref(url, a["BYTERANGE"]?.let { byteRange(it, 0L) })
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val spec = line.substringAfter(':')
                    pendingRange = spec.substringBefore('@').toLong() to spec.substringAfter('@', "").toLongOrNull()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val url = resolve(playlistUrl, line)
                    val range = pendingRange?.let { (length, offset) ->
                        val from = offset ?: nextOffset[url] ?: 0L
                        nextOffset[url] = from + length
                        from until from + length
                    }
                    pendingRange = null
                    media += Ref(url, range)
                }
            }
        }
        if (maps.size > 1) throw Refused("more than one init segment")
        if (media.isEmpty()) throw Refused("a playlist with no segments")
        val init = maps.firstOrNull()
        return if (init != null) Plan(init, media, Kind.MP4, "m4a") else Plan(null, media, null, "aac")
    }

    /** `n@o` (or `n`, from [after]) as an inclusive range. */
    private fun byteRange(spec: String, after: Long): LongRange {
        val length = spec.substringBefore('@').trim().toLong()
        val from = spec.substringAfter('@', "").trim().toLongOrNull() ?: after
        return from until from + length
    }

    // ---------------------------------------------------------------------------------------------
    // DASH

    /** The best audio Representation's pieces of [mpd], resolved against [mpdUrl]. */
    internal fun dashPlan(mpd: String, mpdUrl: String): Plan {
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }.newDocumentBuilder().parse(mpd.byteInputStream())
        }.getOrElse { throw Refused("not a DASH manifest") }
        val root = doc.documentElement
        if (local(root.tagName) != "MPD") throw Refused("not a DASH manifest")
        if (root.getAttribute("type").equals("dynamic", ignoreCase = true)) throw Refused("live")
        if (descendants(root).any { local(it.tagName) == "ContentProtection" }) throw Refused("encrypted")
        val periods = children(root, "Period")
        if (periods.size != 1) throw Refused(if (periods.isEmpty()) "a manifest with no Period" else "more than one Period")
        val period = periods.single()
        val total = isoSeconds(period.getAttribute("duration")) ?: isoSeconds(root.getAttribute("mediaPresentationDuration"))

        class Candidate(val set: org.w3c.dom.Element, val rep: org.w3c.dom.Element, val bandwidth: Long, val codecs: String)
        val candidates = ArrayList<Candidate>()
        for (set in children(period, "AdaptationSet")) {
            for (rep in children(set, "Representation")) {
                val mime = rep.getAttribute("mimeType").ifEmpty { set.getAttribute("mimeType") }.lowercase(Locale.ROOT)
                val content = set.getAttribute("contentType").lowercase(Locale.ROOT)
                val codecs = rep.getAttribute("codecs").ifEmpty { set.getAttribute("codecs") }.lowercase(Locale.ROOT)
                val audio = mime.startsWith("audio") || content == "audio" ||
                    (mime.isEmpty() && content.isEmpty() && codecs.isNotEmpty() && codecs.split(',').none { c -> VIDEO.any { c.trim().startsWith(it) } })
                if (!audio) continue
                candidates += Candidate(set, rep, rep.getAttribute("bandwidth").toLongOrNull() ?: 0L, codecs)
            }
        }
        if (candidates.isEmpty()) throw Refused("no audio Representation")
        val best = candidates.filterNot { dolby(it.codecs) }.maxByOrNull { it.bandwidth } ?: throw Refused("Dolby only")
        val rep = best.rep
        val set = best.set
        var base = mpdUrl
        for (level in listOf(root, period, set, rep)) {
            children(level, "BaseURL").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { base = resolve(base, it) }
        }
        val vars = mapOf("RepresentationID" to rep.getAttribute("id"), "Bandwidth" to rep.getAttribute("bandwidth"))
        val extension = when {
            best.codecs.startsWith("flac") || best.codecs.startsWith("mp4a") || best.codecs.startsWith("alac") -> "m4a"
            set.getAttribute("mimeType").contains("webm") || rep.getAttribute("mimeType").contains("webm") -> "webm"
            else -> "m4a"
        }

        val template = listOfNotNull(children(rep, "SegmentTemplate").firstOrNull(), children(set, "SegmentTemplate").firstOrNull(), children(period, "SegmentTemplate").firstOrNull())
        if (template.isNotEmpty()) {
            fun attr(name: String) = template.firstNotNullOfOrNull { it.getAttribute(name).takeIf(String::isNotEmpty) }
            val timescale = attr("timescale")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            val startNumber = attr("startNumber")?.toLongOrNull() ?: 1L
            val mediaTemplate = attr("media") ?: throw Refused("a segment template with no media")
            val init = attr("initialization")?.let { Ref(resolve(base, fill(it, vars, null, null))) }
            val timeline = template.firstNotNullOfOrNull { children(it, "SegmentTimeline").firstOrNull() }
            val times = ArrayList<Long>()
            if (timeline != null) {
                var t = 0L
                val end = total?.let { (it * timescale).toLong() }
                for (s in children(timeline, "S")) {
                    s.getAttribute("t").toLongOrNull()?.let { t = it }
                    val d = s.getAttribute("d").toLongOrNull()?.takeIf { it > 0 } ?: throw Refused("a timeline entry with no duration")
                    val r = s.getAttribute("r").toLongOrNull() ?: 0L
                    if (r < 0) {
                        val stop = end ?: throw Refused("an open timeline with no duration")
                        while (t < stop) { times += t; t += d; if (times.size > MAX_SEGMENTS) throw Refused("too many segments") }
                    } else {
                        repeat((r + 1).toInt()) { times += t; t += d }
                    }
                    if (times.size > MAX_SEGMENTS) throw Refused("too many segments")
                }
            } else {
                val d = attr("duration")?.toDoubleOrNull()?.takeIf { it > 0 } ?: throw Refused("a segment template with neither a timeline nor a duration")
                val seconds = total ?: throw Refused("a manifest that states no duration")
                val endNumber = attr("endNumber")?.toLongOrNull()
                val count = endNumber?.let { it - startNumber + 1 } ?: ceil(seconds / (d / timescale)).toLong().coerceAtLeast(1)
                if (count > MAX_SEGMENTS) throw Refused("too many segments")
                var t = 0L
                repeat(count.toInt()) { times += t; t += d.toLong() }
            }
            val media = times.mapIndexed { i, time -> Ref(resolve(base, fill(mediaTemplate, vars, startNumber + i, time))) }
            if (media.isEmpty()) throw Refused("a manifest with no segments")
            return Plan(init, media, Kind.MP4, extension)
        }

        val list = children(rep, "SegmentList").firstOrNull() ?: children(set, "SegmentList").firstOrNull()
        if (list != null) {
            val init = children(list, "Initialization").firstOrNull()?.let { i ->
                Ref(i.getAttribute("sourceURL").takeIf(String::isNotEmpty)?.let { resolve(base, it) } ?: base, range(i.getAttribute("range")))
            }
            val media = children(list, "SegmentURL").map { s ->
                Ref(s.getAttribute("media").takeIf(String::isNotEmpty)?.let { resolve(base, it) } ?: base, range(s.getAttribute("mediaRange")))
            }
            if (media.isEmpty()) throw Refused("a segment list with no segments")
            if (media.size > MAX_SEGMENTS) throw Refused("too many segments")
            return Plan(init, media, Kind.MP4, extension)
        }

        // SegmentBase, or a BaseURL alone: the Representation is one file
        if (base == mpdUrl) throw Refused("a Representation with no segments")
        return Plan(null, listOf(Ref(base)), Kind.WHOLE, extension)
    }

    private fun range(spec: String): LongRange? {
        if (spec.isEmpty()) return null
        val from = spec.substringBefore('-').trim().toLongOrNull() ?: return null
        val to = spec.substringAfter('-').trim().toLongOrNull() ?: return null
        return from..to
    }

    private val TEMPLATE = Regex("""\$(RepresentationID|Number|Time|Bandwidth)(%0(\d+)d)?\$|\$\$""")

    /** A DASH template with its identifiers filled in (`%0Nd` widths kept). */
    internal fun fill(template: String, vars: Map<String, String>, number: Long?, time: Long?): String =
        TEMPLATE.replace(template) { m ->
            if (m.value == "$$") return@replace "$"
            val value = when (val name = m.groupValues[1]) {
                "Number" -> number?.toString() ?: throw Refused("\$Number\$ in an initialization")
                "Time" -> time?.toString() ?: throw Refused("\$Time\$ in an initialization")
                else -> vars[name].orEmpty()
            }
            val width = m.groupValues[3].toIntOrNull()
            if (width != null && value.length < width) value.padStart(width, '0') else value
        }

    private fun local(tag: String) = tag.substringAfter(':')

    private fun children(parent: org.w3c.dom.Element, name: String): List<org.w3c.dom.Element> {
        val out = ArrayList<org.w3c.dom.Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is org.w3c.dom.Element && local(n.tagName) == name) out += n
        }
        return out
    }

    private fun descendants(root: org.w3c.dom.Element): List<org.w3c.dom.Element> {
        val nodes = root.getElementsByTagName("*")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
    }

    /** `PT3M50.461S` → 230.461. */
    private fun isoSeconds(value: String): Double? {
        if (value.isBlank()) return null
        val match = Regex("""P(?:(\d+(?:\.\d+)?)D)?T?(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?""").matchEntire(value.trim()) ?: return null
        val (d, h, m, s) = match.destructured
        return ((d.toDoubleOrNull() ?: 0.0) * 86_400 + (h.toDoubleOrNull() ?: 0.0) * 3600 + (m.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0))
            .takeIf { it > 0 }
    }

    // ---------------------------------------------------------------------------------------------
    // MPEG-TS

    /** The audio of an MPEG-TS segment: its first ADTS AAC or MPEG audio stream, its PES payloads joined. */
    internal object TsAudio {
        enum class Codec(val extension: String) { AAC("aac"), MP3("mp3") }

        fun demux(bytes: ByteArray): Pair<Codec, ByteArray> {
            var pmtPid = -1
            var audioPid = -1
            var codec: Codec? = null
            var unreadable = false
            val out = java.io.ByteArrayOutputStream(bytes.size)
            var inPes = false
            var at = 0
            while (at + 188 <= bytes.size) {
                if (bytes[at] != TS_SYNC) throw Refused("a segment it can't read")
                val pusi = bytes[at + 1].toInt() and 0x40 != 0
                val pid = ((bytes[at + 1].toInt() and 0x1F) shl 8) or (bytes[at + 2].toInt() and 0xFF)
                val afc = (bytes[at + 3].toInt() shr 4) and 0x3
                var p = at + 4
                if (afc == 2 || afc == 0) { at += 188; continue }
                if (afc == 3) p += 1 + (bytes[p].toInt() and 0xFF)
                val end = at + 188
                if (p < end) {
                    when {
                        pid == 0 && pusi -> {
                            // PAT: the first programme's PMT
                            val s = p + 1 + (bytes[p].toInt() and 0xFF)
                            if (s + 12 <= end) pmtPid = ((bytes[s + 10].toInt() and 0x1F) shl 8) or (bytes[s + 11].toInt() and 0xFF)
                        }
                        pid == pmtPid && pusi && audioPid < 0 -> {
                            val s = p + 1 + (bytes[p].toInt() and 0xFF)
                            if (s + 12 <= end) {
                                val sectionLength = ((bytes[s + 1].toInt() and 0x0F) shl 8) or (bytes[s + 2].toInt() and 0xFF)
                                val infoLength = ((bytes[s + 10].toInt() and 0x0F) shl 8) or (bytes[s + 11].toInt() and 0xFF)
                                var e = s + 12 + infoLength
                                val stop = minOf(end, s + 3 + sectionLength - 4)
                                while (e + 5 <= stop) {
                                    val type = bytes[e].toInt() and 0xFF
                                    val ePid = ((bytes[e + 1].toInt() and 0x1F) shl 8) or (bytes[e + 2].toInt() and 0xFF)
                                    val esInfo = ((bytes[e + 3].toInt() and 0x0F) shl 8) or (bytes[e + 4].toInt() and 0xFF)
                                    val found = when (type) {
                                        0x0F -> Codec.AAC
                                        0x03, 0x04 -> Codec.MP3
                                        else -> null
                                    }
                                    if (found != null) {
                                        audioPid = ePid
                                        codec = found
                                        break
                                    }
                                    if (type in AUDIO_TS_TYPES) unreadable = true
                                    e += 5 + esInfo
                                }
                            }
                        }
                        pid == audioPid -> {
                            if (pusi) {
                                // a PES header: 00 00 01, stream id, length, two flag bytes, header length
                                if (p + 9 > end || bytes[p] != 0.toByte() || bytes[p + 1] != 0.toByte() || bytes[p + 2] != 1.toByte()) throw Refused("a segment it can't read")
                                p += 9 + (bytes[p + 8].toInt() and 0xFF)
                                inPes = true
                            }
                            if (inPes && p < end) out.write(bytes, p, end - p)
                        }
                    }
                }
                at += 188
            }
            val c = codec ?: throw Refused(if (unreadable) "a segment in a codec it can't take out" else "a segment with no audio it can read")
            if (out.size() == 0) throw Refused("a segment with no audio it can read")
            return c to out.toByteArray()
        }

        /** Audio stream types it can't take out to a file of their own (AC-3, E-AC-3, LATM AAC, …). */
        private val AUDIO_TS_TYPES = setOf(0x11, 0x81, 0x87, 0x1C, 0x2D)
    }

    // ---------------------------------------------------------------------------------------------
    // Links and the network

    /**
     * https only, no user info, and not this machine by any spelling ([ForkThisMachine]: `127.1`,
     * `2130706433`, `[::ffff:127.0.0.1]`, … as well as `localhost`). The client's [Dns] guard below
     * never sees a literal address (OkHttp connects to one without asking it), so this check is the
     * only one a literal meets; the four-part `127.x.x.x` regex it replaced let the short, decimal and
     * IPv4-mapped forms through (combined audit 2026-11-26).
     */
    internal fun linkAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return false
        val host = uri.host ?: return false
        return !ForkThisMachine.isThisMachine(host)
    }

    private fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrElse { throw Refused("a link it can't read") }

    /**
     * The stream's own requests: [headers] only (the stream answer's, rulebook §1), no cookies or
     * credentials, [Limits.requestMs] each, redirects followed by hand so each is checked
     * ([linkAllowed]), and a name that resolves to this machine refused.
     */
    fun http(headers: Map<String, String>, limits: Limits = Limits()): Fetch {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(limits.requestMs, TimeUnit.MILLISECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val all = Dns.SYSTEM.lookup(hostname)
                    if (all.any { it.isLoopbackAddress || it.isAnyLocalAddress }) throw UnknownHostException("$hostname is this machine")
                    return all
                }
            })
            .build()
        return Fetch { url, range, limit ->
            var target = url
            repeat(MAX_REDIRECTS + 1) {
                if (!linkAllowed(target)) throw Refused("a link or redirect off https or to this machine")
                val request = Request.Builder().url(target)
                    .apply { headers.forEach { (name, value) -> runCatching { header(name, value) } } }
                    .header("Accept-Encoding", "identity")
                    .apply { range?.let { header("Range", "bytes=${it.first}-${it.last}") } }
                    .build()
                val answer = try {
                    client.newCall(request).await().use { response -> read(response, range, limit) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Refused) {
                    throw e
                } catch (e: Exception) {
                    throw Refused("a segment it can't read (${e.message ?: e.javaClass.simpleName})")
                }
                when (answer) {
                    is Answer.Body -> return@Fetch answer.bytes
                    is Answer.Redirect -> target = resolve(target, answer.location)
                }
            }
            throw Refused("too many redirects")
        }
    }

    internal sealed interface Answer {
        class Body(val bytes: ByteArray) : Answer
        class Redirect(val location: String) : Answer
    }

    /**
     * One answer's bytes: [range] of the resource when one was asked for, else all of it (at most
     * [limit]).
     *
     * A ranged piece (`#EXT-X-BYTERANGE`) must be exactly the bytes asked for, or the joined stream is
     * quietly wrong (combined audit 2026-11-26): a 206 counts only when its `Content-Range` starts at
     * [range]'s first byte and reaches its last (or the resource's end, when the range runs past it); a
     * 200 is the whole resource, so [range]'s first bytes are skipped exactly, and a body that ends
     * before them, or before the range's last byte while the resource goes on, is refused. The loop
     * before this stopped at the first short skip and kept whatever came after, and took any 206.
     */
    internal fun read(response: Response, range: LongRange?, limit: Long): Answer {
        if (response.code in 300..399) return Answer.Redirect(response.header("Location") ?: throw Refused("a redirect with no link"))
        if (response.code !in 200..299) throw Refused("a segment it can't read (HTTP ${response.code})")
        val input = response.body.source().inputStream()
        var want = range?.let { it.last - it.first + 1 }
        if (range != null && response.code == 206) {
            val (start, end, total) = contentRange(response.header("Content-Range"))
                ?: throw Refused("a byte range answered without a range it can read")
            if (start != range.first) throw Refused("a byte range answered from another offset")
            if (end < range.last) {
                if (total == null || end != total - 1) throw Refused("a byte range answered short")
                want = end - start + 1
            }
        } else if (range != null) {
            skipExactly(input, range.first)
        }
        if (want != null && want > limit) throw Refused("over the size limit")
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (want == null || buffer.size() < want) {
            val ask = if (want == null) chunk.size else minOf(chunk.size.toLong(), want - buffer.size()).toInt()
            val n = input.read(chunk, 0, ask)
            if (n < 0) break
            buffer.write(chunk, 0, n)
            if (buffer.size() > limit) throw Refused("over the size limit")
        }
        if (want != null && buffer.size() < want) {
            // A 200 whose resource ends inside the range is the range cut at the end, as a 206 would be.
            if (response.code == 206 || buffer.size() == 0) throw Refused("a byte range answered short")
            val declared = response.body.contentLength()
            if (declared < 0 || range!!.first + buffer.size() != declared) throw Refused("a byte range answered short")
        }
        return Answer.Body(buffer.toByteArray())
    }

    /** `bytes s-e/t` (t may be `*`) as (s, e, t or null), or null when it isn't one. */
    private fun contentRange(header: String?): Triple<Long, Long, Long?>? {
        val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).matchEntire(header ?: return null) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        return Triple(start, end, m.groupValues[3].toLongOrNull())
    }

    /** Skips exactly [count] bytes of [input], reading when `skip` makes no progress; refused when it ends first. */
    private fun skipExactly(input: java.io.InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) { left -= n; continue }
            if (input.read() < 0) throw Refused("a byte range past the end of the answer")
            left--
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
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

    private const val TS_SYNC: Byte = 0x47
    private const val MAX_REDIRECTS = 5
    private const val MAX_SEGMENTS = 20_000

    /** Above this the whole-file index is left out: reading the file back in would hold it twice in memory. */
    private const val SIDX_MAX_BYTES = 100L * 1024 * 1024
    private val DOLBY = listOf("ac-3", "ec-3", "ac-4", "ac3", "eac3", "ec+3")
    private val VIDEO = listOf("avc", "hvc", "hev", "vp8", "vp09", "vp9", "av01", "dvh", "dva", "mp4v")
}

/**
 * Fork: the files [ForkAddonStreamAssemble] put together, by the stream URL they stand in for, which
 * stays the copy's identity (its offset, its analysis key, the swap's checks). [DesktopAudioDecoder.open]
 * reads the file instead of the URL ([local]). Not a cache (rule 6.1): an entry lives only until
 * [KEEP] newer ones push it out (the playing song, the next, a swap's copy, one more), its file then
 * deleted (or, while a deck still holds it on Windows, at the next push or at exit); every resolve
 * puts the stream together again. The folder is emptied the first time it is used in a run.
 */
internal object ForkAssembledStreams {
    private const val KEEP = 4
    private val files = LinkedHashMap<String, File>()
    private val stale = ArrayList<File>()
    @Volatile private var cleaned = false

    val folder: File get() = File(System.getProperty("java.io.tmpdir"), "bitchord-addon-streams")

    /** A fresh file to put a stream together in. */
    @Synchronized
    fun newFile(extension: String = "part"): File {
        if (!cleaned) {
            cleaned = true
            folder.listFiles()?.forEach { it.delete() }
        }
        folder.mkdirs()
        return File.createTempFile("stream-", ".$extension", folder).also { it.deleteOnExit() }
    }

    /** [url]'s stream now reads from [file]. */
    @Synchronized
    fun put(url: String, file: File) {
        files.remove(url)?.takeIf { it != file }?.let { stale += it }
        files[url] = file
        while (files.size > KEEP) {
            val eldest = files.keys.first()
            files.remove(eldest)?.let { stale += it }
        }
        stale.removeAll { !it.exists() || it.delete() }
    }

    /** The file standing in for [url], when one is kept. */
    @Synchronized
    fun local(url: String): String? = files[url]?.takeIf { it.exists() }?.absolutePath

    @Synchronized
    internal fun clear() {
        files.values.forEach { it.delete() }
        files.clear()
    }
}
