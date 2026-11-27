package com.music.bitchord.fork

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.music.bitchord.data.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Fork: the player's reads of an addon's HLS or DASH stream that [ForkManifestCheck] let through
 * (protocol.md 8.1.5 rule 5, the extension's 3dce391; the user via the master, 2026-11-26: "nothing
 * left open").
 *
 * Until this, the check read the stream once and then handed Media3 the addon's own master playlist or
 * MPD, which left two gaps the PC's assembly (`ForkAddonStreamAssemble`) doesn't have:
 *
 *  - **Media3 chose the variant.** From a master with a Dolby variant and an AAC one, Media3 picks by
 *    what the device decodes and the bitrate, so a phone that decodes E-AC-3 played the Dolby copy with
 *    the Atmos switch off (the check only refuses a master that is Dolby *only*). Now the open of an
 *    HLS master is served the media playlist the check chose (the extension's pick: the best audio
 *    rendition, else the best variant, Dolby passed over unless `DeviceCodecs.atmosWanted`), and an
 *    MPD is served with one audio Representation left ([keepOne]: the PC's pick, the highest
 *    bandwidth that isn't Dolby, or of all of them when Atmos is wanted).
 *  - **Segment redirects weren't judged.** The check follows the playlists' redirects by hand, but the
 *    segments went out through the app's shared client, which follows any redirect, plain http and
 *    this phone included, and adds the WebDAV and Navidrome credentials for their hosts. Now every
 *    read of a host the accepted manifest is on goes through a plain client on the shared pool that
 *    judges each hop before it is followed ([hopAllowed]), and whose lookup refuses this machine.
 *
 * Sits under the cache in `ForkManifestSniff.below` (the hook that already wraps the player's
 * network reads), so nothing in upstream's files changes; any other read goes on as before.
 */
object ForkManifestPlay {

    /** Accepted manifests remembered: a queue's worth of addon copies. */
    private const val KEEP = 16

    /** A playlist or manifest is kilobytes (as [ForkManifestCheck.MAX_BYTES]). */
    private const val MAX_MANIFEST_BYTES = ForkManifestCheck.MAX_BYTES

    private val known = MutableStateFlow<List<ForkManifestCheck.Accepted>>(emptyList())

    /** From [ForkManifestCheck.of]: [a] plays. */
    fun accepted(a: ForkManifestCheck.Accepted) {
        known.update { was -> (listOf(a) + was.filter { it.url != a.url }).take(KEEP) }
    }

    /** The media playlist to serve in place of the master at [url], or null. */
    internal fun pickFor(url: String): String? = known.value.firstOrNull { it.url == url }?.pick

    /** Whether [url] is an accepted addon MPD. */
    internal fun isMpd(url: String): Boolean = known.value.any { it.url == url && it.mpd }

    /** Whether [url] is on a host an accepted manifest is on. */
    internal fun covers(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return known.value.any { host in it.hosts }
    }

    /** Tests. */
    internal fun clear() {
        known.value = emptyList()
    }

    /** A redirect from [from] to [location] may be followed: `https` to a host that isn't this phone. */
    internal fun hopAllowed(from: HttpUrl, location: String?): Boolean {
        val to = location?.let(from::resolve) ?: return false
        return ForkManifestCheck.linkAllowed(to.toString())
    }

    /**
     * An addon request's redirect hop judged before it is followed ([hopAllowed]: https, not this phone; protocol.md §1
     * Redirects, 2026-11-27, the phone's B2-04): the addon client's search and /stream followed any hop.
     */
    val redirectGuard = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.isRedirect && !hopAllowed(request.url, response.header("Location"))) {
            response.close()
            throw IOException("refused: a redirect off https or to this machine")
        }
        response
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(Http.client.connectionPool)
            .dns(ForkManifestCheck.noThisMachine)
            .connectTimeout(ForkManifestCheck.TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(ForkManifestCheck.TIMEOUT_SEC, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (!ForkManifestCheck.linkAllowed(request.url.toString())) throw IOException("refused: off https or to this machine")
                val response = chain.proceed(request)
                if (response.isRedirect && !hopAllowed(request.url, response.header("Location"))) {
                    response.close()
                    throw IOException("refused: a redirect off https or to this machine")
                }
                response
            }
            .build()
    }

    /** [upstream], except for the reads of an accepted manifest's hosts (see [ForkManifestPlay]). */
    fun route(upstream: DataSource): DataSource = Routed(upstream) { OkHttpDataSource.Factory(client).createDataSource() }

    internal class Routed(private val upstream: DataSource, ownOf: () -> DataSource) : DataSource {
        private val own by lazy(ownOf)
        private val listeners = ArrayList<TransferListener>()
        private var current: DataSource? = null
        private var served: ByteArray? = null
        private var servedAt = 0
        private var servedUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
            listeners += transferListener
        }

        private fun ownSource(): DataSource = own.also { o -> listeners.forEach(o::addTransferListener); listeners.clear() }

        override fun open(dataSpec: DataSpec): Long {
            val url = dataSpec.uri.toString()
            if (!covers(url)) {
                current = upstream
                return upstream.open(dataSpec)
            }
            val source = ownSource()
            if (isMpd(url) && dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) {
                source.open(dataSpec)
                val bytes = try {
                    readAll(source)
                } finally {
                    servedUri = source.uri
                    source.close()
                }
                val text = String(bytes, Charsets.UTF_8)
                served = (keepOne(text, com.music.bitchord.data.sources.DeviceCodecs.atmosWanted) ?: text).toByteArray(Charsets.UTF_8)
                servedAt = 0
                current = null
                return served!!.size.toLong()
            }
            current = source
            return source.open(pickFor(url)?.let { dataSpec.withUri(Uri.parse(it)) } ?: dataSpec)
        }

        private fun readAll(source: DataSource): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
                if (out.size() > MAX_MANIFEST_BYTES) throw IOException("a manifest over ${MAX_MANIFEST_BYTES / 1024} KB")
            }
            return out.toByteArray()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            served?.let { bytes ->
                if (length == 0) return 0
                if (servedAt >= bytes.size) return C.RESULT_END_OF_INPUT
                val n = minOf(length, bytes.size - servedAt)
                System.arraycopy(bytes, servedAt, buffer, offset, n)
                servedAt += n
                return n
            }
            return current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }

        override fun getUri(): Uri? = if (served != null) servedUri else current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = if (served != null) emptyMap() else current?.responseHeaders ?: emptyMap()

        override fun close() {
            served = null
            servedUri = null
            val c = current
            current = null
            c?.close()
        }
    }

    // ── DASH: one audio Representation ────────────────────────────────

    private val DOLBY = listOf("ac-3", "ec-3", "ec+3", "ac-4")
    private val VIDEO = listOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "vp08", "vp8", "vp09", "vp9", "av01", "mp4v")

    /**
     * [mpd] with only the audio Representation the PC would join (`ForkAddonStreamAssemble.dashPlan`:
     * the highest `bandwidth` that isn't Dolby; of all of them when [dolbyOk]) and its AdaptationSet
     * left in its one Period; null when it can't be read or nothing fits (it is then served as it is:
     * the check let it through, and Media3 decides as before).
     */
    internal fun keepOne(mpd: String, dolbyOk: Boolean): String? = runCatching {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }.newDocumentBuilder().parse(ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        if (local(root) != "MPD") return@runCatching null
        val period = children(root).singleOrNull { local(it) == "Period" } ?: return@runCatching null
        class Candidate(val set: Element, val rep: Element, val bandwidth: Long, val dolby: Boolean)
        val candidates = ArrayList<Candidate>()
        for (set in children(period).filter { local(it) == "AdaptationSet" }) {
            for (rep in children(set).filter { local(it) == "Representation" }) {
                val mime = rep.getAttribute("mimeType").ifEmpty { set.getAttribute("mimeType") }.lowercase(Locale.ROOT)
                val content = set.getAttribute("contentType").lowercase(Locale.ROOT)
                val codecs = rep.getAttribute("codecs").ifEmpty { set.getAttribute("codecs") }.lowercase(Locale.ROOT)
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val audio = mime.startsWith("audio") || content == "audio" ||
                    (mime.isEmpty() && content.isEmpty() && codecs.isNotEmpty() && codecs.none { c -> VIDEO.any(c::startsWith) })
                if (!audio) continue
                val dolby = codecs.isNotEmpty() && codecs.all { c -> DOLBY.any(c::startsWith) }
                candidates += Candidate(set, rep, rep.getAttribute("bandwidth").toLongOrNull() ?: 0L, dolby)
            }
        }
        val best = candidates.filter { dolbyOk || !it.dolby }.maxByOrNull { it.bandwidth } ?: return@runCatching null
        for (set in children(period).filter { local(it) == "AdaptationSet" }) {
            if (set !== best.set) period.removeChild(set)
        }
        for (rep in children(best.set).filter { local(it) == "Representation" }) {
            if (rep !== best.rep) best.set.removeChild(rep)
        }
        val out = java.io.StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(doc), StreamResult(out))
        out.toString()
    }.getOrNull()

    private fun local(e: Element) = e.localName ?: e.tagName.substringAfter(':')

    private fun children(e: Element): List<Element> {
        val nodes = e.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
}
