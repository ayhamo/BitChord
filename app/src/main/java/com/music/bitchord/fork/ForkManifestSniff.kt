package com.music.bitchord.fork

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.Http
import com.music.bitchord.data.sources.ForkAddonRefusals
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fork: the addon rulebook's two kinds of HLS/DASH answer on the phone (protocol.md 8.1.5 rule 5 and
 * rule 6; the owner's go-ahead 2026-11-26).
 *
 *  1. **Announced** as HLS or DASH (its transport: `manifest`/`mediaType`/`format`/`mimeType`, which
 *     `AddonSource` hands to `StreamContainer.declare`, or a `.m3u8`/`.mpd` path): it is played, from
 *     its own URL, through Media3's HLS/DASH sources, never through the audio cache. Until 2026-11-26
 *     `ForkNextRowPhone.refusesManifest` refused every such addon answer, so the phone never played an
 *     addon's HLS or DASH stream at all. Upstream's machinery plays it: the live race starts on
 *     YouTube and `QualityUpgrade`'s swap declares the type; a song with nothing else to start on
 *     goes out as it is and `PlaybackService.replayAsManifest` reopens it declared. What this adds is
 *     that the first, progressive open of such a URL ([refuseAnnouncedAsFile]) fails before a byte is
 *     read, with the same error a progressive extractor raises on manifest bytes, so
 *     `replayAsManifest` still reopens it but nothing is written to the cache first (it used to write
 *     the playlist under the song's key and rely on the discard). Before it wins the race its playlist
 *     or manifest is read once and checked against the extension's refusals ([ForkManifestCheck]).
 *  2. **Announced as a file but a manifest on the wire** (Content-Type `mpegurl`/`dash+xml`, or first
 *     bytes `#EXTM3U` / `<MPD`, [ForkAddonRefusals.isManifest]): refused like a bad fit (8.1.4), never
 *     played, never cached, never replayed from its URL as audio, and the next row opens. The race
 *     reads the answer's first [HEAD_BYTES] ([headOf], from `ForkNextRowPhone.refusesManifest`), one
 *     small ranged request per addon answer not announced as a manifest. Upstream played the bytes
 *     through a progressive source, which failed and dropped the song to YouTube for the session.
 *
 * Behind the race, two content guards on the player's own reads, for a check the race couldn't make
 * (the head request failed, or a path that doesn't go through the race): [below] sits under the
 * cache, on the real stream, so manifest bytes never reach a cache write; [above] sits over it and
 * drops an entry written before this build that holds a manifest (the Desktop's
 * `ForkAudioRangeCache` guard, on write and on read; audit 2026-11-24 2b #1, 2026-11-26 2b #5).
 * Either one fails the open with the progressive extractor's own error.
 *
 * Module and catalogue HLS (Tidal's DASH, say) is case 1 by its URL or declaration and plays as before.
 */
object ForkManifestSniff {

    /** How much of an answer is read to tell (what [ForkAddonRefusals.isManifest] reads). */
    const val HEAD_BYTES = 512

    private const val HEAD_TIMEOUT_SEC = 4L

    /** `DataSpec.customData` on a resolved stream opened for a progressive (`bitchord://`) item. */
    internal object ProgressiveOpen

    /** What an answer starts with: its first bytes and its Content-Type. */
    /**
     * What an answer starts with: its first bytes and its Content-Type. [blocked]: the link redirected off https or to
     * this phone, so the probe stopped there; the row is refused (`ForkNextRowPhone.refusesManifest`) rather than handed
     * to the player, whose network stack would follow the same hop itself (combined audit 2026-11-26 follow-up).
     */
    class Head(val bytes: ByteArray?, val mime: String?, val blocked: Boolean = false)

    /** A redirect hop [ForkManifestPlay.hopAllowed] refused. */
    internal class BlockedHop : java.io.IOException("refused: a redirect off https or to this phone")

    // ── The race ─────────────────────────────────────────────────────────

    /**
     * Each redirect hop is judged before it is followed ([ForkManifestPlay.hopAllowed]: https, not this phone), so the
     * head request can't carry the answer's headers to plain http or a service on the phone (the extension's M8, the
     * combined audit 2026-11-26 step 2); a refused hop gives a [Head.blocked] head and the row is refused.
     */
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(HEAD_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (response.isRedirect && !ForkManifestPlay.hopAllowed(request.url, response.header("Location"))) {
                    response.close()
                    throw BlockedHop()
                }
                response
            }
            .build()
    }

    /**
     * [stream]'s first [HEAD_BYTES] and Content-Type, by one ranged GET with the stream's own headers;
     * null when the server didn't answer with content (then nothing is judged and the player's guards
     * stand behind).
     */
    suspend fun headOf(stream: SourceStream): Head? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(stream.url)
                .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=0-${HEAD_BYTES - 1}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) return@use null
                val bytes = response.body.byteStream().use { input -> readHead(input::read) }
                Head(bytes, response.header("Content-Type"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BlockedHop) {
            Head(null, null, blocked = true)
        } catch (e: Exception) {
            null
        }
    }

    /** Case 2: an answer not [announced] as a manifest whose [head] says it is one. */
    fun isUnannouncedManifest(announced: Boolean, head: Head?): Boolean =
        !announced && head != null && ForkAddonRefusals.isManifest(head.bytes, head.mime)

    /** Up to [HEAD_BYTES] from [read] (`read(buffer, offset, length)`, -1 at the end). */
    internal fun readHead(read: (ByteArray, Int, Int) -> Int): ByteArray {
        val buf = ByteArray(HEAD_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    // ── The player ───────────────────────────────────────────────────────

    /** Whether an open of [uri] is a progressive item's (`bitchord://`, not a declared manifest open). */
    fun progressiveOpen(uri: String): Boolean = uri.startsWith("bitchord:") && !ForkManifestOpen.skipsCache(uri)

    /** The progressive extractor's own error for manifest bytes, so `replayAsManifest` and the recovery read it alike. */
    private fun notAudio(what: String): ParserException =
        ParserException.createForUnsupportedContainerFeature("$what (ForkManifestSniff, rulebook 8.1.5/8.1.6)")

    /**
     * From the resolver seam (`PlaybackService`'s resolving factory), after `StreamContainer.served`:
     * a progressive item resolved to an announced manifest fails here, before the open reaches the
     * network or the cache, and `replayAsManifest` reopens it with its type declared.
     */
    fun refuseAnnouncedAsFile(openedUri: String, servedUrl: String) {
        if (progressiveOpen(openedUri) && StreamContainer.isManifest(servedUrl)) {
            throw notAudio("an HLS/DASH stream opened as a file; reopened with its type declared")
        }
    }

    /** [resolved] marked as a progressive item's stream when [original] is one, for [below]. */
    fun marked(original: DataSpec, resolved: DataSpec): DataSpec =
        if (resolved.customData == null && progressiveOpen(original.uri.toString())) {
            resolved.buildUpon().setCustomData(ProgressiveOpen).build()
        } else {
            resolved
        }

    /** Under the cache: a progressive item's stream that turns out to be a manifest fails before a byte is cached. */
    fun below(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        // an accepted addon manifest's reads go through ForkManifestPlay (its pick, its redirects judged); the rest as before
        Guard(ForkManifestPlay.route(upstream.createDataSource()), sniffs = { it.customData === ProgressiveOpen && it.position == 0L }) {
            throw notAudio("a playlist or manifest where audio was announced; not played, not cached")
        }
    }

    /** Over the cache: an entry that holds a manifest (written before this build) is dropped, not served. */
    fun above(cached: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        Guard(cached.createDataSource(), sniffs = { it.position == 0L && progressiveOpen(it.uri.toString()) }) { spec ->
            runCatching { AudioCache.discard(spec.uri) }
            throw notAudio("a playlist or manifest in the audio cache; dropped")
        }
    }

    /**
     * A [DataSource] that, when [sniffs] says so, reads the first [HEAD_BYTES] on open, hands them to
     * [onManifest] (which throws) when they or the Content-Type are a manifest, and otherwise serves
     * them back before reading on. Nothing reaches the caller before the verdict.
     */
    internal class Guard(
        private val upstream: DataSource,
        private val sniffs: (DataSpec) -> Boolean,
        private val onManifest: (DataSpec) -> Unit,
    ) : DataSource {
        private val held = Held()

        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            held.clear()
            val length = upstream.open(dataSpec)
            if (!sniffs(dataSpec)) return length
            val head = readHead { b, o, l -> upstream.read(b, o, l).let { if (it == C.RESULT_END_OF_INPUT) -1 else it } }
            val mime = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
            if (ForkAddonRefusals.isManifest(head, mime)) {
                runCatching { upstream.close() }
                onManifest(dataSpec)
            }
            held.hold(head)
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            held.read(buffer, offset, length) { b, o, l -> upstream.read(b, o, l) }

        override fun getUri() = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            held.clear()
            upstream.close()
        }
    }

    /** The head a [Guard] read, served back first. */
    internal class Held {
        private var bytes = ByteArray(0)
        private var at = 0

        fun hold(head: ByteArray) {
            bytes = head
            at = 0
        }

        fun clear() = hold(ByteArray(0))

        fun read(buffer: ByteArray, offset: Int, length: Int, rest: (ByteArray, Int, Int) -> Int): Int {
            if (length == 0) return 0
            if (at < bytes.size) {
                val n = minOf(length, bytes.size - at)
                System.arraycopy(bytes, at, buffer, offset, n)
                at += n
                return n
            }
            return rest(buffer, offset, length)
        }
    }
}
