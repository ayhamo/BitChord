package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ForkAddonRefusals
import com.music.bitchord.data.sources.ForkThisMachine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Fork: the addon rulebook's 8.1.4 "a refusal doesn't end the race" (YT Music Extension
 * `spec/addons/protocol.md`, baa5e1d) on the PC. The rules are [ForkAddonRefusals]'s, shared with the
 * phone.
 *
 * Before this, a copy the window check refused ([ForkWindowCheckPc]) left the song on what plays
 * until the engine's next follow-up search (two at most), and that search opened the same row again:
 * the same row answers first every time, so a refused row stayed the answer.
 *
 * Now the refused row is passed over for the song (`openBest` leaves it out of the rows it opens,
 * [left]; the same addon's other rows and the other sources still count) and the second look is asked
 * again at once ([next]) while what plays keeps playing: at most [ForkAddonRefusals.NEXT_ROWS] more
 * copies per song, then what plays stays. A song here is its video id.
 */
internal object ForkNextRowPc {

    val book = ForkAddonRefusals.Book()

    /** Whether [song] used up its next rows: `upgradeFor` then searches nothing (what plays stays). */
    fun exhausted(song: Song): Boolean = book.exhausted(song.videoId)

    /**
     * "Upgrade quality" by hand (the engine's forced reload): [song]'s refusals go, as the rulebook's
     * 8.1.7 drops the song's ticket, so its rows can be opened again. Without this a song that used up
     * its [ForkAddonRefusals.NEXT_ROWS] got no addon copy until a restart.
     */
    fun forget(song: Song) = book.forget(song.videoId)

    /** `openBest`'s rows of [sourceId] for [song], less the ones refused for it. */
    fun left(song: Song, sourceId: String, rows: List<Song>): List<Song> =
        book.left(song.videoId, sourceId, rows) { it.videoId }

    /** `openBest` opened [url] from [sourceId]'s row [rowId]. */
    fun opened(url: String, sourceId: String, rowId: String) = book.opened(url, sourceId, rowId)

    /**
     * The HLS or DASH transport [stream] is announced as (protocol.md §5: its declared transport, or a
     * `.m3u8`/`.mpd` path), else null: a progressive file.
     */
    fun announcedTransport(stream: DesktopStream): String? {
        stream.transport?.lowercase()?.takeIf { it == DesktopAddonStream.HLS || it == DesktopAddonStream.DASH }?.let { return it }
        val path = stream.url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> DesktopAddonStream.HLS
            path.endsWith(".mpd") -> DesktopAddonStream.DASH
            else -> null
        }
    }

    /**
     * Rulebook 8.1.5 rule 6, case 1 (owner, 2026-11-26: "let all do it then, as addons can be
     * unexpected"): a row announced as HLS or DASH plays, never through an audio cache. Its transport
     * is made explicit (a `.m3u8`/`.mpd` path alone left it to FFmpeg's probe), and it is never read in
     * windows: [DesktopStream.windowedReads] is what sends a stream through [DesktopRangeStream] and
     * [ForkAudioRangeCache], and its Range probe (§7) is not made. `openBest` then puts it together
     * into one file ([assembles], rule 5, as the extension's PC does since 3dce391), which every deck
     * and check reads in the URL's place, as a progressive file. Only when that file has since been
     * dropped does a late reader fall back to FFmpeg's own HLS/DASH demuxers with this transport.
     * Before 2026-11-26 [refusesManifest] refused these rows as case 2.
     */
    fun asAnnounced(stream: DesktopStream): DesktopStream {
        val transport = announcedTransport(stream) ?: return stream
        return stream.copy(transport = transport, windowedReads = false)
    }

    /**
     * Rulebook 8.1.5 rule 5 for `openBest`'s row of [config], announced as HLS or DASH: its stream put
     * together into one file ([ForkAddonStreamAssemble], with only [stream]'s own headers) and kept for
     * [DesktopAudioDecoder.open] by [ForkAssembledStreams]; true. A stream it refuses (encrypted, live,
     * Dolby only, off https, over the limits, …) is refused like a bad fit (8.1.4): false, and
     * `openBest` goes on to the next row. Not an addon's row, or a progressive one: true, nothing done.
     * Fetched again on every resolve (rule 6.1: never cached).
     */
    suspend fun assembles(
        song: Song,
        config: DesktopSourceConfig,
        stream: DesktopStream,
        put: suspend (transport: String, file: java.io.File) -> String = { transport, file ->
            ForkAddonStreamAssemble.assemble(stream.url, transport, file, ForkAddonStreamAssemble.http(stream.headers))
        },
    ): Boolean {
        if (config.kind != DesktopSourceKind.ADDON) return true
        val transport = announcedTransport(stream) ?: return true
        val file = ForkAssembledStreams.newFile()
        ForkAddonCopyPc.fetching(song.videoId, stream) // the progress row's "Getting the lossless copy…" (editor.md §11 rule 6), back to asking after
        val why = try {
            val extension = put(transport, file)
            ForkAssembledStreams.put(stream.url, file)
            DesktopTrackLog.log("${DesktopMusicSources.sourceNameFor(stream)}'s ${transport.uppercase()} stream for '${song.title}' put together (${file.length() / 1024} KB, $extension)")
            return true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            file.delete()
            throw cancelled
        } catch (e: Exception) {
            file.delete()
            e.message ?: e.javaClass.simpleName
        } finally {
            ForkAddonCopyPc.assembled(song.videoId)
        }
        val verdict = book.refuse(song.videoId, stream.url)
        DesktopTrackLog.log(
            "${DesktopMusicSources.sourceNameFor(stream)}'s ${transport.uppercase()} stream for '${song.title}' is refused ($why; rulebook 8.1.5 rule 5); " +
                if (verdict.next) "the next row is asked (${verdict.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried",
        )
        return false
    }

    /** An addon `/stream` answer's first bytes and content type, for [refusesManifest]; null when they couldn't be read. */
    /**
     * [blocked]: the link redirected off https or to this machine, so the probe stopped there; the row is refused
     * ([refusesManifest]) rather than handed to the decoder, which would follow the same hop itself.
     */
    class Head(val bytes: ByteArray, val mime: String?, val blocked: Boolean = false)

    /**
     * `openBest`'s row of [config] answered [stream] for [song]. Rulebook 8.1.5 rule 6, case 2: a row
     * announced as a file ([announcedTransport] null) whose answer is an HLS playlist or a DASH
     * manifest, by its `Content-Type` or its first bytes ([ForkAddonRefusals.isManifest], read by
     * [probe]), is refused like a copy the fit refused (8.1.4): never played (FFmpeg's probe would have
     * opened it as HLS), never kept, and `openBest` goes on to the next row. A row announced as HLS or
     * DASH is case 1 and plays ([asAnnounced]); before 2026-11-26 it was refused here too. Not an
     * addon's row, or a head that couldn't be read: not refused (the decoder's own open decides).
     */
    suspend fun refusesManifest(
        song: Song,
        config: DesktopSourceConfig,
        stream: DesktopStream,
        probe: suspend (DesktopStream) -> Head? = { head(it) },
    ): Boolean {
        if (config.kind != DesktopSourceKind.ADDON || announcedTransport(stream) != null) return false
        val head = probe(stream) ?: return false
        if (head.blocked) {
            refused(song, stream, "its link redirects off https or to this machine, rulebook 8.1.5 rule 5")
            return true
        }
        val verdict = book.refuseManifest(song.videoId, stream.url, isAddon = true, isManifest = ForkAddonRefusals.isManifest(head.bytes, head.mime)) ?: return false
        DesktopTrackLog.log(
            "${DesktopMusicSources.sourceNameFor(stream)} answered a playlist or manifest for '${song.title}' under a file's row; refused (rulebook 8.1.5 rule 6), " +
                if (verdict.next) "the next row is asked (${verdict.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried",
        )
        return true
    }

    /**
     * The opening bytes of [stream] (a ranged GET of [HEAD_BYTES], its own headers sent) and its
     * content type. Awaited, so a race's cancelled lane gives the call up.
     *
     * Redirects are followed here by hand, at most [MAX_REDIRECTS], each hop https and not this
     * machine by any spelling ([ForkThisMachine]); one that isn't ends the probe with a [Head.blocked] head,
     * and [refusesManifest] refuses the row: left to the decoder's own open, FFmpeg followed the same hop
     * itself (combined audit 2026-11-26 follow-up). The shared download
     * client followed them itself, so an addon's link could send this probe, with the stream's own
     * headers, to `http://127.0.0.1` or any other service on the PC (combined audit 2026-11-26).
     * The addon's own link keeps its old rule (http or https): a local addon serves over plain
     * http, and the decoder opens that link anyway.
     */
    internal suspend fun head(stream: DesktopStream, client: okhttp3.OkHttpClient = probeClient): Head? {
        if (!stream.url.startsWith("https://") && !stream.url.startsWith("http://")) return null
        var target = stream.url
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (hop > 0 && !redirectAllowed(target)) {
                DesktopTrackLog.log("an addon link's probe stopped at a redirect off https or to this machine")
                return Head(ByteArray(0), null, blocked = true)
            }
            val request = DesktopDownloadHttp.request(target, stream.headers["User-Agent"] ?: HEAD_USER_AGENT, stream.headers).newBuilder()
                .header("Range", "bytes=0-${HEAD_BYTES - 1}")
                .build()
            when (val step = probeOnce(client, request)) {
                null -> return null
                is Step.Got -> return step.head
                is Step.Redirect -> target = step.location
            }
        }
        return null
    }

    private sealed interface Step {
        class Got(val head: Head?) : Step
        class Redirect(val location: String) : Step
    }

    /** One request of the probe: its head, a redirect's resolved target, or null when it failed. */
    private suspend fun probeOnce(client: okhttp3.OkHttpClient, request: okhttp3.Request): Step? = try {
        kotlinx.coroutines.suspendCancellableCoroutine<Step?> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val step = response.use {
                        when {
                            it.isRedirect -> it.header("Location")?.let { location -> request.url.resolve(location)?.toString() }?.let(Step::Redirect)
                            it.code !in 200..299 -> Step.Got(null)
                            else -> Step.Got(
                                runCatching {
                                    val source = it.body.source()
                                    source.request(HEAD_BYTES.toLong())
                                    Head(source.buffer.readByteArray(minOf(source.buffer.size, HEAD_BYTES.toLong())), it.header("Content-Type"))
                                }.getOrNull(),
                            )
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(step))
                }
            })
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** A redirect hop the probe follows: https, and not this machine. */
    internal fun redirectAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && !ForkThisMachine.isThisMachine(parsed.host)
    }

    /** The download client, redirects left to [head]. */
    private val probeClient: okhttp3.OkHttpClient by lazy {
        DesktopDownloadHttp.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    private const val MAX_REDIRECTS = 5

    /** As much as [ForkAddonRefusals.isManifest] reads. */
    private const val HEAD_BYTES = 512

    /** FFmpeg's own agent family when the addon gave none: the probe asks as the decoder will. */
    private const val HEAD_USER_AGENT = "Lavf/61"

    /** The window check refused the copy [stream] for [song] ([why] for the log). */
    fun refused(song: Song, stream: DesktopStream, why: String) {
        val verdict = book.refuse(song.videoId, stream.url)
        DesktopTrackLog.log(
            "the copy of '${song.title}' from ${DesktopMusicSources.sourceNameFor(stream)} is refused ($why); " +
                if (verdict.next) "the next row or source is asked (${verdict.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried; what plays stays",
        )
    }

    /**
     * After the window check said no to [refused]: when it was a refusal and copies are left to try,
     * [ask] (the second look again, the refused rows passed over), else null. Not for a song no
     * longer [current]: the window check takes seconds, and a song skipped meanwhile had every addon
     * searched again for nothing.
     */
    suspend fun next(song: Song, refused: DesktopStream, current: () -> Boolean = { true }, ask: suspend () -> DesktopStream?): DesktopStream? {
        if (!book.wasRefused(song.videoId, refused.url) || book.exhausted(song.videoId) || !current()) return null
        return ask().also { if (it == null) DesktopTrackLog.log("no other row or source has a copy of '${song.title}' that passes; what plays stays") }
    }
}
