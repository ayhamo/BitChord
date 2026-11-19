package com.music.bitchord.fork

import androidx.media3.common.MimeTypes
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.ForkAddonPickPhone
import com.music.bitchord.data.sources.ModuleSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamLengthProbe
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The shared addon rulebook's `pick-cases.json` (YT Music Extension
 * `spec/addons/`) against the phone's own addon code, end to end where the
 * code is a network path: rows go through a real `AddonSource.search` and
 * [ForkAddonPickPhone], stream answers through `AddonSource.stream`, the probe
 * through `StreamLengthProbe.bytesOf`, parameters are read off the request the
 * addon received. All against a local MockWebServer, as `AddonSourceTest` does.
 *
 * The matcher cases and the pure `rowsToOpen` are `ForkRulebookTest` in the
 * shared module; this is the phone's reading of the same JSON.
 *
 * Every case either passes or is listed in [DIFFERS] with the reason the fork
 * answers differently on purpose (or has no such concept). A listed case that
 * starts passing fails the test too, so the list never outlives the gap.
 * The spec directory is `RULEBOOK_SPEC`, else `/spec`; missing, the test fails.
 */
class ForkRulebookPickTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()
    private val seen = mutableListOf<RecordedRequest>()
    private var slackBefore = AppSettings.upgradeLengthSlackSeconds.value

    private val spec: File by lazy {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.ifBlank { null } ?: "/spec")
        if (!File(dir, "addons/pick-cases.json").isFile) {
            fail("rulebook not found: set RULEBOOK_SPEC to the extension's spec directory (looked in ${dir.absolutePath})")
        }
        dir
    }
    private val cases: JsonObject by lazy {
        Json.parseToJsonElement(File(spec, "addons/pick-cases.json").readText()).jsonObject
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return routes[request.requestUrl?.encodedPath.orEmpty()] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        // A stereo phone: the rulebook's cases are the extension's, which never plays Atmos.
        DeviceCodecs.forced = false
        slackBefore = AppSettings.upgradeLengthSlackSeconds.value
    }

    @After
    fun tearDown() {
        server.shutdown()
        DeviceCodecs.forced = null
        AppSettings.upgradeLengthSlackSeconds.value = slackBefore
    }

    // ── rowsToOpen, through AddonSource.search and the phone's hook ──────

    @Test
    fun rowsToOpen() = runBlocking {
        val results = cases.getValue("rowsToOpen").jsonArray.map { it.jsonObject }.map { c ->
            reset()
            route("/manifest.json", json(MANIFEST))
            route("/search", json(JsonObject(mapOf("tracks" to c.getValue("rows"))).toString()))
            val source = AddonSource(config())
            val songs = source.search("q", limit = 15, request = StreamRequest.Lossless)
            val t = c.getValue("target").jsonObject
            val opt = c["opt"]?.jsonObject ?: JsonObject(emptyMap())
            AppSettings.upgradeLengthSlackSeconds.value = opt["slackSec"]?.num()?.toInt() ?: 5
            // A download is one of SourceResolver's strict callers, at UPGRADE_DRIFT_SEC (2 s).
            val strict = if (opt["forDownload"]?.flag() == true) 2 else null
            val target = TrackMatcher.Target(
                title = t.str("title"),
                artist = t.str("artist"),
                durationSec = t["durationSec"]?.num()?.toInt(),
                album = t["album"]?.text(),
            )
            val open = ForkAddonPickPhone.ranked(source, songs, target, strict).orEmpty().map { rowId(it.videoId) }
            val e = c.getValue("expect").jsonObject
            val want = e.getValue("open").jsonArray.map { it.jsonPrimitive.content }
            Outcome("rowsToOpen", c.str("name"), open == want, "open $open, want $want")
        }
        check("rowsToOpen", results)
    }

    // ── tierOf: ModuleSource.qualityTier, what a row's sourceQuality is read with ──

    @Test
    fun tierOf() {
        val results = cases.getValue("tierOf").jsonArray.map { pair ->
            val (text, want) = pair.jsonArray.let { it[0].jsonPrimitive.content to it[1].text() }
            val got = ModuleSource.qualityTier(text)
            Outcome("tierOf", text, got == want, "got $got, want $want")
        }
        check("tierOf", results)
    }

    // ── kbps: AddonStream.kbps, the reading the probe's floor is taken from ──

    @Test
    fun kbps() {
        val results = cases.getValue("kbps").jsonArray.map { pair ->
            val raw = pair.jsonArray[0].jsonObject
            val want = pair.jsonArray[1].num()?.toInt()
            val got = LENIENT.decodeFromString(AddonStream.serializer(), raw.toString()).kbps
            Outcome("kbps", raw.toString(), got == want, "got $got, want $want")
        }
        check("kbps", results)
    }

    // ── probeVerdict: StreamLengthProbe.bytesOf against a served head, then tooShort ──

    @Test
    fun probeVerdict() = runBlocking {
        val results = cases.getValue("probeVerdict").jsonArray.map { it.jsonObject }.map { c ->
            reset()
            val head = c.getValue("head").jsonObject
            val url = if (head["status"] == null) {
                // No answer at all: a port nothing listens on.
                "http://127.0.0.1:1/probe"
            } else {
                val status = head.getValue("status").num()!!.toInt()
                val length = head["contentLength"]?.num()?.toLong() ?: 1L
                val r = MockResponse().setResponseCode(status).setBody(Buffer().write(ByteArray(length.toInt())))
                head["contentRange"]?.text()?.let { r.setHeader("Content-Range", it) }
                route("/probe", r)
                server.url("/probe").toString()
            }
            val row = c.getValue("row").jsonObject
            val format = if (row.str("tier") == "LOSSLESS") StreamFormat(codec = "flac")
            else StreamFormat(codec = "mp3", kbps = row["kbps"]?.num()?.toInt())
            val bytes = StreamLengthProbe.bytesOf(SourceStream(url = url, format = format))
            val ok = bytes == null || !StreamLengthProbe.tooShort(
                bytes, format, c.getValue("songSec").num()!!.toInt(), c.getValue("slackSec").num()!!.toInt(),
            )
            val want = c.getValue("expect").jsonObject.getValue("ok").flag()
            Outcome("probeVerdict", c.str("name"), ok == want, "ok $ok (bytes $bytes), want $want")
        }
        check("probeVerdict", results)
    }

    // ── paramsFor: the query an addon receives, minus q ──────────────────

    @Test
    fun paramsFor() = runBlocking {
        val results = cases.getValue("paramsFor").jsonArray.map { it.jsonObject }.map { c ->
            reset()
            val manifest = JsonObject(c.getValue("manifest").jsonObject + ("resources" to Json.parseToJsonElement("""["search","stream"]""")))
            route("/manifest.json", json(manifest.toString()))
            route("/search", json("""{"tracks":[]}"""))
            val request = when (c.str("tier")) {
                "LOSSLESS" -> StreamRequest.Lossless
                "HIGH" -> StreamRequest.Best
                else -> StreamRequest.Capped(ModuleSource.LOW_CEILING_KBPS)
            }
            AddonSource(config(picked = c["picked"]?.text())).search("q", limit = 5, request = request)
            val sent = synchronized(seen) { seen.first { it.requestUrl?.encodedPath == "/search" }.requestUrl!! }
            val got = (0 until sent.querySize).map { sent.queryParameterName(it) to sent.queryParameterValue(it).orEmpty() }
                .filter { it.first != "q" }
            val want = c.getValue("expect").jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
            Outcome("paramsFor", c.str("name"), got == want, "got $got, want $want")
        }
        check("paramsFor", results)
    }

    // ── readStream: AddonSource.stream on the answer ─────────────────────

    @Test
    fun readStream() = runBlocking {
        val results = cases.getValue("readStream").jsonArray.map { it.jsonObject }.map { c ->
            reset()
            forgetDeclaredTransports()
            route("/manifest.json", json(MANIFEST))
            route("/stream/t", json(c.getValue("answer").toString()))
            val stream = AddonSource(config()).stream("t", StreamRequest.Lossless)
            val answer = LENIENT.decodeFromString(AddonStream.serializer(), c.getValue("answer").toString())
            val e = c.getValue("expect").jsonObject
            val diffs = mutableListOf<String>()
            if (e["miss"] != null) {
                if (stream != null) diffs += "opened ${stream.url}, want a miss (${e.str("miss")})"
            } else if (stream == null) {
                diffs += "a miss, want a stream"
            } else {
                val got = mapOf(
                    "codec" to stream.format.codec,
                    "transport" to when (StreamContainer.manifestMimeOf(stream.url)) {
                        MimeTypes.APPLICATION_M3U8 -> "hls"
                        MimeTypes.APPLICATION_MPD -> "dash"
                        else -> "file"
                    },
                    "tier" to ModuleSource.qualityTier(answer.qualityText),
                    "sampleRate" to stream.format.sampleRateHz?.toString(),
                    "bitDepth" to stream.format.bitDepth?.toString(),
                    "bitrate" to stream.format.kbps?.toString(),
                )
                // headers, cached: no such reading on a stream answer here (see READ_STREAM_NO_EQUIVALENT)
                for ((k, v) in e) {
                    if (k in READ_STREAM_NO_EQUIVALENT) continue
                    val want = v.text()?.let { w -> w.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else w } ?: w }
                    if (got[k] != want) diffs += "$k ${got[k]}, want $want"
                }
            }
            Outcome("readStream", c.str("name"), diffs.isEmpty(), diffs.joinToString("; "))
        }
        check("readStream", results)
    }

    // ── baseOf, sameAddon ────────────────────────────────────────────────

    @Test
    fun baseOf() {
        val results = cases.getValue("baseOf").jsonArray.map { pair ->
            val (url, want) = pair.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content }
            val got = AddonClient.normalizeBase(url)
            Outcome("baseOf", url, got == want, "got $got, want $want")
        }
        check("baseOf", results)
    }

    /** `SourceRegistry.duplicateOf`'s comparison, on bases as `identify` stores them. */
    @Test
    fun sameAddon() {
        val results = cases.getValue("sameAddon").jsonArray.map { triple ->
            val a = triple.jsonArray[0].jsonPrimitive.content
            val b = triple.jsonArray[1].jsonPrimitive.content
            val want = triple.jsonArray[2].flag()
            val got = SourceRegistry.canonicalUrl(AddonClient.normalizeBase(a)) ==
                SourceRegistry.canonicalUrl(AddonClient.normalizeBase(b))
            Outcome("sameAddon", "$a ~ $b", got == want, "got $got, want $want")
        }
        check("sameAddon", results)
    }

    // ── Bookkeeping ──────────────────────────────────────────────────────

    private data class Outcome(val group: String, val name: String, val ok: Boolean, val detail: String)

    private fun check(group: String, results: List<Outcome>) {
        val bad = mutableListOf<String>()
        var differs = 0
        for (r in results) {
            val reason = DIFFERS["$group: ${r.name}"]
            when {
                reason == null && !r.ok -> bad += "FAIL $group: ${r.name} — ${r.detail}"
                reason != null && r.ok -> bad += "now passes, drop it from DIFFERS: $group: ${r.name}"
                reason != null -> { differs++; println("  differs (known) $group: ${r.name} — ${r.detail} [$reason]") }
            }
        }
        println("$group: ${results.count { it.ok }}/${results.size} pass, $differs known differences")
        if (bad.isNotEmpty()) fail(bad.joinToString("\n"))
    }

    private fun reset() {
        routes.clear()
        synchronized(seen) { seen.clear() }
    }

    private fun route(path: String, response: MockResponse) {
        routes[path] = response
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun config(picked: String? = null) = SourceConfig(
        kind = SourceKind.ADDON,
        baseUrl = server.url("/").toString().trimEnd('/'),
        pickedQuality = picked,
    )

    private fun rowId(videoId: String) = SourceRegistry.parseTrackKey(videoId)?.second ?: videoId

    /**
     * `StreamContainer` remembers what a source declared about a URL for the
     * life of the process; the cases reuse `https://cdn/x`, so one case's DASH
     * would otherwise be read as the next one's transport.
     */
    private fun forgetDeclaredTransports() {
        val field = StreamContainer::class.java.getDeclaredField("declared").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
    }

    private fun JsonObject.str(key: String) = this[key]?.text().orEmpty()
    private fun JsonElement.text(): String? = if (this is JsonNull) null else (this as? JsonPrimitive)?.content
    private fun JsonElement.num(): Double? = text()?.toDoubleOrNull()
    private fun JsonElement.flag(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    private companion object {
        /** A current-protocol manifest with no settings; `bitchordAuth` left out, so no attestation is asked. */
        const val MANIFEST = """{"id":"rulebook.test","name":"Rulebook","version":"1","resources":["search","stream"]}"""

        val LENIENT = Json { isLenient = true; ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

        /** Rulebook stream fields this app has no reading of on a `/stream` answer. */
        val READ_STREAM_NO_EQUIVALENT = setOf(
            // AddonStream has no `headers`: the file request carries none of an addon's headers.
            "headers",
            // Cached is read off the search row (ForkAddonLimits' infotext/cached), not the stream answer.
            "cached",
        )

        private const val BITRATE = "the fork fills a missing bitrate from the tier it asked for (StreamFormat.kbpsInferred), " +
            "not from the answer's own label: asked for LOSSLESS, an AAC answer's bitrate stays unknown"
        private const val TIER_FROM_CODEC = "the fork reads no tier off a stream answer's codec; a stated flac codec makes " +
            "StreamFormat.isLossless true, which is what the fork ranks by"
        private const val LOW_FIRST = "BitChord's rule for LOW without a low option: the first option (AddonClient.matchTier)"

        /** Cases the fork answers differently, and why. Keyed "group: case name". */
        val DIFFERS = mapOf(
            "rowsToOpen: ISRC first, whatever the title says" to
                "no ISRC: a YouTube Music song carries none in this app, so the matcher decides (the rulebook's no-ISRC rule)",
            "paramsFor: LOW: no low option: the high one (ours; BitChord: the first)" to LOW_FIRST,
            "paramsFor: LOW: no low or high option: the last (ours)" to LOW_FIRST,
            "readStream: HLS seen in the URL" to BITRATE,
            "readStream: DASH from the manifest field" to BITRATE,
            "readStream: no bitrate: 320 for HIGH" to BITRATE,
            "readStream: headers: strings with valid names only; the cached flag" to TIER_FROM_CODEC,
            "readStream: an addon's User-Agent, Range and Accept-Encoding are dropped (one fixed UA; ours)" to TIER_FROM_CODEC,
            "readStream: a plain http stream link is refused, not probed" to
                "the fork has no https-only rule for addon streams (upstream opens any URL OkHttp parses; cleartext is allowed for LAN Navidrome)",
            "readStream: HLS from the MIME type" to
                "AddonStream.transport reads manifest, mediaType and format, not mimeType; the URL's extension is the fallback",
        )
    }
}
