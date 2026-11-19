package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fork: a Canvas cut from the song's own music video — the canvas rulebook's first source (§1,
 * §5-§7), Metrolist's pipeline with Prism's picking, as the owner's phone extension runs it
 * (`VideoCanvas.java`):
 *
 *  1. the video: a YouTube Music video search for "title artist", then once more with "official
 *     video", the first result [ForkMusicVideoMatch] takes;
 *  2. the part: the storyboard (YouTube's preview sheets) read as 32 x 18 grey grids and judged
 *     ([ForkClipJudge.judge]: bars, a still upload, the best 8 s window near the "Most replayed"
 *     peak [ForkBestPart] finds, the crop); with no storyboard, the middle 8 s;
 *  3. the end: a small 240p copy of those seconds read frame by frame ([ForkClipJudge.end]: the
 *     last cut 5 to 9 s in, never a flashing part; one other part is tried before giving up);
 *  4. the loop: only the DASH fragments holding it are fetched (YouTube's visionOS client hands out
 *     stream URLs that need no signature solving) and remuxed into a small MP4 that starts on a key
 *     frame, with its crop and bars beside it ([framing]).
 *
 * What needs a platform (decoding a sheet, remuxing, reading frames) comes in as a [Platform]: the
 * phone uses MediaCodec and MediaMuxer, the PC FFmpeg and Skia.
 *
 * The loop is a file, so it plays offline and its lookup is kept at any age ([ForkCanvasStore]):
 * cutting one costs a search, a player request, the storyboard sheets and two short downloads.
 */
object ForkVideoCanvas {

    private const val TAG = "ForkVideoCanvas"
    const val CLIP_SEC = 8.0
    /** Fewer preview frames than this can't tell a still from a moving video (Prism's ClipFinder). */
    private const val MIN_FRAMES = 8
    private const val WEB_NEXT = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val VISION = "https://www.youtube.com/youtubei/v1/"
    private const val VISION_VERSION = "1.02"
    private const val MUSIC_SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    /** The YouTube Music search's "Videos" filter. */
    private const val VIDEOS = "EgWKAQIQAWoMEA4QChADEAQQCRAF"
    /** The loops kept, newest first; older ones are deleted and cut again when their song comes back. */
    private const val KEEP_BYTES = 80L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var visitor: String? = null

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** What a platform does for the pipeline. */
    interface Platform {
        /** An image (a storyboard sheet: JPEG or WebP) as grey values 0..255, row by row, or null. */
        fun grey(bytes: ByteArray): Grey?

        /**
         * A plain MP4 of [raw]'s video track from the key frame at or before [startSec] to [endSec]
         * (seconds in [raw]), times from 0, written to [out]. Returns the first frame's time in [raw] (us).
         */
        fun remux(raw: File, out: File, startSec: Double, endSec: Double): Long

        /** A small video file's frames as [ForkClipJudge] grids, with their times (ms); empty when unreadable. */
        fun frames(file: File): Pair<List<IntArray>, LongArray>
    }

    class Grey(val width: Int, val height: Int, val values: IntArray)

    /** The loop's framing, saved beside it: where the crop sits (0..1) and the bars (shares of the frame). */
    data class Framing(val cropX: Float, val bars: FloatArray) {
        fun line(): String = String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f %.3f", cropX, bars[0], bars[1], bars[2], bars[3])

        companion object {
            fun parse(line: String?): Framing? = runCatching {
                val p = line!!.trim().split(' ').map { it.toFloat() }
                Framing(p[0], floatArrayOf(p[1], p[2], p[3], p[4]))
            }.getOrNull()
        }
    }

    /** The framing saved beside a loop at [clipPath] (".crop" for ".mp4"), or null. */
    fun framing(clipPath: String): Framing? {
        val path = if (clipPath.startsWith("file:")) runCatching { java.net.URI(clipPath).path }.getOrNull() ?: return null else clipPath
        if (!path.endsWith(".mp4")) return null
        val f = File(path.dropLast(4) + ".crop")
        return if (f.exists()) Framing.parse(runCatching { f.readText() }.getOrNull()) else null
    }

    /** What a cut came to. */
    sealed interface Result {
        /** The loop, an MP4 file. */
        data class Clip(val file: File) : Result

        /** The song has no usable music video (none, a still upload, all flashing): a miss to keep. */
        data object None : Result
    }

    /**
     * The loop for [songId] ([title] by [artist], [songMs] long, 0 unknown) in [dir], cut for art
     * about [artPx] wide. Throws when a request fails: that is not saved as "no video".
     */
    @Synchronized
    fun clip(songId: String, title: String, artist: String, songMs: Long, dir: File, artPx: Int, platform: Platform): Result {
        val t0 = System.currentTimeMillis()
        val video = findVideo(title, artist, Math.round(songMs / 1000.0)) ?: return Result.None
        val player = visionPlayer(video.id)
        val status = (player["playabilityStatus"] as? JsonObject)?.text("status")
        if (status != "OK") {
            Log.d(TAG, "${video.id} not playable ($status)")
            return if (status == "UNPLAYABLE" || status == "ERROR") Result.None else throw IOException("player: $status")
        }
        val want = if (artPx < 480) 360 else if (artPx < 900) 720 else 1080
        val streaming = player["streamingData"] as? JsonObject
        val format = pickFormat(streaming, want) ?: return Result.None.also { Log.d(TAG, "${video.id} has no H.264 DASH stream") }
        val seconds = ((player["videoDetails"] as? JsonObject)?.get("lengthSeconds") as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: 0.0
        dir.mkdirs()
        val out = File(dir, "mv_${safe(songId)}.mp4")

        var length = Math.round(seconds * 1000)
        val board = runCatching { board(player, length, platform) }.onFailure { Log.w(TAG, "storyboard not read: ${it.message}") }.getOrNull()
        if (board != null) length = maxOf(length, board.frames.size * board.intervalMs)
        if (length < ForkClipJudge.WINDOW_MS) return Result.None.also { Log.d(TAG, "${video.id} has no length") }

        val loop: ForkClipJudge.Loop
        var peak = -1L
        if (board == null || board.frames.size < MIN_FRAMES) {
            // rule 6.9: no preview frames, nothing to judge by: the middle 8 s (the curve is not used)
            val mid = ForkClipJudge.noFrames(length)
            loop = ForkClipJudge.Loop(mid[0], mid[1], length)
        } else {
            // the still check first: a still upload needs no "most replayed" curve
            if (ForkClipJudge.still(board.frames, ForkClipJudge.bars(board.frames))) {
                return Result.None.also { Log.d(TAG, "${video.id} is a still picture") }
            }
            peak = ForkBestPart.partStart(heatmap(video.id), length)
            // no chorus from synced lyrics yet: null (the rulebook's §12 lists it for both extensions too)
            loop = ForkClipJudge.judge(board.frames, board.intervalMs, length, peak.takeIf { it >= 0 }, null)
                ?: return Result.None.also { Log.d(TAG, "${video.id} has no window that moves") }
        }
        var start = maxOf(0.0, minOf(loop.startMs / 1000.0, length / 1000.0 - CLIP_SEC))
        Log.d(TAG, "${video.id} ${format.intOf("height")}p, part at ${"%.1f".format(Locale.ROOT, start)} s of ${length / 1000} s" +
            (if (peak < 0) "" else ", most replayed at ${peak / 1000} s") + ", crop ${loop.cropX}")

        // the second look, on real frames: end on a cut, never flashing; a part that flashes gives way
        // to the next other part once, and if that flashes too, no canvas
        val low = pickFormat(streaming, 240)
        var end = start + CLIP_SEC
        if (low != null) {
            for (attempt in 0 until 2) {
                val e = refine(low, start, dir, platform)
                if (e > 0) {
                    end = e
                    break
                }
                val next = loop.others.firstOrNull()
                if (e == 0.0 || attempt == 1 || next == null) {
                    if (e < 0) return Result.None.also { Log.d(TAG, "${video.id} flashes: not shown") }
                    break
                }
                start = next / 1000.0
                end = start + CLIP_SEC
            }
        }
        cut(format, start, end, dir, out, platform)
        File(dir, "mv_${safe(songId)}.crop").writeText(Framing(loop.cropX, loop.bars).line())
        trim(dir, keep = out)
        Log.d(TAG, "${out.length() / 1024} KB loop ready ${System.currentTimeMillis() - t0} ms after the lookup")
        return Result.Clip(out)
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Deletes the oldest loops (and their framing) past [KEEP_BYTES]; never [keep]. */
    private fun trim(dir: File, keep: File) {
        val loops = dir.listFiles { f -> f.name.startsWith("mv_") && f.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in loops) {
            total += f.length()
            if (total > KEEP_BYTES && f != keep) {
                f.delete()
                File(f.path.dropLast(4) + ".crop").delete()
            }
        }
    }

    // ---- the video (rule 5.1) -------------------------------------------------------------

    /** The song's music video, or null: a video search, then once more with "official video". */
    fun findVideo(title: String, artist: String, durationSec: Long): ForkMusicVideoMatch.Video? {
        val artists = ForkMusicVideoMatch.artists(artist)
        if (title.isBlank() || artists.isEmpty()) return null
        for (q in listOf("$title ${artists[0]}", "$title ${artists[0]} official video")) {
            val body = buildJsonObject {
                put("query", q)
                put("params", VIDEOS)
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "WEB_REMIX"); put("clientVersion", "1.20251101.01.00"); put("hl", "en"); put("gl", "US")
                    }
                }
            }
            val found = ArrayList<ForkMusicVideoMatch.Video>()
            results(post(MUSIC_SEARCH, body, "Mozilla/5.0", "https://music.youtube.com"), found)
            ForkMusicVideoMatch.pick(title, artists, durationSec, found)?.let { return it }
            Log.d(TAG, "none for '$q' among ${found.take(5).joinToString { "'${it.title}' by ${it.channel}" }}")
        }
        return null
    }

    /** Each search result's id, title, channel and length (the fixed column, else a subtitle run). */
    internal fun results(node: JsonElement?, out: MutableList<ForkMusicVideoMatch.Video>) {
        when (node) {
            is JsonObject -> {
                val r = node["musicResponsiveListItemRenderer"] as? JsonObject
                if (r == null) {
                    node.values.forEach { results(it, out) }
                    return
                }
                val id = (r["playlistItemData"] as? JsonObject)?.text("videoId").orEmpty()
                val columns = r["flexColumns"] as? JsonArray
                if (id.isEmpty() || columns == null || columns.size < 2) return
                val title = runs(column(columns, 0))
                val channel = ((column(columns, 1)?.get("runs") as? JsonArray)?.firstOrNull() as? JsonObject)?.text("text").orEmpty()
                val fixed = ((r["fixedColumns"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("musicResponsiveListItemFixedColumnRenderer") as? JsonObject
                var length = ForkMusicVideoMatch.lengthOf(runs(fixed?.get("text") as? JsonObject))
                var i = 1
                while (length == 0L && i < columns.size) {
                    (column(columns, i)?.get("runs") as? JsonArray)?.forEach { run ->
                        val l = ForkMusicVideoMatch.lengthOf((run as? JsonObject)?.text("text"))
                        if (l > 0) length = l
                    }
                    i++
                }
                out += ForkMusicVideoMatch.Video(id, title, channel, length)
            }
            is JsonArray -> node.forEach { results(it, out) }
            else -> Unit
        }
    }

    private fun column(columns: JsonArray, i: Int): JsonObject? =
        ((columns.getOrNull(i) as? JsonObject)?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject)?.get("text") as? JsonObject

    private fun runs(text: JsonObject?): String {
        if (text == null) return ""
        val r = text["runs"] as? JsonArray ?: return text.text("simpleText").orEmpty()
        return r.joinToString("") { (it as? JsonObject)?.text("text").orEmpty() }
    }

    // ---- the curve (rule 6.1) -------------------------------------------------------------

    private val heats = object : LinkedHashMap<String, List<ForkBestPart.Marker>?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ForkBestPart.Marker>?>) = size > 20
    }

    /** YouTube's "Most replayed" markers for [videoId], or null (none, or not read). */
    fun heatmap(videoId: String): List<ForkBestPart.Marker>? {
        synchronized(heats) { if (heats.containsKey(videoId)) return heats[videoId] }
        return runCatching {
            val body = buildJsonObject {
                put("videoId", videoId)
                putJsonObject("context") {
                    putJsonObject("client") { put("clientName", "WEB"); put("clientVersion", "2.20251101.01.00"); put("hl", "en"); put("gl", "US") }
                }
            }
            ForkBestPart.heatmapOf(post(WEB_NEXT, body, "Mozilla/5.0", "https://www.youtube.com"))
                .also { h -> synchronized(heats) { heats[videoId] = h } } // an answer without a curve is kept; a failed fetch is not
        }.onFailure { Log.w(TAG, "no most-replayed curve: ${it.message}") }.getOrNull()
    }

    // ---- the storyboard (rules 7.1 - 7.8) -------------------------------------------------

    class Board(val frames: List<IntArray>, val intervalMs: Long)

    /** One storyboard level: the sheet URL pattern and its frame layout. */
    data class Storyboard(
        val urlPattern: String, val level: Int, val width: Int, val height: Int, val count: Int,
        val cols: Int, val rows: Int, val intervalMs: Long, val name: String, val sigh: String,
    ) {
        val perSheet: Int get() = cols * rows
        val sheets: Int get() = (count + perSheet - 1) / perSheet

        /** Sheet [n]'s URL. */
        fun sheet(n: Int): String =
            urlPattern.replace("\$L", level.toString()).replace("\$N", name.replace("\$M", n.toString())) +
                "&sigh=" + sigh.replace("$", "%24")
    }

    /** The largest level of a `playerStoryboardSpecRenderer.spec` ("url|w#h#count#cols#rows#interval#name#sigh|…"). */
    fun storyboard(spec: String?, lengthMs: Long): Storyboard? {
        val parts = spec?.split('|') ?: return null
        if (parts.size < 2) return null
        val f = parts.last().split('#')
        if (f.size < 8) return null
        val w = f[0].toIntOrNull() ?: return null
        val h = f[1].toIntOrNull() ?: return null
        val count = f[2].toIntOrNull() ?: return null
        val cols = f[3].toIntOrNull() ?: return null
        val rows = f[4].toIntOrNull() ?: return null
        var interval = f[5].toLongOrNull() ?: return null
        if (interval <= 0) interval = if (lengthMs > 0 && count > 0) lengthMs / count else 0
        if (w <= 0 || h <= 0 || count <= 0 || cols <= 0 || rows <= 0 || interval <= 0) return null
        return Storyboard(parts[0], parts.size - 2, w, h, count, cols, rows, interval, f[6], f[7])
    }

    private fun board(player: JsonObject, lengthMs: Long, platform: Platform): Board? {
        val spec = ((player["storyboards"] as? JsonObject)?.get("playerStoryboardSpecRenderer") as? JsonObject)?.text("spec")
        val sb = storyboard(spec, lengthMs) ?: return null
        val frames = ArrayList<IntArray>()
        var bytes = 0L
        var n = 0
        while (n < sb.sheets && bytes < 2_000_000) {
            val img = getBytes(sb.sheet(n), null) ?: break
            bytes += img.size
            val grey = platform.grey(img) ?: break
            for (i in 0 until sb.perSheet) {
                if (n * sb.perSheet + i >= sb.count) break
                val x0 = (i % sb.cols) * sb.width
                val y0 = (i / sb.cols) * sb.height
                if (x0 + sb.width > grey.width || y0 + sb.height > grey.height) break
                frames += ForkClipJudge.grid(grey.values, grey.width, x0, y0, sb.width, sb.height)
            }
            n++
        }
        return if (frames.size < 3) null else Board(frames, sb.intervalMs)
    }

    /**
     * The loop's end (video seconds; on a cut 5 to 9 s in, else 8 s) from a small copy of the same
     * seconds read frame by frame; -1 when it flashes, 0 when the frames could not be read.
     */
    private fun refine(low: JsonObject, start: Double, dir: File, platform: Platform): Double {
        val tmp = File(dir, "judge.mp4")
        return try {
            val key = cut(low, start, start + CLIP_SEC + 2, dir, tmp, platform)
            val (frames, times) = platform.frames(tmp)
            if (frames.size < 10) return 0.0
            val step = (times.last() - times.first()) / maxOf(1, times.size - 1)
            val end = ForkClipJudge.end(frames, times, times.last() + step)
            Log.d(TAG, "real frames: ${frames.size} from $key s, loop ${if (end < 0) "flashes" else "$end ms"}")
            if (end < 0) -1.0 else key + end / 1000.0
        } catch (t: Throwable) {
            Log.w(TAG, "real frames not read: ${t.message}")
            0.0
        } finally {
            tmp.delete()
        }
    }

    // ---- the stream (rule 7.12) -----------------------------------------------------------

    /** The video-only H.264 DASH stream nearest below [want] px tall, at most 30 fps. */
    fun pickFormat(streaming: JsonObject?, want: Int): JsonObject? {
        val formats = streaming?.get("adaptiveFormats") as? JsonArray ?: return null
        var best: JsonObject? = null
        for (e in formats) {
            val f = e as? JsonObject ?: continue
            if (f.text("mimeType")?.startsWith("video/mp4; codecs=\"avc1") != true || f.text("url").isNullOrEmpty() ||
                f["initRange"] !is JsonObject || f["indexRange"] !is JsonObject || (f.intOf("fps").takeIf { it > 0 } ?: 30) > 30
            ) continue
            val h = f.intOf("height")
            val b = best
            if (b == null) best = f
            else {
                val bh = b.intOf("height")
                val better = if (h <= want) (bh > want || h > bh) else (bh > want && h < bh)
                if (better) best = f
            }
        }
        return best
    }

    /**
     * Fetches the fragments holding [start]..[end] (video seconds) and writes them as one MP4
     * starting at 0 in [out]; returns the video time (s) of its first frame, the key frame at or
     * before [start] (rule 7.12).
     */
    private fun cut(f: JsonObject, start: Double, end: Double, dir: File, out: File, platform: Platform): Double {
        val url = f.text("url") ?: throw IOException("no url")
        val initEnd = (f["initRange"] as JsonObject).text("end")!!.toLong()
        val indexStart = (f["indexRange"] as JsonObject).text("start")!!.toLong()
        val indexEnd = (f["indexRange"] as JsonObject).text("end")!!.toLong()
        val head = getRange(url, 0, indexEnd)
        val sidx = Sidx.parse(head, indexStart.toInt()) ?: throw IOException("no sidx")
        val range = sidx.range(start, end - start, indexEnd + 1)
        val body = getRange(url, range[0], range[1])
        val raw = File(dir, out.name + ".part")
        try {
            raw.outputStream().use { o ->
                o.write(head, 0, (initEnd + 1).toInt())
                o.write(body)
            }
            val base = range[2] / 1e6
            return base + platform.remux(raw, out, start - base, end - base) / 1e6
        } finally {
            raw.delete()
        }
    }

    /** One segment index box: where each fragment's bytes and time are. */
    class Sidx(val timescale: Long, val earliest: Long, val sizes: LongArray, val durations: LongArray) {
        companion object {
            fun parse(b: ByteArray, at: Int): Sidx? {
                val bb = ByteBuffer.wrap(b)
                if (at < 0 || at + 8 > b.size || bb.getInt(at + 4) != 0x73696478) return null // 'sidx'
                var p = at + 8
                val version = b[p].toInt() and 0xFF
                p += 8 // version/flags, reference id
                val timescale = bb.getInt(p).toLong() and 0xFFFFFFFFL
                p += 4
                val earliest: Long
                val firstOffset: Long
                if (version == 0) {
                    earliest = bb.getInt(p).toLong() and 0xFFFFFFFFL
                    firstOffset = bb.getInt(p + 4).toLong() and 0xFFFFFFFFL
                    p += 8
                } else {
                    earliest = bb.getLong(p)
                    firstOffset = bb.getLong(p + 8)
                    p += 16
                }
                if (firstOffset != 0L) return null
                p += 2
                val count = bb.getShort(p).toInt() and 0xFFFF
                p += 2
                val sizes = LongArray(count)
                val durations = LongArray(count)
                for (i in 0 until count) {
                    sizes[i] = bb.getInt(p).toLong() and 0x7FFFFFFFL
                    durations[i] = bb.getInt(p + 4).toLong() and 0xFFFFFFFFL
                    p += 12
                }
                return Sidx(timescale, earliest, sizes, durations)
            }
        }

        /** The byte range {from, to} (inclusive) of the fragments covering [start, start + len], fragments from [base], and the first one's time (us). */
        fun range(start: Double, len: Double, base: Long): LongArray {
            var off = base
            var t = earliest
            var from = -1L
            var to = -1L
            var fromTime = earliest
            for (i in sizes.indices) {
                val a = t / timescale.toDouble()
                val z = (t + durations[i]) / timescale.toDouble()
                if (z > start && a < start + len) {
                    if (from < 0) {
                        from = off
                        fromTime = t
                    }
                    to = off + sizes[i] - 1
                }
                off += sizes[i]
                t += durations[i]
            }
            if (from < 0) {
                from = base
                to = base + sizes[0] - 1
            }
            return longArrayOf(from, to, Math.round(fromTime * 1e6 / timescale))
        }
    }

    // ---- requests -------------------------------------------------------------------------

    private fun visionUserAgent() = "com.google.visionos.youtube/$VISION_VERSION(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)"

    private fun getRange(url: String, from: Long, to: Long): ByteArray =
        getBytes("$url&range=$from-$to", visionUserAgent()) ?: throw IOException("no bytes $from-$to")

    private fun getBytes(url: String, ua: String?): ByteArray? {
        val request = Request.Builder().url(url).apply { if (ua != null) header("User-Agent", ua) }.build()
        client.newCall(request).execute().use { response ->
            if (response.code >= 400) throw IOException("HTTP ${response.code}")
            return response.body?.bytes()
        }
    }

    /** The video's player answer as YouTube's visionOS app (stream URLs need no signature solving), as Prism asks it. */
    private fun visionPlayer(videoId: String): JsonObject {
        fun ask(): JsonObject = visionPost("player", buildJsonObject {
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
        }, visitor())
        var r = ask()
        val ps = r["playabilityStatus"] as? JsonObject
        if (ps?.text("status") == "LOGIN_REQUIRED" && ps.text("reason")?.lowercase(Locale.ROOT)?.contains("bot") == true) {
            visitor = null
            r = ask()
        }
        return r
    }

    private fun visitor(): String {
        visitor?.let { return it }
        val r = visionPost("visitor_id", buildJsonObject { }, null)
        return ((r["responseContext"] as? JsonObject)?.text("visitorData") ?: throw IOException("no visitor id")).also { visitor = it }
    }

    private fun visionPost(endpoint: String, body: JsonObject, visitor: String?): JsonObject {
        val full = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "VISIONOS"); put("clientVersion", VISION_VERSION); put("clientScreen", "WATCH")
                    put("platform", "MOBILE"); put("deviceMake", "Apple"); put("deviceModel", "RealityDevice14,1")
                    put("osName", "visionOS"); put("osVersion", "25.6.0.23O471"); put("hl", "en"); put("gl", "US")
                    put("utcOffsetMinutes", 0)
                    if (visitor != null) put("visitorData", visitor)
                }
            }
        }
        val request = Request.Builder().url("$VISION$endpoint?prettyPrint=false")
            .post(full.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", visionUserAgent())
            .header("X-Goog-Api-Format-Version", "2")
            .header("X-YouTube-Client-Name", "101")
            .header("X-YouTube-Client-Version", VISION_VERSION)
            .apply { if (visitor != null) header("X-Goog-Visitor-Id", visitor) }
            .build()
        return send(request)
    }

    private fun post(url: String, body: JsonObject, ua: String, origin: String): JsonObject = send(
        Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", ua).header("Origin", origin)
            .build(),
    )

    private fun send(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        if (response.code >= 400) throw IOException("HTTP ${response.code}")
        json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject ?: throw IOException("not JSON")
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.intOf(key: String): Int = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0
}
