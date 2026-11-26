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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
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

    /** The client for the loop's byte ranges: no call timeout, a long range may take a while on a slow line. */
    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The client for the JSON answers and storyboard sheets: [client] with a 20 s call timeout.
     * Audit 2026-11-26: only the connect and read timeouts were set, so a server that trickled a
     * byte every few seconds held a lookup (and the canvas waiting on it) without end.
     */
    private val shortClient by lazy { client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }

    /** One storyboard sheet's most (a real one is tens of KB) and the loop's fragment bytes' most. */
    private const val SHEET_MAX = 1 * com.music.bitchord.data.ForkCappedBody.MB
    private const val HEAD_MAX = 2 * com.music.bitchord.data.ForkCappedBody.MB
    internal const val BODY_MAX = 80L * 1024 * 1024

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

    /**
     * The loop's framing, saved beside it: where the crop sits (0..1) and the bars (shares of the
     * frame); and, since canvas rulebook batch 3, what the song menu needs of it (§15): the video it was
     * cut from, its part's start in that video (null: the whole video) and the runners-up. A file
     * written before has only the first five numbers.
     */
    data class Framing(
        val cropX: Float, val bars: FloatArray,
        val video: String? = null, val startMs: Long? = null, val others: List<Long> = emptyList(),
    ) {
        fun line(): String = String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f %.3f", cropX, bars[0], bars[1], bars[2], bars[3]) +
            (video?.let { " v=$it" } ?: "") + (startMs?.let { " s=$it" } ?: "") + (if (others.isEmpty()) "" else " o=" + others.joinToString(","))

        companion object {
            fun parse(line: String?): Framing? = runCatching {
                val all = line!!.trim().split(' ')
                val p = all.take(5).map { it.toFloat() }
                val extra = all.drop(5).mapNotNull { t -> t.indexOf('=').takeIf { it > 0 }?.let { t.substring(0, it) to t.substring(it + 1) } }.toMap()
                Framing(
                    p[0], floatArrayOf(p[1], p[2], p[3], p[4]),
                    video = extra["v"]?.takeIf { it.isNotBlank() },
                    startMs = extra["s"]?.toLongOrNull(),
                    others = extra["o"].orEmpty().split(',').mapNotNull { it.toLongOrNull() },
                )
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
        /**
         * The loop, an MP4 file, and the video it was cut from (null: not known), its part's start in
         * that video (null: the whole video) and the runners-up (§15 rule 3).
         */
        data class Clip(val file: File, val videoId: String? = null, val startMs: Long? = null, val others: List<Long> = emptyList()) : Result

        /** The song has no usable music video (none, a still upload, all flashing): a miss to keep. */
        data object None : Result
    }

    /**
     * What the listener asked for besides the song (fork, audit 2026-11-20 C 1-3 and 7): the height
     * the loop is cut at ([height], null: from [clip]'s `artPx`, canvas.md §13.6), the whole video
     * rather than a part ([full], §13.7), their choices for the song ([choice]: a picked video,
     * which part, the videos turned away), and the chorus from its synced lyrics ([chorusMs], §7.6).
     */
    data class Wish(
        val height: Int? = null,
        val full: Boolean = false,
        val choice: ForkCanvasChoices.Choice = ForkCanvasChoices.Choice(),
        val chorusMs: Long? = null,
        /** Part of the loop's file name, so each choice's loop is its own file. */
        val tag: String = "",
        /** Only the listener's picked video is tried (the menu's test before a pick is kept, §15 rule 6). */
        val onlyPicked: Boolean = false,
    )

    /** One video through the gate: its loop ([Pass]), or why not ([Refused]). */
    sealed interface Gated<out T> {
        data class Pass<T>(val value: T) : Gated<T>
        data class Refused(val why: ForkClipGate.Refusal) : Gated<Nothing>
    }

    /** What [findOn] came to: the loop ([value]) from [videoId], or [why] none; [otherSearches] run (0 or 1). */
    data class Found<T>(val value: T?, val videoId: String?, val why: ForkClipGate.Refusal?, val otherSearches: Int)

    /** Other videos tried after a refused first one (Prism's `OTHER_VIDEOS`, §5 rule 10). */
    const val OTHER_VIDEOS = 2

    /**
     * Canvas.md §5 rules 7-12, Prism's `ClipFinder.findOn` (`PR/canvas/ClipFinder.kt:104-136`), on any
     * network (the shared cases run it on a fake one, group `clipFind`):
     *
     *  1. not a song ([isSong]): nothing is looked up (`NOT_A_SONG`);
     *  2. a video the listener picked ([picked], §15 rule 6), as it is; with none passing, on to 3,
     *     except while a pick is being tried ([onlyPicked]): then its refusal is the answer;
     *  3. the search's first match ([first]) through the gate ([gate] with own = false: the official
     *     type, a title that doesn't say it is something else, playable, not a still);
     *  4. refused for its type or title ([ForkClipGate.SEARCH_AGAIN]): ONE more search ([otherSearch],
     *     "title artist official video", its matches by rules 2-5), leaving out [first] and any whose
     *     title says it is something else; up to [OTHER_VIDEOS] of them through the gate;
     *  5. none passing: [first] as it is (own = true: only what can't make a loop refuses it);
     *  6. no match at all: a lyric video by the artist ([lyricFallback], rule 12), as it is.
     *
     * A request that fails throws (the lookup is not saved, and asked again later).
     */
    fun <T> findOn(
        songTitle: String,
        isSong: Boolean,
        picked: ForkMusicVideoMatch.Video?,
        first: ForkMusicVideoMatch.Video?,
        otherSearch: () -> List<ForkMusicVideoMatch.Video>,
        lyricFallback: () -> ForkMusicVideoMatch.Video?,
        gate: (ForkMusicVideoMatch.Video, Boolean) -> Gated<T>,
        onlyPicked: Boolean = false,
    ): Found<T> {
        if (!isSong) return Found(null, null, ForkClipGate.Refusal.NOT_A_SONG, 0)
        if (onlyPicked && picked == null) return Found(null, null, ForkClipGate.Refusal.NO_VIDEO, 0)
        if (picked != null) {
            when (val p = gate(picked, true)) {
                is Gated.Pass -> return Found(p.value, picked.id, null, 0)
                is Gated.Refused -> if (onlyPicked) return Found(null, null, p.why, 0)
            }
            Log.d(TAG, "the picked video ${picked.id} makes no loop now; searching")
        }
        if (first == null) {
            val lyric = lyricFallback() ?: return Found(null, null, ForkClipGate.Refusal.NO_VIDEO, 0)
            Log.d(TAG, "no music video; the lyric video ${lyric.id} '${lyric.title}'")
            return when (val g = gate(lyric, true)) {
                is Gated.Pass -> Found(g.value, lyric.id, null, 0)
                is Gated.Refused -> Found(null, null, g.why, 0)
            }
        }
        val tried = gate(first, false)
        if (tried is Gated.Pass) return Found(tried.value, first.id, null, 0)
        val why = (tried as Gated.Refused).why
        if (why !in ForkClipGate.SEARCH_AGAIN) return Found(null, null, why, 0)
        val others = otherSearch().filter { it.id != first.id && !ForkClipGate.titleSays(songTitle, it.title) }.distinctBy { it.id }.take(OTHER_VIDEOS)
        for (other in others) {
            (gate(other, false) as? Gated.Pass)?.let { return Found(it.value, other.id, null, 1) }
        }
        // No better video: the song's own, whatever it is called or marked.
        Log.d(TAG, "${first.id} refused ($why), none better: taken as it is")
        return when (val own = gate(first, true)) {
            is Gated.Pass -> Found(own.value, first.id, null, 1)
            is Gated.Refused -> Found(null, null, own.why, 1)
        }
    }

    /** Loops just cut by [tryPicked], handed to the lookup that follows the pick (by file). */
    private val justCut = object : LinkedHashMap<String, Result.Clip>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result.Clip>) = size > 4
    }

    /**
     * The loop for [songId] ([title] by [artist], [songMs] long, 0 unknown) in [dir], cut for art
     * about [artPx] wide, as [wish] asks ([findOn]'s order). Throws when a request fails: that is not
     * saved as "no video".
     *
     * The listener's part ([ForkCanvasChoices.Choice.partMs]) asks its video first, as it is, and cuts
     * that part; else a video they picked. The search leaves out the videos they turned away.
     */
    @Synchronized
    fun clip(songId: String, title: String, artist: String, songMs: Long, dir: File, artPx: Int, platform: Platform, wish: Wish = Wish()): Result {
        val t0 = System.currentTimeMillis()
        val choice = wish.choice
        val rejected = choice.rejected
        val out = File(dir, "mv_${safe(songId)}${wish.tag}.mp4")
        synchronized(justCut) { justCut.remove(out.path) }?.takeIf { it.file.exists() }?.let { return it }
        val partVideo = choice.partVideo?.takeIf { choice.partMs != null && it !in rejected }
        val picked = (partVideo ?: choice.picked?.takeIf { it !in rejected })?.let { ForkMusicVideoMatch.Video(it, "", "", 0) }
        fun gate(v: ForkMusicVideoMatch.Video, own: Boolean): Gated<Result.Clip> =
            tryVideo(v, title, own, dir, out, artPx, platform, wish, startAt = choice.partMs.takeIf { v.id == partVideo })
        val durationSec = Math.round(songMs / 1000.0)
        val search = Search(title, artist, durationSec, rejected)
        val found = findOn(
            songTitle = title,
            isSong = true, // the caller looks up songs only (ForkClipGate.isSong)
            picked = picked,
            first = if (wish.onlyPicked) null else search.first(), // a pick being tried searches nothing
            otherSearch = search::others,
            lyricFallback = search::lyricVideo,
            gate = ::gate,
            onlyPicked = wish.onlyPicked,
        )
        val clip = found.value ?: return Result.None.also { Log.d(TAG, "no loop: ${found.why}") }
        Log.d(TAG, "${clip.file.length() / 1024} KB loop from ${found.videoId} ready ${System.currentTimeMillis() - t0} ms after the lookup")
        return clip
    }

    /**
     * "Pick another video" before it is kept (§15 rule 6): the picked video in [wish] alone, as it is,
     * cut now. A loop is handed to the lookup the pick then starts (no second cut); [Result.None]
     * when that video can't make a loop (the song keeps what it had). Throws when a request fails.
     */
    @Synchronized
    fun tryPicked(songId: String, title: String, songMs: Long, dir: File, artPx: Int, platform: Platform, wish: Wish): Result {
        val r = clip(songId, title, "", songMs, dir, artPx, platform, wish.copy(onlyPicked = true))
        if (r is Result.Clip) synchronized(justCut) { justCut[r.file.path] = r }
        return r
    }

    /**
     * "Not this video" (§15 rule 8): the loops of [songId] cut from [videoId], and their framing,
     * deleted (a loop whose framing doesn't say its video is left to [trim]).
     */
    fun forget(dir: File, songId: String, videoId: String) {
        val prefix = "mv_${safe(songId)}"
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".crop") }?.forEach { crop ->
            val framing = Framing.parse(runCatching { crop.readText() }.getOrNull())
            if (framing?.video == videoId) {
                File(crop.path.dropLast(5) + ".mp4").delete()
                crop.delete()
            }
        }
    }

    /** The runners-up kept beside a loop starting at [startMs]: §15 rule 3, at least this far from it. */
    private const val PART_APART_MS = 15_000L

    /**
     * One video through the gate and, if it passes, cut (canvas.md §5 rule 8: the type, then the
     * title, then playable; then §6-7). [own]: the song's own video with no better one, or one the
     * listener picked: its type and title are not held against it; what can't make a loop (not
     * playable, no stream, a still picture, flashing) still refuses it. [startAt]: the listener's part
     * (§15 rule 4), cut from there in place of the judged one.
     */
    private fun tryVideo(
        video: ForkMusicVideoMatch.Video, songTitle: String, own: Boolean, dir: File, out: File, artPx: Int, platform: Platform, wish: Wish,
        startAt: Long? = null,
    ): Gated<Result.Clip> {
        val id = video.id
        if (!own) {
            val type = video.type ?: videoType(id)
            if (!ForkClipGate.official(type)) {
                Log.d(TAG, "$id is not the official video ($type)")
                return Gated.Refused(ForkClipGate.Refusal.NOT_OFFICIAL)
            }
        }
        val player = visionPlayer(id)
        val details = player["videoDetails"] as? JsonObject
        val videoTitle = details?.text("title") ?: video.title
        if (!own && ForkClipGate.titleSays(songTitle, videoTitle)) {
            Log.d(TAG, "$id '$videoTitle' says it is not the music video")
            return Gated.Refused(ForkClipGate.Refusal.TITLE)
        }
        val playability = player["playabilityStatus"] as? JsonObject
        val status = playability?.text("status")
        if (status != "OK") {
            Log.d(TAG, "$id not playable ($status)")
            // YouTube taking the request for a bot is no answer about the video: asked again later.
            if (status == null || playability.text("reason")?.lowercase(Locale.ROOT)?.contains("bot") == true) throw IOException("player: $status")
            return Gated.Refused(ForkClipGate.Refusal.RESTRICTED)
        }
        val seconds = (details?.get("lengthSeconds") as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: 0.0
        if (!ForkClipGate.playable(status, details?.text("isPrivate") == "true", details?.text("isLive") == "true",
                details?.text("isLiveContent") == "true", seconds.toLong())
        ) {
            Log.d(TAG, "$id is live or private")
            return Gated.Refused(ForkClipGate.Refusal.RESTRICTED)
        }
        val want = wish.height ?: if (artPx < 480) 360 else if (artPx < 900) 720 else 1080
        val streaming = player["streamingData"] as? JsonObject
        val format = pickFormat(streaming, want)
            ?: return Gated.Refused(ForkClipGate.Refusal.NO_STREAM).also { Log.d(TAG, "$id has no H.264 DASH stream") }
        dir.mkdirs()

        var length = Math.round(seconds * 1000)
        val board = runCatching { board(player, length, platform) }.onFailure { Log.w(TAG, "storyboard not read: ${it.message}") }.getOrNull()
        if (board != null) length = maxOf(length, board.frames.size * board.intervalMs)
        if (length < ForkClipJudge.WINDOW_MS) return Gated.Refused(ForkClipGate.Refusal.NO_STREAM).also { Log.d(TAG, "$id has no length") }

        if (wish.full) {
            // §13.7 Full video: the whole video, muted; a still upload is still no canvas.
            val bars = if (board != null && board.frames.size >= MIN_FRAMES) ForkClipJudge.bars(board.frames) else FloatArray(4)
            if (board != null && board.frames.size >= MIN_FRAMES && ForkClipJudge.still(board.frames, bars)) {
                return Gated.Refused(ForkClipGate.Refusal.STILL).also { Log.d(TAG, "$id is a still picture") }
            }
            cut(format, 0.0, length / 1000.0, dir, out, platform)
            File(out.path.dropLast(4) + ".crop").writeText(Framing(0.5f, bars, video = id).line())
            trim(dir, keep = out)
            Log.d(TAG, "$id whole video at ${format.intOf("height")}p")
            return Gated.Pass(Result.Clip(out, id))
        }

        val loop: ForkClipJudge.Loop
        var peak = -1L
        if (board == null || board.frames.size < MIN_FRAMES) {
            // rule 6.9: no preview frames, nothing to judge by: the middle 8 s (the curve is not used)
            val mid = ForkClipJudge.noFrames(length)
            loop = ForkClipJudge.Loop(mid[0], mid[1], length)
        } else {
            // the still check first: a still upload needs no "most replayed" curve
            if (ForkClipJudge.still(board.frames, ForkClipJudge.bars(board.frames))) {
                return Gated.Refused(ForkClipGate.Refusal.STILL).also { Log.d(TAG, "$id is a still picture") }
            }
            peak = ForkBestPart.partStart(heatmap(id), length)
            // rule 7.6: the peak, else the chorus from the song's synced lyrics (the player's, when it had them)
            loop = ForkClipJudge.judge(board.frames, board.intervalMs, length, peak.takeIf { it >= 0 }, wish.chorusMs)
                ?: return Gated.Refused(ForkClipGate.Refusal.STILL).also { Log.d(TAG, "$id has no window that moves") }
        }
        // The judged part and its runners-up; the listener's part (§15 rule 4) in place of the judged one.
        val parts = (listOfNotNull(startAt) + loop.startMs + loop.others).distinct()
        var index = 0
        var start = maxOf(0.0, minOf(parts[index] / 1000.0, length / 1000.0 - CLIP_SEC))
        Log.d(TAG, "$id ${format.intOf("height")}p, ${if (startAt != null) "the listener's part" else "part"} at ${"%.1f".format(Locale.ROOT, start)} s of ${length / 1000} s" +
            (if (peak < 0) "" else ", most replayed at ${peak / 1000} s") + (wish.chorusMs?.let { ", chorus at ${it / 1000} s" } ?: "") + ", crop ${loop.cropX}")

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
                val next = parts.getOrNull(index + 1)
                if (e == 0.0 || attempt == 1 || next == null) {
                    if (e < 0) return Gated.Refused(ForkClipGate.Refusal.FLASHING).also { Log.d(TAG, "$id flashes: not shown") }
                    break
                }
                index++
                start = next / 1000.0
                end = start + CLIP_SEC
            }
        }
        cut(format, start, end, dir, out, platform)
        // The part as the menu offers it (§15 rule 3): its start, and the other parts far enough from it.
        val startMs = parts[index]
        val others = parts.filter { kotlin.math.abs(it - startMs) >= PART_APART_MS }.take(ForkCanvasChoices.MAX_OTHER_PARTS)
        File(out.path.dropLast(4) + ".crop").writeText(Framing(loop.cropX, loop.bars, id, startMs, others).line())
        trim(dir, keep = out)
        return Gated.Pass(Result.Clip(out, id, startMs, others))
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** A [Wish.tag] for a loop key suffix: empty for none, else a short stable name part. */
    fun tagOf(keySuffix: String): String = if (keySuffix.isEmpty()) "" else "_" + Integer.toHexString(keySuffix.hashCode())

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

    /**
     * The searches of §5 for one lookup, each asked once (none turned away, [rejected]):
     *  - [first]: rule 1, "title artist", then "title artist official video" when nothing matches;
     *  - [others]: rule 10, the "official video" search's matches (the answer reused when [first]
     *    already asked it);
     *  - [lyricVideo]: rule 12, with no match in either, a lyric video by the artist in their results.
     */
    private class Search(private val title: String, artist: String, private val durationSec: Long, private val rejected: Set<String>) {
        private val artists = ForkMusicVideoMatch.artists(artist)
        private val asked = LinkedHashMap<String, List<ForkMusicVideoMatch.Video>>()
        private val plain get() = "$title ${artists[0]}"
        private val official get() = "$title ${artists[0]} official video"

        private fun results(q: String) = asked.getOrPut(q) { searchVideos(q) }

        private fun matches(q: String) = ForkMusicVideoMatch.candidates(title, artists, durationSec, results(q)).filter { it.id !in rejected }

        fun first(): ForkMusicVideoMatch.Video? {
            if (title.isBlank() || artists.isEmpty()) return null
            for (q in listOf(plain, official)) {
                matches(q).firstOrNull()?.let { return it }
                Log.d(TAG, "none for '$q' among ${results(q).take(5).joinToString { "'${it.title}' by ${it.channel}" }}")
            }
            return null
        }

        fun others(): List<ForkMusicVideoMatch.Video> = if (title.isBlank() || artists.isEmpty()) emptyList() else matches(official)

        fun lyricVideo(): ForkMusicVideoMatch.Video? {
            if (title.isBlank() || artists.isEmpty()) return null
            val all = (results(plain) + results(official)).distinctBy { it.id }
            return ForkMusicVideoMatch.candidates(title, artists, durationSec, all, lyricsOk = true).firstOrNull { it.id !in rejected }
        }
    }

    /** The song's music video, or null: a video search, then once more with "official video". */
    fun findVideo(title: String, artist: String, durationSec: Long): ForkMusicVideoMatch.Video? =
        Search(title, artist, durationSec, emptySet()).first()

    /**
     * "Pick another video" (§15 rule 5): what a video search for "title artist" finds, in its order,
     * nothing judged; YouTube's own pairing first ([pairing]: none here, the lookups are signed out),
     * at most 10, then none of [rejected] ([ForkCanvasChoices.videoChoices]). Blocking.
     */
    fun choices(title: String, artist: String, rejected: Set<String>, pairing: String? = null): List<ForkMusicVideoMatch.Video> {
        if (title.isBlank()) return emptyList()
        val found = searchVideos("$title ${ForkMusicVideoMatch.artists(artist).firstOrNull().orEmpty()}".trim())
        return ForkCanvasChoices.videoChoices(pairing, found, rejected) { it.id }
    }

    private fun searchVideos(q: String): List<ForkMusicVideoMatch.Video> {
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
        return found
    }

    /**
     * YouTube Music's word for the video ("MUSIC_VIDEO_TYPE_OMV"), from its WEB_REMIX player answer,
     * which carries it even where that client can't play the video; null when not read.
     */
    private fun videoType(videoId: String): String? = runCatching {
        val body = buildJsonObject {
            put("videoId", videoId)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX"); put("clientVersion", "1.20251101.01.00"); put("hl", "en"); put("gl", "US")
                }
            }
        }
        ((post(MUSIC_PLAYER, body, "Mozilla/5.0", "https://music.youtube.com")["videoDetails"] as? JsonObject)?.text("musicVideoType"))
    }.getOrNull()

    private const val MUSIC_PLAYER = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"

    /** The first `musicVideoType` anywhere under [node] (a search result's play button says it). */
    private fun typeIn(node: JsonElement?): String? = when (node) {
        is JsonObject -> (node["musicVideoType"] as? JsonPrimitive)?.contentOrNull ?: node.values.firstNotNullOfOrNull(::typeIn)
        is JsonArray -> node.firstNotNullOfOrNull(::typeIn)
        else -> null
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
                out += ForkMusicVideoMatch.Video(id, title, channel, length, typeIn(r))
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
            val img = getBytes(sb.sheet(n), null, SHEET_MAX, shortClient) ?: break
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
        val raw = File(dir, out.name + ".part")
        try {
            // Streamed to the file, not read whole: a full-video cut (`cut(format, 0, length)`) held the
            // whole range in memory (audit 2026-11-26)
            openRange(url, range[0], range[1]).use { response -> writePart(raw, head, initEnd, response.body.byteStream(), BODY_MAX) }
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

    /** The head (init and index boxes) of the video's stream: capped at [HEAD_MAX]. */
    private fun getRange(url: String, from: Long, to: Long): ByteArray =
        getBytes("$url&range=$from-$to", visionUserAgent(), HEAD_MAX, client) ?: throw IOException("no bytes $from-$to")

    /**
     * [url]'s bytes on [via], at most [max] (ForkCappedBody: past it, or empty, an IOException).
     * Audit 2026-11-26: `body.bytes()` read whatever came, a whole video from a server ignoring the range.
     */
    private fun getBytes(url: String, ua: String?, max: Int, via: OkHttpClient): ByteArray? {
        val request = Request.Builder().url(url).apply { if (ua != null) header("User-Agent", ua) }.build()
        via.newCall(request).execute().use { response ->
            if (response.code >= 400) throw IOException("HTTP ${response.code}")
            return com.music.bitchord.data.ForkCappedBody.bytes(response, max)
        }
    }

    /** The loop's fragments [from]..[to], open for streaming; a range larger than [BODY_MAX] is refused before asking. */
    private fun openRange(url: String, from: Long, to: Long): Response {
        if (to < from || to - from + 1 > BODY_MAX) throw IOException("range $from-$to over ${BODY_MAX / (1024 * 1024)} MB")
        val request = Request.Builder().url("$url&range=$from-$to").header("User-Agent", visionUserAgent()).build()
        val response = client.newCall(request).execute()
        if (response.code >= 400) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        return response
    }

    /**
     * Writes the stream's init segment (`head[0..initEnd]`) then [body] (at most [max] bytes) to [raw].
     * An init range outside [head] is an IOException (it was an IndexOutOfBounds); a body over [max]
     * is one too, and [raw] is deleted on any failure, so no `.part` is left behind.
     */
    internal fun writePart(raw: File, head: ByteArray, initEnd: Long, body: InputStream, max: Long) {
        if (initEnd < 0 || initEnd >= head.size) throw IOException("init range 0-$initEnd outside the ${head.size}-byte head")
        try {
            raw.outputStream().use { o ->
                o.write(head, 0, (initEnd + 1).toInt())
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = body.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > max) throw IOException("fragments over ${max / (1024 * 1024)} MB")
                    o.write(buffer, 0, n)
                }
                if (total == 0L) throw IOException("no fragment bytes")
            }
        } catch (e: Throwable) {
            raw.delete()
            throw e
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

    private fun send(request: Request): JsonObject = shortClient.newCall(request).execute().use { response ->
        if (response.code >= 400) throw IOException("HTTP ${response.code}")
        // capped (ForkCappedBody): a player answer is well under 1 MB; an empty one is a failure, not "no video"
        json.parseToJsonElement(com.music.bitchord.data.ForkCappedBody.text(response, 2 * com.music.bitchord.data.ForkCappedBody.MB)) as? JsonObject ?: throw IOException("not JSON")
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.intOf(key: String): Int = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0
}
