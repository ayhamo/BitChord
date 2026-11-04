package com.music.bitchord.desktop

import com.music.bitchord.data.Http
import com.music.bitchord.data.lyrics.KaraokeLrc
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.artistForLyricsSearch
import com.music.bitchord.data.lyrics.forLyricsSearch
import com.music.bitchord.data.lyrics.lyricsJson
import com.music.bitchord.data.lyrics.withBackgroundVocals
import com.music.bitchord.data.model.Song
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Fork: NetEase Cloud Music as a lyrics source, on the PC only. Taken from PC-Continued aaaaee54
 * ("More lyric providers, with a source-order test", their `DesktopExtraLyrics.netease`): the two
 * calls (search by name, then the song's `yrc`/`lrc`), the phone-app `User-Agent` and `Referer`,
 * the length tolerance, and the "only asked when it sits ahead of what the shared race found" rule.
 *
 * Why the PC alone: the phone has no NetEase provider (`LyricsSource` has no entry for it; only
 * `KaraokeLrc` already knows NetEase's YRC format), and phone parity means the phone's provider
 * list is not changed from here. A `LyricsSource` entry would put it on the phone's list too, so
 * the PC carries it beside the shared race instead, by its label, through the lyrics settings'
 * own name-based order. The parsing is the phone's ([KaraokeLrc] for YRC, [LrcLib] for LRC), so
 * a NetEase line means what a QQ/KuGou line means.
 *
 * What is ours, not PC-Continued's:
 * - A hit must run to the playing track's length (±[DURATION_TOLERANCE_S]); theirs fell back to
 *   the first hit, which for a song NetEase doesn't have is someone else's words.
 * - NetEase's credit lines ("作词 : …", "制作人 : …") are cut from both ends: they carry real
 *   stamps (YRC even word stamps), so left in they are sung as the first lines of the song.
 *   Measured 2026-11-04 on "Blinding Lights" and "Creep": three such lines open both documents.
 * - With "Prioritize syllable lyrics" on, a line-synced winner from the shared race is still
 *   upgraded by a word-synced NetEase answer from further down the list, as the shared race would
 *   do among its own sources.
 *
 * The player's provider picker (sharedUi's `LyricsProviderSheet`) lists `LyricsSource` entries, so
 * NetEase is not in it; it is in Settings > Lyrics sources, where it can be ticked and dragged.
 */
internal object ForkNetEaseLyrics {

    /** The label it is stored and shown by, beside the shared sources' labels. */
    const val LABEL = "NetEase"

    /**
     * Where a fresh order puts it: after the last of the Apple-timed syllable sources, ahead of
     * the line-only ones. It is word-synced (YRC) and strong on the catalogues the Apple hosts
     * miss, which is when it gets asked at all.
     */
    private val DEFAULT_AFTER = LyricsSource.LYRICS_PLUS.label

    private const val DURATION_TOLERANCE_S = 8

    private val source = DesktopLyricsClient.Source(
        name = LABEL,
        detail = "Word-by-word timing from NetEase Cloud Music (PC only)",
        wordSynced = true,
    )

    /** The phone's sources with NetEase slotted in at its default place. */
    fun withNetEase(phone: List<DesktopLyricsClient.Source>): List<DesktopLyricsClient.Source> {
        val anchor = phone.indexOfFirst { it.name == DEFAULT_AFTER }
        val out = phone.toMutableList()
        // Ahead of the last (Genius, the plain-text fallback) when the anchor is gone.
        out.add(if (anchor >= 0) anchor + 1 else (out.size - 1).coerceAtLeast(0), source)
        return out
    }

    /**
     * A saved order brought up to date (the phone's [LyricsSource.ordered] for the shared ones),
     * with NetEase kept where the listener dragged it: after the nearest source saved ahead of it.
     * Never saved (an order from before this build, or a fresh one): its default place.
     */
    fun ordered(saved: List<String>): List<String> {
        val names = saved.map { lyricsSourceNamed(it)?.label ?: it }
        val shared = LyricsSource.ordered(names.mapNotNull(::lyricsSourceNamed)).map { it.label }.toMutableList()
        val savedAt = names.indexOf(LABEL)
        val before = if (savedAt >= 0) {
            names.subList(0, savedAt).lastOrNull { it in shared }
        } else {
            val defaults = DesktopLyricsClient.sources.map { it.name }
            defaults.subList(0, defaults.indexOf(LABEL).coerceAtLeast(0)).lastOrNull { it in shared }
        }
        shared.add(before?.let { shared.indexOf(it) + 1 } ?: 0, LABEL)
        return shared
    }

    /** What a lookup asks by name: the one picked, or the ticked ones in order. */
    fun asked(only: String?, configured: List<String>): List<String> = only?.let(::listOf) ?: configured

    /**
     * NetEase's answer when it should replace the shared race's [winner] (null: the race found
     * nothing), else null. Asked only when it sits ahead of the winner in [names], or when a
     * syllable upgrade is wanted; never asked when it isn't in [names].
     */
    suspend fun ahead(
        names: List<String>,
        winner: LyricsWinner?,
        prioritizeSyllables: Boolean,
        song: Song,
        durationMs: Long,
    ): DesktopLyrics? {
        val at = names.indexOf(LABEL)
        if (at < 0) return null
        val winnerAt = winner?.let { names.indexOf(it.label) }?.takeIf { it >= 0 } ?: names.size
        val first = at < winnerAt
        val upgrade = prioritizeSyllables && winner != null && !winner.wordSynced
        if (!first && !upgrade) return null
        val lines = lyrics(song.title, song.artist, durationMs) ?: return null
        val wordSynced = lines.any(LyricLine::isWordSynced)
        if (!first && !wordSynced) return null
        if (first && prioritizeSyllables && winner?.wordSynced == true && !wordSynced) return null
        return DesktopLyrics(LABEL, lines)
    }

    /** What [ahead] needs of the shared race's answer. */
    data class LyricsWinner(val label: String, val wordSynced: Boolean)

    /** NetEase's lines for the track, or null for a miss or any failure. Never throws, never retries. */
    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val searchTitle = title.forLyricsSearch()
                val query = "$searchTitle ${artist.artistForLyricsSearch()}".trim()
                val search = get(searchUrl(query), SEARCH_HEADERS) ?: return@runCatching null
                val id = pickSong(search, searchTitle, (durationMs / 1000).toInt()) ?: return@runCatching null
                val body = get(lyricUrl(id), LYRIC_HEADERS) ?: return@runCatching null
                linesFrom(body)?.withBackgroundVocals()
            }.onFailure {
                DesktopTrackLog.log("NetEase lyrics: ${it.javaClass.simpleName}")
            }.getOrNull()
        }

    /**
     * The search hit that is this recording: within [DURATION_TOLERANCE_S] of [seconds], the
     * closest, a hit named like [title] first. Without a length to go by, only a hit named like
     * [title]. Null when nothing fits; a near miss is a different take's words.
     */
    internal fun pickSong(body: String, title: String, seconds: Int): Long? {
        val songs = runCatching { lyricsJson.decodeFromString<SearchResponse>(body) }
            .getOrNull()?.result?.songs.orEmpty()
        if (songs.isEmpty()) return null
        val want = canonical(title)
        fun named(song: SearchResponse.Song) = want.isNotEmpty() && canonical(song.name) == want
        if (seconds <= 0) return songs.firstOrNull(::named)?.id
        val fitting = songs.filter { abs((it.duration / 1000).toInt() - seconds) <= DURATION_TOLERANCE_S }
        val ranked = fitting.sortedWith(
            compareBy<SearchResponse.Song> { if (named(it)) 0 else 1 }
                .thenBy { abs((it.duration / 1000).toInt() - seconds) },
        )
        return ranked.firstOrNull()?.id
    }

    /**
     * The song's words from `api/song/lyric`: the YRC (word-synced) when it has word stamps, else
     * the LRC, credits cut from both ends. Null for no words (an instrumental's single notice
     * counts as none).
     */
    internal fun linesFrom(body: String): List<LyricLine>? {
        val response = runCatching { lyricsJson.decodeFromString<LyricResponse>(body) }.getOrNull() ?: return null
        response.yrc?.lyric?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val parsed = withoutCredits(KaraokeLrc.parse(raw))
            if (parsed.any(LyricLine::isWordSynced)) return parsed
        }
        response.lrc?.lyric?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val parsed = withoutCredits(LrcLib.parseLrc(raw))
            if (parsed.any { it.text.isNotBlank() } && !parsed.all { INSTRUMENTAL.containsMatchIn(it.text) || it.text.isBlank() }) {
                return parsed
            }
        }
        return null
    }

    /**
     * NetEase's credit lines cut from the head and tail: a run of lines (gaps allowed) whose text
     * is a short Chinese label, a colon and a value. Only Chinese labels, so a sung line with a
     * colon in it ("Baby: …") is never taken for one; NetEase writes its credits in Chinese
     * whatever the song's language.
     */
    internal fun withoutCredits(lines: List<LyricLine>): List<LyricLine> {
        fun credit(line: LyricLine) = CREDIT.matches(line.text.trim())
        fun skippable(line: LyricLine) = line.text.isBlank() || credit(line)
        var start = 0
        while (start < lines.size && skippable(lines[start])) start++
        var end = lines.size
        while (end > start && skippable(lines[end - 1])) end--
        // Nothing but credits and gaps is no lyrics; a cut that only removed gaps keeps them.
        if (lines.subList(0, start).none(::credit) && lines.subList(end, lines.size).none(::credit)) return lines
        val kept = lines.subList(start, end)
        // The intro break the parsers give a song whose words start late (the shared
        // `MIN_GAP_MS`, 4 s, internal to `:shared`) went with the credits ahead of it; put it back.
        val first = kept.firstOrNull() ?: return kept
        return if (first.timeMs >= INTRO_GAP_MS) listOf(LyricLine(0L, "")) + kept else kept
    }

    private const val INTRO_GAP_MS = 4_000L

    private fun searchUrl(query: String): String =
        "http://music.163.com/api/search/get/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("type", "1")
            .addQueryParameter("offset", "0")
            .addQueryParameter("limit", "5")
            .build().toString()

    /** `yv` asks for the YRC document, `lv` for the line-synced LRC; `tv`/`rv` are ignored here. */
    private fun lyricUrl(id: Long): String =
        "https://music.163.com/api/song/lyric".toHttpUrl().newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("lv", "1")
            .addQueryParameter("tv", "1")
            .addQueryParameter("rv", "1")
            .addQueryParameter("yv", "1")
            .build().toString()

    // PC-Continued's headers: the search answers a phone client's User-Agent with a Referer.
    private val SEARCH_HEADERS = mapOf(
        "User-Agent" to "NeteaseMusic/9.0.0 (iPhone; iOS 16.0; Scale/3.00)",
        "Referer" to "http://music.163.com/",
    )
    private val LYRIC_HEADERS = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://music.163.com")

    /** The app's one client (DNS, pool), with short limits: lyrics that come late are no use. */
    private val http by lazy {
        Http.client.newBuilder()
            .callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private fun get(url: String, headers: Map<String, String>): String? {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return http.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    }

    private fun canonical(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    private val CREDIT = Regex("""^\p{IsHan}{1,8}\s*[:：].*$""")
    private val INSTRUMENTAL = Regex("""纯音乐.*欣赏""")

    @Serializable
    private data class SearchResponse(val result: SearchResult? = null) {
        @Serializable
        data class SearchResult(val songs: List<Song> = emptyList())

        @Serializable
        data class Song(val id: Long, val name: String = "", val duration: Long = 0)
    }

    @Serializable
    private data class LyricResponse(val yrc: Document? = null, val lrc: Document? = null) {
        @Serializable
        data class Document(val lyric: String = "")
    }
}
